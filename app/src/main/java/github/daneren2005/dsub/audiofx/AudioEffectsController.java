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
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.util.Log;

public class AudioEffectsController {
    private static final String TAG = AudioEffectsController.class.getSimpleName();

    private final Context context;
	private int audioSessionId = 0;

	private boolean available = true;

	private EqualizerController equalizerController;
	private LoudnessEnhancerController loudnessEnhancerController;

    public AudioEffectsController(Context context, int audioSessionId) {
        this.context = context;
		this.audioSessionId = audioSessionId;
    }

	public boolean isAvailable() {
		return available;
	}

	public void release() {
		if(equalizerController != null) {
			equalizerController.release();
		}
		if(loudnessEnhancerController != null) {
			loudnessEnhancerController.release();
			loudnessEnhancerController = null;
		}
	}

	public EqualizerController getEqualizerController() {
		if (available && equalizerController == null) {
			equalizerController = new EqualizerController(context, audioSessionId, getLoudnessEnhancerController());
			if (!equalizerController.isAvailable()) {
				equalizerController = null;
			} else {
				equalizerController.loadSettings();
			}
		}
		return equalizerController;
	}

	/**
	 * The session's one loudness enhancer. Owned here rather than by the equalizer, so
	 * that replay gain can add its share without an equalizer being built - and its saved
	 * settings applied - for something the user has not asked to switch on.
	 */
	public LoudnessEnhancerController getLoudnessEnhancerController() {
		if (available && loudnessEnhancerController == null) {
			loudnessEnhancerController = new LoudnessEnhancerController(context, audioSessionId);
		}
		return loudnessEnhancerController;
	}
}

