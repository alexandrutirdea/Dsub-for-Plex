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
package github.daneren2005.dsub.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.MusicDirectory.Entry;

/**
 * Sorting and filtering for a list of albums, used when browsing a Plex
 * collection.
 *
 * Year, date added and last played come straight off the album entries. Running
 * time, track count and style tags do not -- Plex omits all three from listings --
 * so they arrive separately as an {@link AlbumDetails} map keyed by album id, which
 * the caller loads in batches only once one of those filters is asked for.
 *
 * Filtering is a pure function of its inputs ({@code now} and the details map are
 * passed in rather than read from a clock or a service) so it can be reasoned about
 * and tested directly.
 */
public class AlbumBrowseFilter implements Serializable {
	private static final long DAY = 24L * 60L * 60L;

	// RANDOM goes last: the sort chooser lists these in order and reads the choice back
	// off the ordinal, so inserting one anywhere else would relabel the others.
	public enum SortKey { DEFAULT, TITLE, YEAR, ADDED, LAST_PLAYED, RANDOM }

	/** Inclusive year bounds; MIN/MAX stand in for open ends. */
	public enum Decade {
		BEFORE_1980(Integer.MIN_VALUE, 1979),
		EIGHTIES(1980, 1989),
		NINETIES(1990, 1999),
		TWO_THOUSANDS(2000, 2009),
		TWENTY_TENS(2010, 2019),
		TWENTY_TWENTIES(2020, Integer.MAX_VALUE);

		public final int from;
		public final int to;

		Decade(int from, int to) {
			this.from = from;
			this.to = to;
		}
	}

	/** Marks albums carrying no style tags at all, so they can be filtered for. */
	public static final String NO_STYLE = "\u0000none";

	private SortKey sortKey = SortKey.DEFAULT;
	/**
	 * Fixed until Random is chosen again, so the order holds still. Filtering re-runs
	 * constantly - a pill, a reload, coming back to the screen - and an order that
	 * reshuffled every time would move an album the moment it was reached for.
	 */
	private long shuffleSeed = System.nanoTime();
	private boolean descending = false;
	private Decade decade;
	private int minRestedDays;
	private final Set<String> styles = new LinkedHashSet<String>();
	private int minDurationMinutes;
	private int maxDurationMinutes;
	private int minTracks;

	public SortKey getSortKey() {
		return sortKey;
	}

	/** Choosing Random when it is already on is how a different order is asked for. */
	public void setSortKey(SortKey sortKey) {
		if (sortKey == SortKey.RANDOM) {
			shuffleSeed = System.nanoTime();
		}
		this.sortKey = sortKey;
	}

	public boolean isDescending() {
		return descending;
	}

	public void setDescending(boolean descending) {
		this.descending = descending;
	}

	public Decade getDecade() {
		return decade;
	}

	public void setDecade(Decade decade) {
		this.decade = decade;
	}

	/**
	 * Fewest days an album must have rested (gone unplayed) to show; zero for no
	 * limit. A never-played album counts as rested forever, so it always passes.
	 */
	public int getMinRestedDays() {
		return minRestedDays;
	}

	public void setMinRestedDays(int minRestedDays) {
		this.minRestedDays = minRestedDays;
	}

	/** Selected style tags; matching any one of them is enough. */
	public Set<String> getStyles() {
		return styles;
	}

	public void setStyles(Set<String> selected) {
		styles.clear();
		if (selected != null) {
			styles.addAll(selected);
		}
	}

	/** Shortest album to show, in minutes; zero for no limit. */
	public int getMinDurationMinutes() {
		return minDurationMinutes;
	}

	public void setMinDurationMinutes(int minDurationMinutes) {
		this.minDurationMinutes = minDurationMinutes;
	}

	/** Longest album to show, in minutes; zero for no limit. */
	public int getMaxDurationMinutes() {
		return maxDurationMinutes;
	}

	public void setMaxDurationMinutes(int maxDurationMinutes) {
		this.maxDurationMinutes = maxDurationMinutes;
	}

	/** Fewest tracks an album may have; zero for no limit. */
	public int getMinTracks() {
		return minTracks;
	}

	public void setMinTracks(int minTracks) {
		this.minTracks = minTracks;
	}

	/** True when a filter needs data that only {@link AlbumDetails} carries. */
	public boolean needsDetails() {
		return !styles.isEmpty() || minDurationMinutes > 0 || maxDurationMinutes > 0 || minTracks > 0;
	}

	public boolean isActive() {
		return sortKey != SortKey.DEFAULT || decade != null || minRestedDays > 0 || needsDetails();
	}

	public void clear() {
		sortKey = SortKey.DEFAULT;
		descending = false;
		decade = null;
		minRestedDays = 0;
		styles.clear();
		minDurationMinutes = 0;
		maxDurationMinutes = 0;
		minTracks = 0;
	}

	/**
	 * Returns a new list; the input is left untouched. {@code details} may be null or
	 * incomplete -- an album with nothing known about it simply fails any filter that
	 * depends on details, rather than being shown on the strength of missing data.
	 */
	public List<Entry> apply(List<Entry> input, long nowEpochSeconds, Map<String, AlbumDetails> details) {
		List<Entry> result = new ArrayList<Entry>();
		for (Entry entry : input) {
			if (matchesDecade(entry) && matchesRested(entry, nowEpochSeconds)
					&& matchesDetails(entry, details)) {
				result.add(entry);
			}
		}

		sort(result);
		return result;
	}

