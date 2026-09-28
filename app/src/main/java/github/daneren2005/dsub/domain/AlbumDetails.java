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
package github.daneren2005.dsub.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of an album that a listing does not carry: how long it runs, how many
 * tracks it has, the style tags it was given, and who issued it when.
 *
 * Plex leaves all of them off both collection children and the section listing, so they
 * have to be fetched separately and are kept here rather than on
 * {@link MusicDirectory.Entry} -- they are only ever needed while filtering a
 * collection or drawing an album header, and Entry is serialized into every cached
 * directory on disk.
 *
 * These are cached on disk by album id, because none of them meaningfully change once an
 * album exists and refetching them is what a collection filter used to spend its time on.
 * Fields can still be added freely: a cache written by an older build fails to read back
 * and is simply fetched again.
 */
public class AlbumDetails implements Serializable {
	private int trackCount;
	private long durationMs;
	private List<String> styles = new ArrayList<String>();
	private String recordLabel;
	private String originalReleaseDate;

	public int getTrackCount() {
		return trackCount;
	}

	public void setTrackCount(int trackCount) {
		this.trackCount = trackCount;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	/** Never null; an album with no tags returns an empty list. */
	public List<String> getStyles() {
		return styles;
	}

	public void setStyles(List<String> styles) {
		this.styles = (styles != null) ? styles : new ArrayList<String>();
	}

	public int getDurationMinutes() {
		return (int) (durationMs / 60000L);
	}

	/** The company that issued the record, or null where the server has no tag for it. */
	public String getRecordLabel() {
		return recordLabel;
	}

	public void setRecordLabel(String recordLabel) {
		this.recordLabel = emptyToNull(recordLabel);
	}

	/**
	 * When the album first came out, as an ISO date ("1976-04-23"), or null if unknown.
	 *
	 * Kept as the server's own string rather than parsed into a date: this is the
	 * *original* release, which for reissues is deliberately not the year on the tracks,
	 * and servers vary in how much of it they know. Callers that only want the year can
	 * take the leading four characters via {@link #getOriginalReleaseYear()}.
	 */
	public String getOriginalReleaseDate() {
		return originalReleaseDate;
	}

	public void setOriginalReleaseDate(String originalReleaseDate) {
		this.originalReleaseDate = emptyToNull(originalReleaseDate);
	}

	/** The year alone, or null when there is no date to take it from. */
	public String getOriginalReleaseYear() {
		if (originalReleaseDate == null || originalReleaseDate.length() < 4) {
			return null;
		}

		String year = originalReleaseDate.substring(0, 4);
		for (int i = 0; i < year.length(); i++) {
			if (!Character.isDigit(year.charAt(i))) {
				return null;
			}
		}
		return year;
	}

	/** Servers answer an absent tag with an empty string as often as with nothing at all. */
	private static String emptyToNull(String value) {
		if (value == null) {
			return null;
		}

		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
