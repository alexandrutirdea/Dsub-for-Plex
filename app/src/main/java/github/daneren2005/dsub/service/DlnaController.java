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

import java.util.List;
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
import github.daneren2005.dsub.service.dlna.DlnaRenderer;
import github.daneren2005.dsub.service.upnp.UpnpSoap;
import github.daneren2005.dsub.util.Util;

/**
 * Plays the queue on a UPnP MediaRenderer - a TV, a receiver, a network speaker.
 *
 * <h3>One track at a time</h3>
 * A renderer holds a single URI, not a queue, so unlike {@link SonosController} this
 * cannot hand over the whole list and let the device work through it. DSub stays in
 * charge: it pushes a track, polls until the renderer says it has stopped, and pushes the
 * next one.
 *
 * <h3>Gapless where the renderer allows it</h3>
 * AVTransport has an optional second slot - SetNextAVTransportURI - which a renderer
 * fetches and opens while the current track is still playing, so the boundary costs
 * nothing. Renderers that implement it are driven that way and DSub is told about each
 * handover after the fact; the rest keep the one-track-at-a-time path above, where the gap
 * is the fetch. Which of the two a device gets is decided by reading what it says it
 * implements, in {@code ProbeGapless}.
 *
 * <h3>Telling the end of a track from someone pressing stop</h3>
 * Both arrive as the same STOPPED. They are told apart by where playback had got to when
 * it was last seen running: at the end of the track it is the track finishing and the
 * queue moves on, anywhere else it is somebody stopping the renderer and DSub pauses
 * rather than marching on through the album against them.
 */
public class DlnaController extends RemoteController {
	private static final String TAG = DlnaController.class.getSimpleName();
	private static final long STATUS_UPDATE_INTERVAL_SECONDS = 2L;
	private static final int MAX_VOLUME = 100;
	/**
	 * How close to the end counts as having reached it. Renderers stop a little short of
	 * the stated duration often enough that an exact comparison would read the end of
	 * nearly every track as somebody having pressed stop.
	 */
	private static final int END_TOLERANCE_SECONDS = 10;

	private final DlnaRenderer renderer;
	private final Handler handler;
	private volatile boolean running = false;
	private final TaskQueue tasks = new TaskQueue();
	private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> statusUpdateFuture;

	private final AtomicLong timeOfLastUpdate = new AtomicLong();
	private int positionSeconds;
	private int durationSeconds;
	private boolean remotePlaying;
	private int volume = 25;

	/** Set once the renderer has been seen playing the track it was last handed. */
	private boolean sawPlaying;
	/** Where to pick the track up again, when pausing had to be done by stopping. */
	private volatile int resumeSeconds = -1;

	/** The URL queued behind the one playing, and the track it belongs to. */
	private String nextUri;
	private DownloadFile nextFile;
	/** Consecutive polls finding the renderer stopped. */
	private int stoppedPolls;
	/**
	 * The track the proxy could not deliver whole, and how far in the last attempt at
	 * rescuing it started. Written from a proxy thread and read from the worker.
	 */
	private volatile DownloadFile truncatedFile;
	private volatile int recoveredFrom = -1;
	/** Where playback had got to when it was last seen running, for reading a stop. */
	private int lastPlayingPosition;
	private int lastPlayingDuration;
	/** When {@link #lastPlayingPosition} was taken, so a stale one can be carried forward. */
	private long timeOfLastPlayingSample;

	public DlnaController(DownloadService downloadService, Handler handler, DlnaRenderer renderer) {
		super(downloadService);
		this.handler = handler;
		this.renderer = renderer;
	}

	public DlnaRenderer getRenderer() {
		return renderer;
	}

	/**
	 * Always relayed through this device, never fetched by the renderer itself.
	 *
	 * A renderer on the living room shelf is not the audience an https music server was
	 * built for: certificate handling ranges from partial to absent, and a URL it cannot
	 * fetch is usually met with silence rather than an error. Proxying makes every case
	 * plain http from this device, which is the one thing they all manage - the same call
	 * {@link SonosController} makes, and for the same reason.
	 */
	@Override
	protected boolean useProxy() {
		return true;
	}

