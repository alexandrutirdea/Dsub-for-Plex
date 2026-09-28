/*
	This file is part of ServerProxy.
	SocketProxy is free software: you can redistribute it and/or modify
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

package github.daneren2005.serverproxy;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import android.content.Context;
import android.util.Log;

/**
 * Serves a song from this device when it is here, out of the download that is bringing it
 * when it is on its way, and relays the server when it is neither - deciding which as late
 * as the request itself.
 *
 * <h3>Why the decision cannot be made when the address is handed out</h3>
 * A renderer that holds a queue - a Sonos speaker - is given every address at once, well
 * before it asks for any of them, and it keeps what it was given. Choosing there means
 * choosing for a whole queue at the moment the cast starts, when almost nothing has been
 * downloaded yet, and the choice can never be revisited however much of the queue has
 * arrived since. Deciding per request instead means each track is served from disk if it
 * got here in time, which for everything past the first song or two it usually has.
 *
 * A source is registered under a key of the caller's choosing and looked up again by the
 * path of the request, so the address stays the same whichever way it is later answered.
 *
 * <h3>The song that is on its way here</h3>
 * There is a third answer between those two, and it is the usual one at the start of a
 * cast: the song is not here yet but it is being downloaded, and the renderer can be fed
 * out of that download as it arrives. Relaying instead would have this device pulling the
 * same song from the same server twice at once - the download racing the relay, over two
 * connections, for one track - which is both wasteful and the way to lose a stream.
 */
public class CacheAwareProxy extends FileProxy {
	private static final String TAG = CacheAwareProxy.class.getSimpleName();

	/** One song, as much of it as is known at the time it is asked about. */
	public interface Source {
		/** The finished file on this device, or null while there is not one. */
		File getCompleteFile();
		/** Where the same audio can be had from the server. */
		String getStreamUrl();
		/**
		 * The download of this song, finished or not, or null where there is no download
		 * to wait on and the server is the only way to it.
		 */
		BufferFile getDownload();
		/**
		 * This song reached the renderer short of what it was told to expect.
		 *
		 * {@code recovered} says whether the rest was served off this device after all.
		 * When it is false the renderer holds part of a song and will stop part way
		 * through it, which is something only the caller can do anything about.
		 */
		void onStreamCutShort(boolean recovered);
	}

	/**
	 * How long a request will wait for the download of the song to say how big it is.
	 *
	 * Answering out of a download means promising a Content-Length, and the download only
	 * learns it when the server answers - which is a fraction of a second after it starts,
	 * but not always before the renderer has asked. Past this the request stops waiting and
	 * relays instead, which is what it would have done anyway.
	 */
	private static final long DOWNLOAD_WAIT = 3000;
	private static final long DOWNLOAD_POLL = 100;
	/**
	 * How long a download that has stopped growing is given before the response is ended
	 * short rather than held open. The download is asked to carry on once a second while
	 * this runs down, so reaching the end of it means nothing is picking it up again.
	 */
	private static final long DOWNLOAD_STALL = 20000;

	private final Map<String, Source> sources = new ConcurrentHashMap<String, Source>();
	/** Song id to the path it was given, so one song keeps one address. */
	private final Map<String, String> paths = new ConcurrentHashMap<String, String>();
	private final SSLSocketFactory sslSocketFactory;
	private final HostnameVerifier hostnameVerifier;
	private int lastPath = 0;

	public CacheAwareProxy(Context context) {
		this(context, null, null);
	}
	public CacheAwareProxy(Context context, SSLSocketFactory sslSocketFactory, HostnameVerifier hostnameVerifier) {
		super(context);
		this.sslSocketFactory = sslSocketFactory;
		this.hostnameVerifier = hostnameVerifier;
	}

	/**
	 * Registers a song and hands back the address the renderer should ask for it at.
	 *
	 * The address is a number of this proxy's own rather than anything drawn from the id,
	 * which for some servers is itself a path: a renderer only has to hand back what it
	 * was given, and the fewer characters that survives the round trip the better.
	 */
	public synchronized String getPublicAddress(String id, Source source) {
		String path = paths.get(id);
		if(path == null) {
			path = Integer.toString(++lastPath);
			paths.put(id, path);
		}

		sources.put(path, source);
		return getPublicAddress(path);
	}

	@Override
	public void stop() {
		super.stop();
		sources.clear();
		paths.clear();
	}

	@Override
	protected ProxyTask getTask(Socket client) {
		return new CacheAwareTask(client);
	}

