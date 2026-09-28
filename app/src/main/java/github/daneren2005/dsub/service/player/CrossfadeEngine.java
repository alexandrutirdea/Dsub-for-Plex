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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import github.daneren2005.serverproxy.BufferFile;

/**
 * Two tracks overlapping, one going out as the other comes in.
 *
 * A single player cannot do this. Gapless playback is one stream handed straight on to the
 * next, and the engine below walks a playlist to get it - at no point are two tracks
 * sounding at once. So there are two engines here, and which of them is being heard swaps
 * over each time a track ends. They share one audio session, so the equalizer and the
 * loudness enhancer stay attached across the swap rather than being torn down and rebuilt
 * twice a song.
 *
 * The fade begins when the track being heard is within the fade length of its end, and runs
 * for as long as that track really has left rather than for the length that was asked for.
 * The two are usually the same, but the successor is only armed once its own file has
 * buffered, which can be moments before the track being heard ends - and a fade given
 * longer than the overlap it is fading across carries on after the outgoing track has gone,
 * which is heard as the new song starting and then sinking away. That is also the moment the
 * track is reported finished, well before its last samples have gone out: everything above
 * wants to know that the next song is the one playing now, which by then it is. {@link #promoteNext} answers that the successor was already running, and the
 * queue accounts for a track that started early - the same answer a gapless handover gives,
 * for the same reason. The outgoing engine is left to finish fading on its own and is only
 * taken back once it is silent.
 *
 * Not everything can be faded. A track whose length is unknown - a stream that never
 * declared one - has no end to measure back from, and one shorter than twice the fade has
 * nothing left over to fade with. Both fall back on the plain handover, which is what this
 * does anyway whenever {@link #setNextCrossfade} is given nothing - an album playing
 * through, or two records that already begin and end the way the join wants. Fading album
 * tracks into one another destroys exactly what the album was sequenced to do.
 *
 * Nothing here is thread safe, and everything runs on the one thread that owns playback -
 * the same thread both engines were built on, and the one the fade steps are posted to.
 */
public class CrossfadeEngine implements LocalPlayer {
	private static final String TAG = CrossfadeEngine.class.getSimpleName();

	/** How often the fade is stepped, and how often the current track's end is checked. */
	private static final int STEP_MS = 40;
	private static final int WATCH_MS = 200;

	/**
	 * A track has to be at least this many times the fade long to be faded at all, so a
	 * short one is not mostly crossfade.
	 */
	private static final int MINIMUM_TRACK_MULTIPLE = 3;

	/**
	 * An overlap shorter than this is no overlap at all, and takes the plain handover. A
	 * fade this short is inaudible as a fade, and asking for one only risks the two tracks
	 * stepping on each other's first and last moments for nothing.
	 */
	private static final int MINIMUM_OVERLAP_MS = 250;

	/**
	 * How long what is left of a fade is given when the outgoing track runs out inside it.
	 * Long enough not to step, short enough not to be heard as a fade of its own.
	 */
	private static final int CATCH_UP_MS = 250;

	private final ExoPlayerEngine[] engines = new ExoPlayerEngine[2];
	private final Handler handler;
	private final Looper playerLooper;
	/** The longest overlap the setting allows, which no join is given more than. */
	private final int maxFadeMillis;
	/**
	 * What this particular join is allowed, which the queue sets from what it knows about
	 * the two records meeting at it. Zero for a plain handover.
	 */
	private int joinFadeMillis;

	private Listener listener;

	/**
	 * Which of the two engines is the one being heard. Read from whichever thread asks
	 * after a position or a duration, so a swap has to be visible to it straight away.
	 */
	private volatile int currentIndex = 0;

	/** Whether the standby engine holds a prepared successor. */
	private boolean nextPrepared = false;
	/** Whether that successor is set to follow on. */
	private volatile boolean nextArmed = false;

	/** Set while a fade is actually running, between its first step and its last. */
	private boolean fading = false;
	/** The engine being faded out, which is nobody's current track any more. */
	private int fadingOutIndex = -1;
	/**
	 * How long the fade that is running was given: the overlap that was really there, which
	 * is at most the length that was asked for.
	 */
	private int fadeSpan = 0;
	/** When the incoming track was started, which is what the fade is measured from. */
	private long fadeStartedAt = 0;

	/**
	 * A successor handed over while the standby engine was still fading the previous track
	 * out, held until it is free. Preparing it there and then would cut the fade off.
	 */
	private String pendingNextSource;