	@Override
	public void create(boolean playing, int seconds) {
		running = true;
		new Thread("DlnaController") {
			@Override
			public void run() {
				processTasks();
			}
		}.start();

		tasks.add(new ProbeGapless());
		if(renderer.hasVolumeControl()) {
			tasks.add(new ReadVolume());
		}
		tasks.add(new LoadTrack(downloadService.getCurrentPlayingIndex(), seconds, playing));
		if(playing) {
			startStatusUpdate();
		}
	}

	@Override
	public void start() {
		startStatusUpdate();

		// A renderer that had to be stopped rather than paused is back at the start of the
		// track, if it still holds it at all, so resuming means loading it again.
		if(resumeSeconds >= 0) {
			int seconds = resumeSeconds;
			resumeSeconds = -1;
			tasks.add(new LoadTrack(downloadService.getCurrentPlayingIndex(), seconds, true));
		} else {
			tasks.add(new Transport("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"));
		}
	}

	/** DSub's pause: {@link DownloadService#stop()} routes through here as well. */
	@Override
	public void stop() {
		stopStatusUpdate();

		// Freeze where the position is reported to be. It is carried forward by the clock
		// between polls, so left running it would keep advancing while the music does not.
		positionSeconds = getRemotePosition();
		timeOfLastUpdate.set(System.currentTimeMillis());
		remotePlaying = false;

		tasks.add(new Pause());
	}

	@Override
	public void shutdown() {
		stopStatusUpdate();
		executorService.shutdownNow();

		// Queued work is abandoned rather than run against a device we are leaving, but
		// the Shutdown task itself has to go through the worker so the thread unblocks
		// from take() and exits instead of leaking.
		tasks.clear();
		tasks.add(new Shutdown());
	}

	/**
	 * Nothing to mirror: the renderer holds at most the track it is playing and the one
	 * queued behind it. A queue edit that changes what comes next arrives separately as
	 * {@link #changeNextTrack}, and one that changes what is playing as
	 * {@link #changeTrack}.
	 */
	@Override
	public void updatePlaylist() {}

	/**
	 * Hands the renderer the track to start the moment the current one ends.
	 *
	 * This is what gaplessness rests on: the device fetches and opens the next stream
	 * while it is still playing, so nothing has to happen at the boundary. Only called at
	 * all on renderers that turned out to implement it - see {@link ProbeGapless}.
	 */
	@Override
	public void changeNextTrack(DownloadFile song) {
		// DSub recomputes what is next on every progress tick, so this arrives again and
		// again with the same track; the work of deciding it is already set is left to the
		// task, which is the only place that knows what the renderer was told.
		tasks.remove(SetNextTrack.class);
		tasks.add(new SetNextTrack(song));
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
		tasks.add(new LoadTrack(index, 0, true));
		downloadService.setPlayerState(PlayerState.STARTED);
	}

