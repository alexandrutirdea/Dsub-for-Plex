/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2014 (C) Scott Jackson
*/

package github.daneren2005.dsub.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Talks to the user's Plex Dashboard: Last.fm loving, and what it knows about an album.
 *
 * For the playing track neither endpoint needs to be told which track is meant - the
 * dashboard already tracks what Plex is playing and DSub reports itself to Plex through
 * the timeline, so asking about "the current track" is enough. That also keeps Last.fm
 * credentials out of this app entirely; the dashboard holds them.
 *
 * The album calls do name what they are about, by Plex rating key, which is the same id
 * an Entry already carries.
 *
 * Every call is a no-op unless an address has been configured, so none of this shows up
 * for anyone not running the dashboard.
 *
 * Two addresses can be configured, because the public one is typically not reachable
 * from inside the LAN: a router that does not hairpin NAT simply drops a connection to
 * its own external address, and the love button did nothing at all at home. So try the
 * local address first and fall back to the public one, the same way the desktop client
 * does.
 */
public final class LoveClient {
	private static final String TAG = LoveClient.class.getSimpleName();

	// The LAN either answers immediately or is not there at all, so a short connect
	// timeout is what keeps the fallback from stalling the button for seconds.
	private static final int LAN_CONNECT_TIMEOUT = 800;
	private static final int LAN_READ_TIMEOUT = 2500;
	private static final int REMOTE_CONNECT_TIMEOUT = 5 * 1000;
	private static final int REMOTE_READ_TIMEOUT = 10 * 1000;

	/** How long to keep skipping the LAN address after it failed, before probing again. */
	private static final long LAN_REPROBE_INTERVAL = 60 * 1000;

	private static volatile boolean lanDown;
	private static volatile long lastLanProbe;

	private LoveClient() {}

	public static boolean isConfigured(Context context) {
		return getBaseUrl(context, Constants.PREFERENCES_KEY_DASHBOARD_URL) != null
				|| getBaseUrl(context, Constants.PREFERENCES_KEY_DASHBOARD_LAN_URL) != null;
	}

