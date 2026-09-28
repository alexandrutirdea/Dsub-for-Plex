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

package github.daneren2005.dsub.fragments;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.MusicDirectory.Entry;
import github.daneren2005.dsub.domain.Playlist;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;
import github.daneren2005.dsub.service.plex.PlexMusicService;
import github.daneren2005.dsub.util.AlbumBrowseFilter;
import github.daneren2005.dsub.util.AlbumFilterMenu;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.DownloadedAlbums;
import github.daneren2005.dsub.util.FocusedListening;
import github.daneren2005.dsub.util.LoadingTask;
import github.daneren2005.dsub.util.ProgressListener;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.Util;

/**
 * The Home screen: rows of real artwork - the collection being focused on, what is worth
 * revisiting, what is short, what was played and added recently, and the user's playlists
 * - with the shortcut tiles for the album lists underneath.
 *
 * The tiles carry over every destination the old list-based home had, so nothing became
 * unreachable when this replaced it.
 */
public class HomeFragment extends SubsonicFragment {
	private static final String TAG = HomeFragment.class.getSimpleName();
	private static final int ROW_SIZE = 10;
	/**
	 * The curated carousels - focused listening, worth revisiting, short and highly rated.
	 * Far longer than the plain rows because these are meant to be browsed rather than
	 * glanced at, and all three draw on pools of well over thirty.
	 */
	private static final int CAROUSEL_SIZE = 30;
	// Well clear of the generated R.id range (0x7f......) so it cannot collide.
	private static final int MENU_ITEM_FOCUSED = 0x00BF0002;
	/** Argument: open with the Downloaded albums row scrolled into view, as the drawer asks. */
	public static final String ARG_SHOW_DOWNLOADED = "showDownloaded";

	/** Album list type paired with the label shown on its tile. */
	private static class Shortcut {
		final String type;
		final int label;

		Shortcut(String type, int label) {
			this.type = type;
			this.label = label;
		}
	}

	private GridLayout tilesView;

	/** The Focused listening row: one collection, filtered, both remembered between runs. */
	private AlbumBrowseFilter focusedFilter;
	private AlbumFilterMenu focusedFilterMenu;
	private String focusedCollectionId;
	private String focusedCollectionName;
	/** The collection as it came off the server, so a filter change costs no fetch. */
	private List<Entry> focusedAlbums;
	/**
	 * The collection a load is in flight for, so the reloads that come back to this
	 * screen do not stack up requests behind one another. Held by id rather than as a
	 * flag: choosing a different collection has to be read for straight away, whatever
	 * is still arriving for the last one.
	 */
	private String focusedLoadingId;
	/** Set while this screen is away, so coming back to it reads the collection again. */
	private boolean focusedStale;

	// What the rows were last filled from, kept so that rebuilding the view does not mean
	// fetching all of it again. Only the view is thrown away on a rotation; none of this
	// has changed by the time the new one is built.
	private MusicDirectory recentAlbums;
	private MusicDirectory newestAlbums;
	private MusicDirectory recommendedAlbums;
	/** The whole qualifying pool, not the row: a new sample is drawn from it each time. */
	private List<Entry> shortAlbumPool;
	private List<Playlist> homePlaylists;
	private boolean rowsLoaded;
	private List<Entry> downloadedAlbums;
	private boolean downloadedLoading;
	/** Set when the screen was opened to show the downloaded row, until it has been shown. */
	private boolean scrollToDownloaded;

