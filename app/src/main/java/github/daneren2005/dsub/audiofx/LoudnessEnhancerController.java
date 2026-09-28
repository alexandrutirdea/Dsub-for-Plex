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

	Copyright 2014 (C) Scott Jackson
*/
package github.daneren2005.dsub.audiofx;

import android.content.Context;
import android.media.audiofx.LoudnessEnhancer;
import android.util.Log;

/**
 * The one loudness enhancer on the audio session, shared by everything that wants to add
 * gain to it - the slider on the equalizer screen and replay gain's boost for the quiet
 * recordings that ask to be turned up.
 *
 * Shared rather than one each: two insert effects of the same type on one session are not
 * two independent controls, and the equalizer screen already carries a workaround for
 * being denied control of an effect somebody else holds. Each caller sets its own share
 * and reads back its own share; what the effect is told is the sum of them.
 */
public class LoudnessEnhancerController {
	private static final String TAG = LoudnessEnhancerController.class.getSimpleName();

	private final Context context;
	private LoudnessEnhancer enhancer;
	private boolean released = false;
	private int audioSessionId = 0;

	/** The equalizer screen's own slider, in millibels, and whether it is switched on. */
	private int userGain;
	private boolean userEnabled;
	/** What replay gain asks for on top of it, in millibels. */
	private int replayGain;

	public LoudnessEnhancerController(Context context, int audioSessionId) {
		this.context = context;
		try {
			this.audioSessionId = audioSessionId;
			enhancer = new LoudnessEnhancer(audioSessionId);
		} catch (Throwable x) {
			Log.w(TAG, "Failed to create enhancer", x);
		}
	}

	public boolean isAvailable() {
		return enhancer != null;
	}

	public boolean isEnabled() {
		return isAvailable() && userEnabled;
	}

	public void enable() {
		userEnabled = true;
		apply();
	}
	public void disable() {
		userEnabled = false;
		apply();
	}

	public float getGain() {
		return userGain;
	}
	public void setGain(int gain) {
		userGain = gain;
		apply();
	}

	/**
	 * Replay gain's share, in millibels. Kept apart from the slider's so that a track
	 * asking to be turned up neither reads back as the user's setting nor survives it.
	 */
	public void setReplayGain(int gain) {
		if(gain != replayGain) {
			replayGain = gain;
			apply();
		}
	}

	private void apply() {
		if(!isAvailable()) {
			return;
		}

		try {
			int target = (userEnabled ? userGain : 0) + replayGain;
			enhancer.setTargetGain(target);
			enhancer.setEnabled(target != 0);
		} catch(Throwable x) {
			// A device that will not boost is no reason to stop playing.
			Log.w(TAG, "Failed to set a loudness gain of " + replayGain + "mB over " + userGain + "mB", x);
		}
	}

	public void release() {
		if (isAvailable()) {
			enhancer.release();
			released = true;
		}
	}

}