	/**
	 * The successor's id, held until the engine that will play it is actually given it.
	 *
	 * Not pushed to the standby engine as it arrives, because while a fade is running that
	 * engine is still seeing the previous track out - and it is that track it is about to
	 * report a shape for.
	 */
	private String nextTrackId;

	/** What the volume is turned down for, which the fade is applied on top of. */
	private float userVolume = 1f;


	private Runnable watcher;
	private Runnable ramp;

	public CrossfadeEngine(Context context, int preferredSessionId,
			ExoPlayerEngine.Settings settings, int fadeMillis) {
		this.maxFadeMillis = fadeMillis;
		this.joinFadeMillis = fadeMillis;
		this.playerLooper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
		this.handler = new Handler(playerLooper);

		engines[0] = new ExoPlayerEngine(context, preferredSessionId, settings);
		// The second engine is asked for the session the first one ended up with, so the
		// effects hanging off it cover both. A device that refuses to share it leaves the
		// second engine on its own session, where the equalizer will not reach it - worth
		// saying out loud, but not worth refusing to play over.
		engines[1] = new ExoPlayerEngine(context, engines[0].getAudioSessionId(), settings);
		if(engines[0].getAudioSessionId() != engines[1].getAudioSessionId()) {
			Log.w(TAG, "The two engines are on different audio sessions, so effects will"
					+ " only reach every other track");
		}

		engines[0].setListener(new EngineEvents(0));
		engines[1].setListener(new EngineEvents(1));
	}

	private ExoPlayerEngine current() {
		return engines[currentIndex];
	}

	private ExoPlayerEngine standby() {
		return engines[1 - currentIndex];
	}

	private int standbyIndex() {
		return 1 - currentIndex;
	}

	/**
	 * What each engine reports, read as belonging to the track it is holding rather than to
	 * the engine itself - which of the two is the current track changes as they swap over.
	 */
	private final class EngineEvents implements LocalPlayer.Listener {
		private final int index;

		EngineEvents(int index) {
			this.index = index;
		}

		@Override
		public void onPrepared() {
			if(index == currentIndex) {
				if(listener != null) {
					listener.onPrepared();
				}
			} else {
				nextPrepared = true;
				if(listener != null) {
					listener.onNextPrepared();
				}
			}
		}

		@Override
		public void onCompletion() {
			if(index != currentIndex) {
				// The outgoing track running out somewhere inside its own fade, which is
				// expected and is nothing to report: whatever was owed for that track was
				// reported when the fade began. Running out before the fade was over is
				// another matter, and the fade has to be told.
				if(fading && index == fadingOutIndex) {
					catchUpFade();
				}
				return;
			}
			onCurrentCompleted();
		}

		@Override
		public void onError(Exception error) {
			if(index == currentIndex) {
				if(listener != null) {
					listener.onError(error);
				}
			} else if(listener != null) {
				listener.onNextError(error);
			}
		}

		@Override
		public void onTrackMeasured(String id, github.daneren2005.dsub.util.TrackEnvelope envelope) {
			// Belongs to whichever track it is tagged with, which is the point of the tag -
			// by the time a fading track is measured it is no longer the current one.
			if(listener != null) {
				listener.onTrackMeasured(id, envelope);
			}
		}

		/** Neither engine is ever asked for a successor of its own; that is what the other is for. */
		@Override
		public void onNextPrepared() {}

		@Override
		public void onNextError(Exception error) {}
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public int getAudioSessionId() {
		return engines[0].getAudioSessionId();
	}

	@Override
	public boolean canReadGrowingFile() {
		return engines[0].canReadGrowingFile();
	}

	@Override
	public void prepare(String dataSource) {
		prepare(dataSource, null);
	}

	@Override
	public void prepare(final String dataSource, final BufferFile bufferFile) {
		runOnPlayer(new Runnable() {
			public void run() {
				// A track loaded from here is the queue starting somewhere new, so nothing
				// of what was going on survives it - including a fade half way through.
				stopFade();
				clearNextState();
				standby().reset();
				current().setVolume(userVolume);
				current().prepare(dataSource, bufferFile);
			}
		});
	}

	@Override
	public void prepareNext(final String dataSource) {
		runOnPlayer(new Runnable() {
			public void run() {
				if(fading && fadingOutIndex == standbyIndex()) {
					// The engine that would take it is still seeing the last track out.
					// Held until it is free rather than cutting that short.
					pendingNextSource = dataSource;
					return;
				}
				loadNext(dataSource);
			}
		});
	}

