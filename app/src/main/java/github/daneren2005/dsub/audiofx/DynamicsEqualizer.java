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
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.Util;

/**
 * The session's one {@link DynamicsProcessing}, carrying both things DSub asks of it.
 *
 * Used rather than the {@link android.media.audiofx.Equalizer} effect because that one has
 * whatever bands the device offers - five on most phones - at frequencies it chooses, and
 * neither the count nor the centres can be asked for. This one is configured, so the screen
 * gets the ten octave bands a graphic equalizer has always been drawn with, and a headphone
 * correction gets the third-octave resolution it needs.
 *
 * One effect and not two: a second DynamicsProcessing on the same session would be fighting
 * this one over the same audio. The two jobs sit in different stages of the same chain.
 *
 * <ul>
 * <li><b>preEq</b> - the ten bands on the equalizer screen, which are the user's taste.
 * <li><b>postEq</b> - the headphone correction, which is the output's own fault being
 *     undone, and belongs after the taste rather than before it.
 * <li><b>input gain</b> - both preamps added together, since both are headroom.
 * </ul>
 *
 * The correction follows the curve rather than being it: bands are gain over a range, not
 * the peaking filters an {@link AutoEqProfile} describes, so a narrow deep notch comes out
 * shallower and wider than it was measured. Running the filters themselves would mean owning
 * the samples, which DSub does not - it hands file paths to MediaPlayer.
 */
public class DynamicsEqualizer implements GraphicEqualizer {
	private static final String TAG = DynamicsEqualizer.class.getSimpleName();

	/** The ten octave centres a graphic equalizer has been drawn at since they were sliders. */
	private static final int[] BANDS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

	/**
	 * Third-octave centres from 25 Hz up, which is the resolution a headphone correction is
	 * published at and about as fine as the effect will follow.
	 */
	private static final float[] CORRECTION_CENTERS = {
			25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
			1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
	};
	private static final int CHANNELS = 2;

	private static final float MAX_GAIN_DB = 15f;
	private static final float MIN_GAIN_DB = -15f;
	/**
	 * A preamp that only ever takes away. Boosting here is what the bands are for, and a
	 * positive input gain on top of boosted bands is the shortest road to clipping.
	 */
	private static final float MAX_PREAMP_DB = 0f;
	private static final float MIN_PREAMP_DB = -15f;

	/** Close enough to count as sitting on a preset, in dB. */
	private static final float PRESET_EPSILON = 0.05f;

	private static final String[] PRESET_NAMES = {
			"Flat", "Bass Boost", "Treble Boost", "Loudness", "Vocal",
			"Rock", "Pop", "Jazz", "Classical", "Dance"
	};
	private static final float[][] PRESETS = {
			//    31    62   125   250   500    1k    2k    4k    8k   16k
			{      0,    0,    0,    0,    0,    0,    0,    0,    0,    0},
			{      6,    5,    4,    2,    0,    0,    0,    0,    0,    0},
			{      0,    0,    0,    0,    0,    1,    2,    4,    5,    6},
			{      6,    5,    3,    0,   -1,   -1,    0,    2,    4,    6},
			{     -2,   -2,   -1,    1,    3,    4,    4,    2,    0,   -1},
			{      5,    4,    2,   -1,   -2,    0,    2,    4,    5,    5},
			{     -1,    0,    2,    4,    4,    2,    0,   -1,   -1,   -1},
			{      4,    3,    1,    2,   -1,   -1,    0,    1,    3,    4},
			{      4,    3,    2,    0,   -1,   -1,    0,    2,    3,    4},
			{      6,    5,    2,    0,   -1,   -2,   -1,    2,    4,    4}
	};

	private final Context context;
	private final int audioSessionId;
	private DynamicsProcessing processing;

	private final float[] gains = new float[BANDS.length];
	private float preampDb;
	private boolean enabled;

	/** The headroom the headphone correction asked for, and whether there is one on. */
	private float profilePreampDb;
	private boolean profileApplied;

