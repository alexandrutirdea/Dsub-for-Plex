/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.
 */
package github.daneren2005.dsub.service.plex;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.SelfSignedTls;
import github.daneren2005.dsub.util.KeyStoreUtil;
import github.daneren2005.dsub.util.Util;

/**
 * Low level HTTP/auth plumbing for talking to a Plex Media Server.
 *
 * Plex differs from Subsonic in three ways that matter here:
 *   - Auth is a token (X-Plex-Token), obtained once from plex.tv, not
 *     credentials replayed on every request.
 *   - Every client must present a stable X-Plex-Client-Identifier.
 *   - Responses are XML by default; asking for JSON gives a much simpler
 *     shape, always wrapped in a "MediaContainer" object.
 */
public class PlexClient {
	private static final String TAG = PlexClient.class.getSimpleName();

	public static final String PLEX_TV = "https://plex.tv";
	private static final int TIMEOUT_DEFAULT = 15 * 1000;

	/** Plex library "type" discriminators for music. */
	public static final int TYPE_ARTIST = 8;
	public static final int TYPE_ALBUM = 9;
	public static final int TYPE_TRACK = 10;

	private Integer instance;

	public PlexClient() {
	}

	public void setInstance(Integer instance) {
		this.instance = instance;
	}

	public int getInstance(Context context) {
		return instance == null ? Util.getActiveServer(context) : instance;
	}

	// ---------------------------------------------------------------- config

