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
 */
package github.daneren2005.dsub.audiofx;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.Util;

/**
 * The device's own equalizer effect, for the phones too old for {@link DynamicsEqualizer}.
 *
 * Whatever bands the device offers - five on most - at whatever frequencies it picked, and
 * no way to ask for others. The preamp is not a real one either: the effect has no input
 * gain, so it is an offset added onto every band, which is the same shape of thing until a
 * band runs into the end of its range and stops moving with the rest.
 *
 * The user's own gains are kept here rather than read back off the effect, because the sum
 * of gain and preamp is clamped on the way in and what comes back cannot be taken apart
 * again - a band pinned at the top would otherwise creep as the preamp moved under it.
 */
public class LegacyEqualizer implements GraphicEqualizer {
	private static final String TAG = LegacyEqualizer.class.getSimpleName();

	/** How many goes at recreating a released effect before giving up on a call. */
	private static final int RETRIES = 10;

	private final Context context;
	private final EqualizerController controller;
	private Equalizer equalizer;

	private final float[] gains;
	private float preampDb;

	public LegacyEqualizer(Context context, EqualizerController controller) {
		this.context = context;
		this.controller = controller;
		this.equalizer = controller.getEqualizer();

		// Millibels on the way in, as the old settings were written.
		preampDb = Util.getPreferences(context).getInt(Constants.PREFERENCES_EQUALIZER_SETTINGS, 0) / 100f;

		short bandCount = equalizer.getNumberOfBands();
		gains = new float[bandCount];
		boolean on = isEnabled();
		for(short band = 0; band < bandCount; band++) {
			float level = equalizer.getBandLevel(band) / 100f;
			gains[band] = on ? level - preampDb : level;
		}
	}

	@Override
	public boolean isEnabled() {
		try {
			return equalizer.getEnabled();
		} catch(Exception x) {
			return false;
		}
	}

	@Override
	public void setEnabled(boolean enabled) {
		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putBoolean(Constants.PREFERENCES_EQUALIZER_ON, enabled);
		editor.commit();

		for(int i = 0; i < RETRIES; i++) {
			try {
				equalizer.setEnabled(enabled);
				break;
			} catch(UnsupportedOperationException x) {
				reacquire();
			}
		}

		if(enabled) {
			pushAllBands();
		} else {
			// Left flat rather than left alone: the effect stays in the chain when it is
			// switched off on some devices, and a disabled band that still has gain on it
			// comes back the moment something else switches the effect on.
			for(short band = 0; band < gains.length; band++) {
				setBandLevel(band, 0);
			}
		}
	}

	@Override
	public int getBandCount() {
		return gains.length;
	}

	@Override
	public int getCenterFrequency(int band) {
		// The effect answers in millihertz.
		return equalizer.getCenterFreq((short) band) / 1000;
	}

	@Override
	public float getBandGainDb(int band) {
		return gains[band];
	}

	@Override
	public void setBandGainDb(int band, float db) {
		gains[band] = clamp(db, getMinGainDb(), getMaxGainDb());
		setBandLevel((short) band, gains[band] + preampDb);
	}

	@Override
	public float getPreampDb() {
		return preampDb;
	}

	@Override
	public void setPreampDb(float db) {
		preampDb = clamp(db, getMinPreampDb(), getMaxPreampDb());

		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putInt(Constants.PREFERENCES_EQUALIZER_SETTINGS, (int) (preampDb * 100));
		editor.commit();

		pushAllBands();
	}

	@Override
	public float getMinGainDb() {
		return equalizer.getBandLevelRange()[0] / 100f;
	}

	@Override
	public float getMaxGainDb() {
		return equalizer.getBandLevelRange()[1] / 100f;
	}

	@Override
	public float getMinPreampDb() {
		return getMinGainDb();
	}

	@Override
	public float getMaxPreampDb() {
		return getMaxGainDb();
	}

	@Override
	public String[] getPresetNames() {
		short count = equalizer.getNumberOfPresets();
		String[] names = new String[count];
		for(short preset = 0; preset < count; preset++) {
			names[preset] = equalizer.getPresetName(preset);
		}
		return names;
	}

	@Override
	public int getCurrentPreset() {
		try {
			return equalizer.getCurrentPreset();
		} catch(Exception x) {
			return -1;
		}
	}

	@Override
	public void usePreset(int preset) {
		for(int i = 0; i < RETRIES; i++) {
			try {
				equalizer.usePreset((short) preset);
				break;
			} catch(UnsupportedOperationException x) {
				reacquire();
			}
		}

		// A preset writes the bands itself, so what it left is the new starting point.
		for(short band = 0; band < gains.length; band++) {
			try {
				gains[band] = equalizer.getBandLevel(band) / 100f - preampDb;
			} catch(Exception x) {
				Log.w(TAG, "Failed to read back band " + band, x);
			}
		}
		pushAllBands();
	}

	@Override
	public void save() {
		controller.saveSettings();
	}

	private void pushAllBands() {
		for(short band = 0; band < gains.length; band++) {
			setBandLevel(band, gains[band] + preampDb);
		}
	}

	private void setBandLevel(short band, float db) {
		short level = (short) clamp(db * 100, equalizer.getBandLevelRange()[0], equalizer.getBandLevelRange()[1]);
		try {
			equalizer.setBandLevel(band, level);
		} catch(Exception x) {
			Log.w(TAG, "Failed to set band " + band, x);
		}
	}

	/** The effect goes away when something else takes the session; ask for a fresh one. */
	private void reacquire() {
		controller.release();
		equalizer = controller.getEqualizer();
	}

	private static float clamp(float value, float min, float max) {
		if(value < min) {
			return min;
		} else if(value > max) {
			return max;
		}
		return value;
	}
}
