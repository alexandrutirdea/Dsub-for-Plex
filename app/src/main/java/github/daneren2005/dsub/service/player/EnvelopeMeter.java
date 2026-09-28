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

import github.daneren2005.dsub.util.TrackEnvelope;

/**
 * Measures how a track begins and ends while it plays, out of the samples going past.
 *
 * There is no analysis pass anywhere here and there does not need to be: the decoder is
 * already handing every sample to the sink, so the shape of a track is free to anyone
 * standing where {@link ReplayGainSink} stands. The cost is a multiply and an add per
 * sample, and what is kept is bounded - the first few seconds, the last twenty, and a
 * histogram to find the track's own middle with.
 *
 * Only the start and the end are kept because only they are ever asked about. The middle of
 * a track is what the reference level is taken from and is then thrown away.
 *
 * Nothing here is thread safe. Everything runs on the playback thread, which is the only
 * thread the sink is ever touched from.
 */
public class EnvelopeMeter {
	/** How long each measured window is. Short enough to see a sharp start, long enough to be cheap. */
	static final int WINDOW_MS = 200;

	private static final int HEAD_WINDOWS = 8000 / WINDOW_MS;
	private static final int TAIL_WINDOWS = 20000 / WINDOW_MS;

	/** One bucket per decibel, from full scale down to silence. */
	private static final int BUCKETS = 121;
	/** Anything at or below this is taken as nothing at all. */
	private static final float FLOOR_DB = -120f;

	private final float[] head = new float[HEAD_WINDOWS];
	private final float[] tail = new float[TAIL_WINDOWS];
	private final int[] histogram = new int[BUCKETS];

	private int headCount = 0;
	/** Where the next window goes in the tail, which wraps once it is full. */
	private int tailAt = 0;
	private int tailCount = 0;
	private int windowsSeen = 0;

	/** What has been gathered for the window being filled. */
	private double sumSquares = 0;
	private long frames = 0;
	private int currentWindow = -1;

	private boolean started = false;

	/**
	 * Takes one buffer's worth of audio.
	 *
	 * @param meanSquare the mean of the squared samples in the buffer, with full scale at 1
	 * @param frameCount how many frames it held
	 * @param positionMs where in the track it starts
	 */
	public void add(double meanSquare, int frameCount, long positionMs) {
		if(frameCount <= 0 || positionMs < 0) {
			return;
		}

		started = true;
		int window = (int) (positionMs / WINDOW_MS);
		if(currentWindow == -1) {
			currentWindow = window;
		}

		if(window != currentWindow) {
			closeWindow();
			// A gap - a seek, or audio the sink never got - leaves the windows in between
			// unmeasured rather than filled in with something invented.
			currentWindow = window;
		}

		sumSquares += meanSquare * frameCount;
		frames += frameCount;
	}

	/** Whether anything has been measured at all. */
	public boolean hasAudio() {
		return started;
	}

	/** Closes off the track and gives back its shape, or null when too little was heard. */
	public TrackEnvelope finish() {
		closeWindow();

		// Under a few seconds and there is no middle to speak of, so nothing can be read
		// against one. A track this short is not one anybody crossfades anyway.
		if(windowsSeen < (3000 / WINDOW_MS)) {
			return null;
		}

		float[] headOut = new float[Math.min(headCount, HEAD_WINDOWS)];
		System.arraycopy(head, 0, headOut, 0, headOut.length);

		// Unwrapped oldest first, so the last window measured is the last one here.
		float[] tailOut = new float[tailCount];
		int from = tailCount < TAIL_WINDOWS ? 0 : tailAt;
		for(int i = 0; i < tailCount; i++) {
			tailOut[i] = tail[(from + i) % TAIL_WINDOWS];
		}

		return new TrackEnvelope(WINDOW_MS, headOut, tailOut, median());
	}

	/** Forgets everything, for an engine being handed a different track. */
	public void reset() {
		headCount = 0;
		tailAt = 0;
		tailCount = 0;
		windowsSeen = 0;
		sumSquares = 0;
		frames = 0;
		currentWindow = -1;
		started = false;
		java.util.Arrays.fill(histogram, 0);
	}

	private void closeWindow() {
		if(frames <= 0) {
			return;
		}

		float db = toDecibels(Math.sqrt(sumSquares / frames));
		sumSquares = 0;
		frames = 0;

		if(headCount < HEAD_WINDOWS) {
			head[headCount++] = db;
		}
		tail[tailAt] = db;
		tailAt = (tailAt + 1) % TAIL_WINDOWS;
		if(tailCount < TAIL_WINDOWS) {
			tailCount++;
		}

		int bucket = Math.round(-db);
		if(bucket >= 0 && bucket < BUCKETS) {
			histogram[bucket]++;
		}
		windowsSeen++;
	}

	/**
	 * The level half the track's windows sit above, which stands for the track's own level.
	 *
	 * The median rather than the peak: a peak is one window and can be a single crash,
	 * while what everything else is read against wants to be where the music actually sits.
	 */
	private float median() {
		int wanted = windowsSeen / 2;
		int seen = 0;
		for(int i = 0; i < BUCKETS; i++) {
			seen += histogram[i];
			if(seen > wanted) {
				return -i;
			}
		}
		return FLOOR_DB;
	}

	private static float toDecibels(double rms) {
		if(rms <= 0) {
			return FLOOR_DB;
		}
		return (float) Math.max(FLOOR_DB, 20 * Math.log10(rms));
	}
}
