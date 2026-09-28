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
package github.daneren2005.dsub.audiofx;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks headphones up in AutoEq's own index of published corrections.
 *
 * The project keeps one file listing every profile it has, each line naming the headphones
 * and where their results live:
 *
 * <pre>
 * - [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990
 * </pre>
 *
 * That file is fetched once and kept, so searching is a scan of a local file rather than a
 * request per keystroke, and only the profile actually chosen is downloaded. Nothing is
 * bundled with the app: the measurements behind these corrections belong to the people who
 * made them, and this way they stay where they were published.
 */
public class AutoEqIndex {
	private static final String TAG = AutoEqIndex.class.getSimpleName();

	private static final String REPO = "jaakkopasanen/AutoEq";
	private static final String BRANCH = "master";

	/** Where the files are served plainly, and the one everything is tried against first. */
	private static final String RAW_HOST = "https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/";
	/**
	 * The same files through the API, which is counted against a different allowance - so
	 * when raw has had enough of this network, this one has usually not.
	 */
	private static final String API_HOST = "https://api.github.com/repos/" + REPO + "/contents/";

	private static final String RESULTS = "results/";
	private static final String INDEX_PATH = RESULTS + "INDEX.md";
	private static final String INDEX_FILE = "autoeq_index.md";
	/** The index only changes when headphones are added to it, which is not urgent news. */
	private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
	private static final int CONNECT_TIMEOUT = 10000;
	private static final int READ_TIMEOUT = 20000;
	/** Not in HttpURLConnection, which predates the code. */
	private static final int HTTP_TOO_MANY_REQUESTS = 429;

	private static final Pattern ENTRY = Pattern.compile("^-\\s*\\[(.+?)\\]\\((.+?)\\)(?:\\s+by\\s+(.+?))?\\s*$");

	/**
	 * A failure worth repeating to the user word for word.
	 *
	 * An IOException so the callers' signatures are unchanged, but the message is written to
	 * be read: the generic handler turns every IOException into advice about the Subsonic
	 * server address, which is the wrong thing to go and check when it was GitHub that said
	 * no. {@link github.daneren2005.dsub.fragments.EqualizerFragment} passes this one
	 * through instead.
	 */
	public static class DownloadException extends IOException {
		public DownloadException(String message) {
			super(message);
		}
	}

	/**
	 * GitHub turning this network away rather than the file being wrong. Its own type
	 * because it is the one failure worth trying somewhere else before giving up.
	 */
	public static class RateLimitedException extends DownloadException {
		public RateLimitedException(String message) {
			super(message);
		}
	}

	/** One headphone's entry: what to show, and where its results are. */
	public static class Match {
		public final String name;
		public final String source;
		private final String path;

		Match(String name, String source, String path) {
			this.name = name;
			this.source = source;
			this.path = path;
		}

		/** Name and who measured it, since the same headphones are often in there twice. */
		public String getLabel() {
			return source == null ? name : name + " — " + source;
		}
	}

	/**
	 * The entries whose names contain every word of the query, in the index's own order.
	 *
	 * @param limit how many to stop at; the index has thousands of entries and a short query
	 *		matches hundreds of them.
	 */
	public static List<Match> search(Context context, String query, int limit) throws IOException {
		String[] words = query.toLowerCase().trim().split("\\s+");
		List<Match> matches = new ArrayList<Match>();

		BufferedReader reader = new BufferedReader(new InputStreamReader(openIndex(context), "UTF-8"));
		try {
			String line;
			while((line = reader.readLine()) != null && matches.size() < limit) {
				Matcher matcher = ENTRY.matcher(line);
				if(!matcher.matches()) {
					continue;
				}

				String name = matcher.group(1);
				String haystack = name.toLowerCase();
				boolean all = true;
				for(String word : words) {
					if(!haystack.contains(word)) {
						all = false;
						break;
					}
				}

				if(all) {
					matches.add(new Match(name, matcher.group(3), matcher.group(2)));
				}
			}
		} finally {
			reader.close();
		}

		return matches;
	}

	/**
	 * The text of a match's parametric profile, ready for
	 * {@link AutoEqProfile#parse(String)}.
	 *
	 * The file is named after the folder it sits in, which is how the project lays them out -
	 * so the folder's own last part is the name to ask for.
	 */
	public static String fetchProfile(Match match) throws IOException {
		String path = match.path;
		if(path.startsWith("./")) {
			path = path.substring(2);
		}
		path = URLDecoder.decode(path, "UTF-8");

		int lastSlash = path.lastIndexOf('/');
		String model = lastSlash == -1 ? path : path.substring(lastSlash + 1);

		return fetch(RESULTS + path + "/" + model + " ParametricEQ.txt");
	}

	/**
	 * One file from the repository, by its path within it.
	 *
	 * Tried plainly first and through the API only when the plain host has had enough of
	 * this network. The two are counted separately, so a phone that has been turned away by
	 * one will usually still be served by the other - and the API's own allowance is small
	 * enough that it is worth keeping for when it is needed, rather than using by default.
	 */
	private static String fetch(String repoPath) throws IOException {
		String encoded = encodePath(repoPath);

		try {
			return get(RAW_HOST + encoded, "text/plain,text/markdown,*/*");
		} catch(RateLimitedException x) {
			Log.i(TAG, "Rate limited on raw.githubusercontent.com, trying the API");
			// Asks for the file itself rather than the JSON describing it, which is also
			// the only form the API will serve once a file gets past a megabyte.
			return get(API_HOST + encoded + "?ref=" + BRANCH, "application/vnd.github.raw");
		}
	}

	/** Spaces and brackets in headphone names, kept as a path rather than escaped away. */
	private static String encodePath(String path) throws IOException {
		StringBuilder encoded = new StringBuilder();
		for(String segment : path.split("/")) {
			if(encoded.length() > 0) {
				encoded.append('/');
			}
			encoded.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
		}
		return encoded.toString();
	}

	private static InputStream openIndex(Context context) throws IOException {
		File cached = new File(context.getCacheDir(), INDEX_FILE);
		if(cached.exists() && System.currentTimeMillis() - cached.lastModified() < MAX_AGE_MS) {
			return new java.io.FileInputStream(cached);
		}

		Log.i(TAG, "Fetching the AutoEq index");
		byte[] index = fetch(INDEX_PATH).getBytes("UTF-8");

		OutputStream out = new FileOutputStream(cached);
		try {
			out.write(index);
		} finally {
			out.close();
		}

		return new java.io.ByteArrayInputStream(index);
	}

	private static String get(String url, String accept) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT);
			connection.setReadTimeout(READ_TIMEOUT);
			connection.setRequestProperty("Accept", accept);
			// The API turns away anything that will not say who it is.
			connection.setRequestProperty("User-Agent", "DSub");

			int status = connection.getResponseCode();
			if(status == HttpURLConnection.HTTP_NOT_FOUND) {
				throw new DownloadException("AutoEq has no file for those headphones");
			} else if(status == HTTP_TOO_MANY_REQUESTS || status == HttpURLConnection.HTTP_FORBIDDEN) {
				// GitHub counts these per network address rather than per app, so it is
				// usually something else on the same connection having been busy, and it
				// clears on its own. Whoever catches this decides whether there is anywhere
				// else worth asking; retrying the same host would only spend what is left.
				throw new RateLimitedException("GitHub is rate limiting downloads from this network"
						+ " (HTTP " + status + "). It clears on its own - try again in a few"
						+ " minutes, or paste the profile from a browser instead.");
			} else if(status < 200 || status >= 300) {
				throw new DownloadException("AutoEq download failed: HTTP " + status);
			}

			InputStream in = connection.getInputStream();
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while((read = in.read(buffer)) != -1) {
				body.write(buffer, 0, read);
			}
			return new String(body.toByteArray(), "UTF-8");
		} finally {
			connection.disconnect();
		}
	}
}