	private void loadNext(String dataSource) {
		pendingNextSource = null;
		nextPrepared = false;
		nextArmed = false;
		ExoPlayerEngine standby = standby();
		standby.reset();
		// Each engine carries one track, so the successor's id is that engine's current one.
		standby.setCurrentTrackId(nextTrackId);
		// Silent until the fade brings it up, so that arming it cannot make a sound of its
		// own before the fade decides it should.
		standby.setVolume(0f);
		standby.prepare(dataSource);
	}

	@Override
	public void armNext() {
		runOnPlayer(new Runnable() {
			public void run() {
				if(!nextPrepared) {
					return;
				}
				nextArmed = true;
				startWatching();
			}
		});
	}

	@Override
	public boolean isNextArmed() {
		return nextArmed;
	}

	@Override
	public void disarmNext() {
		runOnPlayer(new Runnable() {
			public void run() {
				nextArmed = false;
				stopWatching();
			}
		});
	}

	@Override
	public void clearNext() {
		runOnPlayer(new Runnable() {
			public void run() {
				clearNextState();
				if(!(fading && fadingOutIndex == standbyIndex())) {
					standby().reset();
				}
			}
		});
	}

	private void clearNextState() {
		nextArmed = false;
		nextPrepared = false;
		pendingNextSource = null;
		stopWatching();
	}

	@Override
	public boolean promoteNext(final boolean start) {
		return callOnPlayer(new java.util.concurrent.Callable<Boolean>() {
			public Boolean call() {
				stopWatching();

				boolean wasAlreadyPlaying = fading && fadingOutIndex == currentIndex;
				if(wasAlreadyPlaying) {
					// The fade put the successor on air already and told the queue so. All
					// that is left is to agree on which engine is now the current one; the
					// one going out keeps fading where it is.
					currentIndex = standbyIndex();
					nextPrepared = false;
					nextArmed = false;
					return true;
				}

				if(!nextPrepared) {
					// Nothing was waiting, so there is nothing to swap to.
					if(start) {
						current().start();
					}
					return false;
				}

				// Prepared but never faded - the track before it was cut short, or it was
				// never something that could be faded. Straight over, at full volume.
				currentIndex = standbyIndex();
				nextPrepared = false;
				nextArmed = false;
				current().setVolume(userVolume);
				if(start) {
					current().start();
				}
				return false;
			}
		}, false);
	}

	/**
	 * The current track ran out without a fade having started - it was short, its length
	 * was never known, or an album was playing. Reported straight up, and the queue takes
	 * it from there.
	 */
	private void onCurrentCompleted() {
		stopWatching();
		// The successor is still prepared, but the arming came to nothing: no fade started,
		// so nothing was taken up and it is sitting there stopped. Said now, because the
		// queue asks whether a successor is armed to decide whether it still has to be
		// started - and an answer of yes here leaves it waiting for a track that is not
		// playing.
		nextArmed = false;
		if(listener != null) {
			listener.onCompletion();
		}
	}

	// ------------------------------------------------------------------ fading

	/** Watches the current track's remaining time, looking for where the fade should begin. */
	private void startWatching() {
		stopWatching();
		if(joinFadeMillis <= 0) {
			return;
		}

		watcher = new Runnable() {
			public void run() {
				watcher = null;
				if(!nextArmed || fading) {
					return;
				}

				int overlap = fadeableOverlap();
				if(overlap > 0) {
					beginFade(overlap);
				} else {
					startWatching();
				}
			}
		};
		handler.postDelayed(watcher, WATCH_MS);
	}

	private void stopWatching() {
		if(watcher != null) {
			handler.removeCallbacks(watcher);
			watcher = null;
		}
	}

	/**
	 * How long a fade could run for right now, or zero for not yet and not at all.
	 *
	 * What comes back is what the track being heard has left, not the length that was asked
	 * for. The successor is armed the moment its own file has buffered, which is often long
	 * before it is needed but can be seconds before the track being heard ends - and this is
	 * the first look either way. A fade started there has only those seconds to fade across,
	 * however long a fade the setting asks for.
	 */
	private int fadeableOverlap() {
		if(joinFadeMillis <= 0 || !nextPrepared) {
			return 0;
		}

		ExoPlayerEngine playing = engines[currentIndex];
		if(!playing.isPlaying()) {
			// Paused, so the end is not coming any closer. Nothing to do but keep looking.
			return 0;
		}

		int duration = playing.getDuration();
		if(duration <= 0 || duration < joinFadeMillis * MINIMUM_TRACK_MULTIPLE) {
			return 0;
		}

		int remaining = duration - playing.getCurrentPosition();
		if(remaining > joinFadeMillis) {
			// Not near enough the end yet.
			return 0;
		}
		if(remaining <= MINIMUM_OVERLAP_MS) {
			// Nothing worth overlapping, so the plain handover it is - which is what happens
			// on its own once the track runs out.
			return 0;
		}
		return remaining;
	}

