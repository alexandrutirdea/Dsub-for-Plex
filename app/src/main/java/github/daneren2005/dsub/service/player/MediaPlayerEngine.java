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
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import github.daneren2005.serverproxy.BufferFile;

/**
 * The platform player, which is what DSub has always played through.
 *
 * Two {@link MediaPlayer}s at a time, the second handed to the first through
 * setNextMediaPlayer so the platform moves between them without a gap, and swapped over
 * here when it does. Everything the platform gets wrong about that arrangement - a
 * position that jumps at the handover, a track that ends early because its file is still
 * being written - is compensated for above this class, in DownloadService.
 *
 * Nothing here decides anything. It is the old inline code, moved.
 */
public class MediaPlayerEngine implements LocalPlayer {
	private static final String TAG = MediaPlayerEngine.class.getSimpleName();

	private final Context context;
	private MediaPlayer mediaPlayer;
	private MediaPlayer nextMediaPlayer;
	private int audioSessionId;
	private boolean nextArmed = false;
	private Listener listener;

	/**
	 * Whether the rate stage has been engaged on the current player. Tracked so that a
	 * rate of 1.0 can be left alone entirely rather than run through a stage that happens
	 * to be a no-op - and so that dropping back to 1.0 from something else still lands.
	 */
	private boolean speedApplied = false;

	/** What the volume is turned down for, which outlives any one track. */
	private float volume = 1f;
	/** The gain belonging to the track being heard, and to the one waiting behind it. */
	private float currentTrackGain = 1f;
	private float nextTrackGain = 1f;

	/**
	 * @param preferredSessionId a session id to reuse, or -1 to be given a fresh one. Kept
	 *		across runs by the caller so that effects attached to it survive a restart, and
	 *		refused by the platform after some upgrades - hence the fallback.
	 */
	public MediaPlayerEngine(Context context, int preferredSessionId) {
		this.context = context;

		mediaPlayer = new MediaPlayer();
		mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);

		audioSessionId = -1;
		if(preferredSessionId != -1) {
			try {
				audioSessionId = preferredSessionId;
				mediaPlayer.setAudioSessionId(audioSessionId);
			} catch(Throwable e) {
				Log.w(TAG, "Failed to use cached audio session", e);
				audioSessionId = -1;
			}
		}

		if(audioSessionId == -1) {
			mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			try {
				audioSessionId = mediaPlayer.getAudioSessionId();
			} catch(Throwable t) {
				// Froyo or lower
			}
		}

		attachCurrentListeners();
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public int getAudioSessionId() {
		return audioSessionId;
	}

	@Override
	public boolean canReadGrowingFile() {
		// It stops at the end of what has been written and calls that the end of the song,
		// which is the whole reason the proxy exists.
		return false;
	}

	@Override
	public void prepare(String dataSource, BufferFile bufferFile) throws Exception {
		prepare(dataSource);
	}