	protected class CacheAwareTask extends StreamFileTask {
		private Source source;
		/** Whether the song has still to arrive, and the answer is the download or the server. */
		private boolean notHereYet;
		/** The download being read out as it arrives, or null when this is not that case. */
		private BufferFile download;
		/** Set where the response was ended short because the download stopped coming. */
		private boolean gaveUp;
		private long lastLength = -1;
		private long lastGrowth;

		public CacheAwareTask(Socket client) {
			super(client);
		}

		/**
		 * Deliberately not {@link StreamFileTask#processRequest}, which treats a song that
		 * is not on disk as a request that cannot be answered. Here that is the ordinary
		 * case, and the answer is whatever is bringing it.
		 *
		 * Only the song already being here is settled at this point. Which of the other two
		 * answers it is waits for {@link #run}, because deciding costs a wait on the
		 * download and this runs on the thread that accepts connections, where a wait would
		 * be every other request's as well.
		 */
		@Override
		public boolean processRequest() {
			if(!readRequest()) {
				return false;
			}
			parseRange();

			source = sources.get(path);
			if(source == null) {
				Log.w(TAG, "No song registered under " + path);
				return false;
			}

			file = source.getCompleteFile();
			notHereYet = file == null || !file.exists();
			if(notHereYet) {
				Log.i(TAG, path + " is not on this device yet");
				return true;
			}

			Log.i(TAG, "Serving " + path + " from " + file);
			// Reading past the end of the file is the one thing that cannot be answered.
			return cbSkip == 0 || cbSkip < file.length();
		}

		@Override
		public void run() {
			if(!notHereYet) {
				super.run();
				return;
			}

			// A ranged request is left to the server: answering one out of a download means
			// naming the end of the range, and a file still being written has no end yet.
			if(cbSkip == 0) {
				BufferFile candidate = source.getDownload();
				if(candidate != null && waitForLength(candidate)) {
					serveDownload(candidate);
					return;
				}
			}

			Log.i(TAG, "Relaying " + path + " from the server");
			WebProxy.Relay relay = null;
			boolean recovered = false;
			OutputStream output = null;
			try {
				output = new BufferedOutputStream(client.getOutputStream(), 64 * 1024);
				relay = WebProxy.relay(source.getStreamUrl(), requestHeaders, output,
						sslSocketFactory, hostnameVerifier);
				if(!relay.complete && !relay.clientGone) {
					recovered = finishFromFile(output, relay);
					output.flush();
				}
			} catch(Exception e) {
				Log.w(TAG, "Failed to relay " + path, e);
			} finally {
				WebProxy.closeQuietly(output);
				WebProxy.closeQuietly(client);
			}

			// After the socket is closed rather than before: whatever is done about this
			// ends in the renderer being handed the song again, and it cannot pick it up
			// while the connection it is reading the short one on is still open.
			if(relay != null && !relay.complete && !relay.clientGone) {
				source.onStreamCutShort(recovered);
			}
		}

		/**
		 * Waits for the download to say how big the song is, starting it if it is not
		 * already running.
		 *
		 * Without a length there is no Content-Length to give the renderer, and a response
		 * without one is a response it cannot seek in and some renderers will not play at
		 * all. So the length is what decides whether the download can be read out here: it
		 * is known within a moment of the download starting, and the renderer waiting that
		 * moment for its first byte costs nothing next to fetching the song twice.
		 */
		private boolean waitForLength(BufferFile candidate) {
			long until = System.currentTimeMillis() + DOWNLOAD_WAIT;
			while(true) {
				if(candidate.getContentLength() != null || candidate.isWorkDone()) {
					return true;
				}
				if(System.currentTimeMillis() >= until) {
					Log.i(TAG, "Download of " + path + " has not said how long it is; relaying instead");
					return false;
				}

				// The same nudge the streaming loop gives it, for a download that has not
				// been picked up yet - the renderer can ask before the queue gets to it.
				candidate.onResume();
				try {
					Thread.sleep(DOWNLOAD_POLL);
				} catch(InterruptedException e) {
					return false;
				}
			}
		}