	/**
	 * Puts the successor on air under the track that is ending and moves the level from one
	 * to the other.
	 *
	 * The two levels are taken off a quarter turn of a sine rather than off a straight
	 * line, so that the pair of them hold a constant power through the overlap. Two
	 * unrelated recordings do not cancel, they add - and on a straight line the sum sags
	 * by about three decibels in the middle, which is heard as the music dipping exactly
	 * where the join is.
	 */
	private void beginFade(int overlap) {
		final int outIndex = currentIndex;
		final int inIndex = 1 - currentIndex;

		fading = true;
		fadingOutIndex = outIndex;
		fadeSpan = Math.min(joinFadeMillis, overlap);

		engines[inIndex].setVolume(0f);
		engines[inIndex].start();
		// Taken here rather than after the queue has been told, because the track coming in
		// is already running by then. Everything the queue does on being told a track ended
		// - the scrobble, the notification, finding what follows - happens while it plays,
		// and a fade timed from the far side of all that is a fade that starts late on a
		// track that has already been heard to start.
		fadeStartedAt = SystemClock.elapsedRealtime();
		if(fadeSpan < joinFadeMillis) {
			Log.i(TAG, "Crossfading over " + fadeSpan + "ms, which is all the track going"
					+ " out had left of the " + joinFadeMillis + "ms asked for");
		} else {
			Log.i(TAG, "Crossfading over " + fadeSpan + "ms");
		}

		// The track being heard from here on is the one coming in, whatever is still
		// sounding underneath it.
		if(listener != null) {
			listener.onCompletion();
		}

		ramp = new Runnable() {
			public void run() {
				long elapsed = SystemClock.elapsedRealtime() - fadeStartedAt;
				float through = fadeSpan <= 0
						? 1f
						: Math.min(1f, (float) elapsed / (float) fadeSpan);

				double turn = through * Math.PI / 2;
				engines[outIndex].setVolume(userVolume * (float) Math.cos(turn));
				engines[inIndex].setVolume(userVolume * (float) Math.sin(turn));

				if(through < 1f) {
					handler.postDelayed(this, STEP_MS);
				} else {
					ramp = null;
					finishFade(outIndex, inIndex);
				}
			}
		};
		ramp.run();
	}

	/**
	 * Brings what is left of a fade up short, because the track it was fading across has
	 * already gone.
	 *
	 * A length read off a growing file is an estimate, and a file can stop short of the one
	 * it was cut from, so a track can run out inside a fade that was measured to end with
	 * it. From that moment there is nothing on the other side of the fade and what is left
	 * of it is a fade in on a track playing on its own. The level carries on from where it
	 * is rather than jumping, and arrives over a moment too short to be heard as a fade.
	 */
	private void catchUpFade() {
		if(ramp == null || fadeSpan <= 0) {
			return;
		}

		long now = SystemClock.elapsedRealtime();
		float through = Math.min(1f, (float) (now - fadeStartedAt) / (float) fadeSpan);
		float left = 1f - through;
		if(left <= 0.01f) {
			// All but arrived. Left to finish on its own rather than stretched by a
			// hundredth of a turn, which is also what keeps the span below finite.
			return;
		}

		// Re-based rather than restarted: the same quarter turn, stretched so that the part
		// of it still to come takes CATCH_UP_MS, and reading where it already is right now.
		fadeSpan = (int) Math.ceil(CATCH_UP_MS / left);
		fadeStartedAt = now - (long) (through * fadeSpan);
		Log.i(TAG, "The track going out ended inside its own fade, so the rest of the fade"
				+ " is being brought up over " + CATCH_UP_MS + "ms");
	}

	/** The overlap is over: the engine that was going out is silent and can be taken back. */
	private void finishFade(int outIndex, int inIndex) {
		fading = false;
		fadingOutIndex = -1;
		fadeSpan = 0;

		engines[outIndex].reset();
		engines[outIndex].setVolume(userVolume);
		engines[inIndex].setVolume(userVolume);

		if(pendingNextSource != null) {
			// A successor arrived while this engine was still busy. Now it is not.
			loadNext(pendingNextSource);
		}
	}