	/**
	 * Kept across a configuration change so the rows survive it. Safe here because this
	 * fragment is never put on a FragmentManager back stack - the activity keeps its own
	 * list and hides fragments rather than replacing them - and because the activity
	 * reference is reassigned in onAttach, so nothing retained points at the old one.
	 */
	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);
		setRetainInstance(true);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		rootView = inflater.inflate(R.layout.home_fragment, container, false);
		setTitle(R.string.button_bar_home);

		Bundle args = getArguments();
		if(args != null && args.getBoolean(ARG_SHOW_DOWNLOADED)) {
			// Taken once: a rotation rebuilds this view and should leave the scroll alone.
			args.remove(ARG_SHOW_DOWNLOADED);
			scrollToDownloaded = true;
		}

		tilesView = (GridLayout) rootView.findViewById(R.id.home_tiles);

		setupFocusedListening();
		buildTiles(inflater);
		if(rowsLoaded) {
			fillLoadedRows();
		} else {
			loadRows();
		}

		return rootView;
	}

	/**
	 * Puts back what was already fetched, into the view that has just been built.
	 *
	 * The focused row is the one exception: its filter menu is rebuilt along with the
	 * view and starts out with no album details, so a style or length filter would match
	 * nothing until they are read again. Loading it costs nothing much now that both the
	 * listing and the details are cached on disk.
	 */
	private void fillLoadedRows() {
		fillAlbumRow(R.id.home_recent_row, R.id.home_recent_scroll, R.id.home_recent_header, recentAlbums);
		fillAlbumRow(R.id.home_newest_row, R.id.home_newest_scroll, R.id.home_newest_header, newestAlbums);
		fillAlbumRow(R.id.home_recommended_row, R.id.home_recommended_scroll,
				R.id.home_recommended_header, recommendedAlbums);
		fillShortRow();
		fillDownloadedRow();
		fillPlaylistRow(homePlaylists);
		loadFocusedCollection();
	}

	/**
	 * The Focused listening row is read again every time this screen comes back to the
	 * front, whether from the album that was open over it or from the app being away.
	 *
	 * The other rows are fetched once and kept: what is newest, or worth revisiting, is
	 * the same answer it was ten minutes ago. This one has to move while the app is open.
	 * An album leaves the row once it has been listened to, and the listening happens in
	 * the time between leaving this screen and coming back to it, so a row that is only
	 * ever filled once shows the album that was just played sitting where it was.
	 */
	@Override
	public void setPrimaryFragment(boolean primary) {
		super.setPrimaryFragment(primary);
		if(primary) {
			reloadFocusedRow();
		} else {
			focusedStale = true;
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		focusedStale = true;
	}

	@Override
	public void onResume() {
		super.onResume();
		reloadFocusedRow();
	}

	/** Only once the screen has actually been away: the view builds with a fresh row. */
	private void reloadFocusedRow() {
		if(rootView == null || !focusedStale) {
			return;
		}

		focusedStale = false;
		loadFocusedCollection();
		// A download finishing while the screen was away moves this row too.
		loadDownloadedAlbums();
	}

	/**
	 * The activity builds the menu for whichever fragment it is showing, so this must not
	 * also ask for one of its own: both routes inflate into the same menu and every item
	 * comes out twice.
	 */
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		menuInflater.inflate(R.menu.main, menu);
		addFocusedListeningMenuItem(menu);
		onFinishSetupOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(item.getItemId() == MENU_ITEM_FOCUSED) {
			showFocusedListeningMenu();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	/**
	 * The album lists, as a two-column grid. Which ones appear depends on the server -
	 * the same conditions the old home screen used.
	 */
	private void buildTiles(LayoutInflater inflater) {
		List<Shortcut> shortcuts = new ArrayList<Shortcut>();
		shortcuts.add(new Shortcut("newest", R.string.main_albums_newest));
		shortcuts.add(new Shortcut("random", R.string.main_albums_random));
		shortcuts.add(new Shortcut("starred", R.string.main_albums_starred));
		shortcuts.add(new Shortcut("genres", R.string.main_albums_genres));
		shortcuts.add(new Shortcut("years", R.string.main_albums_year));
		shortcuts.add(new Shortcut("recent", R.string.main_albums_recent));
		shortcuts.add(new Shortcut("frequent", R.string.main_albums_frequent));
		if(!Util.isTagBrowsing(context)) {
			shortcuts.add(new Shortcut("highest", R.string.main_albums_highest));
		}

		tilesView.removeAllViews();
		for(final Shortcut shortcut: shortcuts) {
			View tile = inflater.inflate(R.layout.home_tile_item, tilesView, false);
			TextView name = (TextView) tile.findViewById(R.id.home_tile_name);
			name.setText(shortcut.label);

			GridLayout.LayoutParams params = new GridLayout.LayoutParams();
			params.width = 0;
			params.height = GridLayout.LayoutParams.WRAP_CONTENT;
			params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
			params.setMargins(4, 4, 4, 4);
			tile.setLayoutParams(params);

			name.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showAlbumList(shortcut.type);
				}
			});
			tilesView.addView(tile);
		}
	}

	/**
	 * Fetches every row on one background pass. Each is guarded separately: a server
	 * without playlists, without rating collections, or with an album list the backend
	 * does not support, should cost that row and not the whole screen.
	 */
	private void loadRows() {
		rowsLoaded = true;
		loadFocusedCollection();
		loadRecommendations();
		loadShortAlbums();
		loadDownloadedAlbums();

		new SilentBackgroundTask<Void>(context) {
			private MusicDirectory recent;
			private MusicDirectory newest;
			private List<Playlist> playlists;

			@Override
			protected Void doInBackground() throws Throwable {
				MusicService service = MusicServiceFactory.getMusicService(context);

				try {
					recent = service.getAlbumList("recent", ROW_SIZE, 0, false, context, null);
				} catch(Exception e) {
					Log.w(TAG, "Failed to load recently played", e);
				}
				try {
					newest = service.getAlbumList("newest", ROW_SIZE, 0, false, context, null);
				} catch(Exception e) {
					Log.w(TAG, "Failed to load recently added", e);
				}
				try {
					playlists = service.getPlaylists(false, context, null);
				} catch(Exception e) {
					Log.w(TAG, "Failed to load playlists", e);
				}
				return null;
			}

			@Override
			protected void done(Void result) {
				recentAlbums = recent;
				newestAlbums = newest;
				homePlaylists = playlists;

				if(rootView == null) {
					return;
				}

				fillAlbumRow(R.id.home_recent_row, R.id.home_recent_scroll, R.id.home_recent_header, recent);
				fillAlbumRow(R.id.home_newest_row, R.id.home_newest_scroll, R.id.home_newest_header, newest);
				fillPlaylistRow(playlists);
			}
		}.execute();
	}

	/**
	 * On its own pass, because it is the slowest thing here by far - it reads a window of
	 * two collections - and the other rows should not wait behind it. On mobile data that
	 * difference is the screen appearing at once instead of half a minute later.
	 */
	private void loadRecommendations() {
		new SilentBackgroundTask<MusicDirectory>(context) {
			@Override
			protected MusicDirectory doInBackground() throws Throwable {
				try {
					return MusicServiceFactory.getMusicService(context)
							.getRecommendedAlbums(CAROUSEL_SIZE, context, null);
				} catch(Exception e) {
					Log.w(TAG, "Failed to load recommendations", e);
					return null;
				}
			}

			@Override
			protected void done(MusicDirectory result) {
				recommendedAlbums = result;
				if(rootView != null) {
					fillAlbumRow(R.id.home_recommended_row, R.id.home_recommended_scroll,
							R.id.home_recommended_header, result);
				}
			}
		}.execute();
	}

	/**
	 * The albums on the phone in full. Read off the music folder, so it is the one row with
	 * something in it offline, where every other comes back empty.
	 */
	private void loadDownloadedAlbums() {
		if(downloadedLoading) {
			return;
		}
		downloadedLoading = true;

		new SilentBackgroundTask<List<Entry>>(context) {
			@Override
			protected List<Entry> doInBackground() throws Throwable {
				return DownloadedAlbums.find(context);
			}

			@Override
			protected void done(List<Entry> result) {
				downloadedLoading = false;
				downloadedAlbums = result;
				if(rootView != null) {
					fillDownloadedRow();
					showDownloadedIfAsked();
				}
			}

			@Override
			protected void error(Throwable error) {
				downloadedLoading = false;
				Log.w(TAG, "Failed to load downloaded albums", error);
				if(rootView != null) {
					showDownloadedIfAsked();
				}
			}
		}.execute();
	}

	private void fillDownloadedRow() {
		fillAlbumRow(R.id.home_downloaded_row, R.id.home_downloaded_scroll, R.id.home_downloaded_header,
				downloadedAlbums, null, null);
	}

	/**
	 * Brings the downloaded row into view when the screen was opened for it.
	 *
	 * The rows above it land whenever their own requests do, each one pushing it further
	 * down, so the scroll follows the row until the user takes over by touching the screen.
	 */
	private void showDownloadedIfAsked() {
		if(!scrollToDownloaded) {
			return;
		}
		scrollToDownloaded = false;

		if(downloadedAlbums == null || downloadedAlbums.isEmpty()) {
			Util.toast(context, R.string.main_albums_downloaded_none);
			return;
		}

		final ScrollView scroll = (ScrollView) rootView.findViewById(R.id.home_scroll);
		final View header = rootView.findViewById(R.id.home_downloaded_header);
		final View.OnLayoutChangeListener follow = new View.OnLayoutChangeListener() {
			@Override
			public void onLayoutChange(View v, int left, int top, int right, int bottom,
					int oldLeft, int oldTop, int oldRight, int oldBottom) {
				if(top != oldTop) {
					scroll.scrollTo(0, top);
				}
			}
		};
		header.addOnLayoutChangeListener(follow);
		scroll.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				header.removeOnLayoutChangeListener(follow);
				scroll.setOnTouchListener(null);
				return false;
			}
		});
		scroll.post(new Runnable() {
			@Override
			public void run() {
				scroll.smoothScrollTo(0, header.getTop());
			}
		});
	}

	/**
	 * Its own pass too. What comes back is every album that qualifies, not the row - the
	 * service caches that pool because working it out is expensive, and the row is a fresh
	 * draw from it.
	 */
	private void loadShortAlbums() {
		new SilentBackgroundTask<MusicDirectory>(context) {
			@Override
			protected MusicDirectory doInBackground() throws Throwable {
				try {
					return MusicServiceFactory.getMusicService(context).getShortAlbums(context, null);
				} catch(Exception e) {
					Log.w(TAG, "Failed to load short albums", e);
					return null;
				}
			}

			@Override
			protected void done(MusicDirectory result) {
				shortAlbumPool = (result == null) ? null : result.getChildren(true, false);
				if(rootView != null) {
					fillShortRow();
				}
			}
		}.execute();
	}

	/**
	 * A new sample out of the pool, which is what keeps the row worth looking at twice
	 * even though the pool behind it is cached for days at a time.
	 */
	private void fillShortRow() {
		List<Entry> albums = null;
		if(shortAlbumPool != null) {
			albums = new ArrayList<Entry>(shortAlbumPool);
			Collections.shuffle(albums);
			if(albums.size() > CAROUSEL_SIZE) {
				albums = albums.subList(0, CAROUSEL_SIZE);
			}
		}

		fillAlbumRow(R.id.home_short_row, R.id.home_short_scroll, R.id.home_short_header,
				albums, null, null);
	}

	/**
	 * The Focused listening row: the collection the user chose, filtered the way they
	 * filtered it.
	 *
	 * Everything about it is settled from the overflow menu rather than on the row itself.
	 * A home screen is a place to start listening from, and pills or a chooser sitting
	 * above the artwork would make choosing what to listen to the first thing on it.
	 */
	private void setupFocusedListening() {
		focusedCollectionId = FocusedListening.getCollectionId(context);
		focusedCollectionName = FocusedListening.getCollectionName(context);
		focusedFilter = FocusedListening.loadFilter(context);

		focusedFilterMenu = new AlbumFilterMenu(context, focusedFilter, new AlbumFilterMenu.Host() {
			@Override
			public List<Entry> getFilterAlbums() {
				return focusedAlbums;
			}

			@Override
			public List<Entry> getVisibleFilterAlbums() {
				return filteredFocusedAlbums();
			}

			@Override
			public void onFilterChanged() {
				// Remembered, because the row is a standing decision about what to listen
				// to next rather than a look at the collection.
				FocusedListening.saveFilter(context, focusedFilter);
				fillFocusedRow();
			}
		});
	}

	private void addFocusedListeningMenuItem(Menu menu) {
		if(!FocusedListening.isAvailable(context) || menu.findItem(MENU_ITEM_FOCUSED) != null) {
			return;
		}

		MenuItem item = menu.add(Menu.NONE, MENU_ITEM_FOCUSED, Menu.NONE, R.string.main_albums_focused);
		item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
	}

	/**
	 * The collection chooser above the collection filters, in one menu - these are the
	 * same filters the collection screen offers, from the same code, so a collection
	 * narrowed down there can be narrowed down the same way here.
	 */
	private void showFocusedListeningMenu() {
		// The menu can be built before this screen has a view of its own to filter.
		if(focusedFilterMenu == null) {
			return;
		}

		String collection = (focusedCollectionId == null)
				? getString(R.string.menu_focused_collection_none)
				: getString(R.string.menu_focused_collection, focusedCollectionName);

		focusedFilterMenu.show(R.string.main_albums_focused, new String[] {collection},
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						chooseFocusedCollection();
					}
				});
	}

	/** Fetches the collection list, then offers it. Cached, so this is usually instant. */
	private void chooseFocusedCollection() {
		new LoadingTask<List<Entry>>(context, true) {
			@Override
			protected List<Entry> doInBackground() throws Throwable {
				MusicService service = MusicServiceFactory.getMusicService(context);
				MusicDirectory collections = service.getMusicDirectory(PlexMusicService.COLLECTIONS_ID,
						getString(R.string.button_bar_collections), false, context, this);
				return collections.getChildren(true, false);
			}

			@Override
			protected void done(List<Entry> result) {
				showFocusedCollectionDialog(result);
			}
		}.execute();
	}

	private void showFocusedCollectionDialog(final List<Entry> collections) {
		if(collections == null || collections.isEmpty()) {
			Util.toast(context, R.string.menu_focused_collections_empty);
			return;
		}

		// "None" leads the list because turning the row off is the other half of choosing
		// what it shows, and there is nowhere else to do it.
		final String[] labels = new String[collections.size() + 1];
		labels[0] = getString(R.string.menu_focused_collection_off);

		int selected = 0;
		for(int i = 0; i < collections.size(); i++) {
			Entry collection = collections.get(i);
			labels[i + 1] = collection.getTitle();
			if(focusedCollectionId != null && focusedCollectionId.equals(collection.getId())) {
				selected = i + 1;
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_focused_collection_title)
				.setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						setFocusedCollection(which == 0 ? null : collections.get(which - 1));
					}
				})
				.show();
	}

	/** A null collection turns the row off. The filter is kept either way. */
	private void setFocusedCollection(Entry collection) {
		focusedCollectionId = (collection == null) ? null : collection.getId();
		focusedCollectionName = (collection == null) ? null : collection.getTitle();
		FocusedListening.setCollection(context, focusedCollectionId, focusedCollectionName);

		// The details describe albums that are no longer on the row, and the style tags
		// worth offering are whatever the new collection carries.
		focusedAlbums = null;
		focusedFilterMenu.clearAlbumDetails();

		if(focusedCollectionId == null) {
			fillFocusedRow();
		} else {
			loadFocusedCollection();
		}
	}

	/**
	 * A pass of its own, like the other carousels. It can cost two requests - the listing,
	 * and album details when a filter needs them - and the rest of the screen should not
	 * wait behind either.
	 *
	 * Asked of the server rather than of the directory cache, which is the one row on this
	 * screen where that matters: the cache holds the play dates as they stood when the
	 * collection was last listed, and the rested filter is a question about exactly those.
	 * Reading them from the cache shows an album straight back after it has been listened
	 * to, which is what the row exists to stop. The cache is still there to fall back on
	 * when the server cannot be reached - a row that is a day out of date beats no row.
	 */
	private void loadFocusedCollection() {
		final String collectionId = focusedCollectionId;
		if(collectionId == null || !FocusedListening.isAvailable(context)
				|| collectionId.equals(focusedLoadingId)) {
			return;
		}

		focusedLoadingId = collectionId;
		new SilentBackgroundTask<List<Entry>>(context) {
			@Override
			protected List<Entry> doInBackground() throws Throwable {
				try {
					return readCollection(collectionId, true, this);
				} catch(Exception e) {
					Log.w(TAG, "Failed to refresh focused collection", e);
				}

				try {
					return readCollection(collectionId, false, this);
				} catch(Exception e) {
					Log.w(TAG, "Failed to load focused collection", e);
					return null;
				}
			}

			@Override
			protected void done(List<Entry> result) {
				loadFinished(collectionId);

				// Another collection may have been chosen while this was in flight, and a
				// listing that could not be read at all leaves the row as it was rather
				// than emptying it.
				if(rootView == null || result == null || !collectionId.equals(focusedCollectionId)) {
					return;
				}

				focusedAlbums = result;
				fillFocusedRow();
			}

			@Override
			public void error(Throwable error) {
				loadFinished(collectionId);
				Log.w(TAG, "Failed to load focused collection", error);
			}
		}.execute();
	}

	/** Clears the in-flight mark, unless a load of another collection has taken it over. */
	private void loadFinished(String collectionId) {
		if(collectionId.equals(focusedLoadingId)) {
			focusedLoadingId = null;
		}
	}

	/** The collection's albums, off the server or out of the cache, details and all. */
	private List<Entry> readCollection(String collectionId, boolean refresh, ProgressListener progressListener) throws Exception {
		MusicService service = MusicServiceFactory.getMusicService(context);
		MusicDirectory collection = service.getMusicDirectory(collectionId,
				focusedCollectionName, refresh, context, progressListener);
		List<Entry> albums = collection.getChildren(true, false);

		// Without details a style, length or track-count filter matches nothing at all,
		// which would read as an empty collection rather than a filter.
		focusedFilterMenu.loadDetailsIfNeeded(albums, service, progressListener);
		return albums;
	}

	/** What the filter leaves of the collection; empty until the collection is loaded. */
	private List<Entry> filteredFocusedAlbums() {
		if(focusedAlbums == null) {
			return new ArrayList<Entry>();
		}

		return focusedFilter.apply(focusedAlbums, System.currentTimeMillis() / 1000L,
				focusedFilterMenu.getAlbumDetails());
	}

	private void fillFocusedRow() {
		if(rootView == null) {
			return;
		}

		List<Entry> albums = filteredFocusedAlbums();
		// The same ceiling as the other carousels: past thirty this stops being a row to
		// focus on and becomes the collection screen with worse navigation.
		if(albums.size() > CAROUSEL_SIZE) {
			albums = albums.subList(0, CAROUSEL_SIZE);
		}

		fillAlbumRow(R.id.home_focused_row, R.id.home_focused_scroll, R.id.home_focused_header,
				albums, focusedCollectionId, focusedCollectionName);
	}

	private void fillAlbumRow(int rowId, int scrollId, int headerId, MusicDirectory directory) {
		fillAlbumRow(rowId, scrollId, headerId,
				(directory == null) ? null : directory.getChildren(true, false), null, null);
	}

	/**
	 * Fills one carousel, hiding it and its header when there is nothing to put in it -
	 * a row can be refilled after a filter change, and an empty one has to go away.
	 *
	 * A collection id is carried into the album screen when the row came out of one, so a
	 * play there can still empty a self-emptying collection, exactly as it does when the
	 * album was reached from the collection itself.
	 */
	private void fillAlbumRow(int rowId, int scrollId, int headerId, List<Entry> albums,
			final String collectionId, final String collectionName) {
		LinearLayout row = (LinearLayout) rootView.findViewById(rowId);
		row.removeAllViews();

		if(albums == null || albums.isEmpty()) {
			rootView.findViewById(scrollId).setVisibility(View.GONE);
			rootView.findViewById(headerId).setVisibility(View.GONE);
			return;
		}

		LayoutInflater inflater = LayoutInflater.from(context);
		for(final Entry album: albums) {
			View item = inflater.inflate(R.layout.home_album_item, row, false);
			((TextView) item.findViewById(R.id.home_item_title)).setText(album.getTitle());
			((TextView) item.findViewById(R.id.home_item_subtitle)).setText(album.getArtist());
			getImageLoader().loadImage((ImageView) item.findViewById(R.id.home_item_art), album, false, true);

			item.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					openAlbum(album, collectionId, collectionName);
				}
			});
			row.addView(item);
		}

		rootView.findViewById(scrollId).setVisibility(View.VISIBLE);
		rootView.findViewById(headerId).setVisibility(View.VISIBLE);
	}

	private void fillPlaylistRow(List<Playlist> playlists) {
		LinearLayout row = (LinearLayout) rootView.findViewById(R.id.home_playlist_row);
		row.removeAllViews();

		if(playlists == null || playlists.isEmpty()) {
			return;
		}

		LayoutInflater inflater = LayoutInflater.from(context);
		for(final Playlist playlist: playlists) {
			View item = inflater.inflate(R.layout.home_album_item, row, false);
			((TextView) item.findViewById(R.id.home_item_title)).setText(playlist.getName());
			((TextView) item.findViewById(R.id.home_item_subtitle)).setText(playlist.getOwner());
			getImageLoader().loadImage((ImageView) item.findViewById(R.id.home_item_art), playlist, false, true);

			item.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showPlaylist(playlist);
				}
			});
			row.addView(item);
		}

		rootView.findViewById(R.id.home_playlist_scroll).setVisibility(View.VISIBLE);
		rootView.findViewById(R.id.home_playlist_header).setVisibility(View.VISIBLE);
	}

	private void openAlbum(Entry album, String collectionId, String collectionName) {
		SubsonicFragment fragment = new SelectDirectoryFragment();
		Bundle args = new Bundle();
		args.putString(Constants.INTENT_EXTRA_NAME_ID, album.getId());
		args.putString(Constants.INTENT_EXTRA_NAME_NAME, album.getTitle());
		if(collectionId != null) {
			args.putString(Constants.INTENT_EXTRA_NAME_COLLECTION_ID, collectionId);
			args.putString(Constants.INTENT_EXTRA_NAME_COLLECTION_NAME, collectionName);
		}
		fragment.setArguments(args);
		replaceFragment(fragment);
	}

	private void showPlaylist(Playlist playlist) {
		SubsonicFragment fragment = new SelectDirectoryFragment();
		Bundle args = new Bundle();
		args.putString(Constants.INTENT_EXTRA_NAME_ID, playlist.getId());
		args.putString(Constants.INTENT_EXTRA_NAME_NAME, playlist.getName());
		args.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_ID, playlist.getId());
		args.putString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME, playlist.getName());
		fragment.setArguments(args);
		replaceFragment(fragment);
	}

	/** Same routing the old home screen used, so the tiles land where they always did. */
	private void showAlbumList(String type) {
		if("genres".equals(type)) {
			replaceFragment(new SelectGenreFragment());
			return;
		}
		if("years".equals(type)) {
			replaceFragment(new SelectYearFragment());
			return;
		}

		if("newest".equals(type)) {
			SharedPreferences.Editor editor = Util.getPreferences(context).edit();
			editor.putInt(Constants.PREFERENCES_KEY_RECENT_COUNT + Util.getActiveServer(context), 0);
			editor.commit();
		}

		SubsonicFragment fragment = new SelectDirectoryFragment();
		Bundle args = new Bundle();
		args.putString(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_TYPE, type);
		args.putInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 20);
		args.putInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_OFFSET, 0);
		fragment.setArguments(args);
		replaceFragment(fragment);
	}
}