	@Override
	public void prepare(String dataSource) throws Exception {
		mediaPlayer.setOnCompletionListener(null);
		mediaPlayer.setOnPreparedListener(null);
		mediaPlayer.setOnErrorListener(null);
		mediaPlayer.reset();
		speedApplied = false;
		currentTrackGain = 1f;

		try {
			mediaPlayer.setAudioSessionId(audioSessionId);
		} catch(Throwable e) {
			mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		}

		mediaPlayer.setDataSource(dataSource);

		mediaPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
			public void onBufferingUpdate(MediaPlayer mp, int percent) {
				Log.i(TAG, "Buffered " + percent + "%");
				if(percent == 100) {
					mediaPlayer.setOnBufferingUpdateListener(null);
				}
			}
		});
		mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
			public void onPrepared(MediaPlayer mp) {
				if(mediaPlayer != mp) {
					return;
				}
				if(listener != null) {
					listener.onPrepared();
				}
			}
		});
		attachCurrentListeners();

		mediaPlayer.prepareAsync();
	}

	@Override
	public void prepareNext(String dataSource) throws Exception {
		clearNext();
		nextTrackGain = 1f;

		nextMediaPlayer = new MediaPlayer();
		nextMediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
		try {
			nextMediaPlayer.setAudioSessionId(audioSessionId);
		} catch(Throwable e) {
			nextMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		}
		nextMediaPlayer.setDataSource(dataSource);

		nextMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
			public void onPrepared(MediaPlayer mp) {
				// Changed to different while preparing so ignore
				if(nextMediaPlayer != mp) {
					return;
				}
				if(listener != null) {
					listener.onNextPrepared();
				}
			}
		});
		nextMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
			public boolean onError(MediaPlayer mp, int what, int extra) {
				Log.w(TAG, "Error on playing next (" + what + ", " + extra + ")");
				return true;
			}
		});

		nextMediaPlayer.prepareAsync();
	}

	@Override
	public void armNext() {
		if(nextMediaPlayer == null) {
			return;
		}
		mediaPlayer.setNextMediaPlayer(nextMediaPlayer);
		nextArmed = true;
	}

	@Override
	public boolean isNextArmed() {
		return nextArmed;
	}

	@Override
	public void disarmNext() {
		if(nextArmed) {
			mediaPlayer.setNextMediaPlayer(null);
			nextArmed = false;
		}
	}

	@Override
	public void clearNext() {
		try {
			disarmNext();

			if(nextMediaPlayer != null) {
				nextMediaPlayer.setOnPreparedListener(null);
				nextMediaPlayer.setOnCompletionListener(null);
				nextMediaPlayer.setOnErrorListener(null);
				nextMediaPlayer.reset();
				nextMediaPlayer.release();
				nextMediaPlayer = null;
			}
		} catch(Exception e) {
			Log.w(TAG, "Failed to reset next media player");
		}
	}

	@Override
	public boolean promoteNext(boolean start) {
		boolean wasAlreadyPlaying;
		if(start) {
			nextMediaPlayer.start();
			wasAlreadyPlaying = false;
		} else if(!nextMediaPlayer.isPlaying()) {
			Log.w(TAG, "nextSetup lied about it's state!");
			nextMediaPlayer.start();
			wasAlreadyPlaying = false;
		} else {
			Log.i(TAG, "nextMediaPlayer already playing");
			wasAlreadyPlaying = true;
		}

		MediaPlayer finished = mediaPlayer;
		mediaPlayer = nextMediaPlayer;
		nextMediaPlayer = finished;
		nextArmed = false;
		speedApplied = false;

		// The successor was given its level when it was prepared, and is now the track
		// being heard, so that level is the current one.
		currentTrackGain = nextTrackGain;
		nextTrackGain = 1f;

		// The player that just ended is still carrying the handlers for the track it
		// played. Taking them off here rather than leaving them for clearNext() keeps a
		// late callback from it out of the track that is now being heard.
		finished.setOnCompletionListener(null);
		finished.setOnErrorListener(null);
		finished.setOnBufferingUpdateListener(null);

		attachCurrentListeners();
		return wasAlreadyPlaying;
	}

	/**
	 * Points the current player's completion and error callbacks back here. Called
	 * wherever the current player changes: at construction, on preparing a track, and on
	 * the swap that follows a gapless handover, since the player promoted there was only
	 * ever set up to prepare.
	 */
	private void attachCurrentListeners() {
		mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
			public boolean onError(MediaPlayer mp, int what, int extra) {
				if(mediaPlayer != mp) {
					return true;
				}
				if(listener != null) {
					listener.onError(new Exception("MediaPlayer error: " + what + " (" + extra + ")"));
				}
				return true;
			}
		});
		mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			public void onCompletion(MediaPlayer mp) {
				if(mediaPlayer != mp) {
					return;
				}
				if(listener != null) {
					listener.onCompletion();
				}
			}
		});
	}

	@Override
	public void start() {
		mediaPlayer.start();
	}

	@Override
	public void pause() {
		mediaPlayer.pause();
	}

	@Override
	public void seekTo(int position) {
		mediaPlayer.seekTo(position);
	}

	@Override
	public void reset() {
		mediaPlayer.setOnErrorListener(null);
		mediaPlayer.setOnCompletionListener(null);
		disarmNext();
		mediaPlayer.reset();
		speedApplied = false;
	}

	@Override
	public void release() {
		mediaPlayer.release();
		if(nextMediaPlayer != null) {
			nextMediaPlayer.release();
			nextMediaPlayer = null;
		}
	}

	@Override
	public OutputInfo getOutputInfo() {
		// The platform player exposes nothing about what it decoded or where it sent it.
		return null;
	}

	@Override
	public int getCurrentPosition() {
		return mediaPlayer.getCurrentPosition();
	}

	@Override
	public int getDuration() {
		try {
			return mediaPlayer.getDuration();
		} catch(Exception x) {
			return 0;
		}
	}

	@Override
	public boolean isPlaying() {
		return mediaPlayer.isPlaying();
	}

	@Override
	public void setVolume(float volume) {
		this.volume = volume;
		applyCurrentVolume();
		applyNextVolume();
	}

	@Override
	public void setTrackGain(float gain, boolean current) {
		if(current) {
			currentTrackGain = gain;
			applyCurrentVolume();
		} else {
			nextTrackGain = gain;
			applyNextVolume();
		}
	}

	/**
	 * A player carries one volume, so the two reasons for turning the sound down are
	 * multiplied together before it is set. Both are at or below one, so their product is
	 * too, and nothing here can ask for a volume Android would refuse.
	 */
	private void applyCurrentVolume() {
		float level = volume * currentTrackGain;
		mediaPlayer.setVolume(level, level);
	}

	private void applyNextVolume() {
		if(nextMediaPlayer != null) {
			float level = volume * nextTrackGain;
			nextMediaPlayer.setVolume(level, level);
		}
	}

	@Override
	public void setPlaybackSpeed(float speed) {
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return;
		}

		boolean unaltered = Math.abs(speed - 1.0f) < 0.01f;
		if(unaltered && !speedApplied) {
			// Never asked to play at anything but the recorded rate, so the rate stage is
			// left alone rather than engaged on a value that would not change anything.
			return;
		}

		try {
			PlaybackParams playbackParams = new PlaybackParams();
			playbackParams.setSpeed(speed);
			mediaPlayer.setPlaybackParams(playbackParams);
			speedApplied = !unaltered;
		} catch(Exception e) {
			Log.e(TAG, "Error while applying media player params", e);
		}
	}
}