	private boolean matchesDetails(Entry entry, Map<String, AlbumDetails> details) {
		if (!needsDetails()) {
			return true;
		}

		AlbumDetails album = (details != null && entry.getId() != null) ? details.get(entry.getId()) : null;
		if (album == null) {
			return false;
		}

		if (minDurationMinutes > 0 || maxDurationMinutes > 0) {
			// An album whose running time is unknown cannot be shown to satisfy a bound on
			// it, the same way a missing year fails the decade filter.
			if (album.getDurationMs() <= 0) {
				return false;
			}
			if (maxDurationMinutes > 0 && album.getDurationMinutes() > maxDurationMinutes) {
				return false;
			}
			if (minDurationMinutes > 0 && album.getDurationMinutes() < minDurationMinutes) {
				return false;
			}
		}
		if (minTracks > 0 && album.getTrackCount() < minTracks) {
			return false;
		}
		return matchesStyles(album);
	}

	private boolean matchesStyles(AlbumDetails album) {
		if (styles.isEmpty()) {
			return true;
		}
		if (album.getStyles().isEmpty()) {
			return styles.contains(NO_STYLE);
		}

		for (String style : album.getStyles()) {
			if (styles.contains(style)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every style tag across the given albums, in alphabetical order, with the
	 * {@link #NO_STYLE} marker appended when any album is untagged. This is what the
	 * chooser lists, so it only ever offers styles that would actually match something.
	 */
	public static List<String> collectStyles(List<Entry> albums, Map<String, AlbumDetails> details) {
		Set<String> found = new HashSet<String>();
		boolean untagged = false;

		for (Entry entry : albums) {
			AlbumDetails album = (details != null && entry.getId() != null) ? details.get(entry.getId()) : null;
			if (album == null || album.getStyles().isEmpty()) {
				untagged = true;
			} else {
				found.addAll(album.getStyles());
			}
		}

		List<String> result = new ArrayList<String>(found);
		Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
		if (untagged) {
			result.add(NO_STYLE);
		}
		return result;
	}

	private boolean matchesDecade(Entry entry) {
		if (decade == null) {
			return true;
		}

		Integer year = entry.getYear();
		// An album with no year cannot be shown to satisfy a decade filter.
		return year != null && year >= decade.from && year <= decade.to;
	}

	private boolean matchesRested(Entry entry, long now) {
		if (minRestedDays <= 0) {
			return true;
		}

		Long last = entry.getLastPlayed();
		// Never played reads as rested forever, so it always shows.
		if (last == null) {
			return true;
		}
		return (now - last) >= (long) minRestedDays * DAY;
	}

	private void sort(List<Entry> entries) {
		if (sortKey == SortKey.DEFAULT) {
			return;
		}
		if (sortKey == SortKey.RANDOM) {
			// Seeded, so the same albums come back in the same order until Random is
			// chosen again. Ascending and descending mean nothing here and are ignored.
			Collections.shuffle(entries, new Random(shuffleSeed));
			return;
		}

		Comparator<Entry> comparator;
		switch (sortKey) {
			case TITLE:
				comparator = new Comparator<Entry>() {
					@Override
					public int compare(Entry a, Entry b) {
						int result = safeTitle(a).compareToIgnoreCase(safeTitle(b));
						return descending ? -result : result;
					}
				};
				break;
			case YEAR:
				comparator = nullsLast(new LongValue() {
					@Override
					public Long get(Entry entry) {
						Integer year = entry.getYear();
						return year == null ? null : year.longValue();
					}
				});
				break;
			case ADDED:
				comparator = nullsLast(new LongValue() {
					@Override
					public Long get(Entry entry) {
						return entry.getAdded();
					}
				});
				break;
			case LAST_PLAYED:
				comparator = nullsLast(new LongValue() {
					@Override
					public Long get(Entry entry) {
						return entry.getLastPlayed();
					}
				});
				break;
			default:
				return;
		}

		Collections.sort(entries, comparator);
	}

	private interface LongValue {
		Long get(Entry entry);
	}

	/**
	 * Albums missing the sort value (never played, unknown year) always sink to
	 * the bottom whichever direction is chosen -- reversing the order should
	 * reorder the albums you asked about, not promote the ones we know nothing
	 * about. Ties fall back to title so the order is stable.
	 */
	private Comparator<Entry> nullsLast(final LongValue value) {
		return new Comparator<Entry>() {
			@Override
			public int compare(Entry first, Entry second) {
				Long a = value.get(first);
				Long b = value.get(second);

				if (a == null && b == null) {
					return safeTitle(first).compareToIgnoreCase(safeTitle(second));
				}
				if (a == null) {
					return 1;
				}
				if (b == null) {
					return -1;
				}

				int result = descending ? b.compareTo(a) : a.compareTo(b);
				return result != 0 ? result : safeTitle(first).compareToIgnoreCase(safeTitle(second));
			}
		};
	}

	private static String safeTitle(Entry entry) {
		String title = entry.getTitle();
		return title == null ? "" : title;
	}
}
