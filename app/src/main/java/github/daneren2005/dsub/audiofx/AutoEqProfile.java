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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A headphone correction in the form AutoEq publishes it: a preamp and a chain of peaking
 * and shelving filters.
 *
 * <pre>
 * Preamp: -6.1 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
 * Filter 2: ON PK Fc 1928 Hz Gain 3.5 dB Q 1.28
 * </pre>
 *
 * Nothing here plays audio. The filters are a description of a curve, and what this class
 * offers is the curve itself: {@link #gainAt(double)} is the response of the whole chain at
 * one frequency, so a band-based equalizer can be set to follow it. DSub hands files to
 * MediaPlayer rather than touching samples, so running these filters as filters is not on
 * the table - following their shape as closely as the platform's bands allow is.
 *
 * The response is worked out from the standard biquad forms these files are written for
 * (the Audio EQ Cookbook), which is what every other consumer of them uses, so a curve
 * read here matches what the numbers mean elsewhere.
 */
public class AutoEqProfile {
	/**
	 * The rate the filters are realised at for measuring their response. A biquad's shape
	 * depends on the rate it runs at, and near the top of the band the difference shows;
	 * 48 kHz is what phones mix at.
	 */
	private static final double SAMPLE_RATE = 48000;

	private static final Pattern PREAMP = Pattern.compile("Preamp:\\s*(-?[\\d.]+)\\s*dB", Pattern.CASE_INSENSITIVE);
	private static final Pattern FILTER = Pattern.compile(
			"Filter\\s+\\d+\\s*:\\s*(ON|OFF)\\s+([A-Z]+)\\s+Fc\\s+(-?[\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB\\s+Q\\s+(-?[\\d.]+)",
			Pattern.CASE_INSENSITIVE);

	private enum Type { PEAKING, LOW_SHELF, HIGH_SHELF }

	private static class Filter {
		final Type type;
		final double fc;
		final double gainDb;
		final double q;

		Filter(Type type, double fc, double gainDb, double q) {
			this.type = type;
			this.fc = fc;
			this.gainDb = gainDb;
			// A Q of zero would divide by nothing; the files do not carry one, but a
			// hand-edited file might.
			this.q = q <= 0 ? 0.7 : q;
		}
	}

	private final double preampDb;
	private final List<Filter> filters;

	private AutoEqProfile(double preampDb, List<Filter> filters) {
		this.preampDb = preampDb;
		this.filters = filters;
	}

	/**
	 * Reads one of AutoEq's parametric files.
	 *
	 * @throws IllegalArgumentException with something worth showing the user when the text
	 *		is not one - most usefully when it is the graphic EQ file for the same
	 *		headphones, which is an easy one to pick up by mistake.
	 */
	public static AutoEqProfile parse(String text) {
		if(text == null || text.trim().isEmpty()) {
			throw new IllegalArgumentException("There is nothing to read");
		}

		if(text.contains("GraphicEQ:") && !FILTER.matcher(text).find()) {
			throw new IllegalArgumentException("That is the GraphicEQ file - the ParametricEQ one is the one to use");
		}

		double preamp = 0;
		Matcher preampMatcher = PREAMP.matcher(text);
		if(preampMatcher.find()) {
			preamp = Double.parseDouble(preampMatcher.group(1));
		}

		List<Filter> filters = new ArrayList<Filter>();
		Matcher filterMatcher = FILTER.matcher(text);
		while(filterMatcher.find()) {
			if(!"ON".equalsIgnoreCase(filterMatcher.group(1))) {
				continue;
			}

			Type type = typeOf(filterMatcher.group(2));
			if(type == null) {
				// A filter shape this cannot follow is skipped rather than failing the whole
				// profile: the rest of the curve is still worth having.
				continue;
			}

			filters.add(new Filter(type,
					Double.parseDouble(filterMatcher.group(3)),
					Double.parseDouble(filterMatcher.group(4)),
					Double.parseDouble(filterMatcher.group(5))));
		}

		if(filters.isEmpty()) {
			throw new IllegalArgumentException("No filters found - is this an AutoEq ParametricEQ file?");
		}

		return new AutoEqProfile(preamp, filters);
	}

	private static Type typeOf(String name) {
		String type = name.toUpperCase();
		if("PK".equals(type) || "PEQ".equals(type)) {
			return Type.PEAKING;
		} else if(type.startsWith("LS")) {
			return Type.LOW_SHELF;
		} else if(type.startsWith("HS")) {
			return Type.HIGH_SHELF;
		} else {
			return null;
		}
	}

	/**
	 * The headroom the correction needs, as a negative number of dB. Boosting a band
	 * without taking this off the top is how a correction clips.
	 */
	public float getPreampDb() {
		return (float) preampDb;
	}

	public int getFilterCount() {
		return filters.size();
	}

	/** What the whole chain does at one frequency, in dB. */
	public float gainAt(double frequency) {
		double total = 0;
		for(Filter filter : filters) {
			total += magnitudeDb(filter, frequency);
		}
		return (float) total;
	}

	/**
	 * One filter's response at one frequency. The coefficients are the cookbook's, and the
	 * response is the transfer function evaluated on the unit circle.
	 */
	private static double magnitudeDb(Filter filter, double frequency) {
		double a = Math.pow(10, filter.gainDb / 40);
		double w0 = 2 * Math.PI * filter.fc / SAMPLE_RATE;
		double cosW0 = Math.cos(w0);
		double alpha = Math.sin(w0) / (2 * filter.q);
		double sqrtA = Math.sqrt(a);

		double b0, b1, b2, a0, a1, a2;
		switch(filter.type) {
			case LOW_SHELF:
				b0 = a * ((a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha);
				b1 = 2 * a * ((a - 1) - (a + 1) * cosW0);
				b2 = a * ((a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha);
				a0 = (a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha;
				a1 = -2 * ((a - 1) + (a + 1) * cosW0);
				a2 = (a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha;
				break;
			case HIGH_SHELF:
				b0 = a * ((a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha);
				b1 = -2 * a * ((a - 1) + (a + 1) * cosW0);
				b2 = a * ((a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha);
				a0 = (a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha;
				a1 = 2 * ((a - 1) - (a + 1) * cosW0);
				a2 = (a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha;
				break;
			case PEAKING:
			default:
				b0 = 1 + alpha * a;
				b1 = -2 * cosW0;
				b2 = 1 - alpha * a;
				a0 = 1 + alpha / a;
				a1 = -2 * cosW0;
				a2 = 1 - alpha / a;
				break;
		}

		double w = 2 * Math.PI * frequency / SAMPLE_RATE;
		double cos1 = Math.cos(w);
		double sin1 = Math.sin(w);
		double cos2 = Math.cos(2 * w);
		double sin2 = Math.sin(2 * w);

		double numRe = b0 + b1 * cos1 + b2 * cos2;
		double numIm = -(b1 * sin1 + b2 * sin2);
		double denRe = a0 + a1 * cos1 + a2 * cos2;
		double denIm = -(a1 * sin1 + a2 * sin2);

		double den = Math.hypot(denRe, denIm);
		if(den == 0) {
			return 0;
		}

		return 20 * Math.log10(Math.hypot(numRe, numIm) / den);
	}
}
