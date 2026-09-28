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

/**
 * The bank of sliders the equalizer screen draws, whichever effect is underneath it.
 *
 * Two things can be: {@link DynamicsEqualizer}, ten octave bands at frequencies we choose,
 * and {@link LegacyEqualizer}, however many bands the device's own equalizer effect offers
 * at whatever frequencies it picked. The screen wants the same handful of answers from
 * either, and in the same units - dB throughout, since a slider showing millibels is a
 * slider nobody can read.
 */
public interface GraphicEqualizer {
	boolean isEnabled();
	void setEnabled(boolean enabled);

	int getBandCount();

	/** Centre of a band, in Hz. */
	int getCenterFrequency(int band);

	float getBandGainDb(int band);
	void setBandGainDb(int band, float db);

	/**
	 * Headroom taken off everything before the bands, so a boosted band has somewhere to be
	 * boosted to instead of clipping.
	 */
	float getPreampDb();
	void setPreampDb(float db);

	float getMinGainDb();
	float getMaxGainDb();
	float getMinPreampDb();
	float getMaxPreampDb();

	/** Names for the preset picker, in the order {@link #usePreset(int)} indexes them. */
	String[] getPresetNames();

	/** Which preset the current gains are, or -1 when they are nobody's. */
	int getCurrentPreset();
	void usePreset(int preset);

	/** Keep the current settings for the next time the service starts. */
	void save();
}
