/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2014 (C) Scott Jackson
*/

package github.daneren2005.dsub.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics with a timestamp per line, parsed from an LRC file.
 *
 * LRC is a plain text format where each line is prefixed with one or more
 * {@code [mm:ss.xx]} stamps, plus optional {@code [ar:]} / {@code [ti:]} metadata
 * tags that carry no timing and are ignored here.
 */
public class SyncedLyrics implements Serializable {
	/**
	 * A timestamp may appear more than once on a line - a repeated chorus is written
	 * once and stamped several times - so each stamp becomes its own entry.
	 */
	private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

	public static class Line implements Serializable {
		public final int timeMs;
		public final String text;

		public Line(int timeMs, String text) {
			this.timeMs = timeMs;
			this.text = text;
		}
	}

	private final List<Line> lines;

	private SyncedLyrics(List<Line> lines) {
		this.lines = lines;
	}

	public List<Line> getLines() {
		return lines;
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/**
	 * Index of the line that should be highlighted at {@code positionMs}, or -1 before
	 * the first stamp. Linear rather than binary search: playback moves forward a
	 * second at a time over a list of a few dozen entries.
	 */
	public int indexAt(int positionMs) {
		int index = -1;
		for(int i = 0; i < lines.size(); i++) {
			if(lines.get(i).timeMs <= positionMs) {
				index = i;
			} else {
				break;
			}
		}
		return index;
	}

	/** The whole thing as plain text, for anywhere that cannot show timing. */
	public String getText() {
		StringBuilder builder = new StringBuilder();
		for(Line line: lines) {
			if(builder.length() > 0) {
				builder.append('\n');
			}
			builder.append(line.text);
		}
		return builder.toString();
	}

	/**
	 * Parses LRC, returning null when the input holds no timestamped line at all -
	 * which covers an empty file and, usefully, a file whose lyric fetcher wrote an
	 * error message into it instead of lyrics.
	 */
	public static SyncedLyrics parse(String lrc) {
		if(lrc == null || lrc.isEmpty()) {
			return null;
		}

		List<Line> parsed = new ArrayList<Line>();
		for(String raw: lrc.split("\\r?\\n")) {
			Matcher matcher = STAMP.matcher(raw);

			List<Integer> stamps = new ArrayList<Integer>(1);
			int textStart = 0;
			while(matcher.find()) {
				stamps.add(toMillis(matcher));
				textStart = matcher.end();
			}
			if(stamps.isEmpty()) {
				continue;
			}

			String text = raw.substring(textStart).trim();
			for(Integer stamp: stamps) {
				parsed.add(new Line(stamp, text));
			}
		}

		if(parsed.isEmpty()) {
			return null;
		}

		Collections.sort(parsed, new Comparator<Line>() {
			@Override
			public int compare(Line first, Line second) {
				return first.timeMs - second.timeMs;
			}
		});
		return new SyncedLyrics(parsed);
	}

	private static int toMillis(Matcher matcher) {
		int minutes = Integer.parseInt(matcher.group(1));
		int seconds = Integer.parseInt(matcher.group(2));

		int fraction = 0;
		String fractionGroup = matcher.group(3);
		if(fractionGroup != null) {
			// Two digits are hundredths, three are already milliseconds.
			int value = Integer.parseInt(fractionGroup);
			fraction = (fractionGroup.length() >= 3) ? value : value * 10;
		}
		return (minutes * 60 + seconds) * 1000 + fraction;
	}
}
