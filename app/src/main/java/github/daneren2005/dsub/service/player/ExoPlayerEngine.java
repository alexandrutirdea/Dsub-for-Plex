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
import android.media.AudioFormat;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import github.daneren2005.dsub.util.TrackEnvelope;
import github.daneren2005.serverproxy.BufferFile;

/**
 * The engine that can be told how to reach the speaker.
 *
 * Two things are different in kind from {@link MediaPlayerEngine}, and both are the point
 * of it being here:
 *
 * A track that is still downloading is read straight off the growing file through
 * {@link BufferFileDataSource}, rather than being served to the player over a socket on
 * this device. Running out of bytes is no longer the end of the song, so the queue no
 * longer has to notice a track that ended early and start it again.
 *
 * The successor is a second entry in the engine's own playlist rather than a second
 * player handed over by the platform, so the position it reports belongs to the track it
 * is measuring and the handover needs no compensating for.
 *
 * <b>Threading.</b> ExoPlayer refuses to be touched from any thread but the one it was
 * built on, and DownloadService calls a player from several - the queue's own thread, the
 * one a download finishes on, whichever one the volume comes from. Every call below is
 * therefore run on the player's thread: directly when it is already the caller's, and
 * handed over when it is not.
 */
@OptIn(markerClass = UnstableApi.class)
public class ExoPlayerEngine implements LocalPlayer {
	private static final String TAG = ExoPlayerEngine.class.getSimpleName();

	/** How long to wait on the player thread for an answer before giving up on it. */
	private static final long CALL_TIMEOUT_MS = 2000;

	private final ExoPlayer exoPlayer;
	private final Looper playerLooper;
	private final Handler playerHandler;
	private final DefaultMediaSourceFactory defaultSourceFactory;

	private Listener listener;
	private int audioSessionId;

	/**
	 * The track the engine is playing, as the queue knows it. Only ever used to say what a
	 * measurement belongs to.
	 */
	private String currentTrackId;
	/** The successor's id, which becomes the current one when the successor takes over. */
	private String nextTrackId;

	/** True between a prepare and the ready that answers it, so later readies are not it. */
	private boolean awaitingPrepared = false;
	/** True once the end of the playlist has been reported, so it is only reported once. */
	private boolean endReported = false;

	/** The successor, held from when it is prepared until it is armed or dropped. */
	private MediaSource nextSource;
	/**
	 * Read from whichever thread asks, so that the queue can ask what is armed while
	 * holding its own lock without waiting on this one.
	 */
	private volatile boolean nextArmed = false;
	/**
	 * The current track's length, kept up to date here rather than asked for on demand.
	 * {@link github.daneren2005.dsub.service.DownloadService#getPlayerDuration()} asks for
	 * it while holding the queue's lock, and this thread takes that same lock whenever a
	 * track ends - so an answer that had to be waited for here would be two threads
	 * waiting on each other.
	 */
	private volatile int durationMs = 0;
	/**
	 * The sink that gives each track its own level on the samples themselves. Built as the
	 * engine is, since it is the renderers factory that makes it.
	 */
	private ReplayGainSink gainSink;

	private boolean speedApplied = false;

	/** Whether the sink was built to carry more than sixteen bits per sample. */
	private final boolean floatOutput;
	/** Whether the DSP is actually taking the stream, as opposed to having been offered it. */
	private volatile boolean offloaded = false;

	/**
	 * Kept above the constructor because the constructor hands it to the sink as the
	 * engine is built, which is easier to follow when it is already in sight.
	 */
	private final ExoPlayer.AudioOffloadListener offloadReporter = new ExoPlayer.AudioOffloadListener() {
		@Override
		public void onOffloadedPlayback(boolean offloadedPlayback) {
			Log.i(TAG, "Offloaded playback is " + (offloadedPlayback ? "on" : "off"));
			offloaded = offloadedPlayback;
		}
	};