	@Override
	public void setVolume(int volume) {
		this.volume = Math.max(0, Math.min(MAX_VOLUME, volume));
		if(renderer.hasVolumeControl()) {
			tasks.remove(SetVolume.class);
			tasks.add(new SetVolume(this.volume));
		}
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

	private void onStatusUpdate(boolean playing, int position, int duration) {
		long now = System.currentTimeMillis();
		boolean wasPlaying = remotePlaying;

		timeOfLastUpdate.set(now);
		positionSeconds = position;
		durationSeconds = duration;
		remotePlaying = playing;

		if(playing) {
			lastPlayingPosition = position;
			lastPlayingDuration = duration;
			timeOfLastPlayingSample = now;
		} else if(wasPlaying) {
			// The music ran on between the last poll and this one, and the poll is a plain
			// sleep on a phone that can suspend, so that gap is not always the couple of
			// seconds it is meant to be. Carrying the sample across it is what tells the
			// end of a track from a stop in the middle of one.
			//
			// Only across this gap, and only the once: it is the last stretch the renderer
			// was known to be playing, so a device paused from its own remote and stopped
			// an hour later still reads as stopped where it was paused.
			lastPlayingPosition += (int) Math.max((now - timeOfLastPlayingSample) / 1000L, 0);
			timeOfLastPlayingSample = now;
		}
	}

	/**
	 * True when playback stopped where the track ends rather than somewhere in it.
	 *
	 * Read against where the renderer had got to rather than where it was last seen -
	 * {@link #onStatusUpdate} carries the sample across the gap it was not being watched
	 * over, which on a sleeping phone is the difference between the two.
	 */
	private boolean stoppedAtEndOfTrack() {
		// A renderer that never reported a duration leaves nothing to compare against, so
		// take the stop at face value and let the queue move on.
		if(lastPlayingDuration <= 0) {
			return true;
		}
		return lastPlayingPosition >= lastPlayingDuration - END_TOLERANCE_SECONDS;
	}

	/**
	 * The renderer moved to the track queued behind on its own.
	 *
	 * Nothing is sent to it here - it is already playing the right thing. All that is left
	 * is for DSub to agree about which track that is, which is the same handover the local
	 * player reports when its own next player takes over.
	 */
	private void onHandedOverToNext() {
		final DownloadFile handedOver = nextFile;
		nextUri = null;
		nextFile = null;
		stoppedPolls = 0;

		// The new track has its own length, and the old one's would make the check for
		// having reached the end read against the wrong number.
		lastPlayingPosition = 0;
		lastPlayingDuration = 0;
		timeOfLastPlayingSample = System.currentTimeMillis();

		if(handedOver != null) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					downloadService.onNextStarted(handedOver);
				}
			});
		}
	}

	/**
	 * Whether two URLs point at the same stream.
	 *
	 * Renderers are not obliged to hand back the URL exactly as it was given - some
	 * re-encode it, some report it against a different address of ours - so the part that
	 * identifies the track is compared rather than the whole string.
	 */
	private static boolean sameUri(String reported, String expected) {
		if(reported == null || expected == null) {
			return false;
		}
		if(reported.trim().equals(expected.trim())) {
			return true;
		}
		return lastSegmentOf(reported).equals(lastSegmentOf(expected));
	}

	private static String lastSegmentOf(String url) {
		String trimmed = url.trim();
		int slash = trimmed.lastIndexOf('/');
		return slash == -1 ? trimmed : trimmed.substring(slash + 1);
	}

	/**
	 * The track finished, so DSub moves the queue on - which comes straight back as a
	 * {@link #changeTrack} carrying the next one.
	 */
	private void onTrackEnded() {
		sawPlaying = false;
		stopStatusUpdate();
		handler.post(new Runnable() {
			@Override
			public void run() {
				downloadService.onSongCompleted();
			}
		});
	}

	/**
	 * Whether this stop is the renderer reaching the end of a stream that was cut short
	 * rather than anybody stopping anything.
	 *
	 * A relay that ends early leaves the renderer holding part of a song, which it plays
	 * to the end of and then reports as stopped - in the middle of the track as far as
	 * everything here can tell, and indistinguishable from somebody pressing stop. The
	 * proxy is the only thing that knows better, and it says so through
	 * {@link #onStreamTruncated}.
	 *
	 * Only if the track has got further than the last attempt at rescuing it did. A server
	 * cutting every stream off at the same point would otherwise have DSub reload the same
	 * track forever; two goes at it that get nowhere and the stop is taken at face value.
	 */
	private boolean ranOutOfStream() {
		return truncatedFile != null && truncatedFile == downloadService.getCurrentPlaying()
				&& lastPlayingPosition > recoveredFrom;
	}

	/**
	 * The renderer played out everything it was given and stopped short of the end of the
	 * track, because the stream it was handed was short.
	 *
	 * The song is loaded again from where it ran out, which is a gap in the music rather
	 * than an end to it - and by now the download that was running alongside the relay has
	 * almost certainly finished, so the second attempt is served off this device and there
	 * is nothing left to go wrong with it.
	 */
	private void onStreamRanOut() {
		final int seconds = lastPlayingPosition;
		recoveredFrom = seconds;
		Log.w(TAG, "Renderer ran out of a short stream at " + seconds + "s; loading the track again from there.");

		// Where the track is being picked up again, rather than the nought a renderer
		// reports once it has stopped, so the progress bar does not drop to the start.
		positionSeconds = seconds;
		timeOfLastUpdate.set(System.currentTimeMillis());

		sawPlaying = false;
		tasks.add(new LoadTrack(downloadService.getCurrentPlayingIndex(), seconds, true));
	}

	/**
	 * Somebody stopped the renderer from somewhere other than DSub - its own remote, or
	 * another app taking it over. Pushing the next track would be arguing with them, so
	 * the queue stays where it is and playback pauses.
	 */
	private void onStoppedElsewhere() {
		sawPlaying = false;
		stopStatusUpdate();
		handler.post(new Runnable() {
			@Override
			public void run() {
				downloadService.setPlayerState(PlayerState.PAUSED);
			}
		});
	}

	/**
	 * Told by the proxy that a song reached the renderer short. Nothing is done here: the
	 * renderer is playing what it has, and it is only when that runs out - and the stop
	 * that follows would otherwise read as somebody else's - that this matters.
	 */
	@Override
	protected void onStreamTruncated(DownloadFile file) {
		if(file != truncatedFile) {
			truncatedFile = file;
			recoveredFrom = -1;
		}
	}

	private void onError(RemoteTask task, Throwable x) {
		Log.e(TAG, "Failed to run DLNA task " + task + ": " + x, x);

		// A renderer that has gone away cannot be recovered from here - dropping back to
		// the phone at least leaves playback possible instead of a dead transport.
		if(task instanceof LoadTrack) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					Util.toast(downloadService, R.string.dlna_error, false);
					downloadService.setRemoteEnabled(RemoteControlState.LOCAL);
				}
			});
		}
	}

	private String avTransport(String action, String innerXml) throws Exception {
		return UpnpSoap.request(renderer.getAvTransportUrl(),
				UpnpSoap.SERVICE_AV_TRANSPORT, action, innerXml);
	}

	private String renderingControl(String action, String innerXml) throws Exception {
		return UpnpSoap.request(renderer.getRenderingControlUrl(),
				UpnpSoap.SERVICE_RENDERING, action, innerXml);
	}

	/**
	 * What the renderer is told about the track it is being handed.
	 *
	 * Renderers vary in how much of this they use - some show every field, some show the
	 * file name whatever is sent - but a well-formed DIDL item is what the ones that do
	 * care are looking for, and the duration is what stops a proxied stream showing up as
	 * a track of unknown length.
	 */
	private static String didlFor(MusicDirectory.Entry song, String url) {
		return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
				+ " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
				+ " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
				+ "<item id=\"0\" parentID=\"0\" restricted=\"1\">"
				+ "<dc:title>" + UpnpSoap.escape(song.getTitle()) + "</dc:title>"
				+ "<dc:creator>" + UpnpSoap.escape(song.getArtist()) + "</dc:creator>"
				+ "<upnp:album>" + UpnpSoap.escape(song.getAlbum()) + "</upnp:album>"
				+ "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
				+ "<res protocolInfo=\"http-get:*:" + mimeTypeOf(song) + ":*\"" + durationAttribute(song) + ">"
				+ UpnpSoap.escape(url) + "</res>"
				+ "</item></DIDL-Lite>";
	}

	private static String durationAttribute(MusicDirectory.Entry song) {
		Integer duration = song.getDuration();
		if(duration == null || duration <= 0) {
			return "";
		}
		return " duration=\"" + UpnpSoap.formatDuration(duration) + "\"";
	}

	/**
	 * The transcoded suffix is what actually arrives, falling back on the original when
	 * the server is not transcoding.
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

	/**
	 * Pause is optional in AVTransport and plenty of renderers do without it, so a refusal
	 * is answered with Stop. That loses the position on the device, so it is kept here for
	 * {@link #start()} to resume from.
	 */
	private class Pause extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			try {
				avTransport("Pause", "<InstanceID>0</InstanceID>");
			} catch(Exception x) {
				Log.i(TAG, "Renderer would not pause, stopping instead: " + x);
				resumeSeconds = positionSeconds;
				avTransport("Stop", "<InstanceID>0</InstanceID>");
			}
			return null;
		}
	}

	/**
	 * Hands the renderer one track and starts it.
	 *
	 * Stopped first because a renderer that is already playing may well refuse to be
	 * pointed at something else, and a refused SetAVTransportURI leaves it playing the
	 * track DSub has already moved on from.
	 */
	private class LoadTrack extends RemoteTask {
		private final int index;
		private final int startSeconds;
		private final boolean play;

		LoadTrack(int index, int startSeconds, boolean play) {
			this.index = index;
			this.startSeconds = startSeconds;
			this.play = play;
		}

		@Override
		RemoteStatus execute() throws Exception {
			List<DownloadFile> songs = downloadService.getSongs();
			if(index < 0 || index >= songs.size()) {
				return null;
			}

			DownloadFile file = songs.get(index);
			MusicService musicService = MusicServiceFactory.getMusicService(downloadService);
			String url = getStreamUrl(musicService, file);

			sawPlaying = false;
			lastPlayingPosition = 0;
			lastPlayingDuration = 0;
			stoppedPolls = 0;
			// Pointing the renderer somewhere new drops whatever was queued behind it, so
			// forget it here; DSub offers the next track again on its own.
			nextUri = null;
			nextFile = null;

			try {
				avTransport("Stop", "<InstanceID>0</InstanceID>");
			} catch(Exception x) {
				// Nothing was playing, on the renderers that treat that as an error.
				Log.i(TAG, "Renderer would not stop before loading: " + x);
			}

			avTransport("SetAVTransportURI", "<InstanceID>0</InstanceID>"
					+ "<CurrentURI>" + UpnpSoap.escape(url) + "</CurrentURI>"
					+ "<CurrentURIMetaData>" + UpnpSoap.escape(didlFor(file.getSong(), url)) + "</CurrentURIMetaData>");

			if(play) {
				avTransport("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
			}
			if(startSeconds > 0) {
				avTransport("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
						+ UpnpSoap.formatDuration(startSeconds) + "</Target>");
			}
			return null;
		}

		@Override
		public String toString() {
			return "LoadTrack(" + index + ")";
		}
	}

	private class GetStatus extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			Element transport = UpnpSoap.parseXml(avTransport("GetTransportInfo", "<InstanceID>0</InstanceID>"));
			String state = UpnpSoap.textOf(transport, "CurrentTransportState");

			// TRANSITIONING is the renderer opening the stream; treating it as stopped
			// would read the start of every track as the end of one.
			boolean playing = "PLAYING".equals(state) || "TRANSITIONING".equals(state);
			if("PLAYING".equals(state)) {
				sawPlaying = true;
			}

			Element position = UpnpSoap.parseXml(avTransport("GetPositionInfo", "<InstanceID>0</InstanceID>"));

			// A renderer playing the track that was queued behind has crossed the
			// boundary by itself, which is the whole point of queueing it. DSub is told
			// the same way the local player reports a gapless handover, so nothing is
			// reloaded and nothing is heard.
			if(nextUri != null && sameUri(UpnpSoap.textOf(position, "TrackURI"), nextUri)) {
				onHandedOverToNext();
			}

			onStatusUpdate(playing,
					Math.max(UpnpSoap.parseDuration(UpnpSoap.textOf(position, "RelTime")), 0),
					UpnpSoap.parseDuration(UpnpSoap.textOf(position, "TrackDuration")));

			// Only meaningful once the track has actually been heard: everything reads as
			// stopped in the moment between being handed a URI and starting to play it.
			boolean stopped = "STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state);
			stoppedPolls = stopped ? stoppedPolls + 1 : 0;

			// A renderer crossing into a queued track can read as stopped for a moment,
			// and acting on that would reload the track it has just started by itself. So
			// while something is queued behind, a stop has to still be there next time.
			if(sawPlaying && stopped && (nextUri == null || stoppedPolls > 1)) {
				if(stoppedAtEndOfTrack()) {
					onTrackEnded();
				} else if(ranOutOfStream()) {
					onStreamRanOut();
				} else {
					onStoppedElsewhere();
				}
			}
			return null;
		}
	}

	/**
	 * Asks the renderer whether it can be given a track to follow the current one.
	 *
	 * SetNextAVTransportURI is optional in AVTransport, and a renderer without it can only
	 * ever be fed one track at a time - the gap being the fetch of the next. Rather than
	 * guess, the service description says which actions are implemented, so gapless is
	 * turned on for the devices that will really do it and left off for the rest, which
	 * keep the reload-on-stop path they already work with.
	 */
	private class ProbeGapless extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			String scpdUrl = renderer.getAvTransportScpdUrl();
			if(scpdUrl == null) {
				Log.i(TAG, renderer.getName() + " publishes no AVTransport description; assuming no gapless.");
				return null;
			}

			if(UpnpSoap.get(scpdUrl).contains("SetNextAVTransportURI")) {
				nextSupported = true;
				Log.i(TAG, renderer.getName() + " takes a next track; playing it gaplessly.");
			} else {
				Log.i(TAG, renderer.getName() + " takes no next track; each track is loaded as the last one ends.");
			}
			return null;
		}
	}

	/**
	 * Queues the track to play after the current one, or clears what was queued when DSub
	 * says there is nothing to follow.
	 */
	private class SetNextTrack extends RemoteTask {
		private final DownloadFile song;

		SetNextTrack(DownloadFile song) {
			this.song = song;
		}

		@Override
		RemoteStatus execute() throws Exception {
			if(song == null) {
				if(nextUri != null) {
					avTransport("SetNextAVTransportURI",
							"<InstanceID>0</InstanceID><NextURI></NextURI><NextURIMetaData></NextURIMetaData>");
					nextUri = null;
					nextFile = null;
				}
				return null;
			}

			// Already queued. Worth checking, because this is asked on every progress tick
			// and re-sending would have the renderer fetch the same stream over and over.
			if(nextFile == song) {
				return null;
			}

			MusicService musicService = MusicServiceFactory.getMusicService(downloadService);
			String url = getStreamUrl(musicService, song);
			avTransport("SetNextAVTransportURI", "<InstanceID>0</InstanceID>"
					+ "<NextURI>" + UpnpSoap.escape(url) + "</NextURI>"
					+ "<NextURIMetaData>" + UpnpSoap.escape(didlFor(song.getSong(), url)) + "</NextURIMetaData>");

			nextUri = url;
			nextFile = song;
			return null;
		}

		@Override
		public String toString() {
			return "SetNextTrack(" + (song == null ? "none" : song.getSong().getTitle()) + ")";
		}
	}

	/** Seeds the volume from the renderer so the slider starts where the device is. */
	private class ReadVolume extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			Element result = UpnpSoap.parseXml(renderingControl("GetVolume",
					"<InstanceID>0</InstanceID><Channel>Master</Channel>"));
			String current = UpnpSoap.textOf(result, "CurrentVolume");
			if(current != null) {
				try {
					volume = Math.max(0, Math.min(MAX_VOLUME, Integer.parseInt(current.trim())));
				} catch(NumberFormatException x) {
					Log.w(TAG, "Renderer reported an unreadable volume: " + current);
				}
			}
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
			renderingControl("SetVolume",
					"<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + volume + "</DesiredVolume>");
			return null;
		}
	}

	private class Shutdown extends RemoteTask {
		@Override
		RemoteStatus execute() throws Exception {
			running = false;
			try {
				avTransport("Stop", "<InstanceID>0</InstanceID>");
			} finally {
				stopProxies();
			}
			return null;
		}
	}
}