	private static String getBaseUrl(Context context, String preference) {
		SharedPreferences prefs = Util.getPreferences(context);
		String url = prefs.getString(preference, null);
		if(url == null) {
			return null;
		}

		url = url.trim();
		while(url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url.isEmpty() ? null : url;
	}

	/**
	 * The addresses to try, in order. The local one goes first while it is working; once
	 * it has failed the public one leads, so being away from home costs one attempt
	 * rather than a connect timeout on every call, until it is worth looking again.
	 */
	private static List<String> getBaseUrls(Context context) {
		String lan = getBaseUrl(context, Constants.PREFERENCES_KEY_DASHBOARD_LAN_URL);
		String remote = getBaseUrl(context, Constants.PREFERENCES_KEY_DASHBOARD_URL);

		List<String> urls = new ArrayList<String>();
		boolean tryLanFirst = !lanDown || (System.currentTimeMillis() - lastLanProbe) > LAN_REPROBE_INTERVAL;
		if(lan != null && tryLanFirst) {
			urls.add(lan);
		}
		if(remote != null) {
			urls.add(remote);
		}
		if(lan != null && !tryLanFirst) {
			urls.add(lan);
		}
		return urls;
	}

	private static String buildUrl(Context context, String base, String path) {
		String key = Util.getPreferences(context).getString(Constants.PREFERENCES_KEY_DASHBOARD_KEY, null);
		if(key == null || key.trim().isEmpty()) {
			return base + path;
		}

		try {
			// A path may already carry a query of its own, and a second '?' would make
			// the key part of the previous parameter's value rather than a parameter.
			String separator = path.indexOf('?') >= 0 ? "&" : "?";
			return base + path + separator + "key=" + URLEncoder.encode(key.trim(), "UTF-8");
		} catch(Exception e) {
			return base + path;
		}
	}

	/**
	 * What the dashboard currently sees, or null when nothing can be said - not
	 * configured, nothing playing, or unreachable - so callers can leave the button
	 * alone rather than showing a wrong state.
	 *
	 * The track it names matters as much as the loved flag: the dashboard reports the
	 * Plex session, which lags a track change by a few seconds, so an answer has to be
	 * checked against the song actually being asked about.
	 */
	public static LoveState getCurrentState(Context context) {
		JSONObject response = request(context, "/api/love", "GET");
		if(response == null || !response.optBoolean("playing", false)) {
			return null;
		}

		return new LoveState(response.optBoolean("loved", false),
				response.optString("artist", null), response.optString("track", null));
	}

	/** An answer from the dashboard, tied to the track it was actually about. */
	public static final class LoveState {
		private final boolean loved;
		private final String artist;
		private final String track;

		LoveState(boolean loved, String artist, String track) {
			this.loved = loved;
			this.artist = artist;
			this.track = track;
		}

		public boolean isLoved() {
			return loved;
		}

		/**
		 * True when this answer is about the given song.
		 *
		 * The title has to match. The artist only has to match when both sides know one,
		 * because a disagreement there is far more likely to be a tagging difference than
		 * two different songs sharing a title back to back.
		 */
		public boolean isAbout(String songArtist, String songTitle) {
			if(!equalsLoosely(track, songTitle)) {
				return false;
			}
			if(isBlank(artist) || isBlank(songArtist)) {
				return true;
			}
			return equalsLoosely(artist, songArtist);
		}

		private static boolean isBlank(String value) {
			return value == null || value.trim().isEmpty();
		}

		private static boolean equalsLoosely(String first, String second) {
			if(isBlank(first) || isBlank(second)) {
				return false;
			}
			return first.trim().equalsIgnoreCase(second.trim());
		}
	}

	/** Loves the current track. Returns false when the dashboard could not be reached. */
	public static boolean loveCurrent(Context context) {
		return request(context, "/api/love", "POST") != null;
	}

	/**
	 * What the dashboard knows about one album: how it rates it, and which of its tracks
	 * are loved. Null when it could not be asked at all.
	 *
	 * Asked through /api/love rather than a route of its own. Everything under
	 * /api/albums/ sits behind the dashboard's single sign-on, which a phone away from
	 * home has no session for - the proxy answers a redirect to the login page, and the
	 * album screen quietly got HTML where it wanted JSON. The exact path /api/love is
	 * exempt from that and guards itself with the access key instead, so asking there
	 * works from anywhere the dashboard is reachable at all.
	 */
	public static AlbumSummary getAlbumSummary(Context context, String albumId) {
		if(albumId == null || albumId.isEmpty()) {
			return null;
		}

		JSONObject response = request(context, "/api/love?album=" + albumId, "GET");
		if(response == null) {
			return null;
		}

		Set<String> loved = null;
		JSONArray lovedIds = response.optJSONArray("loved");
		if(lovedIds != null) {
			loved = new HashSet<String>();
			for(int i = 0; i < lovedIds.length(); i++) {
				String key = lovedIds.optString(i, null);
				if(key != null && !key.isEmpty()) {
					loved.add(key);
				}
			}
		}

		return new AlbumSummary(response.optInt("stars", 0), response.optDouble("rating", 0), loved);
	}

	/** An album's standing with the dashboard, and which of its tracks are loved. */
	public static final class AlbumSummary {
		private final int stars;
		private final double rating;
		private final Set<String> lovedIds;

		AlbumSummary(int stars, double rating, Set<String> lovedIds) {
			this.stars = stars;
			this.rating = rating;
			this.lovedIds = lovedIds;
		}

		public int getStars() {
			return stars;
		}

		public double getRating() {
			return rating;
		}

		/**
		 * The tracks to draw a heart against, or null where the dashboard could not say -
		 * which is not the same as none of them being loved, and should leave the rows
		 * unmarked rather than assert otherwise.
		 *
		 * The ids are Plex rating keys, which is what {@link github.daneren2005.dsub.domain.MusicDirectory.Entry#getId()}
		 * already holds for a Plex track, so callers can match on id without any lookup.
		 */
		public Set<String> getLovedIds() {
			return lovedIds;
		}

		/** True for an album the dashboard has not scored, which is not worth a line. */
		public boolean hasNoRating() {
			return stars <= 0 && rating <= 0;
		}
	}

	private static JSONObject request(Context context, String path, String method) {
		String lan = getBaseUrl(context, Constants.PREFERENCES_KEY_DASHBOARD_LAN_URL);

		for(String base: getBaseUrls(context)) {
			boolean isLan = base.equals(lan);
			try {
				JSONObject response = request(context, base, path, method, isLan);
				lanDown = !isLan;
				return response;
			} catch(AuthFailure e) {
				// Both addresses answer to the same key and the same proxy in front, so
				// the other one would say the same.
				Log.w(TAG, "Dashboard " + e.getMessage());
				return null;
			} catch(NotFound e) {
				// The route is missing rather than the dashboard being away, so the other
				// address would answer the same way. Not worth a second connection.
				Log.w(TAG, "Dashboard has no " + path);
				return null;
			} catch(Exception e) {
				Log.w(TAG, "Dashboard request to " + base + " failed: " + e);
				if(isLan) {
					lanDown = true;
					lastLanProbe = System.currentTimeMillis();
				}
			}
		}
		return null;
	}

	private static JSONObject request(Context context, String base, String path, String method, boolean isLan) throws Exception {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(buildUrl(context, base, path)).openConnection();
			connection.setRequestMethod(method);
			connection.setConnectTimeout(isLan ? LAN_CONNECT_TIMEOUT : REMOTE_CONNECT_TIMEOUT);
			connection.setReadTimeout(isLan ? LAN_READ_TIMEOUT : REMOTE_READ_TIMEOUT);
			// The dashboard answers every request itself; a redirect means something in
			// front of it is having its say, in practice a single sign-on proxy sending
			// an unauthenticated caller to a login page. Following that quietly hands the
			// access key to whatever host is named in the Location and brings back a page
			// of HTML to be parsed as JSON, which is a confusing way to learn one is not
			// signed in.
			connection.setInstanceFollowRedirects(false);
			if("POST".equals(method)) {
				connection.setDoOutput(true);
				connection.setFixedLengthStreamingMode(0);
			}

			int code = connection.getResponseCode();
			if(code == 401 || code == 403) {
				throw new AuthFailure();
			}
			if(code >= 300 && code < 400) {
				throw new AuthFailure(connection.getHeaderField("Location"));
			}
			if(code == 404) {
				throw new NotFound();
			}
			if(code >= 400) {
				throw new IOException("Dashboard returned " + code + " for " + method + " " + path);
			}

			String body = readFully(connection.getInputStream());
			return (body == null || body.trim().isEmpty()) ? new JSONObject() : new JSONObject(body);
		} finally {
			if(connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * A wrong key, or a sign-in wanted by something in front of the dashboard. Trying
	 * the other address cannot fix either.
	 */
	private static class AuthFailure extends Exception {
		AuthFailure() {
			super("rejected the access key");
		}

		AuthFailure(String location) {
			super("wants a sign-in first: " + location);
		}
	}

	/** A route this dashboard does not serve, which trying the other address cannot fix. */
	private static class NotFound extends Exception {}

	private static String readFully(InputStream input) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int read;
		while((read = input.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
		return output.toString("UTF-8");
	}
}
