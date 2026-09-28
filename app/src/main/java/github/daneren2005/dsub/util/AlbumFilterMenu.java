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

import android.app.Activity;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.MusicDirectory.Entry;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;

/**
 * The "Filter &amp; Sort" menu over an {@link AlbumBrowseFilter}: a short list of
 * categories, each opening a single-choice dialog. Simpler and far more robust than a
 * custom multi-control dialog layout.
 *
 * It belongs here rather than on the collection screen because two places offer the same
 * filters over different albums - a collection being browsed, and the Home screen's
 * Focused listening row - and "the same filters" has to mean the same options, the same
 * wording and the same behaviour, which one copy guarantees and two do not.
 *
 * Track count, running time and style tags are not in an album listing, so the three
 * filters that need them fetch details first. Those are held here, keyed by album id, and
 * kept afterwards so changing filters is free.
 */
public class AlbumFilterMenu {
	/** What the menu needs from the screen it is filtering. */
	public interface Host {
		/** Every album the filter runs over, whatever it currently hides. */
		List<Entry> getFilterAlbums();

		/** The albums left showing, which is what the style list is drawn from. */
		List<Entry> getVisibleFilterAlbums();

		/** Called on the main thread after any change to the filter. */
		void onFilterChanged();
	}

	/**
	 * Above this many albums the detail fetch stops being worth the wait. A batch of fifty
	 * costs about a third of a second, so this is roughly a minute at the limit. The
	 * progress dialog is cancellable, which is what makes a wait that long acceptable.
	 */
	private static final int MAX_DETAIL_ALBUMS = 5000;

	private final Activity context;
	private final AlbumBrowseFilter filter;
	private final Host host;
	/**
	 * Null until a filter that needs it is chosen. Volatile because a screen reloading its
	 * albums applies the filter on its loading thread, and this is what it reads.
	 */
	private volatile Map<String, AlbumDetails> albumDetails;

	public AlbumFilterMenu(Activity context, AlbumBrowseFilter filter, Host host) {
		this.context = context;
		this.filter = filter;
		this.host = host;
	}

	public AlbumBrowseFilter getFilter() {
		return filter;
	}

	public Map<String, AlbumDetails> getAlbumDetails() {
		return albumDetails;
	}

	/** Drops the details when the albums underneath the filter are replaced wholesale. */
	public void clearAlbumDetails() {
		albumDetails = null;
	}

	/**
	 * Fetches details when a filter needs them and none are held, on the caller's thread.
	 *
	 * For screens that reload their albums in the background: a filter on style, length or
	 * track count matches nothing at all without details, so a reload that skipped this
	 * would quietly empty the list rather than filter it.
	 */
	public void loadDetailsIfNeeded(List<Entry> albums, MusicService service,
			ProgressListener progressListener) throws Exception {
		if(albumDetails != null || !filter.needsDetails() || albums == null
				|| albums.isEmpty() || albums.size() > MAX_DETAIL_ALBUMS) {
			return;
		}

		List<String> ids = new ArrayList<String>();
		for(Entry album: albums) {
			ids.add(album.getId());
		}
		albumDetails = service.getAlbumDetails(ids, context, progressListener);
	}

	/** The categories, under the standard title. */
	public void show() {
		show(R.string.menu_filter, null, null);
	}

