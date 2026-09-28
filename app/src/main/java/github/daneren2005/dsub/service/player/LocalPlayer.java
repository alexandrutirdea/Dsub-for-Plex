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

 Copyright 2026 (C) Scott Jackson
*/
package github.daneren2005.dsub.service.player;

import github.daneren2005.serverproxy.BufferFile;

/**
 * The thing that turns a file into sound on this device.
 *
 * Everything above this interface - the queue, the download list, what counts as played,
 * what the notification says - is {@link github.daneren2005.dsub.service.DownloadService}'s
 * business. Everything below it is one audio engine's, and there is more than one: see
 * {@link MediaPlayerEngine} for the platform player and
 * {@link ExoPlayerEngine} for the one that can be told how to reach the speaker.
 *
 * Two tracks are in play at once. The <em>current</em> one is being heard; the
 * <em>next</em> one is prepared ahead of time and armed so that it takes over without a
 * gap. An engine is free to implement that handover however it likes - a second player
 * handed over by the platform, or a playlist the engine walks by itself - as long as the
 * events below arrive in the same order.
 *
 * Nothing here is thread safe. Calls are expected from the one thread that owns playback.
 */
public interface LocalPlayer {
	/** Where events go. Set once, before anything is prepared. */
	void setListener(Listener listener);

	/**
	 * The audio session the engine plays on, which the equalizer and the loudness
	 * enhancer attach their effects to. Stable for the life of the engine, and shared by
	 * the current and next tracks so that effects survive a gapless handover.
	 */
	int getAudioSessionId();

	/**
	 * Loads a track and prepares it. {@link Listener#onPrepared()} follows when it is
	 * ready to play. Anything already loaded is dropped, and any callback still owed for
	 * it is cancelled.
	 *
	 * @param dataSource a local file path, or a URL when the bytes come from elsewhere
	 */
	void prepare(String dataSource) throws Exception;

	/**
	 * Loads a track whose file is still being written.
	 *
	 * @param bufferFile the download still filling that file, so that an engine which can
	 *		read a growing one waits for the rest rather than taking the end it can see for
	 *		the end of the song. Only ever passed to an engine that says it
	 *		{@link #canReadGrowingFile() can}; null means an ordinary complete source.
	 */
	void prepare(String dataSource, BufferFile bufferFile) throws Exception;

	/**
	 * Whether this engine can be handed a file that is still downloading and read it to
	 * the end as it grows.
	 *
	 * The engines that cannot have to be fed the same file over a socket on this device
	 * instead, which is what {@link github.daneren2005.serverproxy.BufferProxy} is for -
	 * so this is what decides whether that proxy is started at all.
	 */
	boolean canReadGrowingFile();

	/**
	 * Loads the track that follows this one. {@link Listener#onNextPrepared()} follows
	 * when it is ready. Preparing it does not arm it - see {@link #armNext()}.
	 */
	void prepareNext(String dataSource) throws Exception;

	/**
	 * Arms the prepared successor, so that the engine moves to it on its own when the
	 * current track runs out. Only meaningful once {@link Listener#onNextPrepared()} has
	 * arrived.
	 */
	void armNext();

	/** Whether a successor is armed and will be taken up without a gap. */
	boolean isNextArmed();

	/** Leaves the successor prepared but no longer set to follow on. */
	void disarmNext();

	/** Drops the successor entirely, armed or not. */
	void clearNext();

	/**
	 * Makes the successor the current track. Called after the engine has reported the
	 * current one finished, whether or not the handover has already happened.
	 *
	 * @param start whether to start it here, for the case where it is not already running
	 * @return true when the successor was already playing, so the handover was gapless and
	 *		anything measuring position has to account for a track that started early
	 */
	boolean promoteNext(boolean start);

	void start();
	void pause();
	void seekTo(int position);

	/**
	 * Stops the current track and unloads it, along with any handlers owed for it. The
	 * engine stays alive and can be given another track. Does not touch the successor.
	 */
	void reset();

