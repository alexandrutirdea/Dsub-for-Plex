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
package github.daneren2005.dsub.util;

import java.io.Serializable;

/**
 * How a recording begins and how it ends, which is the one thing about a track that
 * decides how it should be joined to its neighbours and the one thing no server reports.
 *
 * A crossfade length is a setting, so every join gets the same one - but the joins are not
 * the same. A track that stops dead wants an overlap; a track that has already faded itself
 * out does not, and fading a fade only drags the ending further into the next song. A track
 * that fades itself in wants one even less: the fade meets the record's own and the song
 * arrives twice over, which is the sound of the tape being started late.
 *
 * Levels here are the loudness of each window in decibels below full scale, and every
 * question below is asked against {@link #getReference()} - the track's own middle - rather
 * than against an absolute level. A quiet recording is not a fade out.
 */
public class TrackEnvelope implements Serializable {
	private static final long serialVersionUID = 1L;

	/**
	 * Below this far under the track's own level, nothing of the music is left.
	 *
	 * Set where it is to tell a record that fades itself out from a chord let ring. A fade
	 * ends at silence or near enough, tens of decibels down; a ring-out is a second or two
	 * and lands around twenty. How long either took is {@link #departureMs}, and a caller
	 * that cares about the difference between a long fade and a short one reads that -
	 * this only answers whether there is anything still playing at the end.
	 */
	private static final float GONE_DB = 20f;
	/** Within this far of the track's own level, the music is fully underway. */
	private static final float UNDERWAY_DB = 6f;
	/** How much of the start or end is looked at to say how it begins or ends. */
	private static final int EDGE_MS = 500;

	private final int windowMs;
	private final float[] head;
	private final float[] tail;
	private final float reference;

	public TrackEnvelope(int windowMs, float[] head, float[] tail, float reference) {
		this.windowMs = windowMs;
		this.head = head;
		this.tail = tail;
		this.reference = reference;
	}

	public int getWindowMs() {
		return windowMs;
	}

	/** The track's own middle level, which everything else is read against. */
	public float getReference() {
		return reference;
	}

	/** Whether there is enough here to answer anything. */
	public boolean isUsable() {
		return head != null && tail != null && windowMs > 0
				&& head.length > 0 && tail.length > 0
				&& reference > -120f;
	}

	/** The music is at full level within half a second of the first sample. */
	public boolean startsCold() {
		return isUsable() && edgeLevel(head, true) >= reference - UNDERWAY_DB;
	}

	/** The track begins on next to nothing - a fade in, or an intro out of silence. */
	public boolean startsQuiet() {
		return isUsable() && edgeLevel(head, true) < reference - GONE_DB;
	}

	/** The music is still at full level when the track stops. */
	public boolean endsCold() {
		return isUsable() && edgeLevel(tail, false) >= reference - UNDERWAY_DB;
	}

	/** The track has already taken itself away - a fade out, or a long decay. */
	public boolean endsQuiet() {
		return isUsable() && edgeLevel(tail, false) < reference - GONE_DB;
	}

	/**
	 * How long the track takes to arrive, in milliseconds - the time from the first sample
	 * until the music is first fully underway, or 0 for a track that starts cold.
	 */
	public int arrivalMs() {
		if(!isUsable()) {
			return 0;
		}
		for(int i = 0; i < head.length; i++) {
			if(head[i] >= reference - UNDERWAY_DB) {
				return i * windowMs;
			}
		}
		return head.length * windowMs;
	}

	/**
	 * How long the track spends leaving, in milliseconds - the time from when the music was
	 * last fully underway to the final sample, or 0 for a track that ends cold.
	 */
	public int departureMs() {
		if(!isUsable()) {
			return 0;
		}
		for(int i = tail.length - 1; i >= 0; i--) {
			if(tail[i] >= reference - UNDERWAY_DB) {
				return (tail.length - 1 - i) * windowMs;
			}
		}
		return tail.length * windowMs;
	}

	/** The mean level of the first or last {@link #EDGE_MS} of what was measured. */
	private float edgeLevel(float[] windows, boolean fromStart) {
		int count = Math.max(1, Math.min(windows.length, EDGE_MS / Math.max(1, windowMs)));
		double total = 0;
		for(int i = 0; i < count; i++) {
			total += windows[fromStart ? i : windows.length - 1 - i];
		}
		return (float) (total / count);
	}

	@Override
	public String toString() {
		if(!isUsable()) {
			return "envelope(nothing measured)";
		}
		return "envelope(reference " + Math.round(reference) + "dB"
				+ ", in " + (startsQuiet() ? "quiet" : startsCold() ? "cold" : "ordinary")
				+ " over " + arrivalMs() + "ms"
				+ ", out " + (endsQuiet() ? "quiet" : endsCold() ? "cold" : "ordinary")
				+ " over " + departureMs() + "ms)";
	}
}