	public ExoPlayerEngine(Context context, int preferredSessionId, Settings settings) {
		this.floatOutput = settings.floatOutput;
		this.playerLooper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
		this.playerHandler = new Handler(playerLooper);
		this.defaultSourceFactory = new DefaultMediaSourceFactory(context);

		// Float output keeps a twenty four bit recording out of a sixteen bit sink, which
		// is where it lands by default - the one place the engine truncates on its own.
		//
		// The sink is wrapped on the way past so that replay gain can go on the samples
		// rather than on the player, which is the only way a gapless handover can change
		// level at the track boundary rather than shortly after it.
		DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
			/**
			 * Asks the decoder for float only where a recording has something above sixteen
			 * bits to put in it.
			 *
			 * ExoPlayer otherwise asks for every track, the moment the sink says it can take
			 * float at all - so switching high resolution output on puts a sixteen bit
			 * recording through a path built for something else, which is all risk and no
			 * gain. On this phone it is worse than no gain: the decoder and the sink stop
			 * agreeing on how wide a sample is, and what comes out is noise at the wrong
			 * speed.
			 *
			 * A format that does not say what it holds is treated as not needing it, which
			 * is what the player did before any of this was switchable.
			 */
			@Override
			protected void buildAudioRenderers(Context rendererContext, int extensionRendererMode,
					MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback,
					AudioSink sink, Handler eventHandler,
					AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
				out.add(new MediaCodecAudioRenderer(rendererContext, mediaCodecSelector,
						enableDecoderFallback, eventHandler, eventListener, sink) {
					@Override
					protected MediaFormat getMediaFormat(Format sourceFormat, String codecMimeType,
							int codecMaxInputSize, float codecOperatingRate) {
						MediaFormat mediaFormat = super.getMediaFormat(sourceFormat, codecMimeType,
								codecMaxInputSize, codecOperatingRate);

						if(Build.VERSION.SDK_INT >= 24
								&& !Util.isEncodingHighResolutionPcm(sourceFormat.pcmEncoding)) {
							mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
									AudioFormat.ENCODING_PCM_16BIT);
						}

						return mediaFormat;
					}
				});
			}

