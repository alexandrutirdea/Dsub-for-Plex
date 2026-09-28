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
	
	Copyright 2009 (C) Sindre Mehus
*/

package github.daneren2005.dsub.service;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.RemoteStatus;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.serverproxy.BufferFile;
import github.daneren2005.serverproxy.CacheAwareProxy;
import github.daneren2005.serverproxy.FileProxy;
import github.daneren2005.serverproxy.ServerProxy;
import github.daneren2005.serverproxy.WebProxy;

public abstract class RemoteController {
	private static final String TAG = RemoteController.class.getSimpleName();
	protected DownloadService downloadService;
	protected boolean nextSupported = false;
	protected ServerProxy proxy;
	/**
	 * Serves songs already downloaded to this device.
	 *
	 * Kept apart from {@link #proxy} rather than swapping that one over, because a queue
	 * can hold both kinds at once: taking down a web proxy that is still relaying a track
	 * the speaker has not finished fetching would cut it off mid-song.
	 */
	protected ServerProxy fileProxy;
	/**
	 * Serves songs that are not on this device yet, and takes them off disk instead as
	 * soon as they are. Kept apart from the other two for the same reason they are kept
	 * apart from each other: one queue can be holding addresses from all three.
	 */
	protected CacheAwareProxy cacheProxy;
	protected String rootLocation = "";

	public RemoteController(DownloadService downloadService) {
		this.downloadService = downloadService;
		SharedPreferences prefs = Util.getPreferences(downloadService);
		rootLocation = prefs.getString(Constants.PREFERENCES_KEY_CACHE_LOCATION, null);
	}

	public abstract void create(boolean playing, int seconds);
	public abstract void start();
	public abstract void stop();
	public abstract void shutdown();
	
	public abstract void updatePlaylist();
	public abstract void changePosition(int seconds);
	public abstract void changeTrack(int index, DownloadFile song);
	// Really is abstract, just don't want to require RemoteController's support it
	public void changeNextTrack(DownloadFile song) {}
	public boolean isNextSupported() {
		if(Util.getPreferences(downloadService).getBoolean(Constants.PREFERENCES_KEY_CAST_GAPLESS_PLAYBACK, true)) {
			return this.nextSupported;
		} else {
			return false;
		}
	}
	public abstract void setVolume(int volume);
	public abstract void updateVolume(boolean up);
	public abstract double getVolume();
	public boolean isSeekable() {
		return true;
	}
	/**
	 * Whether the renderer keeps the queue itself and moves through it on its own, rather
	 * than being handed one track at a time. Such a queue cannot have songs taken off the
	 * front of it without the music stopping - see {@link DownloadService#checkDownloads}.
	 */
	public boolean holdsQueue() {
		return false;
	}
	
	public abstract int getRemotePosition();
	public int getRemoteDuration() {
		return 0;
	}

	protected abstract class RemoteTask {
		abstract RemoteStatus execute() throws Exception;

		@Override
		public String toString() {
			return getClass().getSimpleName();
		}
	}

	protected static class TaskQueue {
		private final LinkedBlockingQueue<RemoteTask> queue = new LinkedBlockingQueue<RemoteTask>();

		void add(RemoteTask jukeboxTask) {
			queue.add(jukeboxTask);
		}

		RemoteTask take() throws InterruptedException {
			return queue.take();
		}

		void remove(Class<? extends RemoteTask> clazz) {
			try {
				Iterator<RemoteTask> iterator = queue.iterator();
				while (iterator.hasNext()) {
					RemoteTask task = iterator.next();
					if (clazz.equals(task.getClass())) {
						iterator.remove();
					}
				}
			} catch (Throwable x) {
				Log.w(TAG, "Failed to clean-up task queue.", x);
			}
		}

		void clear() {
			queue.clear();
		}
	}

	/**
	 * Only the Subsonic backend exposes a socket factory tuned for self-signed
	 * certificates; anything else has to make do with the platform default.
	 */
	private RESTMusicService getRESTMusicService() {
		MusicService musicService = MusicServiceFactory.getMusicService(downloadService);
		if(musicService instanceof CachedMusicService) {
			musicService = ((CachedMusicService)musicService).getMusicService();
		}

		return musicService instanceof RESTMusicService ? (RESTMusicService) musicService : null;
	}

	protected WebProxy createWebProxy() {
		RESTMusicService restMusicService = getRESTMusicService();
		if(restMusicService != null) {
			return new WebProxy(downloadService, restMusicService.getSSLSocketFactory(downloadService), restMusicService.getHostNameVerifier(downloadService));
		} else {
			return new WebProxy(downloadService);
		}
	}

	protected CacheAwareProxy createCacheAwareProxy() {
		RESTMusicService restMusicService = getRESTMusicService();
		if(restMusicService != null) {
			return new CacheAwareProxy(downloadService, restMusicService.getSSLSocketFactory(downloadService), restMusicService.getHostNameVerifier(downloadService));
		} else {
			return new CacheAwareProxy(downloadService);
		}
	}

	/**
	 * Whether the stream should be relayed through this device rather than fetched by
	 * the renderer itself. Normally the user's choice, but a renderer that cannot reach
	 * the server on its own has to override this.
	 */
	protected boolean useProxy() {
		return Util.isCastProxy(downloadService);
	}

