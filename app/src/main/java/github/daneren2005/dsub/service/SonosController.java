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
*/

package github.daneren2005.dsub.service;

import android.os.Handler;
import android.util.Log;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.PlayerState;
import github.daneren2005.dsub.domain.RemoteControlState;
import github.daneren2005.dsub.domain.RemoteStatus;
import github.daneren2005.dsub.domain.RepeatMode;
import github.daneren2005.dsub.service.sonos.SonosRoom;
import github.daneren2005.dsub.service.upnp.UpnpSoap;
import github.daneren2005.dsub.util.Util;

/**
 * Plays the queue on a Sonos room.
 *
 * <h3>Why the whole queue is pushed to the speaker</h3>
 * Sonos is gapless only across tracks that are already in its own queue, because it
 * prefetches and decodes the next entry while the current one is still playing. Handing it
 * one URI at a time - the way a Chromecast is driven - can never be gapless: the speaker
 * does not learn what comes next until the current track has already ended, and the gap is
 * the fetch. So this controller mirrors the whole of DSub's queue into the speaker's queue
 * and afterwards only ever tells it which entry to sit on.
 *
 * That inverts the usual relationship. DSub is no longer feeding tracks one by one; the
 * speaker advances on its own and this class polls to find out where it got to, then tells
 * {@link DownloadService} to catch up. {@link #changeNextTrack} is therefore not how the
 * next track is delivered - it is only a safety net that makes sure the track really is in
 * the speaker's queue.
 */
public class SonosController extends RemoteController {
	private static final String TAG = SonosController.class.getSimpleName();
	private static final long STATUS_UPDATE_INTERVAL_SECONDS = 2L;
	/** Enqueued before playback starts; the rest follows once the first note is out. */
	private static final int HEAD_TRACKS = 2;
	private static final int MAX_VOLUME = 100;
	/**
	 * What one step of the speaker's volume is taken to be worth, for turning a replay
	 * gain figure into a number of them. Sonos does not publish the curve behind its dial
	 * and it differs between models, so this is an estimate: a 0-100 dial spanning about
	 * 50 dB. Corrections coming out too strong or too weak is this number being wrong.
	 */
	private static final float DB_PER_VOLUME_STEP = 0.5f;
	/** However wrong the estimate above turns out to be, it cannot run away with the dial. */
	private static final int MAX_GAIN_STEPS = 10;
	/** Polls finding a foreign source in a row before the speaker is given up. */
	private static final int FOREIGN_POLLS_BEFORE_RELEASE = 2;
	/** Longest the first track is given to reach the cache before it is streamed instead. */
	private static final long CACHE_WAIT_TIMEOUT_MS = 60000L;
	private static final long CACHE_WAIT_POLL_MS = 1000L;
	/** How often the download is nudged back into life while it is being waited on. */
	private static final long CACHE_WAIT_RETRY_MS = 5000L;

	private final SonosRoom room;
	private final Handler handler;
	private volatile boolean running = false;
	private final TaskQueue tasks = new TaskQueue();
	private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> statusUpdateFuture;

	/** Song ids in the order the speaker holds them, mirroring its queue. */
	private final List<String> speakerQueue = new ArrayList<String>();
	/**
	 * The song the speaker was last seen playing, which tells a load that starts something
	 * new from one that rebuilds the queue under a song already playing. Touched only on
	 * the task thread, as {@link #speakerQueue} is.
	 */
	private String speakerSongId;
	/**
	 * Counts the queue loads that have started music, so a skip can tell whether one has
	 * happened since it was asked for. Read on whichever thread queues a skip and written
	 * on the task thread, hence atomic.
	 */
	private final AtomicLong loads = new AtomicLong();
	/** The track the last such load left the speaker on. Task thread only. */
	private int loadedIndex = -1;
	/** Origins of the URLs handed to the speaker, to tell our streams from anyone else's. */
	private final Set<String> streamOrigins = new HashSet<String>();

	private final AtomicLong timeOfLastUpdate = new AtomicLong();
	private int positionSeconds;
	private int durationSeconds;
	private boolean remotePlaying;
	private int volume = 25;
	/** The volume the user themselves asked for, which replay gain is applied on top of. */
	private int anchorVolume = -1;
	/** Steps of replay gain currently sitting on top of the anchor. */
	private int gainSteps = 0;
	private int foreignPolls;
	/** Set once another source owns the speaker; no command is sent to it after that. */
	private volatile boolean released = false;
	/** Set as soon as shutdown is asked for, so a wait in progress can let go of it. */
	private volatile boolean stopping = false;

	public SonosController(DownloadService downloadService, Handler handler, SonosRoom room) {
		super(downloadService);
		this.handler = handler;
		this.room = room;
		// The speaker holds the queue, so it crosses track boundaries by itself.
		this.nextSupported = true;
	}

	public SonosRoom getRoom() {
		return room;
	}

	@Override
	public boolean holdsQueue() {
		return true;
	}