	/**
	 * Reached from the service's own thread when it starts and from the main thread when an
	 * output comes or goes, so the effect is only ever touched under this object's lock.
	 */
	public DynamicsEqualizer(Context context, int audioSessionId) {
		this.context = context;
		this.audioSessionId = audioSessionId;
		load();
	}

	/** Whether the effect could be built at all; a device may not carry it. */
	public synchronized boolean isAvailable() {
		return create() != null;
	}

	// The equalizer screen's half.

	@Override
	public synchronized boolean isEnabled() {
		return enabled;
	}

	@Override
	public synchronized void setEnabled(boolean enabled) {
		this.enabled = enabled;

		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putBoolean(Constants.PREFERENCES_EQUALIZER_ON, enabled);
		editor.commit();

		pushUserBands();
	}

	@Override
	public int getBandCount() {
		return BANDS.length;
	}

	@Override
	public int getCenterFrequency(int band) {
		return BANDS[band];
	}

	@Override
	public synchronized float getBandGainDb(int band) {
		return gains[band];
	}

	@Override
	public synchronized void setBandGainDb(int band, float db) {
		gains[band] = clamp(db, MIN_GAIN_DB, MAX_GAIN_DB);
		pushUserBands();
	}

	@Override
	public synchronized float getPreampDb() {
		return preampDb;
	}

	@Override
	public synchronized void setPreampDb(float db) {
		preampDb = clamp(db, MIN_PREAMP_DB, MAX_PREAMP_DB);
		pushUserBands();
	}

	@Override
	public float getMinGainDb() {
		return MIN_GAIN_DB;
	}

	@Override
	public float getMaxGainDb() {
		return MAX_GAIN_DB;
	}

	@Override
	public float getMinPreampDb() {
		return MIN_PREAMP_DB;
	}

	@Override
	public float getMaxPreampDb() {
		return MAX_PREAMP_DB;
	}

	@Override
	public String[] getPresetNames() {
		return PRESET_NAMES.clone();
	}

	@Override
	public synchronized int getCurrentPreset() {
		for(int preset = 0; preset < PRESETS.length; preset++) {
			boolean matches = true;
			for(int band = 0; band < BANDS.length; band++) {
				if(Math.abs(gains[band] - PRESETS[preset][band]) > PRESET_EPSILON) {
					matches = false;
					break;
				}
			}
			if(matches) {
				return preset;
			}
		}
		return -1;
	}

	@Override
	public synchronized void usePreset(int preset) {
		if(preset < 0 || preset >= PRESETS.length) {
			return;
		}

		System.arraycopy(PRESETS[preset], 0, gains, 0, BANDS.length);
		pushUserBands();
	}

	// The headphone correction's half.

	public synchronized void applyProfile(AutoEqProfile profile) {
		DynamicsProcessing processing = create();
		if(processing == null) {
			return;
		}

		try {
			DynamicsProcessing.Eq eq = processing.getPostEqByChannelIndex(0);
			for(int band = 0; band < CORRECTION_CENTERS.length; band++) {
				DynamicsProcessing.EqBand eqBand = eq.getBand(band);
				eqBand.setEnabled(true);
				eqBand.setCutoffFrequency(CORRECTION_CENTERS[band]);
				eqBand.setGain(profile.gainAt(CORRECTION_CENTERS[band]));
				eq.setBand(band, eqBand);
			}
			processing.setPostEqAllChannelsTo(eq);

			profilePreampDb = profile.getPreampDb();
			profileApplied = true;
		} catch(Throwable x) {
			Log.w(TAG, "Failed to apply a headphone correction", x);
			release();
			return;
		}

		// This is the first thing the service does with the effect, so it is also where the
		// bands the user last left behind first reach it.
		pushUserBands();
	}

	/** Back to flat, for an output with no profile of its own. */
	public synchronized void clearProfile() {
		profilePreampDb = 0;
		profileApplied = false;

		if(processing != null) {
			try {
				DynamicsProcessing.Eq eq = processing.getPostEqByChannelIndex(0);
				for(int band = 0; band < CORRECTION_CENTERS.length; band++) {
					DynamicsProcessing.EqBand eqBand = eq.getBand(band);
					eqBand.setCutoffFrequency(CORRECTION_CENTERS[band]);
					eqBand.setGain(0);
					eq.setBand(band, eqBand);
				}
				processing.setPostEqAllChannelsTo(eq);
			} catch(Throwable x) {
				Log.w(TAG, "Failed to clear the headphone correction", x);
				release();
				return;
			}
		}

		// A freshly built effect has a flat postEq anyway; either way the user's own bands
		// still want putting on, and may still want the effect running.
		pushUserBands();
	}