	/**
	 * Honours the internal address and local network SSID, so being on the home wifi
	 * reaches Plex directly instead of going out to the public name and back in - which
	 * is what made Plex log local playback as an external session.
	 */
	public String getServerUrl(Context context) {
		SharedPreferences prefs = Util.getPreferences(context);
		String url = Util.getServerUrl(context, prefs, getInstance(context), true);
		if (url == null) {
			return null;
		}
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	/**
	 * The id of the Plex library section holding music. Resolved lazily on
	 * first use and then cached, since almost every call needs it.
	 */
	public String getLibraryKey(Context context) throws Exception {
		SharedPreferences prefs = Util.getPreferences(context);
		String key = Constants.PREFERENCES_KEY_PLEX_LIBRARY + getInstance(context);
		String cached = prefs.getString(key, null);
		if (cached != null) {
			return cached;
		}

		String resolved = null;
		JSONObject container = get(context, "/library/sections", null);
		JSONArray directories = container.optJSONArray("Directory");
		for (int i = 0; directories != null && i < directories.length(); i++) {
			JSONObject dir = directories.getJSONObject(i);
			if ("artist".equals(dir.optString("type"))) {
				resolved = dir.optString("key");
				break;
			}
		}

		if (resolved == null) {
			throw new Exception("No music library found on this Plex server");
		}
		prefs.edit().putString(key, resolved).apply();
		return resolved;
	}

	/** Stable per-install identifier, required by Plex on every request. */
	public static String getClientIdentifier(Context context) {
		SharedPreferences prefs = Util.getPreferences(context);
		String id = prefs.getString(Constants.PREFERENCES_KEY_PLEX_CLIENT_ID, null);
		if (id == null) {
			id = UUID.randomUUID().toString();
			prefs.edit().putString(Constants.PREFERENCES_KEY_PLEX_CLIENT_ID, id).apply();
		}
		return id;
	}

	// ------------------------------------------------------------------ auth

	public String getToken(Context context) throws Exception {
		SharedPreferences prefs = Util.getPreferences(context);
		String key = Constants.PREFERENCES_KEY_PLEX_TOKEN + getInstance(context);
		String token = prefs.getString(key, null);
		if (token != null && !token.isEmpty()) {
			return token;
		}

		token = signIn(context);
		prefs.edit().putString(key, token).apply();
		return token;
	}

	/** Forget the cached token so the next call re-authenticates. */
	public void clearToken(Context context) {
		Util.getPreferences(context).edit()
				.remove(Constants.PREFERENCES_KEY_PLEX_TOKEN + getInstance(context))
				.apply();
	}

	/**
	 * Exchange the stored plex.tv credentials for an auth token. If the user
	 * pasted a token into the password field instead, use it directly.
	 */
	private String signIn(Context context) throws Exception {
		SharedPreferences prefs = Util.getPreferences(context);
		int inst = getInstance(context);
		String username = prefs.getString(Constants.PREFERENCES_KEY_USERNAME + inst, null);
		String password = prefs.getString(Constants.PREFERENCES_KEY_PASSWORD + inst, null);
		if (prefs.getBoolean(Constants.PREFERENCES_KEY_ENCRYPTED_PASSWORD + inst, false)) {
			password = KeyStoreUtil.decrypt(password);
		}

		if (password == null || password.isEmpty()) {
			throw new Exception("No Plex password or token configured");
		}

		// A Plex token is a 20 character opaque string with no separators. If
		// the username was left blank, treat the password field as a raw token.
		if (username == null || username.isEmpty()) {
			return password;
		}

		HttpURLConnection connection = null;
		try {
			URL url = new URL(PLEX_TV + "/users/sign_in.json");
			connection = (HttpURLConnection) url.openConnection();
			// Deliberately not weakened: this is plex.tv, which has a certificate from a
			// real CA, and it is the request carrying the user's Plex password.
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(TIMEOUT_DEFAULT);
			connection.setReadTimeout(TIMEOUT_DEFAULT);
			addPlexHeaders(connection, context, null);
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

			String body = "user%5Blogin%5D=" + URLEncoder.encode(username, "UTF-8")
					+ "&user%5Bpassword%5D=" + URLEncoder.encode(password, "UTF-8");
			OutputStream out = connection.getOutputStream();
			out.write(body.getBytes("UTF-8"));
			out.flush();
			out.close();

			int code = connection.getResponseCode();
			if (code == 401) {
				throw new Exception("Plex rejected the username or password");
			}
			if (code >= 400) {
				throw new IOException("plex.tv sign in failed with code " + code);
			}

			JSONObject response = new JSONObject(readFully(connection.getInputStream()));
			JSONObject user = response.optJSONObject("user");
			String token = user != null ? user.optString("authToken", null) : null;
			if (token == null || token.isEmpty()) {
				throw new Exception("plex.tv did not return an auth token");
			}
			return token;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	// -------------------------------------------------------------- requests

	public JSONObject get(Context context, String path, List<String> params) throws Exception {
		return request(context, "GET", path, params);
	}

	/**
	 * GET without auth. Used to probe /identity, which every Plex server answers
	 * unauthenticated -- letting us tell "not a Plex server" apart from
	 * "credentials rejected".
	 */
	public JSONObject getAnonymous(Context context, String path) throws Exception {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(buildUrl(context, path, null)).openConnection();
			applySsl(connection, context);
			connection.setConnectTimeout(TIMEOUT_DEFAULT);
			connection.setReadTimeout(TIMEOUT_DEFAULT);
			addPlexHeaders(connection, context, null);
			connection.setRequestProperty("Accept", "application/json");

			int code = connection.getResponseCode();
			if (code >= 400) {
				throw new IOException("Plex returned HTTP " + code + " for " + path);
			}

			String body = readFully(connection.getInputStream());
			if (body == null || body.trim().isEmpty()) {
				return new JSONObject();
			}

			JSONObject root = new JSONObject(body);
			JSONObject container = root.optJSONObject("MediaContainer");
			return container != null ? container : root;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	public JSONObject put(Context context, String path, List<String> params) throws Exception {
		return request(context, "PUT", path, params);
	}

	public JSONObject post(Context context, String path, List<String> params) throws Exception {
		return request(context, "POST", path, params);
	}

	public JSONObject delete(Context context, String path, List<String> params) throws Exception {
		return request(context, "DELETE", path, params);
	}

	/**
	 * Issues a command and checks only the HTTP status. Plex's control
	 * endpoints (/:/timeline, /:/scrobble, /:/rate) answer with an empty body or
	 * XML regardless of the Accept header, so parsing them as JSON would fail a
	 * request that actually succeeded.
	 */
	public void command(Context context, String path, List<String> params) throws Exception {
		HttpURLConnection connection = null;
		try {
			connection = openConnection(context, buildUrl(context, path, params));
			connection.setRequestMethod("GET");

			int code = connection.getResponseCode();
			if (code == 401) {
				clearToken(context);
				throw new Exception("Plex authentication failed");
			}
			if (code >= 400) {
				throw new IOException("Plex request failed with code " + code + ": " + path);
			}

			// Drain so the connection can be reused, but ignore the content.
			readFully(connection.getInputStream());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private JSONObject request(Context context, String method, String path, List<String> params) throws Exception {
		String url = buildUrl(context, path, params);
		HttpURLConnection connection = null;
		try {
			connection = openConnection(context, url);
			connection.setRequestMethod(method);
			connection.setRequestProperty("Accept", "application/json");

			int code = connection.getResponseCode();
			if (code == 401) {
				// Token expired or revoked; drop it so the next attempt re-authenticates.
				clearToken(context);
				throw new Exception("Plex authentication failed");
			}
			if (code >= 400) {
				throw new IOException("Plex request failed with code " + code + ": " + path);
			}

			// Writes (rate, scrobble, playlist edits) often return 200 with an
			// empty body, which is not valid JSON.
			String body = readFully(connection.getInputStream());
			if (body == null || body.trim().isEmpty()) {
				return new JSONObject();
			}

			JSONObject root = new JSONObject(body);
			JSONObject container = root.optJSONObject("MediaContainer");
			return container != null ? container : root;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Fetches a path as plain text rather than JSON. Used for lyric streams, which
	 * Plex serves as an LRC file body.
	 */
	public String getText(Context context, String path) throws Exception {
		String url = buildUrl(context, path, null);
		HttpURLConnection connection = null;
		try {
			connection = openConnection(context, url);
			connection.setRequestMethod("GET");

			int code = connection.getResponseCode();
			if (code >= 400) {
				throw new IOException("Plex request failed with code " + code + ": " + path);
			}
			return readFully(connection.getInputStream());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** Opens a connection with Plex auth headers already applied. */
	public HttpURLConnection openConnection(Context context, String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		applySsl(connection, context);
		connection.setConnectTimeout(TIMEOUT_DEFAULT);
		connection.setReadTimeout(TIMEOUT_DEFAULT);
		addPlexHeaders(connection, context, getToken(context));
		return connection;
	}

	/**
	 * A Plex server on a LAN normally presents a certificate for a *.plex.direct hostname
	 * that will not match the address the user typed, which is a real reason to want
	 * checking off - but a reason to turn it off for that server, not for plex.tv and
	 * every other connection as well, which is what this used to do.
	 */
	private void applySsl(HttpURLConnection connection, Context context) {
		SelfSignedTls.applyTo(connection, context, getInstance(context));
	}

	private void addPlexHeaders(HttpURLConnection connection, Context context, String token) {
		connection.setRequestProperty("X-Plex-Client-Identifier", getClientIdentifier(context));
		connection.setRequestProperty("X-Plex-Product", Constants.REST_CLIENT_ID);
		connection.setRequestProperty("X-Plex-Version", Util.getVersionName(context));
		connection.setRequestProperty("X-Plex-Platform", "Android");
		connection.setRequestProperty("X-Plex-Device", android.os.Build.MODEL);
		connection.setRequestProperty("X-Plex-Device-Name", Constants.REST_CLIENT_ID);
		if (token != null) {
			connection.setRequestProperty("X-Plex-Token", token);
		}
	}

	// ------------------------------------------------------------------ urls

	/**
	 * Builds a fully qualified server URL. Params are supplied as flat
	 * name/value pairs; null values are skipped.
	 */
	public String buildUrl(Context context, String path, List<String> params) throws Exception {
		String server = getServerUrl(context);
		if (server == null) {
			throw new Exception("No Plex server URL configured");
		}

		StringBuilder builder = new StringBuilder(server).append(path);
		boolean first = path.indexOf('?') == -1;
		if (params != null) {
			for (int i = 0; i + 1 < params.size(); i += 2) {
				String value = params.get(i + 1);
				if (value == null) {
					continue;
				}
				builder.append(first ? '?' : '&');
				first = false;
				builder.append(params.get(i)).append('=').append(URLEncoder.encode(value, "UTF-8"));
			}
		}
		return builder.toString();
	}

	/**
	 * Same as buildUrl but with the token baked into the query string. Needed
	 * for URLs handed to MediaPlayer/ImageLoader, which cannot set headers.
	 */
	public String buildTokenUrl(Context context, String path, List<String> params) throws Exception {
		List<String> withToken = params == null ? new ArrayList<String>() : new ArrayList<String>(params);
		withToken.add("X-Plex-Token");
		withToken.add(getToken(context));
		return buildUrl(context, path, withToken);
	}

	public static List<String> params(String... values) {
		List<String> list = new ArrayList<String>();
		for (String value : values) {
			list.add(value);
		}
		return list;
	}

	private static String readFully(InputStream in) throws IOException {
		if (in == null) {
			return null;
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			return out.toString("UTF-8");
		} finally {
			in.close();
		}
	}
}