	/** Tears the engine down for good. */
	void release();

	/** Milliseconds into the current track, or 0 when nothing is loaded. */
	int getCurrentPosition();

	/**
	 * Length of the current track in milliseconds, or a value of zero or less when the
	 * engine does not know it - which is the normal case while streaming something whose
	 * length was never declared.
	 */
	int getDuration();

	/** Whether the current track is running right now. */
	boolean isPlaying();

	/**
	 * Linear gain on everything this engine plays, 0 to 1, taking effect at once.
	 *
	 * What the volume is being turned down <em>for</em> rather than anything about the
	 * track: ducking under a notification, the fade the sleep timer asks for. Kept apart
	 * from {@link #setTrackGain} because the two change at different moments and for
	 * different reasons.
	 */
	void setVolume(float volume);

	/**
	 * Linear gain belonging to one particular track, 0 to 1, on top of {@link #setVolume}.
	 *
	 * Replay gain, in other words - a property of the recording, which has to change at
	 * exactly the point one recording gives way to the next and nowhere else. Setting the
	 * successor's ahead of time is the whole point: by the time a gapless handover is
	 * reported it has already happened, and a level applied then is a level applied late.
	 *
	 * @param current true for the track being heard, false for the prepared successor
	 */
	void setTrackGain(float gain, boolean current);

	/**
	 * Rate for the current track, 1.0 being unaltered. An engine is expected to leave the
	 * samples alone at 1.0 rather than run them through a rate stage that happens to be a
	 * no-op.
	 */
	void setPlaybackSpeed(float speed);

	/**
	 * What this engine can say about the path the current track is taking to the speaker,
	 * or null when it can say nothing - which is the honest answer from an engine that is
	 * handed a file and gives back sound with nothing inspectable in between.
	 */
	OutputInfo getOutputInfo();

	/**
	 * How long the handover into the successor may be overlapped for, in milliseconds, or
	 * zero for none - which is a plain gapless handover.
	 *
	 * The queue decides this rather than the engine, because it turns on things only the
	 * queue can see. An album played through is sequenced to run one track straight into
	 * the next, and fading those together throws away the join the record was built around.
	 * A record that fades itself out, or in, has a join of its own already, and laying
	 * another over it is what makes a song seem to start twice. A mix of unrelated tracks
	 * that all start and stop dead has nothing to lose and everything to gain.
	 *
	 * Ignored by an engine that only ever hands over gaplessly, which is all of them but
	 * {@link CrossfadeEngine}.
	 */
	default void setNextCrossfade(int millis) {}

	/**
	 * The id of the track about to be handed to {@link #prepare}, so that anything the
	 * engine measures while playing it can be reported against the right track.
	 *
	 * Told rather than asked because the engine is given a path and a path is not an
	 * identity: the same file is a different track on a different server, and a track being
	 * streamed has no file at all.
	 */
	default void setCurrentTrackId(String id) {}

	/**
	 * The id of the track about to be handed to {@link #prepareNext}, which becomes the
	 * current one once the handover happens.
	 */
	default void setNextTrackId(String id) {}

	interface Listener {
		/** The track handed to {@link #prepare} is ready to play. */
		void onPrepared();

		/** The current track reached its end. */
		void onCompletion();

		/** The current track failed. */
		void onError(Exception error);

		/** The track handed to {@link #prepareNext} is ready. */
		void onNextPrepared();

		/**
		 * How a track turned out to begin and end, once it has played through to its end.
		 *
		 * Only ever for a track that really finished. One that was skipped, seeked inside or
		 * cut short was not heard all the way through, and half a shape is worse than none.
		 */
		default void onTrackMeasured(String id, github.daneren2005.dsub.util.TrackEnvelope envelope) {}

		/** The successor failed while preparing. */
		void onNextError(Exception error);
	}
}