			@Override
			protected AudioSink buildAudioSink(Context sinkContext, boolean enableFloatOutput,
					boolean enableAudioTrackPlaybackParams) {
				gainSink = new ReplayGainSink(new DefaultAudioSink.Builder(sinkContext)
						.setEnableFloatOutput(enableFloatOutput)
						.setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
						// Whether the DSP took the stream is the sink's to report and only
						// the sink's: ExoPlayer's own offload listeners are told when the
						// player sleeps, which is a different question.
						.setExperimentalAudioOffloadListener(offloadReporter)
						.build());
				return gainSink;
			}
		};
		renderersFactory
				.setEnableAudioFloatOutput(settings.floatOutput)
				.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

		exoPlayer = new ExoPlayer.Builder(context, renderersFactory)
				.setLooper(playerLooper)
				.setMediaSourceFactory(defaultSourceFactory)
				.build();

		// DSub takes and gives up audio focus itself, and has its own idea of what to do
		// when it loses it, so the engine is told to keep its hands off.
		exoPlayer.setAudioAttributes(
				new AudioAttributes.Builder()
						.setUsage(C.USAGE_MEDIA)
						.setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
						.build(),
				/* handleAudioFocus= */ false);
		exoPlayer.setWakeMode(C.WAKE_MODE_LOCAL);

		// Nothing starts on its own. The queue decides when a prepared track plays.
		exoPlayer.setPlayWhenReady(false);
		exoPlayer.setPauseAtEndOfMediaItems(false);

		if(preferredSessionId != -1) {
			try {
				exoPlayer.setAudioSessionId(preferredSessionId);
			} catch(Throwable e) {
				Log.w(TAG, "Failed to use cached audio session", e);
			}
		}
		audioSessionId = exoPlayer.getAudioSessionId();
		if(audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
			audioSessionId = -1;
		}

		if(settings.offload) {
			// Hands the encoded stream to the DSP instead of decoding it here and feeding
			// the mixer, which is the one path on Android where the mixer is out of the
			// way. Asked for rather than insisted on: the platform ignores it for a format
			// its DSP cannot take, and playback carries on the ordinary way.
			exoPlayer.setTrackSelectionParameters(
					exoPlayer.getTrackSelectionParameters().buildUpon()
							.setAudioOffloadPreferences(
									new TrackSelectionParameters.AudioOffloadPreferences.Builder()
											.setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
											// Gapless in offload is not everywhere, so it is
											// only insisted on when gapless is wanted.
											.setIsGaplessSupportRequired(settings.gapless)
											.setIsSpeedChangeSupportRequired(false)
											.build())
							.build());
		}

		if(gainSink == null) {
			// Never seen: the renderers factory is asked for a sink whenever it builds
			// audio renderers, which it always does. Said out loud rather than left to be
			// noticed as replay gain quietly doing nothing.
			Log.w(TAG, "No gain sink was built, so per track levels have nowhere to go");
		}

		exoPlayer.addListener(playerEvents);
	}

	/** What the engine is built to do, which cannot be changed once it has been. */
	public static class Settings {
		public final boolean floatOutput;
		public final boolean offload;
		public final boolean gapless;

		public Settings(boolean floatOutput, boolean offload, boolean gapless) {
			this.floatOutput = floatOutput;
			this.offload = offload;
			this.gapless = gapless;
		}
	}

	private final Player.Listener playerEvents = new Player.Listener() {
		@Override
		public void onPlaybackStateChanged(int playbackState) {
			rememberDuration();

			if(playbackState == Player.STATE_READY) {
				if(awaitingPrepared) {
					awaitingPrepared = false;
					if(listener != null) {
						listener.onPrepared();
					}
				}
			} else if(playbackState == Player.STATE_ENDED) {
				// The playlist ran out, so there was nothing queued behind this one.
				if(!endReported) {
					endReported = true;
					reportEnvelope();
					if(listener != null) {
						listener.onCompletion();
					}
				}
			}
		}

		@Override
		public void onTimelineChanged(Timeline timeline, int reason) {
			rememberDuration();
		}

		@Override
		public void onMediaItemTransition(MediaItem mediaItem, int reason) {
			rememberDuration();

			if(reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
				return;
			}

			// Nothing to do about the level here: the sink took the new track's up at the
			// boundary itself, which is the whole reason it is the sink doing it.

			// The track that was playing reached its end for the successor to start, so
			// what was measured of it is whole. Reported before the id moves on, since it
			// belongs to the track that just ended rather than the one starting.
			reportEnvelope();
			currentTrackId = nextTrackId;
			nextTrackId = null;

			// The track that was playing reached its end for the successor to start.
			if(listener != null) {
				listener.onCompletion();
			}
		}

		@Override
		public void onPlayerError(PlaybackException error) {
			awaitingPrepared = false;
			if(listener != null) {
				listener.onError(error);
			}
		}
	};

	/** Called on the player's thread wherever the length may have just become known. */
	private void rememberDuration() {
		long duration = exoPlayer.getDuration();
		durationMs = (duration == C.TIME_UNSET) ? 0 : (int) duration;
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public void setCurrentTrackId(final String id) {
		runOnPlayer(new Runnable() {
			public void run() {
				currentTrackId = id;
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

	/**
	 * Hands over the shape of the track that has just run out, and clears it either way.
	 *
	 * Called only where a stream reached its own end. Everywhere else the track was cut
	 * short, and what was measured of it describes where it was stopped rather than how it
	 * ends - so it is dropped rather than reported.
	 */
	private void reportEnvelope() {
		if(gainSink == null) {
			return;
		}

		TrackEnvelope envelope = gainSink.takeEnvelope();
		String id = currentTrackId;
		if(envelope != null && envelope.isUsable() && id != null && listener != null) {
			listener.onTrackMeasured(id, envelope);
		}
	}

	@Override
	public int getAudioSessionId() {
		return audioSessionId;
	}

	@Override
	public boolean canReadGrowingFile() {
		return true;
	}

	@Override
	public void prepare(String dataSource) {
		prepare(dataSource, null);
	}

	@Override
	public void prepare(final String dataSource, final BufferFile bufferFile) {
		runOnPlayer(new Runnable() {
			public void run() {
				awaitingPrepared = true;
				endReported = false;
				speedApplied = false;
				durationMs = 0;
				exoPlayer.setPlayWhenReady(false);
				if(gainSink != null) {
					// Whatever was being measured belonged to the track being replaced, which
					// is not going to finish now.
					gainSink.forgetEnvelope();
					// A new track starts at its own level, which the queue sets once it is
					// prepared. Nothing is audible before then.
					gainSink.setCurrentGain(1f);
				}
				exoPlayer.setMediaSource(buildSource(dataSource, bufferFile));
				exoPlayer.prepare();
			}
		});
	}

	@Override
	public void prepareNext(final String dataSource) {
		runOnPlayer(new Runnable() {
			public void run() {
				nextSource = buildSource(dataSource, null);
				nextArmed = false;

				// Nothing has to be read for a successor to be ready here: it is a source
				// waiting to be put in the playlist, and the engine starts reading it once
				// it is. Reported on the next turn of the loop rather than from inside this
				// call, so it arrives the way the platform player's did.
				playerHandler.post(new Runnable() {
					public void run() {
						if(nextSource != null && listener != null) {
							listener.onNextPrepared();
						}
					}
				});
			}
		});
	}

	private MediaSource buildSource(String dataSource, BufferFile bufferFile) {
		MediaItem item = MediaItem.fromUri(toUri(dataSource));
		if(bufferFile != null) {
			return new ProgressiveMediaSource.Factory(new BufferFileDataSource.Factory(bufferFile))
					.createMediaSource(item);
		}
		return defaultSourceFactory.createMediaSource(item);
	}

	private static Uri toUri(String dataSource) {
		if(dataSource.startsWith("/")) {
			return Uri.fromFile(new File(dataSource));
		}
		return Uri.parse(dataSource);
	}

	@Override
	public void armNext() {
		runOnPlayer(new Runnable() {
			public void run() {
				if(nextSource == null || nextArmed) {
					return;
				}
				exoPlayer.addMediaSource(nextSource);
				nextArmed = true;
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
				if(!nextArmed) {
					return;
				}
				int current = exoPlayer.getCurrentMediaItemIndex();
				for(int i = exoPlayer.getMediaItemCount() - 1; i > current; i--) {
					exoPlayer.removeMediaItem(i);
				}
				nextArmed = false;
			}
		});
	}

	@Override
	public void clearNext() {
		runOnPlayer(new Runnable() {
			public void run() {
				if(nextArmed) {
					int current = exoPlayer.getCurrentMediaItemIndex();
					for(int i = exoPlayer.getMediaItemCount() - 1; i > current; i--) {
						exoPlayer.removeMediaItem(i);
					}
					nextArmed = false;
				}
				nextSource = null;
				if(gainSink != null) {
					gainSink.clearPendingGain();
				}
			}
		});
	}

	@Override
	public boolean promoteNext(final boolean start) {
		return callOnPlayer(new Callable<Boolean>() {
			public Boolean call() {
				boolean wasAlreadyPlaying = exoPlayer.getCurrentMediaItemIndex() > 0;

				if(wasAlreadyPlaying) {
					// It took over on its own. Drop what has been played so the track being
					// heard sits at the front of the playlist again.
					while(exoPlayer.getCurrentMediaItemIndex() > 0) {
						exoPlayer.removeMediaItem(0);
					}
				} else if(exoPlayer.getMediaItemCount() > 1) {
					// Armed but not taken up - the track before it was cut short rather
					// than left to run out.
					exoPlayer.seekTo(1, 0);
					exoPlayer.removeMediaItem(0);
					exoPlayer.play();
				} else if(nextSource != null) {
					// Nothing left to follow on from, so it goes in on its own.
					exoPlayer.setMediaSource(nextSource);
					exoPlayer.prepare();
					exoPlayer.play();
				} else {
					exoPlayer.play();
				}

				if(nextTrackId != null) {
					// Whether it took over on its own or was put over by hand, the track
					// that was waiting is the one playing now.
					currentTrackId = nextTrackId;
					nextTrackId = null;
				}

				nextSource = null;
				nextArmed = false;
				speedApplied = false;
				endReported = false;
				awaitingPrepared = false;
				return wasAlreadyPlaying;
			}
		}, false);
	}

	@Override
	public void start() {
		runOnPlayer(new Runnable() {
			public void run() {
				exoPlayer.play();
			}
		});
	}

	@Override
	public void pause() {
		runOnPlayer(new Runnable() {
			public void run() {
				exoPlayer.pause();
			}
		});
	}

	@Override
	public void seekTo(final int position) {
		runOnPlayer(new Runnable() {
			public void run() {
				// Jumping about inside a track leaves a shape with a hole in it, and no way
				// to tell that from a quiet passage. Measuring this one is given up on.
				if(gainSink != null) {
					gainSink.forgetEnvelope();
				}
				exoPlayer.seekTo(position);
			}
		});
	}

	@Override
	public void reset() {
		runOnPlayer(new Runnable() {
			public void run() {
				// Only the current track goes. The successor stays prepared as a source
				// waiting to be armed again, which is what the platform player's reset did
				// with its second player.
				nextArmed = false;
				awaitingPrepared = false;
				endReported = false;
				speedApplied = false;
				durationMs = 0;
				exoPlayer.stop();
				exoPlayer.clearMediaItems();
			}
		});
	}

	@Override
	public void release() {
		runOnPlayer(new Runnable() {
			public void run() {
				exoPlayer.removeListener(playerEvents);
				exoPlayer.release();
			}
		});
	}

	@Override
	public OutputInfo getOutputInfo() {
		return callOnPlayer(new Callable<OutputInfo>() {
			public OutputInfo call() {
				Format format = exoPlayer.getAudioFormat();
				if(format == null) {
					return null;
				}
				return new OutputInfo(
						format.sampleMimeType,
						format.sampleRate == Format.NO_VALUE ? 0 : format.sampleRate,
						format.channelCount == Format.NO_VALUE ? 0 : format.channelCount,
						bitDepthOf(format.pcmEncoding),
						offloaded,
						gainSink == null ? null : describeEncoding(gainSink.getSinkPcmEncoding()));
			}
		}, null);
	}

	/** How one of ExoPlayer's PCM encodings reads, or null when it is not PCM at all. */
	private static String describeEncoding(int pcmEncoding) {
		switch(pcmEncoding) {
			case C.ENCODING_PCM_FLOAT: return "float";
			case C.ENCODING_PCM_8BIT:
			case C.ENCODING_PCM_16BIT:
			case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
			case C.ENCODING_PCM_24BIT:
			case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
			case C.ENCODING_PCM_32BIT:
			case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
				return bitDepthOf(pcmEncoding) + "-bit";
			default:
				return null;
		}
	}

	/**
	 * Bits per sample behind one of ExoPlayer's PCM encodings, or 0 when it does not say -
	 * which is the usual answer for a compressed format, where the depth lives in the
	 * container rather than in what the decoder was asked for.
	 */
	private static int bitDepthOf(int pcmEncoding) {
		switch(pcmEncoding) {
			case C.ENCODING_PCM_8BIT: return 8;
			case C.ENCODING_PCM_16BIT:
			case C.ENCODING_PCM_16BIT_BIG_ENDIAN: return 16;
			case C.ENCODING_PCM_24BIT:
			case C.ENCODING_PCM_24BIT_BIG_ENDIAN: return 24;
			case C.ENCODING_PCM_32BIT:
			case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
			case C.ENCODING_PCM_FLOAT: return 32;
			default: return 0;
		}
	}

	@Override
	public int getCurrentPosition() {
		return callOnPlayer(new Callable<Integer>() {
			public Integer call() {
				long position = exoPlayer.getCurrentPosition();
				return position == C.TIME_UNSET ? 0 : (int) position;
			}
		}, 0);
	}

	@Override
	public int getDuration() {
		// Zero while nothing is known, which is the normal case for a stream whose length
		// was never declared, and what the queue reads as unknown.
		return durationMs;
	}

	@Override
	public boolean isPlaying() {
		return callOnPlayer(new Callable<Boolean>() {
			public Boolean call() {
				return exoPlayer.isPlaying();
			}
		}, false);
	}

	@Override
	public void setVolume(final float volume) {
		runOnPlayer(new Runnable() {
			public void run() {
				// Only ever what the volume is turned down for. Anything belonging to a
				// particular track goes to the sink, which folds the two together itself.
				exoPlayer.setVolume(volume);
			}
		});
	}

	@Override
	public void setTrackGain(final float gain, final boolean current) {
		runOnPlayer(new Runnable() {
			public void run() {
				if(gainSink == null) {
					return;
				}
				if(current) {
					gainSink.setCurrentGain(gain);
				} else {
					gainSink.setPendingGain(gain);
				}
			}
		});
	}

	@Override
	public void setPlaybackSpeed(final float speed) {
		runOnPlayer(new Runnable() {
			public void run() {
				boolean unaltered = Math.abs(speed - 1.0f) < 0.01f;
				if(unaltered && !speedApplied) {
					// Never asked to play at anything but the recorded rate, so the rate
					// stage is left out of the path rather than set to do nothing.
					return;
				}
				exoPlayer.setPlaybackSpeed(speed);
				speedApplied = !unaltered;
			}
		});
	}

	/** Runs on the player's thread, straight away when that is already this one. */
	private void runOnPlayer(Runnable work) {
		if(Looper.myLooper() == playerLooper) {
			work.run();
		} else {
			playerHandler.post(work);
		}
	}

	/**
	 * Asks the player's thread for an answer. Waits for it when called from elsewhere,
	 * which is brief - nothing the player does on its own thread blocks - but capped, so a
	 * player that has wedged cannot take the caller down with it.
	 */
	private <T> T callOnPlayer(final Callable<T> work, T fallback) {
		if(Looper.myLooper() == playerLooper) {
			try {
				return work.call();
			} catch(Exception e) {
				Log.w(TAG, "Player call failed", e);
				return fallback;
			}
		}

		final Object[] result = new Object[1];
		final CountDownLatch done = new CountDownLatch(1);
		playerHandler.post(new Runnable() {
			public void run() {
				try {
					result[0] = work.call();
				} catch(Exception e) {
					Log.w(TAG, "Player call failed", e);
				} finally {
					done.countDown();
				}
			}
		});

		try {
			if(!done.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				Log.w(TAG, "Timed out waiting on the player thread");
				return fallback;
			}
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return fallback;
		}

		@SuppressWarnings("unchecked")
		T value = (T) result[0];
		return value == null ? fallback : value;
	}
}