	/**
	 * Always relayed through this device, never fetched by the speaker itself.
	 *
	 * Sonos will not play an https URL handed to it this way - verified against an Era
	 * 100, which skipped straight past a track whose URL was https and played the very
	 * same file when it was served over http. Since a Plex or Subsonic server reached
	 * over the internet is https, going direct would work only on a plain-http server on
	 * the same network, and fail silently everywhere else. The proxy turns every case
	 * into http from this device, so this is not the user's {@code castProxy} setting to
	 * make - it is what makes Sonos work at all.
	 */
	@Override
	protected boolean useProxy() {
		return true;
	}

	@Override
	public void create(boolean playing, int seconds) {
		running = true;
		new Thread("SonosController") {
			@Override
			public void run() {
				processTasks();
			}
		}.start();

		// Before the queue, so the first album has a real volume to be corrected from.
		tasks.add(new GetVolume());
		tasks.add(new LoadQueue(downloadService.getCurrentPlayingIndex(), seconds, playing));
		if(playing) {
			startStatusUpdate();
		}
	}

	@Override
	public void start() {
		startStatusUpdate();
		tasks.add(new Transport("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"));
	}

	/** DSub's pause: {@link DownloadService#stop()} routes through here as well. */
	@Override
	public void stop() {
		stopStatusUpdate();
		tasks.add(new Transport("Pause", "<InstanceID>0</InstanceID>"));
	}

	@Override
	public void shutdown() {
		stopping = true;
		stopStatusUpdate();
		executorService.shutdownNow();

		// Queued work is abandoned rather than run against a speaker we are leaving, but
		// the Shutdown task itself has to go through the worker so the thread unblocks
		// from take() and exits instead of leaking.
		tasks.clear();
		tasks.add(new Shutdown());
	}

	@Override
	public void updatePlaylist() {
		tasks.remove(SyncQueue.class);
		tasks.add(new SyncQueue());
	}