	/** Abandons a fade in progress, leaving both engines at the volume the user asked for. */
	private void stopFade() {
		stopWatching();
		if(ramp != null) {
			handler.removeCallbacks(ramp);
			ramp = null;
		}
		if(fading && fadingOutIndex != currentIndex) {
			// Never the engine being heard. Between a fade starting and the queue coming
			// back round to promote the successor, the track going out is still the current
			// one, and dropping it there would take the music with it.
			engines[fadingOutIndex].reset();
		}
		fading = false;
		fadingOutIndex = -1;
		fadeSpan = 0;
		engines[0].setVolume(userVolume);
		engines[1].setVolume(userVolume);
	}

	// ------------------------------------------------------------ plain delegation

	@Override
	public void setNextCrossfade(final int millis) {
		runOnPlayer(new Runnable() {
			public void run() {
				joinFadeMillis = Math.max(0, Math.min(maxFadeMillis, millis));
			}
		});
	}

	@Override
	public void setCurrentTrackId(final String id) {
		runOnPlayer(new Runnable() {
			public void run() {
				// Whichever engine is holding the track being heard is the one that will
				// measure it, so the id goes there rather than to both.
				current().setCurrentTrackId(id);
			}
		});
	}

	@Override
	public void setNextTrackId(final String id) {
		runOnPlayer(new Runnable() {
			public void run() {
				nextTrackId = id;
			}
		});
	}

	@Override
	public void start() {
		runOnPlayer(new Runnable() {
			public void run() {
				current().start();
				if(nextArmed) {
					startWatching();
				}
			}
		});
	}

	@Override
	public void pause() {
		runOnPlayer(new Runnable() {
			public void run() {
				current().pause();
				if(fading) {
					// Pausing in the middle of an overlap would leave the outgoing track
					// hanging under the new one for as long as the pause lasts. It has
					// already been reported finished, so it goes now.
					stopFade();
					current().setVolume(userVolume);
				}
			}
		});
	}

	@Override
	public void seekTo(final int position) {
		runOnPlayer(new Runnable() {
			public void run() {
				if(fading) {
					stopFade();
					current().setVolume(userVolume);
				}
				current().seekTo(position);
			}
		});
	}

	@Override
	public void reset() {
		runOnPlayer(new Runnable() {
			public void run() {
				stopFade();
				current().reset();
				current().setVolume(userVolume);
			}
		});
	}

	@Override
	public void release() {
		runOnPlayer(new Runnable() {
			public void run() {
				stopFade();
				engines[0].release();
				engines[1].release();
			}
		});
	}

	@Override
	public int getCurrentPosition() {
		return current().getCurrentPosition();
	}

	@Override
	public int getDuration() {
		return current().getDuration();
	}

	@Override
	public boolean isPlaying() {
		return current().isPlaying();
	}

	@Override
	public void setVolume(final float volume) {
		runOnPlayer(new Runnable() {
			public void run() {
				userVolume = volume;
				if(fading) {
					// The ramp owns both volumes while it runs and will pick this up on its
					// next step; setting them here would undo the fade for one frame.
					return;
				}
				current().setVolume(volume);
			}
		});
	}

	@Override
	public void setTrackGain(final float gain, final boolean isCurrent) {
		runOnPlayer(new Runnable() {
			public void run() {
				// Each engine carries one track, so the successor's level goes to the other
				// engine as its own current level rather than as something held pending.
				(isCurrent ? current() : standby()).setTrackGain(gain, true);
			}
		});
	}

	@Override
	public void setPlaybackSpeed(final float speed) {
		runOnPlayer(new Runnable() {
			public void run() {
				current().setPlaybackSpeed(speed);
			}
		});
	}

	@Override
	public OutputInfo getOutputInfo() {
		return current().getOutputInfo();
	}

	/** Runs on the player's thread, straight away when that is already this one. */
	private void runOnPlayer(Runnable work) {
		if(Looper.myLooper() == playerLooper) {
			work.run();
		} else {
			handler.post(work);
		}
	}

	private <T> T callOnPlayer(java.util.concurrent.Callable<T> work, T fallback) {
		if(Looper.myLooper() == playerLooper) {
			try {
				return work.call();
			} catch(Exception x) {
				Log.w(TAG, "Failed on the player's thread", x);
				return fallback;
			}
		}

		final java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<T>(work);
		handler.post(task);
		try {
			return task.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch(Exception x) {
			Log.w(TAG, "Gave up waiting on the player's thread", x);
			return fallback;
		}
	}
}