	/**
	 * The same categories with rows of the host's own above them, so a screen that has
	 * more to say about the filtered list than the filter itself - Home names the
	 * collection there - keeps all of it in one menu.
	 *
	 * {@code leadingListener} is called with the index into {@code leading}.
	 */
	public void show(int titleRes, final String[] leading, final DialogInterface.OnClickListener leadingListener) {
		final int offset = (leading == null) ? 0 : leading.length;
		final String[] categories = new String[] {
				context.getString(R.string.menu_filter_sort),
				context.getString(R.string.menu_filter_order),
				context.getString(R.string.menu_filter_decade),
				context.getString(R.string.menu_filter_rested),
				context.getString(R.string.menu_filter_style),
				context.getString(R.string.menu_filter_length),
				context.getString(R.string.menu_filter_tracks),
				context.getString(R.string.menu_filter_clear)
		};

		final String[] options = new String[offset + categories.length];
		for(int i = 0; i < offset; i++) {
			options[i] = leading[i];
		}
		System.arraycopy(categories, 0, options, offset, categories.length);

		new AlertDialog.Builder(context)
				.setTitle(titleRes)
				.setItems(options, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if(which < offset) {
							if(leadingListener != null) {
								leadingListener.onClick(dialog, which);
							}
							return;
						}

						switch(which - offset) {
							case 0: showSortDialog(); break;
							case 1: showOrderDialog(); break;
							case 2: showDecadeDialog(); break;
							case 3: showRestedDialog(); break;
							case 4: showStyleDialog(); break;
							case 5: showLengthDialog(); break;
							case 6: showTracksDialog(); break;
							default:
								filter.clear();
								host.onFilterChanged();
								break;
						}
					}
				})
				.show();
	}

	private void showSortDialog() {
		final AlbumBrowseFilter.SortKey[] keys = AlbumBrowseFilter.SortKey.values();
		String[] labels = new String[] {
				context.getString(R.string.menu_filter_sort_default),
				context.getString(R.string.menu_filter_sort_title),
				context.getString(R.string.menu_filter_sort_year),
				context.getString(R.string.menu_filter_sort_added),
				context.getString(R.string.menu_filter_sort_played),
				context.getString(R.string.menu_filter_sort_random)
		};

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_sort)
				.setSingleChoiceItems(labels, filter.getSortKey().ordinal(), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						filter.setSortKey(keys[which]);
						dialog.dismiss();
						host.onFilterChanged();
					}
				})
				.show();
	}

	private void showOrderDialog() {
		String[] labels = new String[] {
				context.getString(R.string.menu_filter_order_asc),
				context.getString(R.string.menu_filter_order_desc)
		};

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_order)
				.setSingleChoiceItems(labels, filter.isDescending() ? 1 : 0, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						filter.setDescending(which == 1);
						dialog.dismiss();
						host.onFilterChanged();
					}
				})
				.show();
	}

	private void showDecadeDialog() {
		final AlbumBrowseFilter.Decade[] decades = AlbumBrowseFilter.Decade.values();
		String[] labels = new String[decades.length + 1];
		labels[0] = context.getString(R.string.menu_filter_any);
		labels[1] = context.getString(R.string.menu_filter_decade_pre80);
		labels[2] = "1980s";
		labels[3] = "1990s";
		labels[4] = "2000s";
		labels[5] = "2010s";
		labels[6] = "2020s";

		int selected = filter.getDecade() == null ? 0 : filter.getDecade().ordinal() + 1;
		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_decade)
				.setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						filter.setDecade(which == 0 ? null : decades[which - 1]);
						dialog.dismiss();
						host.onFilterChanged();
					}
				})
				.show();
	}

	/**
	 * Runs {@code then} once album details are in hand, fetching them first if needed.
	 *
	 * Deferred rather than loaded with the listing because it costs two requests per
	 * fifty albums, and most visits to a collection never open a filter at all.
	 */
	private void withAlbumDetails(final Runnable then) {
		if(albumDetails != null) {
			then.run();
			return;
		}

		// The whole list, not just what is visible: a later filter change can widen it,
		// and albums fetched without details would drop out when it does.
		final List<Entry> albums = host.getFilterAlbums();
		if(albums == null || albums.isEmpty()) {
			Util.toast(context, R.string.menu_filter_no_match);
			return;
		}
		if(albums.size() > MAX_DETAIL_ALBUMS) {
			Util.toast(context, context.getResources().getString(
					R.string.menu_filter_details_too_many, MAX_DETAIL_ALBUMS));
			return;
		}

		new LoadingTask<Map<String, AlbumDetails>>(context, true) {
			@Override
			protected Map<String, AlbumDetails> doInBackground() throws Throwable {
				List<String> ids = new ArrayList<String>();
				for(Entry album: albums) {
					ids.add(album.getId());
				}

				MusicService service = MusicServiceFactory.getMusicService(context);
				return service.getAlbumDetails(ids, context, this);
			}

			@Override
			protected void done(Map<String, AlbumDetails> result) {
				albumDetails = result;
				then.run();
			}
		}.execute();
	}

	/** Multi-select over the style tags actually present in these albums. */
	public void showStyleDialog() {
		withAlbumDetails(new Runnable() {
			@Override
			public void run() {
				showStyleChoices();
			}
		});
	}

	private void showStyleChoices() {
		final List<String> styles = AlbumBrowseFilter.collectStyles(host.getVisibleFilterAlbums(), albumDetails);
		if(styles.isEmpty()) {
			Util.toast(context, R.string.menu_filter_style_none);
			return;
		}

		final boolean[] checked = new boolean[styles.size()];
		String[] labels = new String[styles.size()];
		for(int i = 0; i < styles.size(); i++) {
			String style = styles.get(i);
			labels[i] = AlbumBrowseFilter.NO_STYLE.equals(style)
					? context.getString(R.string.menu_filter_style_untagged) : style;
			checked[i] = filter.getStyles().contains(style);
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_style)
				.setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which, boolean isChecked) {
						checked[which] = isChecked;
					}
				})
				.setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Set<String> selected = new LinkedHashSet<String>();
						for(int i = 0; i < styles.size(); i++) {
							if(checked[i]) {
								selected.add(styles.get(i));
							}
						}

						filter.setStyles(selected);
						dialog.dismiss();
						host.onFilterChanged();
					}
				})
				.setNegativeButton(R.string.common_cancel, null)
				.show();
	}

	/** Both ends of the album length, typed in. */
	public void showLengthDialog() {
		withAlbumDetails(new Runnable() {
			@Override
			public void run() {
				showLengthRange();
			}
		});
	}

	/**
	 * Typed rather than picked from a list, and in the same two-bound shape as the mix's
	 * track length filter: a useful cutoff is a matter of taste and of what is in the
	 * collection, where a preset list is either long or wrong, and leaving one field blank
	 * is what lets the one dialog say "over", "under" and a window between the two.
	 */
	private void showLengthRange() {
		View view = LayoutInflater.from(context).inflate(R.layout.filter_range, null);
		final EditText minView = (EditText) view.findViewById(R.id.filter_range_min);
		final EditText maxView = (EditText) view.findViewById(R.id.filter_range_max);

		((TextView) view.findViewById(R.id.filter_range_min_label)).setText(R.string.menu_filter_length_longer);
		((TextView) view.findViewById(R.id.filter_range_max_label)).setText(R.string.menu_filter_length_shorter);
		((TextView) view.findViewById(R.id.filter_range_min_suffix)).setText(R.string.menu_filter_length_minutes);
		((TextView) view.findViewById(R.id.filter_range_max_suffix)).setText(R.string.menu_filter_length_minutes);

		// Whole minutes: an album's running time is filtered on the minute, so a keyboard
		// without a decimal point is one fewer way to type something that has to be rounded.
		minView.setInputType(InputType.TYPE_CLASS_NUMBER);
		maxView.setInputType(InputType.TYPE_CLASS_NUMBER);

		minView.setText(minutesText(filter.getMinDurationMinutes()));
		maxView.setText(minutesText(filter.getMaxDurationMinutes()));

		final AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_length)
				.setView(view)
				.setNegativeButton(R.string.common_cancel, null)
				.setPositiveButton(R.string.common_ok, null)
				.create();
		dialog.show();

		// The listener goes on after showing so a rejected pair can leave the dialog up:
		// handed to the builder instead, the button dismisses whatever the listener does,
		// and the numbers being corrected would be gone by the time the toast was read.
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View button) {
				int min = parseMinutes(minView.getText().toString());
				int max = parseMinutes(maxView.getText().toString());

				// A window with its ends the wrong way round matches nothing, and silently
				// emptying the list would read as a bug in the filter rather than a typo
				// in the numbers.
				if(min > 0 && max > 0 && min >= max) {
					Util.toast(context, R.string.menu_filter_length_invalid);
					return;
				}

				filter.setMinDurationMinutes(min);
				filter.setMaxDurationMinutes(max);
				dialog.dismiss();
				host.onFilterChanged();
			}
		});
	}

	/** Blank for a bound that is off, so an unset field shows empty rather than "0". */
	private static String minutesText(int minutes) {
		return minutes > 0 ? String.valueOf(minutes) : "";
	}

	/** Whole minutes as typed. Blank, nonsense and anything at or below zero mean "off". */
	private static int parseMinutes(String text) {
		if(text == null) {
			return 0;
		}

		String trimmed = text.trim();
		if(trimmed.isEmpty()) {
			return 0;
		}

		try {
			int value = Integer.parseInt(trimmed);
			return value > 0 ? value : 0;
		} catch(NumberFormatException e) {
			return 0;
		}
	}

	public void showTracksDialog() {
		withAlbumDetails(new Runnable() {
			@Override
			public void run() {
				showTracksChoices();
			}
		});
	}

	private void showTracksChoices() {
		final int[] minimums = new int[] {0, 5, 7, 10};
		String[] labels = new String[minimums.length];
		labels[0] = context.getString(R.string.menu_filter_any);
		for(int i = 1; i < minimums.length; i++) {
			labels[i] = context.getResources().getString(R.string.menu_filter_tracks_least, minimums[i]);
		}

		int selected = 0;
		for(int i = 0; i < minimums.length; i++) {
			if(minimums[i] == filter.getMinTracks()) {
				selected = i;
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_tracks)
				.setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						filter.setMinTracks(minimums[which]);
						dialog.dismiss();
						host.onFilterChanged();
					}
				})
				.show();
	}

	/** Days an album must have rested to still show. Reached from the pill and the menu. */
	public void showRestedDialog() {
		final int[] days = new int[] {0, 30, 90, 180, 365, 730, 1825};
		String[] labels = new String[days.length];
		labels[0] = context.getString(R.string.menu_filter_any);
		for(int i = 1; i < days.length; i++) {
			labels[i] = restedOptionLabel(days[i]);
		}

		int selected = 0;
		for(int i = 0; i < days.length; i++) {
			if(days[i] == filter.getMinRestedDays()) {
				selected = i;
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_filter_rested)
				.setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						filter.setMinRestedDays(days[which]);
						dialog.dismiss();
						host.onFilterChanged();
					}
				})
				.show();
	}

	/** "3 months" reads better than "90 days"; a year and up is spoken in years. */
	private String restedOptionLabel(int days) {
		if(days >= 365) {
			int years = days / 365;
			return context.getResources().getQuantityString(R.plurals.filter_rested_years, years, years);
		}
		return context.getResources().getQuantityString(R.plurals.filter_rested_days, days, days);
	}
}