	public synchronized void release() {
		if(processing != null) {
			try {
				processing.release();
			} catch(Throwable x) {
				Log.w(TAG, "Failed to release the equalizer", x);
			}
			processing = null;
		}
	}

	@Override
	public synchronized void save() {
		StringBuilder joined = new StringBuilder();
		for(int band = 0; band < BANDS.length; band++) {
			if(band > 0) {
				joined.append(',');
			}
			joined.append(gains[band]);
		}

		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putString(Constants.PREFERENCES_EQUALIZER_BANDS, joined.toString());
		editor.putFloat(Constants.PREFERENCES_EQUALIZER_PREAMP, preampDb);
		editor.putBoolean(Constants.PREFERENCES_EQUALIZER_ON, enabled);
		editor.commit();
	}

	/**
	 * Kept in the preferences rather than the serialized blob the old equalizer used: the
	 * bands are ours and fixed, so there is nothing to go stale the way a saved array of
	 * whatever-the-device-offered would.
	 */
	private void load() {
		SharedPreferences prefs = Util.getPreferences(context);
		enabled = prefs.getBoolean(Constants.PREFERENCES_EQUALIZER_ON, false);
		preampDb = clamp(prefs.getFloat(Constants.PREFERENCES_EQUALIZER_PREAMP, 0), MIN_PREAMP_DB, MAX_PREAMP_DB);

		String saved = prefs.getString(Constants.PREFERENCES_EQUALIZER_BANDS, null);
		if(saved == null) {
			return;
		}

		String[] parts = saved.split(",");
		for(int band = 0; band < BANDS.length && band < parts.length; band++) {
			try {
				gains[band] = clamp(Float.parseFloat(parts[band].trim()), MIN_GAIN_DB, MAX_GAIN_DB);
			} catch(NumberFormatException x) {
				gains[band] = 0;
			}
		}
	}

	/**
	 * Both preamps, since the effect has one input gain and both of them are asking the same
	 * thing of it. A correction's headroom stands whether the screen's bands are switched on
	 * or not - the correction is not the user's taste, and does not go away with it.
	 */
	private float inputGainDb() {
		return (enabled ? preampDb : 0f) + profilePreampDb;
	}

	private void pushUserBands() {
		DynamicsProcessing processing = create();
		if(processing == null) {
			return;
		}

		try {
			DynamicsProcessing.Eq eq = processing.getPreEqByChannelIndex(0);
			for(int band = 0; band < BANDS.length; band++) {
				DynamicsProcessing.EqBand eqBand = eq.getBand(band);
				eqBand.setEnabled(true);
				eqBand.setCutoffFrequency(BANDS[band]);
				eqBand.setGain(enabled ? gains[band] : 0f);
				eq.setBand(band, eqBand);
			}
			processing.setPreEqAllChannelsTo(eq);
			processing.setInputGainAllChannelsTo(inputGainDb());

			// Off only when neither half wants it: a correction outlives the screen's switch.
			processing.setEnabled(enabled || profileApplied);
		} catch(Throwable x) {
			Log.w(TAG, "Failed to apply the equalizer bands", x);
			release();
		}
	}

	/**
	 * Built on first use and kept: the bands are set on it again for each output, and
	 * building one per track would drop the audio while the effect is inserted.
	 */
	private DynamicsProcessing create() {
		if(processing != null) {
			return processing;
		}

		try {
			DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
					DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
					true, BANDS.length,
					false, 0,
					true, CORRECTION_CENTERS.length,
					false).build();
			processing = new DynamicsProcessing(0, audioSessionId, config);
		} catch(Throwable x) {
			Log.w(TAG, "Failed to create the equalizer", x);
			processing = null;
		}

		return processing;
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