	/**
	 * Stops both proxies. Called when a controller is done with the renderer it was
	 * driving, since either may have been left running by {@link #getStreamUrl}.
	 */
	protected void stopProxies() {
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
		if(fileProxy != null) {
			fileProxy.stop();
			fileProxy = null;
		}
		if(cacheProxy != null) {
			cacheProxy.stop();
			cacheProxy = null;
		}
	}

	/**
	 * Serves a song that is already on this device, so playing it needs nothing of the
	 * server at all - not even to stay reachable.
	 */
	private String cachedFileUrl(DownloadFile downloadFile) {
		if(fileProxy == null) {
			fileProxy = new FileProxy(downloadService);
			fileProxy.start();
		}
		return fileProxy.getPublicAddress(downloadFile.getCompleteFile().getPath());
	}

	/**
	 * Serves a song that is not here yet, from the server for now and from this device
	 * from the moment it has finished downloading.
	 *
	 * A renderer that holds a queue is handed every address at once, long before it asks
	 * for any of them - so deciding at this point, which is the moment the cast starts,
	 * would settle the whole queue on the server when hardly any of it has been
	 * downloaded, and settle it for good. The address given out here answers to whichever
	 * is true when the renderer actually asks.
	 */
	private String cacheAwareUrl(MusicService musicService, final DownloadFile downloadFile) throws Exception {
		if(cacheProxy == null) {
			cacheProxy = createCacheAwareProxy();
			cacheProxy.start();
		}

		MusicDirectory.Entry song = downloadFile.getSong();
		// Worked out now rather than on request: it carries the credentials and transcoding
		// the song was queued with, and the request arrives on a proxy thread with no
		// music service of its own.
		final String serverUrl = musicService.getMusicUrl(downloadService, song, downloadFile.getBitRate());

		return cacheProxy.getPublicAddress(song.getId(), new CacheAwareProxy.Source() {
			@Override
			public File getCompleteFile() {
				// Not DownloadFile's own, which names where the file would go rather than
				// promising it is there.
				return downloadFile.isCompleteFileAvailable() ? downloadFile.getCompleteFile() : null;
			}

			@Override
			public String getStreamUrl() {
				return serverUrl;
			}

			@Override
			public BufferFile getDownload() {
				// A download that has run out of attempts is not something to wait on; the
				// server is the only way to the song left.
				return downloadFile.isFailedMax() ? null : downloadFile;
			}

			@Override
			public void onStreamCutShort(boolean recovered) {
				if(recovered) {
					Log.i(TAG, "The stream of " + downloadFile + " was finished off this device.");
				} else {
					Log.w(TAG, "The stream of " + downloadFile + " ended early and could not be finished here.");
					onStreamTruncated(downloadFile);
				}
			}
		});
	}

	/**
	 * A song was handed to the renderer short: what was carrying it - the server, or a
	 * download of it that stopped arriving - ended part way through, and there was no
	 * copy on this device to finish it with.
	 *
	 * Nothing is wrong yet - the renderer has whatever it managed to buffer and is playing
	 * it - but it will run out somewhere inside the track and stop there, which is a
	 * controller's business rather than the proxy's. Does nothing unless a controller says
	 * what to do about it.
	 */
	protected void onStreamTruncated(DownloadFile downloadFile) {}

	protected String getStreamUrl(MusicService musicService, DownloadFile downloadFile) throws Exception {
		MusicDirectory.Entry song = downloadFile.getSong();

		String url;
		// In offline mode or playing offline song
		if(downloadFile.isStream()) {
			url = downloadFile.getStream();
		} else if(Util.shouldCastFromCache(downloadService) && downloadFile.isCompleteFileAvailable()) {
			// The download is finished, so send that rather than relaying the server: the
			// bytes are the same and nothing further is asked of the server, which is the
			// point of the setting. A song that has not finished has nothing to settle yet
			// and is left to the cache aware proxy below.
			url = cachedFileUrl(downloadFile);
		} else if(Util.isOffline(downloadService) || song.getId().indexOf(rootLocation) != -1) {
			if(proxy == null) {
				proxy = new FileProxy(downloadService);
				proxy.start();
			}

			// Offline song
			if(song.getId().indexOf(rootLocation) != -1) {
				url = proxy.getPublicAddress(song.getId());
			} else {
				// Playing online song in offline mode
				url = proxy.getPublicAddress(downloadFile.getCompleteFile().getPath());
			}
		} else if(Util.shouldCastFromCache(downloadService) && useProxy() && !song.isVideo()) {
			// Not here yet, but the renderer fetches through this device either way, so
			// which of the two it gets can be left until it asks. Video is left out: HLS
			// hands over a playlist of segments rather than the one file this would swap in.
			url = cacheAwareUrl(musicService, downloadFile);
		} else {
			// Check if we want a proxy going still
			if(useProxy()) {
				if(proxy instanceof FileProxy) {
					proxy.stop();
					proxy = null;
				}

				if(proxy == null) {
					proxy = createWebProxy();
					proxy.start();
				}
			} else if(proxy != null) {
				proxy.stop();
				proxy = null;
			}

			if(song.isVideo()) {
				url = musicService.getHlsUrl(song.getId(), downloadFile.getBitRate(), downloadService);
			} else {
				url = musicService.getMusicUrl(downloadService, song, downloadFile.getBitRate());
			}

			// If proxy is going, it is a WebProxy
			if(proxy != null) {
				url = proxy.getPublicAddress(url);
			}
		}

		return url;
	}
}
