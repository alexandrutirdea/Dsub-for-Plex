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

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the Home screen's Focused listening row is set to: one collection, and how its
 * albums are filtered.
 *
 * Both are remembered rather than asked for each time - the point of the row is to keep
 * a handful of albums in front of you until they have been listened to, which is a
 * decision that outlives the session it was made in. Kept per server, since a collection
 * id means nothing on another one.
 */
public final class FocusedListening {
	/**
	 * The filter as one string: fields separated by a record separator, the styles inside
	 * their own field by a unit separator. Both are control characters, so neither can
	 * turn up in a style tag and split a value in half.
	 */
	private static final String FIELD_SEPARATOR = "\u001e";
	private static final String STYLE_SEPARATOR = "\u001f";
	/**
	 * What an older build wrote. The minimum duration was added after it, at the end, so a
	 * value saved before then still reads - the row keeps the filter it was left with
	 * rather than quietly showing the collection whole after an update.
	 */
	private static final int FIELD_COUNT = 7;
	private static final int FIELD_COUNT_WITH_MIN_DURATION = 8;

	private FocusedListening() {}

	/** Collections are a Plex idea, and reading one needs the server. */
	public static boolean isAvailable(Context context) {
		return Util.isPlex(context) && !Util.isOffline(context);
	}

	public static String getCollectionId(Context context) {
		return Util.getPreferences(context).getString(key(context, Constants.PREFERENCES_KEY_FOCUSED_COLLECTION_ID), null);
	}

	public static String getCollectionName(Context context) {
		return Util.getPreferences(context).getString(key(context, Constants.PREFERENCES_KEY_FOCUSED_COLLECTION_NAME), null);
	}

	/** A null id turns the row off. */
	public static void setCollection(Context context, String id, String name) {
		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putString(key(context, Constants.PREFERENCES_KEY_FOCUSED_COLLECTION_ID), id);
		editor.putString(key(context, Constants.PREFERENCES_KEY_FOCUSED_COLLECTION_NAME), name);
		editor.commit();
	}

	public static void saveFilter(Context context, AlbumBrowseFilter filter) {
		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putString(key(context, Constants.PREFERENCES_KEY_FOCUSED_FILTER), encode(filter));
		editor.commit();
	}

	/** Always a filter, never null: an unreadable or missing value reads as no filter. */
	public static AlbumBrowseFilter loadFilter(Context context) {
		return decode(Util.getPreferences(context)
				.getString(key(context, Constants.PREFERENCES_KEY_FOCUSED_FILTER), null));
	}

	private static String key(Context context, String base) {
		return base + Util.getActiveServer(context);
	}

	private static String encode(AlbumBrowseFilter filter) {
		StringBuilder styles = new StringBuilder();
		for(String style: filter.getStyles()) {
			if(styles.length() > 0) {
				styles.append(STYLE_SEPARATOR);
			}
			styles.append(style);
		}

		return filter.getSortKey().name()
				+ FIELD_SEPARATOR + filter.isDescending()
				+ FIELD_SEPARATOR + (filter.getDecade() == null ? "" : filter.getDecade().name())
				+ FIELD_SEPARATOR + filter.getMinRestedDays()
				+ FIELD_SEPARATOR + filter.getMaxDurationMinutes()
				+ FIELD_SEPARATOR + filter.getMinTracks()
				+ FIELD_SEPARATOR + styles
				+ FIELD_SEPARATOR + filter.getMinDurationMinutes();
	}

	private static AlbumBrowseFilter decode(String value) {
		AlbumBrowseFilter filter = new AlbumBrowseFilter();
		if(value == null || value.isEmpty()) {
			return filter;
		}

		// -1 keeps empty fields, so the field count stays honest whatever is unset.
		String[] fields = value.split(FIELD_SEPARATOR, -1);
		if(fields.length != FIELD_COUNT && fields.length != FIELD_COUNT_WITH_MIN_DURATION) {
			return filter;
		}

		try {
			// A random sort is stored as the choice, not as the order it produced, so it
			// comes back shuffled afresh each run - which is the point of asking for one.
			filter.setSortKey(AlbumBrowseFilter.SortKey.valueOf(fields[0]));
			filter.setDescending(Boolean.parseBoolean(fields[1]));
			if(!fields[2].isEmpty()) {
				filter.setDecade(AlbumBrowseFilter.Decade.valueOf(fields[2]));
			}
			filter.setMinRestedDays(Integer.parseInt(fields[3]));
			filter.setMaxDurationMinutes(Integer.parseInt(fields[4]));
			filter.setMinTracks(Integer.parseInt(fields[5]));

			if(!fields[6].isEmpty()) {
				Set<String> styles = new LinkedHashSet<String>();
				for(String style: fields[6].split(STYLE_SEPARATOR, -1)) {
					styles.add(style);
				}
				filter.setStyles(styles);
			}
			if(fields.length == FIELD_COUNT_WITH_MIN_DURATION) {
				filter.setMinDurationMinutes(Integer.parseInt(fields[7]));
			}
		} catch(Exception e) {
			// A value written by an older build, or one whose enum names have moved on.
			// No filter is the safe reading: it shows the collection whole rather than
			// hiding albums for a reason the user cannot see.
			filter.clear();
		}

		return filter;
	}
}