	@Override
	public void changePosition(int seconds) {
		positionSeconds = seconds;
		timeOfLastUpdate.set(System.currentTimeMillis());

		startStatusUpdate();
		tasks.add(new Transport("Seek",
				"<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + UpnpSoap.formatDuration(seconds) + "</Target>"));
	}

	@Override
	public void changeTrack(int index, DownloadFile song) {
		positionSeconds = 0;
		timeOfLastUpdate.set(System.currentTimeMillis());

		startStatusUpdate();
		tasks.add(new SkipTo(index));
		downloadService.setPlayerState(PlayerState.STARTED);
	}

	/**
	 * Not how the next track is delivered - the speaker already has it. This only covers
	 * the case where DSub grew the queue by one, as shuffle play and artist radio do,
	 * and the addition has not reached the speaker yet.
	 */
	@Override
	public void changeNextTrack(DownloadFile song) {
		if(song == null) {
			return;
		}

		tasks.remove(SyncQueue.class);
		tasks.add(new SyncQueue());
	}

	@Override
	public void setVolume(int volume) {
		this.volume = Math.max(0, Math.min(MAX_VOLUME, volume));

		// Asked for while a correction is in place, this is the user setting where the
		// music should sit now, so what they have chosen is the corrected volume and the
		// anchor the next album is corrected from is what is left underneath it.
		anchorVolume = Math.max(0, Math.min(MAX_VOLUME, this.volume - gainSteps));

		tasks.remove(SetVolume.class);
		tasks.add(new SetVolume(this.volume));
	}

	@Override
	public void updateVolume(boolean up) {
		setVolume(volume + (up ? 5 : -5));
	}

	@Override
	public double getVolume() {
		return volume / (double) MAX_VOLUME;
	}

	@Override
	public int getRemotePosition() {
		if(timeOfLastUpdate.get() == 0) {
			return 0;
		}

		// Polling every couple of seconds would make the progress bar step rather than
		// run, so the reported position is carried forward by the time since it was read.
		if(remotePlaying) {
			return positionSeconds + (int) ((System.currentTimeMillis() - timeOfLastUpdate.get()) / 1000L);
		}
		return positionSeconds;
	}

	@Override
	public int getRemoteDuration() {
		return Math.max(durationSeconds, 0);
	}

	private void processTasks() {
		while(running) {
			RemoteTask task = null;
			try {
				task = tasks.take();
				// Disconnecting runs the usual teardown, which pauses and stops the
				// transport. On a speaker that is now playing someone else's music that
				// would cut the very thing the user switched to, so once it has been given
				// up nothing is sent to it again - only the shutdown itself still runs, to
				// unblock this thread and let go of the proxy.
				if(released && !(task instanceof Shutdown)) {
					continue;
				}
				task.execute();
			} catch(InterruptedException x) {
				return;
			} catch(Throwable x) {
				onError(task, x);
			}
		}
	}

	private synchronized void startStatusUpdate() {
		stopStatusUpdate();
		if(executorService.isShutdown()) {
			return;
		}

		Runnable updateTask = new Runnable() {
			@Override
			public void run() {
				tasks.remove(GetStatus.class);
				tasks.add(new GetStatus());
			}
		};
		statusUpdateFuture = executorService.scheduleWithFixedDelay(updateTask,
				STATUS_UPDATE_INTERVAL_SECONDS, STATUS_UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private synchronized void stopStatusUpdate() {
		if(statusUpdateFuture != null) {
			statusUpdateFuture.cancel(false);
			statusUpdateFuture = null;
		}
	}

	/**
	 * Reads where the speaker actually is and drags DSub to match.
	 *
	 * The speaker is the authority here, not DSub: it moved to the next track by itself,
	 * so a disagreement means DSub is behind, never that the speaker is wrong.
	 *
	 * A stopped speaker is the exception, because its track number is not where playback
	 * got to - it is where playback would start again. Finishing a queue leaves a Sonos
	 * stopped on track 1 at 0:00, not on the last track, so a stop is read here before the
	 * track number is, and the number is only followed while the speaker is on it.
	 */
	private void onStatusUpdate(int trackIndex, boolean playing, boolean stopped, int position, int duration) {
		timeOfLastUpdate.set(System.currentTimeMillis());
		positionSeconds = position;
		durationSeconds = duration;
		remotePlaying = playing;
		// "Last seen playing", so a stopped speaker leaves it where it was: a queue that has
		// just run out reports track 1, and taking that would have the next load believe the
		// speaker is already sitting on the song it is being asked to start.
		if(!stopped && trackIndex >= 0 && trackIndex < speakerQueue.size()) {
			speakerSongId = speakerQueue.get(trackIndex);
		}

		int currentIndex = downloadService.getCurrentPlayingIndex();
		if(stopped && downloadService.getPlayerState() == PlayerState.STARTED) {
			// Reaching the end of the queue stops the speaker rather than moving it on,
			// so this is also how a finished queue is noticed. Mirror the local playback
			// completion sequence so queueEnded gets set and the mini player hides.
			//
			// Before the track number, and on a stop rather than on anything that is merely
			// not playing: the speaker rewinds to track 1 as it stops, which read as it
			// having moved to another track, so DSub followed it back to the top of the
			// album and sat there. The queue never ended and the mini player stayed up.
			downloadService.setPlayerState(PlayerState.COMPLETED);
			downloadService.postPlayCleanup();
			downloadService.play(downloadService.size());
			stopStatusUpdate();
		} else if(!stopped && trackIndex != -1 && trackIndex != currentIndex
				&& trackIndex < downloadService.size()) {
			downloadService.setPlayerState(PlayerState.COMPLETED);
			downloadService.setCurrentPlaying(trackIndex, true);
			if(playing) {
				downloadService.setPlayerState(PlayerState.STARTED);
			}

			// Moving to the next track is DownloadService's cue to look at the queue again:
			// to top it up when shuffle play, artist radio or collection autoplay is on, and
			// to carry on filling the cache ahead of the speaker. Locally that comes of
			// playback going through play(); here the speaker moves by itself and this is
			// the only moment DSub hears about it, so without this the queue is only ever
			// looked at when the user touches it or a download happens to finish.
			downloadService.checkDownloads();
		} else if(!playing && !stopped && downloadService.getPlayerState() == PlayerState.STARTED) {
			// Paused from the speaker's own app or its buttons. It keeps the queue and the
			// track it is on, so this is only DSub agreeing about the state - it is not the
			// end of anything, which is what telling a pause from a stop is for.
			downloadService.setPlayerState(PlayerState.PAUSED);
		} else if(playing && downloadService.getPlayerState() != PlayerState.STARTED
				&& downloadService.getPlayerState() != PlayerState.DOWNLOADING) {
			// The speaker is playing the track DSub thinks it is on, and DSub is not showing
			// it as playing. Following the speaker up as well as down is what makes this
			// recoverable at all: without it a state that goes wrong on the current track
			// stays wrong until the speaker moves to the next one, because that move is the
			// only other thing here that sets STARTED - so exactly one track, the first,
			// goes by unscrobbled with a play button on it and a still progress bar.
			//
			// DOWNLOADING is left alone: that is the cache wait deliberately holding, not a
			// disagreement to correct. Guarded on the state actually differing because
			// setPlayerState is not idempotent - it scrobbles, takes audio focus and reports
			// to the server every time it is called.
			downloadService.setPlayerState(PlayerState.STARTED);
		}
	}

	private void onError(RemoteTask task, Throwable x) {
		// Asking the transport for something its state does not allow is not a failure.
		// Disconnecting pauses the speaker, and a speaker that had already stopped answers
		// 701 rather than doing nothing quietly - so every ordinary disconnect logged an
		// error with a stack trace under it.
		if(x instanceof UpnpSoap.UpnpException
				&& ((UpnpSoap.UpnpException) x).getErrorCode() == UpnpSoap.ERROR_TRANSITION_NOT_AVAILABLE) {
			Log.i(TAG, "Speaker was not in a state to run " + task + "; leaving it alone.");
		} else {
			Log.e(TAG, "Failed to run Sonos task " + task + ": " + x, x);
		}

		// A speaker that has gone away cannot be recovered from here - dropping back to
		// the phone at least leaves playback possible instead of a dead transport. A
		// speaker given up to another source is already on its way to LOCAL and is not a
		// failure to report.
		if(task instanceof LoadQueue && !released) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					Util.toast(downloadService, R.string.sonos_error, false);
					downloadService.setRemoteEnabled(RemoteControlState.LOCAL);
				}
			});
		}
	}