		/**
		 * Reads the song out to the renderer as it downloads, the same way a song being
		 * played on this device is read out of the download that is fetching it.
		 *
		 * Holding the download open across this is what keeps the file where it is: a
		 * finished download renames itself, and one being played is left alone until it is
		 * not. That is what {@link BufferFile#onStart} is for and why it is taken here
		 * before the file is looked up rather than after.
		 */
		private void serveDownload(BufferFile candidate) {
			candidate.onStart();
			try {
				download = candidate;
				file = candidate.getFile();
				lastLength = -1;
				lastGrowth = System.currentTimeMillis();
				Log.i(TAG, "Serving " + path + " out of the download of " + file);

				super.run();
			} finally {
				download = null;
				// Balances the hold taken above, and covers the streaming loop's own way
				// out of an unexpected exception, which does not release it.
				candidate.onStop();
			}

			// The renderer has part of a song, for the same reason and to the same effect
			// as a relay that ended early.
			if(gaveUp) {
				source.onStreamCutShort(false);
			}
		}

		@Override
		Long getContentLength() {
			if(download == null) {
				return super.getContentLength();
			}

			// A download that finished without the server ever saying how long it would be
			// has said it now, by being finished: the file is the length.
			Long length = download.getContentLength();
			return (length == null && download.isWorkDone()) ? file.length() : length;
		}

		@Override
		long getFileSize() {
			return (download == null) ? super.getFileSize() : download.getEstimatedSize();
		}

		@Override
		public void onStart() {
			if(download != null) {
				download.onStart();
			}
		}

		@Override
		public void onStop() {
			if(download != null) {
				download.onStop();
			}
		}

		@Override
		public void onResume() {
			if(download != null) {
				download.onResume();
			}
		}

		/**
		 * Done when the download is done and all of it has gone out - or when the download
		 * has stopped arriving altogether, which ends the response rather than holding the
		 * renderer on a stream that is not coming. A download that has failed for good is
		 * asked to carry on once a second by the loop above and answers nothing, so there
		 * is nothing else to wait for.
		 */
		@Override
		public boolean isWorkDone() {
			if(download == null) {
				return super.isWorkDone();
			}
			if(download.isWorkDone()) {
				return cbSkip >= file.length();
			}

			long length = file.length();
			if(length != lastLength) {
				lastLength = length;
				lastGrowth = System.currentTimeMillis();
				return false;
			}
			if(System.currentTimeMillis() - lastGrowth < DOWNLOAD_STALL) {
				return false;
			}

			Log.w(TAG, "Download of " + path + " has not grown past " + length
					+ " bytes; ending the response there");
			gaveUp = true;
			return true;
		}

		/**
		 * Serves the rest of a broken relay off this device.
		 *
		 * A song being cast is usually being downloaded at the same time, over a second
		 * connection to the same server, and the download is the one that tends to finish:
		 * it is read as fast as the network allows while the relay is read at the speed the
		 * renderer drains it. So by the time a relay dies there is often a complete copy of
		 * the very bytes it was carrying sitting on disk, and the renderer can be given the
		 * remainder of them without ever knowing the difference - same response, same
		 * length, the body simply continues.
		 *
		 * Only when the file is exactly as long as the server said its answer would be.
		 * A different length means a different rendering of the song - another bitrate, a
		 * transcode - and splicing two of those together would hand the renderer a corrupt
		 * stream, which is worse than the short one it is getting anyway.
		 */
		private boolean finishFromFile(OutputStream output, WebProxy.Relay relay) {
			File complete = source.getCompleteFile();
			if(complete == null || !complete.exists() || relay.contentLength < 0) {
				return false;
			}

			// cbSkip because a ranged request is answered with the tail of the file, and
			// what the server promised is the length of that tail rather than of the song.
			long fileLength = cbSkip + relay.contentLength;
			if(complete.length() != fileLength) {
				Log.w(TAG, "Not finishing " + path + " from " + complete + ": it holds "
						+ complete.length() + " bytes where the server was sending " + fileLength);
				return false;
			}

			long from = cbSkip + relay.written;
			if(from >= fileLength) {
				// The stream ended on the last byte; there was nothing missing after all.
				return false;
			}

			FileInputStream input = null;
			try {
				input = new FileInputStream(complete);
				input.getChannel().position(from);

				byte[] buffer = new byte[32 * 1024];
				long sent = 0;
				int n;
				while(-1 != (n = input.read(buffer))) {
					output.write(buffer, 0, n);
					sent += n;
				}
				output.flush();

				Log.i(TAG, "Relay of " + path + " ended " + (fileLength - from) + " bytes early; served the rest from " + complete);
				return sent == fileLength - from;
			} catch(Exception e) {
				Log.w(TAG, "Failed to finish " + path + " from " + complete, e);
				return false;
			} finally {
				WebProxy.closeQuietly(input);
			}
		}
	}
}