	private String avTransport(String action, String innerXml) throws Exception {
		return UpnpSoap.request(room.getControlUrl("/MediaRenderer/AVTransport/Control"),
				UpnpSoap.SERVICE_AV_TRANSPORT, action, innerXml);
	}

	/**
	 * Whether what the speaker is playing is a stream this device is serving.
	 *
	 * Every track DSub enqueues is proxied from here, so its URI carries the proxy's
	 * address and port - which nothing else on the network hands out. Anything else means
	 * a different source is on the speaker: another app replaced the queue, started a line
	 * in or a Spotify Connect session, or grouped the room under another coordinator.
	 */
	private boolean isOwnStream(String trackUri) {
		if(trackUri == null) {
			return false;
		}

		for(String origin : streamOrigins) {
			if(trackUri.startsWith(origin)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gives up the speaker to whatever took it over.
	 *
	 * DSub cannot keep following a speaker it no longer drives - it would carry on
	 * advancing its own queue against someone else's playback - so it disconnects, which
	 * is what the user would otherwise have to remember to do by hand. Playback is paused
	 * before dropping back to the phone rather than continuing there, since the music has
	 * deliberately been handed to something else and the phone bursting into song instead
	 * is not what was asked for.
	 */
	private void releaseToOtherSource() {
		if(released) {
			return;
		}

		released = true;
		stopStatusUpdate();
		handler.post(new Runnable() {
			@Override
			public void run() {
				Util.toast(downloadService, downloadService.getResources()
						.getString(R.string.sonos_taken_over, room.getName()), false);
				// Not cosmetic: the switch below carries on playing wherever it lands if
				// it finds the player started, which here would be out loud on the phone.
				downloadService.setPlayerState(PlayerState.PAUSED);
				downloadService.setRemoteEnabled(RemoteControlState.LOCAL);
			}
		});
	}

	/** "http://192.168.1.5:41234/" - what a URL from the proxy on this device starts with. */
	private static String originOf(String url) {
		int path = url.indexOf('/', url.indexOf("://") + "://".length());
		return path == -1 ? url : url.substring(0, path + 1);
	}

	/**
	 * Repeat is handed to the speaker rather than handled here, because the speaker is
	 * the thing that decides what follows the last track. Shuffle is not: DSub shuffles
	 * by reordering the queue itself, so the speaker must play it straight through.
	 */
	private String playMode() {
		RepeatMode repeatMode = downloadService.getRepeatMode();
		if(repeatMode == RepeatMode.ALL) {
			return "REPEAT_ALL";
		} else if(repeatMode == RepeatMode.SINGLE) {
			return "REPEAT_ONE";
		}
		return "NORMAL";
	}

	/**
	 * Holds the cast back until the track it starts on is on disk.
	 *
	 * "Cast from this device" serves a local file only for a song whose download has
	 * finished (see {@link RemoteController#getStreamUrl}), and when a cast starts the
	 * song being cast usually has not - so the one track the user is listening to right
	 * then is the one that comes off the server, which is the opposite of what the setting
	 * is asked for. Waiting here is what makes it hold from the first note rather than
	 * from the second song onwards.
	 *
	 * The wait is bounded and gives up rather than failing the cast: relaying the server
	 * is a poor second to serving the file, and a long way better than silence.
	 *
	 * @return the state the wait displaced, or null when there was no wait and so nothing
	 *         to put back. Read under DownloadService's own monitor because the state it
	 *         replaces can be set from the main thread at the same moment: play() calls
	 *         changeTrack(), which sets STARTED, while this is already running on the
	 *         worker. Reading it before taking the lock loses that race and reports the
	 *         cast as paused music nobody asked to pause.
	 */
	private PlayerState awaitCachedFile(DownloadFile file) throws InterruptedException {
		if(file == null || !Util.shouldCastFromCache(downloadService) || file.isStream()
				|| file.isCompleteFileAvailable() || Util.isOffline(downloadService)
				|| !Util.isAllowedToDownload(downloadService)) {
			return null;
		}

		Log.i(TAG, "Waiting for " + file + " to finish downloading before casting it");
		PlayerState displaced;
		synchronized(downloadService) {
			displaced = downloadService.getPlayerState();
			downloadService.setPlayerState(PlayerState.DOWNLOADING);
		}

		long giveUpAt = System.currentTimeMillis() + CACHE_WAIT_TIMEOUT_MS;
		long nextRetry = 0;
		while(System.currentTimeMillis() < giveUpAt) {
			// A download the user cancelled is not going to arrive, and restarting it from
			// here would leave two tasks appending to one partial file for as long as the
			// first takes to notice it has been cancelled.
			if(file.isCompleteFileAvailable() || file.isFailedMax() || file.isDownloadCancelled()
					|| stopping || released) {
				break;
			}
			// Something else has been started - a tap on another song, or the queue moved
			// on - so this is no longer the track the cast is waiting for.
			if(downloadService.getCurrentPlaying() != file) {
				break;
			}

			if(System.currentTimeMillis() >= nextRetry) {
				downloadService.downloadCurrentPlaying();
				nextRetry = System.currentTimeMillis() + CACHE_WAIT_RETRY_MS;
			}
			Thread.sleep(CACHE_WAIT_POLL_MS);
		}

		if(!file.isCompleteFileAvailable()) {
			Log.w(TAG, "Casting " + file + " from the server: it did not finish downloading in time");
		}
		return displaced;
	}

	private void enqueue(DownloadFile file) throws Exception {
		MusicService musicService = MusicServiceFactory.getMusicService(downloadService);
		String url = getStreamUrl(musicService, file);
		MusicDirectory.Entry song = file.getSong();

		String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
				+ " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
				+ " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
				+ "<item id=\"0\" parentID=\"0\" restricted=\"1\">"
				+ "<dc:title>" + UpnpSoap.escape(song.getTitle()) + "</dc:title>"
				+ "<dc:creator>" + UpnpSoap.escape(song.getArtist()) + "</dc:creator>"
				+ "<upnp:album>" + UpnpSoap.escape(song.getAlbum()) + "</upnp:album>"
				+ "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
				// Without a stated duration the speaker reports 0:00 for the whole track,
				// since a proxied stream gives it nothing to work the length out from.
				+ "<res protocolInfo=\"http-get:*:" + mimeTypeOf(song) + ":*\"" + durationAttribute(song) + ">"
				+ UpnpSoap.escape(url) + "</res>"
				+ "</item></DIDL-Lite>";

		avTransport("AddURIToQueue", "<InstanceID>0</InstanceID>"
				+ "<EnqueuedURI>" + UpnpSoap.escape(url) + "</EnqueuedURI>"
				+ "<EnqueuedURIMetaData>" + UpnpSoap.escape(didl) + "</EnqueuedURIMetaData>"
				+ "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>"
				+ "<EnqueueAsNext>0</EnqueueAsNext>");
		speakerQueue.add(file.getSong().getId());
		// A queue mixing cached and streamed songs is served by more than one proxy, each
		// on its own port, so this is a set rather than one address settled on at the start.
		streamOrigins.add(originOf(url));
	}

	private static String durationAttribute(MusicDirectory.Entry song) {
		Integer duration = song.getDuration();
		if(duration == null || duration <= 0) {
			return "";
		}
		return " duration=\"" + UpnpSoap.formatDuration(duration) + "\"";
	}

	/**
	 * Sonos wants a concrete type in the DIDL rather than working it out itself, and gets
	 * unhappy about a wrong one. The transcoded suffix is what actually arrives, falling
	 * back on the original when the server is not transcoding.
	 */
	private static String mimeTypeOf(MusicDirectory.Entry song) {
		String suffix = song.getTranscodedSuffix() != null ? song.getTranscodedSuffix() : song.getSuffix();
		if(suffix == null) {
			return "audio/mpeg";
		}

		suffix = suffix.toLowerCase();
		if("m4a".equals(suffix) || "mp4".equals(suffix) || "aac".equals(suffix)) {
			return "audio/mp4";
		} else if("flac".equals(suffix)) {
			return "audio/flac";
		} else if("ogg".equals(suffix) || "opus".equals(suffix)) {
			return "application/ogg";
		} else if("wav".equals(suffix)) {
			return "audio/wav";
		}
		return "audio/mpeg";
	}

	private static List<String> songIdsOf(List<DownloadFile> files) {
		List<String> ids = new ArrayList<String>(files.size());
		for(DownloadFile file : files) {
			ids.add(file.getSong().getId());
		}
		return ids;
	}

	/** How many entries the speaker's queue and DSub's agree on from the start. */
	private int commonPrefixLength(List<String> desired) {
		int limit = Math.min(desired.size(), speakerQueue.size());
		for(int i = 0; i < limit; i++) {
			if(!desired.get(i).equals(speakerQueue.get(i))) {
				return i;
			}
		}
		return limit;
	}

	private class Transport extends RemoteTask {
		private final String action;
		private final String innerXml;

		Transport(String action, String innerXml) {
			this.action = action;
			this.innerXml = innerXml;
		}

		@Override
		RemoteStatus execute() throws Exception {
			avTransport(action, innerXml);
			return null;
		}

		@Override
		public String toString() {
			return "Transport(" + action + ")";
		}
	}

	private class SkipTo extends RemoteTask {
		private final int index;
		/** Loads that had started music by the time this was asked for. */
		private final long askedAt;

		SkipTo(int index) {
			this.index = index;
			this.askedAt = loads.get();
		}

		@Override
		RemoteStatus execute() throws Exception {
			// Picking an album is one gesture that queues two things: the queue itself,
			// and then the track to play out of it. The load has already put the speaker
			// on that track and started it, so seeking to the same track number here only
			// sends it back to 0:00 - heard as the first track of a cast playing for as
			// long as the rest of the album takes to enqueue and then starting over.
			//
			// Only a load that happened after this was asked for counts. A tap that lands
			// later is the user asking for the track again, and restarting it is what
			// playing on this device does too.
			if(index == loadedIndex && askedAt < loads.get()) {
				Log.i(TAG, "Load already left the speaker playing track " + index + "; not seeking to it again");
				return null;
			}

			// The track has to be in the speaker's queue before it can be skipped to,
			// which it may not be when a tap lands on a song past the enqueued head.
			if(index >= speakerQueue.size()) {
				new SyncQueue().execute();
			}
			if(index < 0 || index >= speakerQueue.size()) {
				return null;
			}

			avTransport("Seek", "<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>" + (index + 1) + "</Target>");
			avTransport("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
			return null;
		}
	}

	/**
	 * Levels an album against the rest by the only means a speaker offers: its volume
	 * dial, moved off the volume the user set.
	 *
	 * Once per queue load rather than per track, because the speaker walks its own queue
	 * and DSub only hears of a track change a poll later - a correction per track would
	 * land a second or two into each one, as a jump. Album gain is one figure for the
	 * album anyway, which is how this gets listened to.
	 */
	private void applyGain(DownloadFile song) {
		if(anchorVolume < 0) {
			// The speaker has never said what it is set to, so there is nothing to correct
			// from and guessing would move the dial to somewhere of our own invention.
			return;
		}

		try {
			float gainDb = downloadService.remoteReplayGainAdjust(song);
			int steps = Math.round(gainDb / DB_PER_VOLUME_STEP);
			steps = Math.max(-MAX_GAIN_STEPS, Math.min(MAX_GAIN_STEPS, steps));
			gainSteps = steps;

			int target = Math.max(0, Math.min(MAX_VOLUME, anchorVolume + steps));
			if(target != volume) {
				Log.i(TAG, "Replay gain of " + gainDb + "dB puts the volume at " + target + " from " + anchorVolume);
				volume = target;
				new SetVolume(target).execute();
			}
		} catch(Throwable x) {
			// This is called from the middle of loading the queue, and a volume that would
			// not move is no reason for the music not to start.
			Log.w(TAG, "Failed to apply replay gain to the speaker's volume", x);
		}
	}

	/**
	 * What the speaker is actually set to. Read rather than assumed, both so the volume
	 * keys move it from where it is and so replay gain has a real figure to work off.
	 */
	private class GetVolume extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			String body;
			try {
				body = UpnpSoap.request(room.getControlUrl("/MediaRenderer/GroupRenderingControl/Control"),
						UpnpSoap.SERVICE_GROUP_RENDERING, "GetGroupVolume", "<InstanceID>0</InstanceID>");
			} catch(Exception x) {
				// Older speakers have no group rendering service; ask the one we address.
				body = UpnpSoap.request(room.getBaseUrl() + "/MediaRenderer/RenderingControl/Control",
						UpnpSoap.SERVICE_RENDERING, "GetVolume",
						"<InstanceID>0</InstanceID><Channel>Master</Channel>");
			}

			String current = UpnpSoap.textOf(UpnpSoap.parseXml(body), "CurrentVolume");
			if(current == null) {
				return null;
			}

			volume = Math.max(0, Math.min(MAX_VOLUME, Integer.parseInt(current.trim())));
			anchorVolume = volume;
			gainSteps = 0;
			return null;
		}
	}

	private class SetVolume extends RemoteTask {
		private final int volume;

		SetVolume(int volume) {
			this.volume = volume;
		}

		@Override
		RemoteStatus execute() throws Exception {
			try {
				UpnpSoap.request(room.getControlUrl("/MediaRenderer/GroupRenderingControl/Control"),
						UpnpSoap.SERVICE_GROUP_RENDERING, "SetGroupVolume",
						"<InstanceID>0</InstanceID><DesiredVolume>" + volume + "</DesiredVolume>");
			} catch(Exception x) {
				// Older speakers have no group rendering service; set each one instead.
				for(String baseUrl : room.getMemberBaseUrls()) {
					UpnpSoap.request(baseUrl + "/MediaRenderer/RenderingControl/Control",
							UpnpSoap.SERVICE_RENDERING, "SetVolume",
							"<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + volume + "</DesiredVolume>");
				}
			}
			return null;
		}
	}

	private class GetStatus extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			Element transport = UpnpSoap.parseXml(avTransport("GetTransportInfo", "<InstanceID>0</InstanceID>"));
			Element position = UpnpSoap.parseXml(avTransport("GetPositionInfo", "<InstanceID>0</InstanceID>"));

			String state = UpnpSoap.textOf(transport, "CurrentTransportState");
			// TRANSITIONING is the moment between tracks; treating it as stopped would
			// read every gapless handover as the queue having ended.
			boolean playing = "PLAYING".equals(state) || "TRANSITIONING".equals(state);
			// A pause is neither, and has to be told from a stop: the speaker stops when its
			// queue runs out and pauses when somebody asks it to, and only one of those means
			// there is nothing left to play.
			boolean stopped = "STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state);

			// Somebody else may have taken the speaker while DSub was playing on it. Two
			// polls have to agree before acting on it, because the URI reads back empty
			// for a moment as the speaker moves between tracks, and one blink is not a
			// reason to walk away from a speaker that is still ours.
			if(!streamOrigins.isEmpty() && !isOwnStream(UpnpSoap.textOf(position, "TrackURI"))) {
				if(++foreignPolls >= FOREIGN_POLLS_BEFORE_RELEASE) {
					releaseToOtherSource();
				}
				// Whatever it is playing is not in DSub's queue, so its track number means
				// nothing here - following it would drag the queue somewhere arbitrary.
				return null;
			}
			foreignPolls = 0;

			String track = UpnpSoap.textOf(position, "Track");
			int trackIndex = -1;
			if(track != null) {
				try {
					trackIndex = Integer.parseInt(track.trim()) - 1;
				} catch(NumberFormatException x) {
					trackIndex = -1;
				}
			}

			onStatusUpdate(trackIndex, playing, stopped,
					Math.max(UpnpSoap.parseDuration(UpnpSoap.textOf(position, "RelTime")), 0),
					UpnpSoap.parseDuration(UpnpSoap.textOf(position, "TrackDuration")));
			return null;
		}
	}

	/**
	 * Replaces whatever the speaker was doing with DSub's queue and starts it.
	 *
	 * Only the tracks up to and including the starting one - plus one to hand over to -
	 * go in before playback begins, because each track is its own SOAP round trip and a
	 * long playlist would otherwise hold up the first note by several seconds. The tail
	 * is appended afterwards, while the first track is already playing.
	 */
	private class LoadQueue extends RemoteTask {
		private final int startIndex;
		private final int startSeconds;
		private final boolean play;

		LoadQueue(int startIndex, int startSeconds, boolean play) {
			this.startIndex = Math.max(startIndex, 0);
			this.startSeconds = startSeconds;
			this.play = play;
		}

		@Override
		RemoteStatus execute() throws Exception {
			List<DownloadFile> songs = new ArrayList<DownloadFile>(downloadService.getSongs());
			if(songs.isEmpty()) {
				return null;
			}

			int index = Math.min(startIndex, songs.size() - 1);
			DownloadFile starting = songs.get(index);
			Log.i(TAG, "Loading " + songs.size() + " songs from " + index + " at " + startSeconds + "s");

			// Waiting is for a song the speaker is not already playing: the start of a cast,
			// or a queue swapped for different music. A rebuild that keeps the same song
			// going must not stop it for the length of a download - and it happens before
			// anything is said to the speaker, so it carries on meanwhile either way.
			PlayerState displaced = starting.getSong().getId().equals(speakerSongId)
					? null : awaitCachedFile(starting);
			if(stopping || released) {
				// Long enough for the user to have gone elsewhere in the meantime.
				return null;
			}

			// Whether to start the music, decided as late as possible rather than taken from
			// the flag this task was built with.
			//
			// That flag is read when the task is queued, on whichever thread queued it, and
			// the request to play routinely lands after it: picking an album calls
			// updatePlaylist() and then play(), so this task is already running by the time
			// changeTrack() sets STARTED. Trusting the flag meant the speaker was never told
			// to play and DSub put itself into PAUSED a second later, which is what left the
			// first track of a cast silent, unscrobbled and showing a play button.
			boolean shouldPlay = play || displaced == PlayerState.STARTED
					|| downloadService.getPlayerState() == PlayerState.STARTED;

			avTransport("Stop", "<InstanceID>0</InstanceID>");
			avTransport("RemoveAllTracksFromQueue", "<InstanceID>0</InstanceID>");
			speakerQueue.clear();
			speakerSongId = null;

			// While the speaker is stopped, so the correction is in place before the first
			// note rather than a moment into it.
			applyGain(starting);

			int head = Math.min(index + HEAD_TRACKS, songs.size());
			for(int i = 0; i < head; i++) {
				enqueue(songs.get(i));
			}

			// Point the speaker at its own queue. Without this it stays on whatever it
			// was last told to play - a radio stream, or another app's URI.
			avTransport("SetAVTransportURI", "<InstanceID>0</InstanceID>"
					+ "<CurrentURI>x-rincon-queue:" + room.getCoordinatorUuid() + "#0</CurrentURI>"
					+ "<CurrentURIMetaData></CurrentURIMetaData>");
			avTransport("SetPlayMode", "<InstanceID>0</InstanceID><NewPlayMode>" + playMode() + "</NewPlayMode>");

			if(index > 0) {
				avTransport("Seek", "<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>" + (index + 1) + "</Target>");
			}
			if(shouldPlay) {
				avTransport("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
			}
			if(startSeconds > 0) {
				avTransport("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
						+ UpnpSoap.formatDuration(startSeconds) + "</Target>");
			}
			if(shouldPlay) {
				// Recorded only where the music was actually started, since a load that
				// left the speaker paused has not done a skip's work for it.
				loadedIndex = index;
				loads.incrementAndGet();
			}

			// Before the tail is appended rather than after, so the UI leaves the download
			// behind the moment the speaker starts instead of at the end of the enqueueing.
			// Only where the wait is still the state showing: a request to play that arrived
			// while it ran has already put STARTED there and must not be written over.
			if(displaced != null && downloadService.getPlayerState() == PlayerState.DOWNLOADING) {
				downloadService.setPlayerState(shouldPlay ? PlayerState.STARTED : PlayerState.PAUSED);
			}

			for(int i = head; i < songs.size(); i++) {
				enqueue(songs.get(i));
			}
			return null;
		}
	}

	/**
	 * Brings the speaker's queue back in line with DSub's after the queue changed.
	 *
	 * Appending, and trimming a tail that has diverged past the current track, both leave
	 * playback untouched - which matters, because DSub calls this every time the queue is
	 * edited and a rebuild would cut the music each time. A rebuild is only unavoidable
	 * when the disagreement reaches the track being played.
	 */
	private class SyncQueue extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			List<DownloadFile> songs = new ArrayList<DownloadFile>(downloadService.getSongs());
			List<String> desired = songIdsOf(songs);
			if(desired.equals(speakerQueue)) {
				return null;
			}
			if(desired.isEmpty()) {
				avTransport("RemoveAllTracksFromQueue", "<InstanceID>0</InstanceID>");
				speakerQueue.clear();
				return null;
			}
			if(speakerQueue.isEmpty()) {
				new LoadQueue(downloadService.getCurrentPlayingIndex(), 0,
						downloadService.getPlayerState() == PlayerState.STARTED).execute();
				return null;
			}

			int shared = commonPrefixLength(desired);
			int currentIndex = Math.max(downloadService.getCurrentPlayingIndex(), 0);

			if(shared <= currentIndex && shared < speakerQueue.size()) {
				// The track being played is itself in the wrong place; nothing short of a
				// rebuild fixes that, so take the interruption.
				//
				// The position is only worth carrying over when the same song is going to
				// carry on playing, which is what a rebuild usually means. It is not what
				// it means when the queue has been swapped for different music - and
				// seeking a song the user has just chosen to wherever the last one had got
				// to reads as the song ending early, out of nowhere.
				boolean sameSong = currentIndex < songs.size()
						&& songs.get(currentIndex).getSong().getId().equals(speakerSongId);
				new LoadQueue(currentIndex, sameSong ? getRemotePosition() : 0,
						downloadService.getPlayerState() == PlayerState.STARTED).execute();
				return null;
			}

			if(shared < speakerQueue.size()) {
				avTransport("RemoveTrackRangeFromQueue", "<InstanceID>0</InstanceID><UpdateID>0</UpdateID>"
						+ "<StartingIndex>" + (shared + 1) + "</StartingIndex>"
						+ "<NumberOfTracks>" + (speakerQueue.size() - shared) + "</NumberOfTracks>");
				while(speakerQueue.size() > shared) {
					speakerQueue.remove(speakerQueue.size() - 1);
				}
			}

			for(int i = speakerQueue.size(); i < songs.size(); i++) {
				enqueue(songs.get(i));
			}
			return null;
		}
	}

	private class Shutdown extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			running = false;
			try {
				if(!released) {
					avTransport("Stop", "<InstanceID>0</InstanceID>");

					// The dial goes back to where the user had it, so replay gain is not
					// left behind on a speaker DSub is no longer playing to.
					if(gainSteps != 0 && anchorVolume >= 0) {
						gainSteps = 0;
						volume = anchorVolume;
						new SetVolume(anchorVolume).execute();
					}
				}
			} finally {
				speakerQueue.clear();
				speakerSongId = null;
				streamOrigins.clear();
				stopProxies();
			}
			return null;
		}
	}
}
