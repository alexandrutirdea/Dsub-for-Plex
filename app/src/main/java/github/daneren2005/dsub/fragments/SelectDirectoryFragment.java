package github.daneren2005.dsub.fragments;

import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.TextViewCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import github.daneren2005.dsub.R;
import github.daneren2005.dsub.adapter.AlphabeticalAlbumAdapter;
import github.daneren2005.dsub.adapter.EntryInfiniteGridAdapter;
import github.daneren2005.dsub.adapter.EntryGridAdapter;
import github.daneren2005.dsub.adapter.SectionAdapter;
import github.daneren2005.dsub.adapter.TopRatedAlbumAdapter;
import github.daneren2005.dsub.domain.AlbumDetails;
import github.daneren2005.dsub.domain.AlbumSnapshot;
import github.daneren2005.dsub.domain.ArtistInfo;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.ServerInfo;
import github.daneren2005.dsub.domain.Share;
import github.daneren2005.dsub.service.CachedMusicService;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.util.AlbumSnapshots;
import github.daneren2005.dsub.util.ArtworkColors;
import github.daneren2005.dsub.util.DownloadedAlbums;
import github.daneren2005.dsub.util.DrawableTint;
import github.daneren2005.dsub.util.FileUtil;
import github.daneren2005.dsub.util.ImageLoader;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import android.text.format.DateUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

import github.daneren2005.dsub.domain.PodcastEpisode;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;
import github.daneren2005.dsub.service.OfflineException;
import github.daneren2005.dsub.service.ServerTooOldException;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.LoadingTask;
import github.daneren2005.dsub.util.Pair;
import github.daneren2005.dsub.util.RetainedState;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.TabBackgroundTask;
import github.daneren2005.dsub.util.ThemeUtil;
import github.daneren2005.dsub.util.UpdateHelper;
import github.daneren2005.dsub.util.AlbumBrowseFilter;
import github.daneren2005.dsub.util.AlbumFilterMenu;
import github.daneren2005.dsub.util.CollectionCuration;
import github.daneren2005.dsub.util.PlaylistMix;
import github.daneren2005.dsub.service.plex.PlexMusicService;
import github.daneren2005.dsub.util.UserUtil;
import github.daneren2005.dsub.util.LoveClient;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.dsub.view.FastScroller;
import github.daneren2005.dsub.view.GridSpacingDecoration;
import github.daneren2005.dsub.view.MyLeadingMarginSpan2;
import github.daneren2005.dsub.view.UpdateView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static github.daneren2005.dsub.domain.MusicDirectory.Entry;

public class SelectDirectoryFragment extends SubsonicFragment implements SectionAdapter.OnItemClickedListener<Entry> {
	private static final String TAG = SelectDirectoryFragment.class.getSimpleName();

	private RecyclerView recyclerView;
	private FastScroller fastScroller;
	private EntryGridAdapter entryGridAdapter;
	private Boolean licenseValid;
	private List<Entry> albums;
	private List<Entry> entries;
	private LoadTask currentTask;
	private ArtistInfo artistInfo;
	private String artistInfoDelayed;

	private SilentBackgroundTask updateCoverArtTask;
	private ImageView coverArtView;
	private Entry coverArtRep;
	private String coverArtId;
	/** The colour the screen is washed in from the header's cover, or null for none. */
	private Integer screenBackdropColor;
	/** Waiting on the header's cover to land, so its colour can be read off it. */
	private Runnable headerArtworkWatch;
	/** The header's artist line without the trailing year. Null unless there is one artist. */
	private String headerArtistWithoutYear;
	/** The album's own last played, once the server has been asked. Epoch seconds. */
	private Long albumLastPlayed;
	/** Whether the server has answered, which it does with null for a record never played. */
	private boolean albumLastPlayedResolved;

	String id;
	String name;
	Entry directory;
	String playlistId;
	String playlistName;
	boolean playlistOwner;
	String podcastId;
	String podcastName;
	String podcastDescription;
	String albumListType;
	String albumListExtra;
	int albumListSize;
	boolean refreshListing = false;
	boolean showAll = false;
	boolean restoredInstance = false;
	boolean lookupParent = false;
	boolean largeAlbums = false;
	boolean topTracks = false;
	/** The artist's picture, handed over by the row that opened this screen. */
	String artistArt;
	String lookupEntry;
	/** Unfiltered copy so filters can be changed without refetching. */
	private List<Entry> unfilteredEntries;
	private AlbumBrowseFilter browseFilter = new AlbumBrowseFilter();
	/** The filter dialogs, shared with the Home screen's Focused listening row. */
	private AlbumFilterMenu filterMenu;
	// Well clear of the generated R.id range (0x7f......) so it cannot collide.
	private static final int MENU_ITEM_BROWSE_FILTER = 0x00BF0001;
	/**
	 * How long a collection listing is trusted before returning to it fetches it again.
	 * Long enough that walking in and out of albums is not a request every time, short
	 * enough that a collection edited while the phone was in a pocket comes back current.
	 */
	private static final long COLLECTION_STALE_MILLIS = 5 * 60 * 1000L;
	private EditText collectionSearchView;
	/** Live text of the collection filter, kept so a reload re-applies it. */
	private String collectionSearchQuery = "";
	private View collectionActionBar;
	private TextView collectionRandomButton;
	private TextView collectionAutoplayButton;

	private View collectionFilterBar;
	private TextView collectionRestedButton;
	private TextView collectionDurationButton;

	/**
	 * The collection an album on this screen was opened from. Only set on a tap-through
	 * out of a collection; inside the collection itself {@link #id} and {@link #name} are
	 * the collection already. Either way it is what lets a play here empty 01A-05A.
	 */
	private String sourceCollectionId;
	private String sourceCollectionName;

	private View playlistActionBar;
	private TextView playlistRestedButton;
	private TextView playlistPlaysButton;
	private TextView playlistLengthButton;
	private TextView playlistGapButton;
	private TextView playlistShuffleButton;
	private TextView playlistFlowButton;
	private int mixCooldownDays;
	private int mixArtistGap;
	private int mixMinPlays;
	private int mixMaxPlays;
	private int mixMinSeconds;
	private int mixMaxSeconds;
	private List<Entry> unfilteredPlaylistEntries;

	public SelectDirectoryFragment() {
		super();
	}

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);
		if(bundle != null) {
			RetainedState state = RetainedState.of(this);
			entries = state.get(Constants.FRAGMENT_LIST);
			albums = state.get(Constants.FRAGMENT_LIST2);
			if(albums == null) {
				albums = new ArrayList<>();
			}
			artistInfo = state.get(Constants.FRAGMENT_EXTRA);
			restoredInstance = true;
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		// Too big for the Bundle: a directory's tracks break the Binder transaction the
		// state is handed over on. See RetainedState.
		RetainedState state = RetainedState.of(this);
		state.put(Constants.FRAGMENT_LIST, entries);
		state.put(Constants.FRAGMENT_LIST2, albums);
		state.put(Constants.FRAGMENT_EXTRA, artistInfo);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
		Bundle args = getArguments();
		if(args != null) {
			id = args.getString(Constants.INTENT_EXTRA_NAME_ID);
			name = args.getString(Constants.INTENT_EXTRA_NAME_NAME);
			directory = (Entry) args.getSerializable(Constants.INTENT_EXTRA_NAME_DIRECTORY);
			playlistId = args.getString(Constants.INTENT_EXTRA_NAME_PLAYLIST_ID);
			playlistName = args.getString(Constants.INTENT_EXTRA_NAME_PLAYLIST_NAME);
			playlistOwner = args.getBoolean(Constants.INTENT_EXTRA_NAME_PLAYLIST_OWNER, false);
			podcastId = args.getString(Constants.INTENT_EXTRA_NAME_PODCAST_ID);
			podcastName = args.getString(Constants.INTENT_EXTRA_NAME_PODCAST_NAME);
			podcastDescription = args.getString(Constants.INTENT_EXTRA_NAME_PODCAST_DESCRIPTION);
			Object shareObj = args.getSerializable(Constants.INTENT_EXTRA_NAME_SHARE);
			share = (shareObj != null) ? (Share) shareObj : null;
			sourceCollectionId = args.getString(Constants.INTENT_EXTRA_NAME_COLLECTION_ID);
			sourceCollectionName = args.getString(Constants.INTENT_EXTRA_NAME_COLLECTION_NAME);
			albumListType = args.getString(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_TYPE);
			albumListExtra = args.getString(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_EXTRA);
			albumListSize = args.getInt(Constants.INTENT_EXTRA_NAME_ALBUM_LIST_SIZE, 0);
			refreshListing = args.getBoolean(Constants.INTENT_EXTRA_REFRESH_LISTINGS);
			artist = args.getBoolean(Constants.INTENT_EXTRA_NAME_ARTIST, false);
			artistArt = args.getString(Constants.INTENT_EXTRA_NAME_ARTIST_ART);
			lookupEntry = args.getString(Constants.INTENT_EXTRA_SEARCH_SONG);
			topTracks = args.getBoolean(Constants.INTENT_EXTRA_TOP_TRACKS);
			showAll = args.getBoolean(Constants.INTENT_EXTRA_SHOW_ALL);

			String childId = args.getString(Constants.INTENT_EXTRA_NAME_CHILD_ID);
			if(childId != null) {
				id = childId;
				lookupParent = true;
			}
			if(entries == null) {
				entries = (List<Entry>) args.getSerializable(Constants.FRAGMENT_LIST);
				albums = (List<Entry>) args.getSerializable(Constants.FRAGMENT_LIST2);

				if(albums == null) {
					albums = new ArrayList<Entry>();
				}
			}
		}

		rootView = inflater.inflate(R.layout.abstract_recycler_fragment, container, false);

		refreshLayout = (SwipeRefreshLayout) rootView.findViewById(R.id.refresh_layout);
		refreshLayout.setOnRefreshListener(this);

		if(Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_LARGE_ALBUM_ART, true)) {
			largeAlbums = true;
		}

		// An artist's screen is their discography, and a discography is covers: a
		// grid regardless of the setting, which speaks for lists of albums in general
		// rather than for the one screen whose whole subject is the artwork.
		if(artist) {
			largeAlbums = true;
		}

		setupCollectionSearch();
		setupCollectionActionBar();
		setupFilterMenu();
		setupCollectionFilterBar();
		setupPlaylistActionBar();

		recyclerView = (RecyclerView) rootView.findViewById(R.id.fragment_recycler);
		recyclerView.setHasFixedSize(true);
		fastScroller = (FastScroller) rootView.findViewById(R.id.fragment_fast_scroller);
		setupScrollList(recyclerView);
		setupLayoutManager(recyclerView, largeAlbums);

		if(entries == null) {
			if(primaryFragment || secondaryFragment) {
				load(false);
			} else {
				invalidated = true;
			}
		} else {
            licenseValid = true;
            finishLoading();
		}

		if(name != null) {
			setTitle(name);
		}

		return rootView;
	}

	@Override
	public void onResume() {
		super.onResume();
		// Autoplay can be turned off elsewhere - by Now Playing, or by a restart - so the
		// pill is re-read every time this screen comes back rather than trusted.
		updateCollectionActionBar();
		updateCollectionFilterBar();
		refreshStaleCollection();
	}

	/**
	 * Fetches a collection listing again when the one on screen has had time to go stale.
	 *
	 * Collections are edited from outside this screen - the Dashboard empties them as albums
	 * are played, Plex refills a smart one on its own, and a play here curates through
	 * {@link CollectionCuration} - and none of that reaches a listing already loaded. Coming
	 * back to the screen is the moment worth checking on: a tap into an album and back is
	 * exactly when what the collection holds is most likely to have moved on.
	 *
	 * The age comes from the cached copy itself, so the load that just filled this screen
	 * counts as fresh and arriving here never costs a second fetch. Filters survive it -
	 * both are re-applied as the listing lands, the same as any other reload.
	 */
	private void refreshStaleCollection() {
		if(!primaryFragment || !(isCollectionView() || isCollectionListView())) {
			return;
		}

		// Nothing to fetch from, and no reason to make the user watch it fail.
		if(Util.isOffline(context) || !Util.isNetworkConnected(context)) {
			return;
		}

		// A load already on its way will land with current contents anyway.
		if(currentTask != null && currentTask.isRunning()) {
			return;
		}

		MusicService service = MusicServiceFactory.getMusicService(context);
		if(!(service instanceof CachedMusicService)) {
			return;
		}

		// Nothing cached means the ordinary load path is about to fetch this regardless.
		long age = ((CachedMusicService) service).getDirectoryCacheAge(context, id);
		if(age < 0 || age < COLLECTION_STALE_MILLIS) {
			return;
		}

		Log.i(TAG, "Collection listing is " + (age / 1000L) + "s old; fetching it again.");
		refresh(true);
	}

	private boolean isPlaylistView() {
		return playlistId != null;
	}

	@Override
	public void setIsOnlyVisible(boolean isOnlyVisible) {
		boolean update = this.isOnlyVisible != isOnlyVisible;
		super.setIsOnlyVisible(isOnlyVisible);
		if(update && entryGridAdapter != null) {
			RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
			if(layoutManager instanceof GridLayoutManager) {
				((GridLayoutManager) layoutManager).setSpanCount(getRecyclerColumnCount());
			}
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		if(licenseValid == null) {
			menuInflater.inflate(R.menu.empty, menu);
		} else if(albumListType != null && !"starred".equals(albumListType)) {
			menuInflater.inflate(R.menu.select_album_list, menu);
		} else if(artist && !showAll) {
			menuInflater.inflate(R.menu.select_album, menu);

			if(!ServerInfo.hasTopSongs(context)) {
				menu.removeItem(R.id.menu_top_tracks);
			}
			if(!ServerInfo.checkServerVersion(context, "1.11")) {
				menu.removeItem(R.id.menu_radio);
				menu.removeItem(R.id.menu_similar_artists);
			} else if(!ServerInfo.hasSimilarArtists(context)) {
				menu.removeItem(R.id.menu_similar_artists);
			}
		} else {
			if(podcastId == null) {
				if(Util.isOffline(context)) {
					menuInflater.inflate(R.menu.select_song_offline, menu);
				}
				else {
					menuInflater.inflate(R.menu.select_song, menu);

					if(playlistId == null || !playlistOwner) {
						menu.removeItem(R.id.menu_remove_playlist);
					}
				}

				SharedPreferences prefs = Util.getPreferences(context);
				if(!prefs.getBoolean(Constants.PREFERENCES_KEY_MENU_PLAY_NEXT, true)) {
					menu.setGroupVisible(R.id.hide_play_next, false);
				}
				if(!prefs.getBoolean(Constants.PREFERENCES_KEY_MENU_PLAY_LAST, true)) {
					menu.setGroupVisible(R.id.hide_play_last, false);
				}
			} else {
				if(Util.isOffline(context)) {
					menuInflater.inflate(R.menu.select_podcast_episode_offline, menu);
				}
				else {
					menuInflater.inflate(R.menu.select_podcast_episode, menu);

					if(!UserUtil.canPodcast()) {
						menu.removeItem(R.id.menu_download_all);
					}
				}
			}
		}

		if("starred".equals(albumListType)) {
			menuInflater.inflate(R.menu.unstar, menu);
		}

		addBrowseFilterMenuItem(menu);

		// The song menus carry a cast button, which is inert until it is pointed at a
		// route selector.
		setupMediaRouteButton(menu);
	}

	/** True when this screen is the list of collections rather than one collection. */
	private boolean isCollectionListView() {
		return PlexMusicService.COLLECTIONS_ID.equals(id);
	}

	/**
	 * A plain name filter over the collections already loaded. Nothing is re-fetched -
	 * the whole list is in hand, so this is only ever a matter of which rows to show.
	 */
	private void setupCollectionSearch() {
		collectionSearchView = (EditText) rootView.findViewById(R.id.list_filter);
		if(!isCollectionListView()) {
			return;
		}

		collectionSearchView.setHint(R.string.menu_collection_search);
		collectionSearchView.setVisibility(View.VISIBLE);
		collectionSearchView.setText(collectionSearchQuery);
		collectionSearchView.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence text, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable text) {
				collectionSearchQuery = text.toString();
				applyCollectionSearch();
			}
		});
	}

	private void applyCollectionSearch() {
		if(unfilteredEntries == null) {
			return;
		}

		entries = matchingCollections(unfilteredEntries, collectionSearchQuery);
		albums = new ArrayList<Entry>(entries);

		// Swap the rows rather than rebuild the screen. finishLoading() recreates the
		// header, whose title asks for focus so it can marquee, and that pulls the
		// keyboard out of this field after a single letter.
		if(entryGridAdapter != null) {
			entryGridAdapter.replaceExistingData(entries);
		} else {
			finishLoading();
		}
	}

	/** Case-insensitive substring match, so "essential" finds "Essential Jangle Pop". */
	private static List<Entry> matchingCollections(List<Entry> source, String query) {
		if(query == null || query.trim().isEmpty()) {
			return new ArrayList<Entry>(source);
		}

		String needle = query.trim().toLowerCase(Locale.getDefault());
		List<Entry> result = new ArrayList<Entry>();
		for(Entry entry: source) {
			String title = entry.getTitle();
			if(title != null && title.toLowerCase(Locale.getDefault()).contains(needle)) {
				result.add(entry);
			}
		}
		return result;
	}

	/** True when this screen is showing the albums inside a Plex collection. */
	private boolean isCollectionView() {
		return id != null && id.startsWith(PlexMusicService.COLLECTION_PREFIX);
	}

	/**
	 * Menu actions that put something in the play queue, whether they start it or append
	 * to it. The Dashboard empties a self-emptying collection on a queue as well as on a
	 * play, so the two are treated alike here.
	 */
	private static boolean isPlayOrQueueAction(int itemId) {
		return itemId == R.id.menu_play_now || itemId == R.id.menu_play_last
				|| itemId == R.id.menu_play_next || itemId == R.id.menu_shuffle
				|| itemId == R.id.album_menu_play_now || itemId == R.id.album_menu_play_shuffled
				|| itemId == R.id.album_menu_play_next || itemId == R.id.album_menu_play_last
				|| itemId == R.id.song_menu_play_now || itemId == R.id.song_menu_play_next
				|| itemId == R.id.song_menu_play_last;
	}

	/**
	 * Takes whatever was just played out of its self-emptying collection.
	 *
	 * Inside a collection the rows are albums, so it is the rows that were acted on. On an
	 * album opened from a collection the rows are tracks, and playing any of them counts
	 * as playing the album - that is the whole point of carrying the collection through
	 * the tap-through.
	 */
	private void curateOnPlay(List<Entry> played) {
		if(isCollectionView()) {
			CollectionCuration.removeAlbums(context, id, name, CollectionCuration.albumIdsOf(played));
		} else if(sourceCollectionId != null && id != null) {
			CollectionCuration.removeAlbum(context, sourceCollectionId, sourceCollectionName, id);
		}
	}

	private void addBrowseFilterMenuItem(Menu menu) {
		if(!isCollectionView() || menu.findItem(MENU_ITEM_BROWSE_FILTER) != null) {
			return;
		}

		MenuItem item = menu.add(Menu.NONE, MENU_ITEM_BROWSE_FILTER, Menu.NONE,
				browseFilter.isActive() ? R.string.menu_filter_active : R.string.menu_filter);
		item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
	}

	/** Wires the two pills above the list. They only ever show inside a collection. */
	private void setupCollectionActionBar() {
		collectionActionBar = rootView.findViewById(R.id.collection_action_bar);
		collectionRandomButton = (TextView) rootView.findViewById(R.id.collection_random_button);
		collectionAutoplayButton = (TextView) rootView.findViewById(R.id.collection_autoplay_button);

		collectionRandomButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				playRandomAlbum();
			}
		});
		collectionAutoplayButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if(isAutoplayingThisCollection()) {
					stopCollectionAutoplay();
				} else {
					startCollectionAutoplay();
				}
			}
		});

		updateCollectionActionBar();
	}

	/**
	 * Keeps the autoplay pill honest about what the download service is actually doing.
	 * Autoplay outlives this screen, so the button state is read back rather than
	 * remembered - coming back to the collection has to show "Stop autoplay".
	 */
	private void updateCollectionActionBar() {
		if(collectionActionBar == null) {
			return;
		}

		if(!isCollectionView()) {
			collectionActionBar.setVisibility(View.GONE);
			return;
		}
		collectionActionBar.setVisibility(View.VISIBLE);

		boolean running = isAutoplayingThisCollection();
		int foreground = running
				? ContextCompat.getColor(context, R.color.modernAccent) : Color.WHITE;

		collectionAutoplayButton.setText(running
				? R.string.menu_collection_autoplay_stop : R.string.menu_collection_autoplay);
		collectionAutoplayButton.setBackgroundResource(running
				? R.drawable.collection_action_pill_active : R.drawable.collection_action_pill);
		collectionAutoplayButton.setTextColor(foreground);

		// Loaded through AppCompat so the vector still inflates below API 24, and mutated
		// before tinting so the shared constant state is not recoloured everywhere.
		Drawable icon = AppCompatResources.getDrawable(context, running
				? R.drawable.ic_collection_stop : R.drawable.ic_collection_autoplay);
		if(icon != null) {
			icon = DrawableCompat.wrap(icon.mutate());
			DrawableCompat.setTint(icon, foreground);
		}
		TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
				collectionAutoplayButton, icon, null, null, null);
	}

	private boolean isAutoplayingThisCollection() {
		DownloadService downloadService = getDownloadService();
		return downloadService != null && id != null
				&& id.equals(downloadService.getCollectionAutoplayId());
	}

	/** Plays one album picked at random from what the filter currently leaves visible. */
	private void playRandomAlbum() {
		// The filtered list, the same one autoplay draws from: a filter is a statement
		// about what to play now, and picking from behind it would hand back an album the
		// user has just said they do not want.
		final List<Entry> albums = getVisibleAlbums();
		if(albums.isEmpty()) {
			Util.toast(context, R.string.menu_filter_no_match);
			return;
		}

		final Entry album = albums.get(new Random().nextInt(albums.size()));
		new LoadingTask<MusicDirectory>(context) {
			@Override
			protected MusicDirectory doInBackground() throws Throwable {
				MusicService service = MusicServiceFactory.getMusicService(context);
				return service.getAlbum(album.getId(), album.getTitle(), false, context, this);
			}

			@Override
			protected void done(MusicDirectory result) {
				List<Entry> songs = result.getChildren(false, true);
				if(songs.isEmpty()) {
					Util.toast(context, R.string.menu_filter_no_match);
					return;
				}

				DownloadService downloadService = getDownloadService();
				downloadService.clear();
				downloadService.download(songs, false, true, false, false);
				curateOnPlay(Arrays.asList(album));
				context.openNowPlaying();
			}
		}.execute();
	}

	/** Hands the visible album list to the autoplay buffer and starts it. */
	private void startCollectionAutoplay() {
		final List<Entry> albums = getVisibleAlbums();
		if(albums.isEmpty()) {
			Util.toast(context, R.string.menu_filter_no_match);
			return;
		}

		new LoadingTask<Void>(context) {
			@Override
			protected Void doInBackground() throws Throwable {
				DownloadService downloadService = getDownloadService();
				downloadService.clear();
				downloadService.setCollectionAutoplay(id, name, albums);
				return null;
			}

			@Override
			protected void done(Void result) {
				updateCollectionActionBar();
				context.openNowPlaying();
			}
		}.execute();
	}

	/**
	 * Turns the feed off without touching what is already queued. Stopping the music too
	 * would throw away an album the user is halfway through; letting the queue play out
	 * ends in a full stop anyway, since running off the end now stops playback.
	 */
	private void stopCollectionAutoplay() {
		DownloadService downloadService = getDownloadService();
		if(downloadService != null) {
			downloadService.setCollectionAutoplay(null, null, null);
		}
		updateCollectionActionBar();
		Util.toast(context, R.string.menu_collection_autoplay_stopped);
	}

	/**
	 * Wires the two filter pills above a collection's albums: rest time and length.
	 * They drive the same browse filter as the overflow menu, so a value set on either
	 * shows on both. Rest reads straight off the album entries and applies at once;
	 * length needs the album details fetched first, like the menu's length filter.
	 */
	private void setupCollectionFilterBar() {
		collectionFilterBar = rootView.findViewById(R.id.collection_filter_bar);
		collectionRestedButton = (TextView) rootView.findViewById(R.id.collection_rested_button);
		collectionDurationButton = (TextView) rootView.findViewById(R.id.collection_duration_button);

		collectionRestedButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				filterMenu.showRestedDialog();
			}
		});
		collectionDurationButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				filterMenu.showLengthDialog();
			}
		});

		updateCollectionFilterBar();
	}

	private void updateCollectionFilterBar() {
		if(collectionFilterBar == null) {
			return;
		}
		if(!isCollectionView()) {
			collectionFilterBar.setVisibility(View.GONE);
			return;
		}
		collectionFilterBar.setVisibility(View.VISIBLE);

		int restedDays = browseFilter.getMinRestedDays();
		styleFilterPill(collectionRestedButton, R.drawable.ic_mix_rested, restedDays > 0,
				restedDays > 0 ? restedPillLabel(restedDays) : getString(R.string.menu_filter_rested_off));

		int minMinutes = browseFilter.getMinDurationMinutes();
		int maxMinutes = browseFilter.getMaxDurationMinutes();
		styleFilterPill(collectionDurationButton, R.drawable.ic_collection_length,
				minMinutes > 0 || maxMinutes > 0, durationPillLabel(minMinutes, maxMinutes));
	}

	/** The compact form of whichever ends of the length filter are set. */
	private String durationPillLabel(int minMinutes, int maxMinutes) {
		if(minMinutes > 0 && maxMinutes > 0) {
			return getString(R.string.menu_filter_duration_range_pill, minMinutes, maxMinutes);
		}
		if(maxMinutes > 0) {
			return getString(R.string.menu_filter_duration_pill, maxMinutes);
		}
		if(minMinutes > 0) {
			return getString(R.string.menu_filter_duration_over_pill, minMinutes);
		}
		return getString(R.string.menu_filter_duration_off);
	}

	/**
	 * A filter fills with accent red once it holds a value and is a quiet grey outline
	 * until then.
	 *
	 * The two bars carry actions and filters side by side, and filling both made a bar of
	 * four identical red blocks in which the loudest thing on screen was a pair of filters
	 * announcing that nothing was set. Filled now means one thing on this bar: an action,
	 * or a filter actually narrowing the list.
	 */
	private void styleFilterPill(TextView pill, int iconRes, boolean active, String label) {
		int foreground = active
				? Color.WHITE : ContextCompat.getColor(context, R.color.filterChipIdle);
		pill.setText(label);
		pill.setBackgroundResource(active
				? R.drawable.collection_action_pill : R.drawable.collection_filter_pill);
		pill.setTextColor(foreground);

		Drawable icon = AppCompatResources.getDrawable(context, iconRes);
		if(icon != null) {
			icon = DrawableCompat.wrap(icon.mutate());
			DrawableCompat.setTint(icon, foreground);
		}
		TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(pill, icon, null, null, null);
	}

	/**
	 * A span written out for a sentence: "90 days", "2 years". Anything from a year up
	 * reads in years, since "over 1825 days ago" is a number to do arithmetic on rather
	 * than one to recognise.
	 */
	private String restedSpan(int days) {
		if(days >= 365) {
			return context.getResources().getQuantityString(R.plurals.filter_rested_years,
					days / 365, days / 365);
		}
		return context.getResources().getQuantityString(R.plurals.filter_rested_days, days, days);
	}

	/** The compact pill form: "90+ days", "2+ yr". The dialog spells the options out in full. */
	private String restedPillLabel(int days) {
		if(days >= 365) {
			return getString(R.string.menu_filter_rested_years_pill, days / 365);
		}
		return getString(R.string.menu_filter_rested_days_pill, days);
	}

	/** Wires the four mix pills above the list. They only ever show on a playlist. */
	private void setupPlaylistActionBar() {
		playlistActionBar = rootView.findViewById(R.id.playlist_action_bar);
		playlistRestedButton = (TextView) rootView.findViewById(R.id.playlist_rested_button);
		playlistPlaysButton = (TextView) rootView.findViewById(R.id.playlist_plays_button);
		playlistLengthButton = (TextView) rootView.findViewById(R.id.playlist_length_button);
		playlistGapButton = (TextView) rootView.findViewById(R.id.playlist_gap_button);
		playlistShuffleButton = (TextView) rootView.findViewById(R.id.playlist_shuffle_button);
		playlistFlowButton = (TextView) rootView.findViewById(R.id.playlist_flow_button);

		SharedPreferences prefs = Util.getPreferences(context);
		mixCooldownDays = prefs.getInt(Constants.PREFERENCES_KEY_MIX_COOLDOWN, 7);
		mixArtistGap = prefs.getInt(Constants.PREFERENCES_KEY_MIX_GAP, 5);
		mixMinPlays = prefs.getInt(Constants.PREFERENCES_KEY_MIX_MIN_PLAYS, 0);
		mixMaxPlays = prefs.getInt(Constants.PREFERENCES_KEY_MIX_MAX_PLAYS, 0);
		mixMinSeconds = prefs.getInt(Constants.PREFERENCES_KEY_MIX_MIN_SECONDS, 0);
		mixMaxSeconds = prefs.getInt(Constants.PREFERENCES_KEY_MIX_MAX_SECONDS, 0);

		playlistRestedButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				showRestedDialog();
			}
		});
		playlistPlaysButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				showPlaysDialog();
			}
		});
		playlistLengthButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				showLengthDialog();
			}
		});
		playlistGapButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				showGapDialog();
			}
		});
		playlistShuffleButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				playMixShuffle();
			}
		});
		playlistFlowButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				playMixFlow();
			}
		});

		updatePlaylistActionBar();
	}

	private void updatePlaylistActionBar() {
		if(playlistActionBar == null) {
			return;
		}
		if(!isPlaylistView()) {
			playlistActionBar.setVisibility(View.GONE);
			return;
		}
		playlistActionBar.setVisibility(View.VISIBLE);

		applyMixFilter();

		// Three filters and one action. The filters take the same outline-until-set
		// treatment as the collection bar's, so the only filled pill on a mix that
		// narrows nothing is the one that starts it.
		// The same compact form the collection bar uses, which writes the longer spans as
		// years: the options now reach ten of them, and "3650+ days" is a number to work
		// out rather than one to read.
		styleFilterPill(playlistRestedButton, R.drawable.ic_mix_rested, mixCooldownDays > 0,
				mixCooldownDays > 0
						? restedPillLabel(mixCooldownDays)
						: getString(R.string.menu_playlist_mix_rested_off));
		styleFilterPill(playlistPlaysButton, R.drawable.ic_mix_plays,
				mixMinPlays > 0 || mixMaxPlays > 0, mixPlaysPillLabel());
		styleFilterPill(playlistLengthButton, R.drawable.ic_collection_length,
				mixMinSeconds > 0 || mixMaxSeconds > 0, mixLengthPillLabel());
		styleFilterPill(playlistGapButton, R.drawable.ic_mix_gap, mixArtistGap > 0,
				mixArtistGap > 0
						? getString(R.string.menu_playlist_mix_gap, mixArtistGap)
						: getString(R.string.menu_playlist_mix_gap_off));
		playlistShuffleButton.setText(mixShufflePillLabel());
		playlistFlowButton.setText(mixFlowPillLabel());
	}

	private String mixPlaysPillLabel() {
		if(mixMinPlays > 0 && mixMaxPlays > 0) {
			return getString(R.string.menu_playlist_mix_plays_range, mixMinPlays, mixMaxPlays);
		}
		if(mixMinPlays > 0) {
			return getString(R.string.menu_playlist_mix_plays, mixMinPlays);
		}
		if(mixMaxPlays > 0) {
			return getString(R.string.menu_playlist_mix_plays_under, mixMaxPlays);
		}
		return getString(R.string.menu_playlist_mix_plays_off);
	}

	/** "Over 2.5 min", "Under 5 min", or "2.5-5 min" when both ends are set. */
	private String mixLengthPillLabel() {
		if(mixMinSeconds > 0 && mixMaxSeconds > 0) {
			return getString(R.string.menu_playlist_mix_length_range,
					formatMinutes(mixMinSeconds), formatMinutes(mixMaxSeconds));
		}
		if(mixMinSeconds > 0) {
			return getString(R.string.menu_playlist_mix_length_over, formatMinutes(mixMinSeconds));
		}
		if(mixMaxSeconds > 0) {
			return getString(R.string.menu_playlist_mix_length_under, formatMinutes(mixMaxSeconds));
		}
		return getString(R.string.menu_playlist_mix_length_off);
	}

	/**
	 * Seconds back to the minutes they were typed as: "150" reads "2.5", "180" reads "3".
	 * A whole number keeps no decimal point, so the common case does not carry a ".0"
	 * around on a pill with little room for it.
	 */
	private String formatMinutes(int seconds) {
		double minutes = seconds / 60.0;
		if(Math.abs(minutes - Math.rint(minutes)) < 0.001) {
			return String.format(Locale.getDefault(), "%d", Math.round(minutes));
		}
		return String.format(Locale.getDefault(), "%.1f", minutes);
	}

	/**
	 * How many tracks the mix has to draw on, shown on the button that consumes them so
	 * it is clear before pressing whether that is most of the playlist or a handful.
	 *
	 * It belongs here rather than on either filter pill because both filters shape it -
	 * a number on one of them would be read as that filter's own doing. Left off until
	 * the tracks are in hand, and while no filter is set, when it would only repeat the
	 * count in the header.
	 */
	private String mixShufflePillLabel() {
		if(entries == null || entries.isEmpty() || !mixNarrowsAnything()) {
			return getString(R.string.menu_playlist_mix_shuffle);
		}

		return getString(R.string.menu_playlist_mix_shuffle_count,
				PlaylistMix.countEligible(entries, mixCooldownDays, mixMinPlays, mixMaxPlays,
						mixMinSeconds, mixMaxSeconds));
	}

	/**
	 * Re-derives the visible playlist list from the untouched copy so the songs on screen
	 * match what Shuffle would actually draw from, rather than just showing a count on the pill.
	 */
	private void applyMixFilter() {
		if(unfilteredPlaylistEntries == null) {
			return;
		}

		entries = mixNarrowsAnything()
				? PlaylistMix.eligibleTracks(unfilteredPlaylistEntries, mixCooldownDays, mixMinPlays,
						mixMaxPlays, mixMinSeconds, mixMaxSeconds)
				: new ArrayList<Entry>(unfilteredPlaylistEntries);

		if(entries.isEmpty()) {
			Util.toast(context, R.string.menu_playlist_mix_empty);
		}

		if(entryGridAdapter != null) {
			entryGridAdapter.replaceExistingData(entries);
		}
	}

	/**
	 * Reaches out to ten years because that is the span a long-lived library actually
	 * asks about: on a playlist kept for years, "over 90 days ago" is nearly every track
	 * on it, and the filter only starts telling them apart further out.
	 */
	private void showRestedDialog() {
		final int[] days = new int[] {0, 3, 7, 14, 30, 60, 90, 180, 365, 730, 1825, 3650};
		String[] labels = new String[days.length];
		labels[0] = getString(R.string.menu_playlist_mix_rested_off);
		for(int i = 1; i < days.length; i++) {
			labels[i] = getString(R.string.menu_playlist_mix_rested_ago, restedSpan(days[i]));
		}

		int selected = 0;
		for(int i = 0; i < days.length; i++) {
			if(days[i] == mixCooldownDays) {
				selected = i;
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_playlist_mix_rested_title)
				.setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						mixCooldownDays = days[which];
						Util.getPreferences(context).edit()
								.putInt(Constants.PREFERENCES_KEY_MIX_COOLDOWN, mixCooldownDays).apply();
						dialog.dismiss();
						updatePlaylistActionBar();
					}
				})
				.show();
	}

	/**
	 * Both ends typed in, the same way lengths are: a floor finds the old favourites, a
	 * ceiling finds what has been neglected, and setting both asks for the middle ground
	 * -- which the preset list this replaces could not express, since picking one of its
	 * entries cleared the other.
	 */
	private void showPlaysDialog() {
		showRangeDialog(R.string.menu_playlist_mix_plays_title,
				R.string.menu_playlist_mix_plays_at_least, R.string.menu_playlist_mix_plays_fewer_than,
				R.string.menu_playlist_mix_plays_unit, false,
				mixMinPlays > 0 ? String.valueOf(mixMinPlays) : "",
				mixMaxPlays > 0 ? String.valueOf(mixMaxPlays) : "",
				new RangeListener() {
					@Override
					public boolean onAccept(String minText, String maxText) {
						int min = parseCount(minText);
						int max = parseCount(maxText);

						// The floor is inclusive and the ceiling is not, so equal ends ask
						// for tracks played at least n times and fewer than n times, which
						// is nothing at all rather than "exactly n".
						if(min > 0 && max > 0 && min >= max) {
							Util.toast(context, R.string.menu_playlist_mix_plays_invalid);
							return false;
						}

						mixMinPlays = min;
						mixMaxPlays = max;
						Util.getPreferences(context).edit()
								.putInt(Constants.PREFERENCES_KEY_MIX_MIN_PLAYS, mixMinPlays)
								.putInt(Constants.PREFERENCES_KEY_MIX_MAX_PLAYS, mixMaxPlays).apply();
						return true;
					}
				});
	}

	/** A whole count as typed. Blank, nonsense and anything at or below zero mean "off". */
	private int parseCount(String text) {
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

	/** Whether any threshold is set, and so whether filtering would change the list at all. */
	private boolean mixNarrowsAnything() {
		return mixCooldownDays > 0 || mixMinPlays > 0 || mixMaxPlays > 0
				|| mixMinSeconds > 0 || mixMaxSeconds > 0;
	}

	/** Takes what was typed; returns false to refuse it and leave the dialog up. */
	private interface RangeListener {
		boolean onAccept(String minText, String maxText);
	}

	/**
	 * A two-bound filter typed in rather than picked from a list.
	 *
	 * The length and play-count filters both want a threshold whose useful values are a
	 * matter of taste, where a preset list is either long or wrong, so they share this and
	 * differ only in their labels, units and whether fractions mean anything.
	 */
	private void showRangeDialog(int titleRes, int minLabelRes, int maxLabelRes, int unitRes,
			boolean fractions, String minInitial, String maxInitial, final RangeListener listener) {
		View view = LayoutInflater.from(context).inflate(R.layout.filter_range, null);
		final EditText minView = (EditText) view.findViewById(R.id.filter_range_min);
		final EditText maxView = (EditText) view.findViewById(R.id.filter_range_max);

		((TextView) view.findViewById(R.id.filter_range_min_label)).setText(minLabelRes);
		((TextView) view.findViewById(R.id.filter_range_max_label)).setText(maxLabelRes);
		((TextView) view.findViewById(R.id.filter_range_min_suffix)).setText(unitRes);
		((TextView) view.findViewById(R.id.filter_range_max_suffix)).setText(unitRes);

		// A play count has no fractions to offer, and a keyboard without a decimal point
		// is one fewer way to type something that has to be rejected.
		int inputType = fractions
				? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
				: InputType.TYPE_CLASS_NUMBER;
		minView.setInputType(inputType);
		maxView.setInputType(inputType);

		minView.setText(minInitial);
		maxView.setText(maxInitial);

		final AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(titleRes)
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
				if(listener.onAccept(minView.getText().toString(), maxView.getText().toString())) {
					dialog.dismiss();
					updatePlaylistActionBar();
				}
			}
		});
	}

	private void showLengthDialog() {
		showRangeDialog(R.string.menu_playlist_mix_length_title,
				R.string.menu_playlist_mix_length_longer, R.string.menu_playlist_mix_length_shorter,
				R.string.menu_playlist_mix_length_minutes, true,
				mixMinSeconds > 0 ? formatMinutes(mixMinSeconds) : "",
				mixMaxSeconds > 0 ? formatMinutes(mixMaxSeconds) : "",
				new RangeListener() {
					@Override
					public boolean onAccept(String minText, String maxText) {
						int min = parseMinutesToSeconds(minText);
						int max = parseMinutesToSeconds(maxText);

						// A window with its ends the wrong way round matches nothing, and
						// silently showing an empty playlist would read as a bug in the
						// filter rather than a typo in the numbers.
						if(min > 0 && max > 0 && min >= max) {
							Util.toast(context, R.string.menu_playlist_mix_length_invalid);
							return false;
						}

						mixMinSeconds = min;
						mixMaxSeconds = max;
						Util.getPreferences(context).edit()
								.putInt(Constants.PREFERENCES_KEY_MIX_MIN_SECONDS, mixMinSeconds)
								.putInt(Constants.PREFERENCES_KEY_MIX_MAX_SECONDS, mixMaxSeconds).apply();
						return true;
					}
				});
	}

	/**
	 * Minutes as typed into whole seconds: "2.5" is 150, "3" is 180. Blank, nonsense and
	 * anything at or below zero all come back 0, which is how this filter spells "off".
	 */
	private int parseMinutesToSeconds(String text) {
		if(text == null) {
			return 0;
		}

		String trimmed = text.trim();
		if(trimmed.isEmpty()) {
			return 0;
		}

		try {
			// The field is numberDecimal, whose separator follows the keyboard's locale,
			// so a comma has to be read as the decimal point a German or French user meant.
			double minutes = Double.parseDouble(trimmed.replace(',', '.'));
			return minutes > 0 ? (int) Math.round(minutes * 60.0) : 0;
		} catch(NumberFormatException e) {
			return 0;
		}
	}

	private void showGapDialog() {
		final int[] gaps = new int[] {0, 3, 5, 8, 10};
		String[] labels = new String[gaps.length];
		labels[0] = getString(R.string.menu_playlist_mix_gap_off);
		for(int i = 1; i < gaps.length; i++) {
			labels[i] = getString(R.string.menu_playlist_mix_gap_value, gaps[i]);
		}

		int selected = 0;
		for(int i = 0; i < gaps.length; i++) {
			if(gaps[i] == mixArtistGap) {
				selected = i;
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.menu_playlist_mix_gap_title)
				.setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						mixArtistGap = gaps[which];
						Util.getPreferences(context).edit()
								.putInt(Constants.PREFERENCES_KEY_MIX_GAP, mixArtistGap).apply();
						dialog.dismiss();
						updatePlaylistActionBar();
					}
				})
				.show();
	}

	/**
	 * Drops tracks the rest, play-count and length thresholds exclude, shuffles what is
	 * left keeping artists apart, and plays. The filter and shuffle run off the UI thread
	 * since a long playlist makes the artist-spacing pass do real work.
	 */
	private String mixFlowPillLabel() {
		if(entries == null || entries.isEmpty()) {
			return getString(R.string.menu_playlist_mix_flow);
		}

		return getString(R.string.menu_playlist_mix_flow_count,
				PlaylistMix.countEligible(entries, mixCooldownDays, mixMinPlays, mixMaxPlays,
						mixMinSeconds, mixMaxSeconds));
	}

	/**
	 * Plays what the filters leave, ordered so each track sits beside one it suits rather
	 * than wherever the shuffle dropped it.
	 *
	 * The ordering runs on what the server measured about each recording, and a listing
	 * carries none of it - so the details are fetched first, for the filtered set only. That
	 * is the slow part and the reason this sits behind a progress dialog where Shuffle does
	 * not: a few hundred tracks is a few requests, not one.
	 */
	private void playMixFlow() {
		if(entries == null || entries.isEmpty()) {
			Util.toast(context, R.string.menu_playlist_mix_empty);
			return;
		}

		final List<Entry> source = new ArrayList<Entry>(entries);
		new LoadingTask<List<Entry>>(context) {
			private boolean shuffledInstead = false;

			@Override
			protected List<Entry> doInBackground() throws Throwable {
				List<Entry> eligible = PlaylistMix.eligibleTracks(source, mixCooldownDays, mixMinPlays,
						mixMaxPlays, mixMinSeconds, mixMaxSeconds);
				if(eligible.isEmpty()) {
					return eligible;
				}

				updateProgress(R.string.menu_playlist_mix_flow_preparing);
				try {
					MusicServiceFactory.getMusicService(context).fillSongDetails(eligible, context);
				} catch(Exception x) {
					// Ordering on nothing is a shuffle, which is what flowOrder falls back
					// on by itself - so a failed fetch is not worth refusing to play over.
					Log.w(TAG, "Could not fetch what the tracks sound like", x);
				}

				List<Entry> ordered = PlaylistMix.flowOrder(eligible, mixArtistGap);
				shuffledInstead = !PlaylistMix.canOrderByFlow(eligible);
				return ordered;
			}

			@Override
			protected void done(List<Entry> ordered) {
				if(ordered.isEmpty()) {
					Util.toast(context, R.string.menu_playlist_mix_empty);
					return;
				}

				DownloadService downloadService = getDownloadService();
				if(downloadService == null) {
					return;
				}
				if(shuffledInstead) {
					Util.toast(context, R.string.menu_playlist_mix_flow_unmeasured);
				}

				downloadService.clear();
				downloadService.download(ordered, false, true, false, false);
				downloadService.setSuggestedPlaylistName(playlistName, playlistId);
				context.openNowPlaying();
			}
		}.execute();
	}

	private void playMixShuffle() {
		if(entries == null || entries.isEmpty()) {
			Util.toast(context, R.string.menu_playlist_mix_empty);
			return;
		}

		final List<Entry> source = new ArrayList<Entry>(entries);
		new LoadingTask<List<Entry>>(context) {
			@Override
			protected List<Entry> doInBackground() throws Throwable {
				List<Entry> eligible = PlaylistMix.eligibleTracks(source, mixCooldownDays, mixMinPlays,
						mixMaxPlays, mixMinSeconds, mixMaxSeconds);
				if(eligible.isEmpty()) {
					return eligible;
				}
				return PlaylistMix.artistGapShuffle(eligible, mixArtistGap);
			}

			@Override
			protected void done(List<Entry> mixed) {
				if(mixed.isEmpty()) {
					Util.toast(context, R.string.menu_playlist_mix_empty);
					return;
				}

				DownloadService downloadService = getDownloadService();
				if(downloadService == null) {
					return;
				}
				downloadService.clear();
				downloadService.download(mixed, false, true, false, false);
				// A mix is still the playlist playing, so it gets the playlist preload count
				downloadService.setSuggestedPlaylistName(playlistName, playlistId);
				context.openNowPlaying();
			}
		}.execute();
	}

	/**
	 * The albums on screen. Reads the filtered list so both actions obey an active
	 * filter, and skips anything that is not a directory in case tracks ever appear here.
	 */
	private List<Entry> getVisibleAlbums() {
		return albumsIn((entries != null) ? entries : unfilteredEntries);
	}

	/** Every album in the collection, whatever the filter currently hides. */
	private List<Entry> getAllAlbums() {
		return albumsIn((unfilteredEntries != null) ? unfilteredEntries : entries);
	}

	private List<Entry> albumsIn(List<Entry> source) {
		List<Entry> albums = new ArrayList<Entry>();
		if(source != null) {
			for(Entry entry: source) {
				if(entry != null && entry.isDirectory() && entry.getId() != null) {
					albums.add(entry);
				}
			}
		}
		return albums;
	}

	/**
	 * The filter dialogs, over this screen's albums. The Home screen builds the same menu
	 * over its focused collection, which is what keeps the two sets of filters identical.
	 */
	private void setupFilterMenu() {
		filterMenu = new AlbumFilterMenu(context, browseFilter, new AlbumFilterMenu.Host() {
			@Override
			public List<Entry> getFilterAlbums() {
				return getAllAlbums();
			}

			@Override
			public List<Entry> getVisibleFilterAlbums() {
				return getVisibleAlbums();
			}

			@Override
			public void onFilterChanged() {
				applyBrowseFilter();
			}
		});
	}

	/** Re-derives the visible list from the untouched copy and redraws. */
	private void applyBrowseFilter() {
		if(unfilteredEntries == null) {
			return;
		}

		entries = browseFilter.apply(unfilteredEntries, System.currentTimeMillis() / 1000L,
				filterMenu.getAlbumDetails());
		albums = new ArrayList<Entry>(entries);

		if(entries.isEmpty()) {
			Util.toast(context, R.string.menu_filter_no_match);
		}

		// Keep the pills honest whether the change came from a pill or the overflow menu.
		updateCollectionFilterBar();
		finishLoading();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(item.getItemId() == MENU_ITEM_BROWSE_FILTER) {
			filterMenu.show();
			return true;
		}

		// Read before super runs: these actions act on the selection and then clear it.
		if(isPlayOrQueueAction(item.getItemId())) {
			List<Entry> selected = getSelectedEntries();
			if(!selected.isEmpty()) {
				curateOnPlay(selected);
			}
		}

		switch (item.getItemId()) {
			case R.id.menu_remove_playlist:
				removeFromPlaylist(playlistId, playlistName, getSelectedEntries());
				return true;
			case R.id.menu_download_all:
				downloadAllPodcastEpisodes();
				return true;
			case R.id.menu_show_all:
				setShowAll();
				return true;
			case R.id.menu_top_tracks:
				showTopTracks();
				return true;
			case R.id.menu_similar_artists:
				showSimilarArtists(id);
				return true;
			case R.id.menu_radio:
				startArtistRadio(id);
				return true;
		}

		return super.onOptionsItemSelected(item);

	}

	@Override
	public void onCreateContextMenu(Menu menu, MenuInflater menuInflater, UpdateView updateView, Entry entry) {
		onCreateContextMenuSupport(menu, menuInflater, updateView, entry);
		if(!entry.isVideo() && !Util.isOffline(context) && (playlistId == null || !playlistOwner) && (podcastId == null  || Util.isOffline(context) && podcastId != null)) {
			menu.removeItem(R.id.song_menu_remove_playlist);
		}

		recreateContextMenu(menu);
	}
	@Override
	public boolean onContextItemSelected(MenuItem menuItem, UpdateView<Entry> updateView, Entry entry) {
		if(isPlayOrQueueAction(menuItem.getItemId())) {
			curateOnPlay(Arrays.asList(entry));
		}

		if(onContextItemSelected(menuItem, entry)) {
			return true;
		}

		switch (menuItem.getItemId()) {
			case R.id.song_menu_remove_playlist:
				removeFromPlaylist(playlistId, playlistName, Arrays.asList(entry));
				break;
		}

		return true;
	}

	@Override
	public void onItemClicked(UpdateView<Entry> updateView, Entry entry) {
		if (entry.isDirectory()) {
			SubsonicFragment fragment = new SelectDirectoryFragment();
			Bundle args = new Bundle();
			args.putString(Constants.INTENT_EXTRA_NAME_ID, entry.getId());
			args.putString(Constants.INTENT_EXTRA_NAME_NAME, entry.getTitle());
			args.putSerializable(Constants.INTENT_EXTRA_NAME_DIRECTORY, entry);
			if ("newest".equals(albumListType)) {
				args.putBoolean(Constants.INTENT_EXTRA_REFRESH_LISTINGS, true);
			}
			if(!entry.isAlbum()) {
				args.putBoolean(Constants.INTENT_EXTRA_NAME_ARTIST, true);
			}
			// Carry the collection into the album so a play started there still counts as
			// a play out of the collection. Passed whatever the collection is; whether it
			// self-empties is CollectionCuration's call, in one place.
			if(isCollectionView()) {
				args.putString(Constants.INTENT_EXTRA_NAME_COLLECTION_ID, id);
				args.putString(Constants.INTENT_EXTRA_NAME_COLLECTION_NAME, name);
			}
			fragment.setArguments(args);

			replaceFragment(fragment, true);
		} else if (entry.isVideo()) {
			playVideo(entry);
		} else if(entry instanceof PodcastEpisode) {
			String status = ((PodcastEpisode)entry).getStatus();
			if("error".equals(status)) {
				Util.toast(context, R.string.select_podcasts_error);
				return;
			} else if(!"completed".equals(status)) {
				Util.toast(context, R.string.select_podcasts_skipped);
				return;
			}

			onSongPress(Arrays.asList(entry), entry, false);
		} else {
			curateOnPlay(Arrays.asList(entry));
			onSongPress(entries, entry, albumListType == null || "starred".equals(albumListType));
		}
	}

	@Override
	protected void refresh(boolean refresh) {
		load(refresh);
	}

	@Override
	protected boolean isShowArtistEnabled() {
		return albumListType != null;
	}

	@Override
	protected String getCurrentDirectoryId() {
		return id;
	}

	private void load(boolean refresh) {
		if(refreshListing) {
			refresh = true;
		}

		if(currentTask != null) {
			currentTask.cancel();
		}

		recyclerView.setVisibility(View.INVISIBLE);
		if (playlistId != null) {
			getPlaylist(playlistId, playlistName, refresh);
		} else if(podcastId != null) {
			getPodcast(podcastId, podcastName, refresh);
		} else if (share != null) {
			if(showAll) {
				getRecursiveMusicDirectory(share.getId(), share.getName(), refresh);
			} else {
				getShare(share, refresh);
			}
		} else if (albumListType != null) {
			getAlbumList(albumListType, albumListSize, refresh);
		} else {
			if(showAll) {
				getRecursiveMusicDirectory(id, name, refresh);
			} else if(topTracks) {
				getTopTracks(id, name, refresh);
			} else {
				getMusicDirectory(id, name, refresh);
			}
		}
	}

	private void getMusicDirectory(final String id, final String name, final boolean refresh) {
		setTitle(name);

		new LoadTask(refresh) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				MusicDirectory dir = getMusicDirectory(id, name, refresh, service, this);

				if(lookupParent && dir.getParent() != null) {
					dir = getMusicDirectory(dir.getParent(), name, refresh, service, this);

					// Update the fragment pointers so other stuff works correctly
					SelectDirectoryFragment.this.id = dir.getId();
					SelectDirectoryFragment.this.name = dir.getName();
				} else if(id != null && directory == null && dir.getParent() != null && !artist) {
					// View Album, try to lookup parent to get a complete entry to use for starring
					MusicDirectory parentDir = getMusicDirectory(dir.getParent(), name, refresh, true, service, this);
					for(Entry child: parentDir.getChildren()) {
						if(id.equals(child.getId())) {
							directory = child;
							break;
						}
					}
				}

				return dir;
			}

			@Override
			protected void done(Pair<MusicDirectory, Boolean> result) {
				SelectDirectoryFragment.this.name = result.getFirst().getName();
				setTitle(SelectDirectoryFragment.this.name);
				super.done(result);
			}
		}.execute();
	}

	private void getRecursiveMusicDirectory(final String id, final String name, final boolean refresh) {
		setTitle(name);

		new LoadTask(refresh) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				MusicDirectory root;
				if(share == null) {
					root = getMusicDirectory(id, name, refresh, service, this);
				} else {
					root = share.getMusicDirectory();
				}
				List<Entry> songs = new ArrayList<Entry>();
				getSongsRecursively(root, songs);

				// CachedMusicService is refreshing this data in the background, so will wipe out the songs list from root
				MusicDirectory clonedRoot = new MusicDirectory(songs);
				clonedRoot.setId(root.getId());
				clonedRoot.setName(root.getName());
				return clonedRoot;
			}

			private void getSongsRecursively(MusicDirectory parent, List<Entry> songs) throws Exception {
				songs.addAll(parent.getChildren(false, true));
				for (Entry dir : parent.getChildren(true, false)) {
					MusicService musicService = MusicServiceFactory.getMusicService(context);

					MusicDirectory musicDirectory;
					if(Util.isTagBrowsing(context) && !Util.isOffline(context)) {
						musicDirectory = musicService.getAlbum(dir.getId(), dir.getTitle(), false, context, this);
					} else {
						musicDirectory = musicService.getMusicDirectory(dir.getId(), dir.getTitle(), false, context, this);
					}
					getSongsRecursively(musicDirectory, songs);
				}
			}

			@Override
			protected void done(Pair<MusicDirectory, Boolean> result) {
				SelectDirectoryFragment.this.name = result.getFirst().getName();
				setTitle(SelectDirectoryFragment.this.name);
				super.done(result);
			}
		}.execute();
	}

	private void getPlaylist(final String playlistId, final String playlistName, final boolean refresh) {
		setTitle(playlistName);

		new LoadTask(refresh) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				return service.getPlaylist(refresh, playlistId, playlistName, context, this);
			}
		}.execute();
	}

	private void getPodcast(final String podcastId, final String podcastName, final boolean refresh) {
		setTitle(podcastName);

		new LoadTask(refresh) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				return service.getPodcastEpisodes(refresh, podcastId, context, this);
			}
		}.execute();
	}

	private void getShare(final Share share, final boolean refresh) {
		setTitle(share.getName());

		new LoadTask(refresh) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				return share.getMusicDirectory();
			}
		}.execute();
	}

	private void getTopTracks(final String id, final String name, final boolean refresh) {
		setTitle(name);

		new LoadTask(refresh) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				return service.getTopTrackSongs(name, 50, context, this);
			}
		}.execute();
	}

	private void getAlbumList(final String albumListType, final int size, final boolean refresh) {
		if ("newest".equals(albumListType)) {
			setTitle(R.string.main_albums_newest);
		} else if ("random".equals(albumListType)) {
			setTitle(R.string.main_albums_random);
		} else if ("highest".equals(albumListType)) {
			setTitle(R.string.main_albums_highest);
		} else if ("recent".equals(albumListType)) {
			setTitle(R.string.main_albums_recent);
		} else if ("frequent".equals(albumListType)) {
			setTitle(R.string.main_albums_frequent);
		} else if ("starred".equals(albumListType)) {
			setTitle(R.string.main_albums_starred);
		} else if("genres".equals(albumListType) || "years".equals(albumListType)) {
			setTitle(albumListExtra);
		} else if("alphabeticalByName".equals(albumListType)) {
			setTitle(R.string.main_albums_alphabetical);
		} if (MainFragment.SONGS_NEWEST.equals(albumListType)) {
			setTitle(R.string.main_songs_newest);
		} else if (MainFragment.SONGS_TOP_PLAYED.equals(albumListType)) {
			setTitle(R.string.main_songs_top_played);
		} else if (MainFragment.SONGS_RECENT.equals(albumListType)) {
			setTitle(R.string.main_songs_recent);
		} else if (MainFragment.SONGS_FREQUENT.equals(albumListType)) {
			setTitle(R.string.main_songs_frequent);
		}

		new LoadTask(true) {
			@Override
			protected MusicDirectory load(MusicService service) throws Exception {
				MusicDirectory result;
				if ("starred".equals(albumListType)) {
					result = service.getStarredList(context, this);
				} else if(("genres".equals(albumListType) && ServerInfo.checkServerVersion(context, "1.10.0")) || "years".equals(albumListType)) {
					result = service.getAlbumList(albumListType, albumListExtra, size, 0, refresh, context, this);
					if(result.getChildrenSize() == 0 && "genres".equals(albumListType)) {
						SelectDirectoryFragment.this.albumListType = "genres-songs";
						result = service.getSongsByGenre(albumListExtra, size, 0, context, this);
					}
				} else if("genres".equals(albumListType) || "genres-songs".equals(albumListType)) {
					result = service.getSongsByGenre(albumListExtra, size, 0, context, this);
				} else if(albumListType.indexOf(MainFragment.SONGS_LIST_PREFIX) != -1) {
					result = service.getSongList(albumListType, size, 0, context, this);
				} else {
					result = service.getAlbumList(albumListType, size, 0, refresh, context, this);
				}
				return result;
			}
		}.execute();
	}

	private abstract class LoadTask extends TabBackgroundTask<Pair<MusicDirectory, Boolean>> {
		private boolean refresh;

		public LoadTask(boolean refresh) {
			super(SelectDirectoryFragment.this);
			this.refresh = refresh;

			currentTask = this;
		}

		protected abstract MusicDirectory load(MusicService service) throws Exception;

		@Override
		protected Pair<MusicDirectory, Boolean> doInBackground() throws Throwable {
		  MusicService musicService = MusicServiceFactory.getMusicService(context);
			MusicDirectory dir = load(musicService);
			licenseValid = musicService.isLicenseValid(context, this);

			albums = dir.getChildren(true, false);
			entries = dir.getChildren();

			// Keep the untouched list so filters can be changed without refetching.
			if(isCollectionListView()) {
				unfilteredEntries = new ArrayList<Entry>(entries);
				entries = matchingCollections(unfilteredEntries, collectionSearchQuery);
				albums = new ArrayList<Entry>(entries);
			} else if(isCollectionView()) {
				unfilteredEntries = new ArrayList<Entry>(entries);
				if(browseFilter.isActive()) {
					entries = browseFilter.apply(unfilteredEntries, System.currentTimeMillis() / 1000L,
							filterMenu.getAlbumDetails());
					albums = new ArrayList<Entry>(entries);
				}
			} else if(isPlaylistView()) {
				unfilteredPlaylistEntries = new ArrayList<Entry>(entries);
			}

			// This isn't really an artist if no albums on it!
			if(albums.size() == 0) {
				artist = false;
			}

			// If artist, we want to load the artist info to use later
			if(artist && ServerInfo.hasArtistInfo(context)  && !Util.isOffline(context)) {
				try {
					String artistId;
					if(id.indexOf(';') == -1) {
						artistId = id;
					} else {
						artistId = id.substring(0, id.indexOf(';'));
					}

					artistInfo = musicService.getArtistInfo(artistId, refresh, false, context, this);

					if(artistInfo == null) {
						artistInfoDelayed = artistId;
					}
				} catch(Exception e) {
					Log.w(TAG, "Failed to get Artist Info even though it should be supported");
				}
			}

			return new Pair<>(dir, licenseValid);
		}

		@Override
		protected void done(Pair<MusicDirectory, Boolean> result) {
			finishLoading();
			currentTask = null;
		}

		@Override
		public void updateCache(int changeCode) {
			if(entryGridAdapter != null && changeCode == CachedMusicService.CACHE_UPDATE_LIST) {
				entryGridAdapter.notifyDataSetChanged();
			} else if(changeCode == CachedMusicService.CACHE_UPDATE_METADATA) {
				if(coverArtView != null && coverArtRep != null && !Util.equals(coverArtRep.getCoverArt(), coverArtId)) {
					synchronized (coverArtRep) {
						if (updateCoverArtTask != null && updateCoverArtTask.isRunning()) {
							updateCoverArtTask.cancel();
						}
						updateCoverArtTask = loadHeaderCoverArt(getImageLoader(), coverArtView);
						coverArtId = coverArtRep.getCoverArt();
					}
					watchHeaderArtwork(coverArtView);
				}
			}
		}
	}

	@Override
	public SectionAdapter<Entry> getCurrentAdapter() {
		return entryGridAdapter;
	}

	@Override
	public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(final GridLayoutManager gridLayoutManager) {
		return new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				int viewType = entryGridAdapter.getItemViewType(position);
				if(viewType == EntryGridAdapter.VIEW_TYPE_SONG || viewType == EntryGridAdapter.VIEW_TYPE_HEADER || viewType == EntryInfiniteGridAdapter.VIEW_TYPE_LOADING) {
					return gridLayoutManager.getSpanCount();
				} else {
					return 1;
				}
			}
		};
	}

    private void finishLoading() {
		boolean validData = !entries.isEmpty() || !albums.isEmpty();
		if(!validData) {
			setEmpty(true);
		}

		if(validData) {
			recyclerView.setVisibility(View.VISIBLE);
		}

		if(albumListType == null || "starred".equals(albumListType)) {
			entryGridAdapter = new EntryGridAdapter(context, entries, getImageLoader(), largeAlbums);
			entryGridAdapter.setRemoveFromPlaylist(playlistId != null);
		} else {
			if("alphabeticalByName".equals(albumListType)) {
				entryGridAdapter = new AlphabeticalAlbumAdapter(context, entries, getImageLoader(), largeAlbums);
			} else if("highest".equals(albumListType)) {
				entryGridAdapter = new TopRatedAlbumAdapter(context, entries, getImageLoader(), largeAlbums);
			} else {
				entryGridAdapter = new EntryInfiniteGridAdapter(context, entries, getImageLoader(), largeAlbums);
			}

			// Setup infinite loading based on scrolling
			final EntryInfiniteGridAdapter infiniteGridAdapter = (EntryInfiniteGridAdapter) entryGridAdapter;
			infiniteGridAdapter.setData(albumListType, albumListExtra, albumListSize);

			recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
				@Override
				public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
					super.onScrollStateChanged(recyclerView, newState);
				}

				@Override
				public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
					super.onScrolled(recyclerView, dx, dy);

					RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
					int totalItemCount = layoutManager.getItemCount();
					int lastVisibleItem;
					if(layoutManager instanceof GridLayoutManager) {
						lastVisibleItem = ((GridLayoutManager) layoutManager).findLastVisibleItemPosition();
					} else if(layoutManager instanceof LinearLayoutManager) {
						lastVisibleItem = ((LinearLayoutManager) layoutManager).findLastVisibleItemPosition();
					} else {
						return;
					}

					if(totalItemCount > 0 && lastVisibleItem >= totalItemCount - 2) {
						infiniteGridAdapter.loadMore();
					}
				}
			});
		}
		entryGridAdapter.setOnItemClickedListener(this);
		// Always show artist if this is not a artist we are viewing
		if(!artist) {
			entryGridAdapter.setShowArtist(true);
		}
		if(topTracks || showAll) {
			entryGridAdapter.setShowAlbum(true);
		}
		// On one album's own track listing the artist under nearly every title is the
		// album's artist, which the header states once already. Hand the adapter that name
		// so those rows can drop the line and the odd guest track keeps it.
		if(usesHeroHeader()) {
			entryGridAdapter.setAlbumArtist(findAlbumArtist());
		}

		boolean addedHeader = false;

		// An artist gets a header of their own: their name, how many albums there are and
		// how to start playing. It stands where the album header stands on an album, and
		// it does not wait on artist info the way the biography header below does - most
		// servers have no biography to give, and the screen used to open on nothing at all.
		if(albumListType == null && artist && albums != null && !albums.isEmpty()) {
			entryGridAdapter.setHeader(createArtistHeader());
			addedHeader = true;
		}

		// Show header if not album list type and not root and not artist
		// For Subsonic 5.1+ display a header for artists with getArtistInfo data if it exists
		// The collection list gets none: its header would read "Collections / 0 SONGS /
		// 0:00", which says nothing, and it costs a third of the screen just as the filter
		// field and keyboard are competing for the rest.
		if(!addedHeader && albumListType == null && !isCollectionListView()
				&& (!artist || artistInfo != null || artistInfoDelayed != null)
				&& (share == null || entries.size() != albums.size())) {
			View header = createHeader();

			if(header != null) {
				if (artistInfoDelayed != null) {
					final View finalHeader = header.findViewById(R.id.select_album_header);
					final View headerProgress = header.findViewById(R.id.header_progress);

					finalHeader.setVisibility(View.INVISIBLE);
					headerProgress.setVisibility(View.VISIBLE);

					new SilentBackgroundTask<Void>(context) {
						@Override
						protected Void doInBackground() throws Throwable {
							MusicService musicService = MusicServiceFactory.getMusicService(context);
							artistInfo = musicService.getArtistInfo(artistInfoDelayed, false, true, context, this);

							return null;
						}

						@Override
						protected void done(Void result) {
							setupCoverArt(finalHeader);
							setupTextDisplay(finalHeader);
							setupButtonEvents(finalHeader);

							finalHeader.setVisibility(View.VISIBLE);
							headerProgress.setVisibility(View.GONE);
						}
					}.execute();
				}

				entryGridAdapter.setHeader(header);
				addedHeader = true;
			}
		}

		int scrollToPosition = -1;
		if(lookupEntry != null) {
			for(int i = 0; i < entries.size(); i++) {
				if(lookupEntry.equals(entries.get(i).getTitle())) {
					scrollToPosition = i;
					entryGridAdapter.addSelected(entries.get(i));
					lookupEntry = null;
					break;
				}
			}
		}

		recyclerView.setAdapter(entryGridAdapter);
		fastScroller.attachRecyclerView(recyclerView);
		context.supportInvalidateOptionsMenu();

		// The rested count is a function of the tracks, so it can only be filled in now.
		updatePlaylistActionBar();

		if(scrollToPosition != -1) {
			recyclerView.scrollToPosition(scrollToPosition + (addedHeader ? 1 : 0));
		}

		Bundle args = getArguments();
		boolean playAll = args.getBoolean(Constants.INTENT_EXTRA_NAME_AUTOPLAY, false);
		if (playAll && !restoredInstance) {
			playAll(args.getBoolean(Constants.INTENT_EXTRA_NAME_SHUFFLE, false), false, false);
		}
	}

	@Override
	protected void playNow(final boolean shuffle, final boolean append, final boolean playNext) {
		List<Entry> songs = getSelectedEntries();
		if(!songs.isEmpty()) {
			download(songs, append, false, !append, playNext, shuffle);
			entryGridAdapter.clearSelected();
		} else {
			playAll(shuffle, append, playNext);
		}
	}
	private void playAll(final boolean shuffle, final boolean append, final boolean playNext) {
		boolean hasSubFolders = albums != null && !albums.isEmpty();

		if (hasSubFolders && (id != null || share != null || "starred".equals(albumListType))) {
			downloadRecursively(id, false, append, !append, shuffle, false, playNext);
		} else if(hasSubFolders && albumListType != null) {
			downloadRecursively(albums, shuffle, append, playNext);
		} else {
			download(entries, append, false, !append, playNext, shuffle);
		}
	}

	@Override
	protected void executeOnValid(RecursiveLoader onValid) {
		checkLicenseAndTrialPeriod(onValid);
	}

	@Override
	protected void downloadBackground(final boolean save) {
		List<Entry> songs = getSelectedEntries();
		if(playlistId != null) {
			songs = entries;
		}

		if(!songs.isEmpty()) {
			downloadBackground(save, songs);
			return;
		}

		// On a collection screen the rows are what the filters left visible, and a filter is
		// a statement about what is wanted - so caching means caching that, not the whole
		// collection. Going back to the server by id would fetch everything regardless, so
		// the visible rows are handed over directly instead.
		if(isCollectionView() || isCollectionListView()) {
			List<Entry> visible = getVisibleAlbums();
			if(visible.isEmpty()) {
				Util.toast(context, R.string.menu_filter_no_match);
				return;
			}

			downloadBackground(save, visible);
			return;
		}

		// Get both songs and albums
		downloadRecursively(id, save, false, false, false, true);
	}
	@Override
	protected void downloadBackground(final boolean save, final List<Entry> entries) {
		if (getDownloadService() == null) {
			return;
		}

		warnIfStorageUnavailable();
		RecursiveLoader onValid = new RecursiveLoader(context) {
			@Override
			protected Boolean doInBackground() throws Throwable {
				getSongsRecursively(entries, true);
				getDownloadService().downloadBackground(songs, save);
				return null;
			}

			@Override
			protected void done(Boolean result) {
				Util.toast(context, context.getResources().getQuantityString(R.plurals.select_album_n_songs_downloading, songs.size(), songs.size()));
			}
		};

		checkLicenseAndTrialPeriod(onValid);
	}

	@Override
	protected void download(List<Entry> entries, boolean append, boolean save, boolean autoplay, boolean playNext, boolean shuffle) {
		download(entries, append, save, autoplay, playNext, shuffle, playlistName, playlistId);
	}

	@Override
	protected void delete() {
		List<Entry> songs = getSelectedEntries();
		if(songs.isEmpty()) {
			for(Entry entry: entries) {
				if(entry.isDirectory()) {
					deleteRecursively(entry);
				} else {
					songs.add(entry);
				}
			}
		}
		if (getDownloadService() != null) {
			getDownloadService().delete(songs);
		}
	}

	/**
	 * Removes songs from the playlist. Indexes are resolved against the untouched master
	 * list rather than whatever the mix filter currently has on screen, since the server
	 * only understands the real playlist positions - sending filtered-view indexes would
	 * delete the wrong songs.
	 */
	public void removeFromPlaylist(final String id, final String name, final List<Entry> songs) {
		final List<Entry> master = (unfilteredPlaylistEntries != null) ? unfilteredPlaylistEntries : entries;
		final List<Integer> indexes = new ArrayList<Integer>();
		for(Entry song: songs) {
			indexes.add(master.indexOf(song));
		}

		new LoadingTask<Void>(context, true) {
			@Override
			protected Void doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				musicService.removeFromPlaylist(id, indexes, context, null);
				return null;
			}

			@Override
			protected void done(Void result) {
				for(Entry song: songs) {
					entryGridAdapter.removeItem(song);
					master.remove(song);
					if(entries != master) {
						entries.remove(song);
					}
				}
				Util.toast(context, context.getResources().getString(R.string.removed_playlist, String.valueOf(songs.size()), name));
			}

			@Override
			protected void error(Throwable error) {
				String msg;
				if (error instanceof OfflineException || error instanceof ServerTooOldException) {
					msg = getErrorMessage(error);
				} else {
					msg = context.getResources().getString(R.string.updated_playlist_error, name) + " " + getErrorMessage(error);
				}

				Util.toast(context, msg, false);
			}
		}.execute();
	}
	
	public void downloadAllPodcastEpisodes() {
		new LoadingTask<Void>(context, true) {
			@Override
			protected Void doInBackground() throws Throwable {				
				MusicService musicService = MusicServiceFactory.getMusicService(context);

				for(int i = 0; i < entries.size(); i++) {
					PodcastEpisode episode = (PodcastEpisode) entries.get(i);
					if("skipped".equals(episode.getStatus())) {
						musicService.downloadPodcastEpisode(episode.getEpisodeId(), context, null);
					}
				}
				return null;
			}

			@Override
			protected void done(Void result) {
				Util.toast(context, context.getResources().getString(R.string.select_podcasts_downloading, podcastName));
			}

			@Override
			protected void error(Throwable error) {
				Util.toast(context, getErrorMessage(error), false);
			}
		}.execute();
	}

	@Override
	protected void toggleSelectedStarred() {
		UpdateHelper.OnStarChange onStarChange = null;
		if(albumListType != null && "starred".equals(albumListType)) {
			onStarChange = new UpdateHelper.OnStarChange() {
				@Override
				public void starChange(boolean starred) {

				}

				@Override
				public void starCommited(boolean starred) {
					if(!starred) {
						for (Entry entry : entries) {
							entryGridAdapter.removeItem(entry);
						}
					}
				}
			};
		}

		UpdateHelper.toggleStarred(context, getSelectedEntries(), onStarChange);
	}

	private void checkLicenseAndTrialPeriod(LoadingTask onValid) {
		if (licenseValid) {
			onValid.execute();
			return;
		}

		int trialDaysLeft = Util.getRemainingTrialDays(context);
		Log.i(TAG, trialDaysLeft + " trial days left.");

		if (trialDaysLeft == 0) {
			showDonationDialog(trialDaysLeft, null);
		} else if (trialDaysLeft < Constants.FREE_TRIAL_DAYS / 2) {
			showDonationDialog(trialDaysLeft, onValid);
		} else {
			Util.toast(context, context.getResources().getString(R.string.select_album_not_licensed, trialDaysLeft));
			onValid.execute();
		}
	}

	private void showDonationDialog(int trialDaysLeft, final LoadingTask onValid) {
		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setIcon(android.R.drawable.ic_dialog_info);

		if (trialDaysLeft == 0) {
			builder.setTitle(R.string.select_album_donate_dialog_0_trial_days_left);
		} else {
			builder.setTitle(context.getResources().getQuantityString(R.plurals.select_album_donate_dialog_n_trial_days_left,
															  trialDaysLeft, trialDaysLeft));
		}

		builder.setMessage(R.string.select_album_donate_dialog_message);

		builder.setPositiveButton(R.string.select_album_donate_dialog_now,
			new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialogInterface, int i) {
					startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DONATION_URL)));
				}
			});

		builder.setNegativeButton(R.string.select_album_donate_dialog_later,
			new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialogInterface, int i) {
					dialogInterface.dismiss();
					if (onValid != null) {
						onValid.execute();
					}
				}
			});

		builder.create().show();
	}

	private void showTopTracks() {
		SubsonicFragment fragment = new SelectDirectoryFragment();
		Bundle args = new Bundle(getArguments());
		args.putBoolean(Constants.INTENT_EXTRA_TOP_TRACKS, true);
		fragment.setArguments(args);

		replaceFragment(fragment, true);
	}

	private void setShowAll() {
		SubsonicFragment fragment = new SelectDirectoryFragment();
		Bundle args = new Bundle(getArguments());
		args.putBoolean(Constants.INTENT_EXTRA_SHOW_ALL, true);
		fragment.setArguments(args);

		replaceFragment(fragment, true);
	}

	private void showSimilarArtists(String artistId) {
		SubsonicFragment fragment = new SimilarArtistFragment();
		Bundle args = new Bundle();
		args.putString(Constants.INTENT_EXTRA_NAME_ARTIST, artistId);
		fragment.setArguments(args);

		replaceFragment(fragment, true);
	}

	private void startArtistRadio(final String artistId) {
		new LoadingTask<Void>(context) {
			@Override
			protected Void doInBackground() throws Throwable {
				DownloadService downloadService = getDownloadService();
				downloadService.clear();
				downloadService.setArtistRadio(artistId);
				return null;
			}

			@Override
			protected void done(Void result) {
				context.openNowPlaying();
			}
		}.execute();
	}

	/**
	 * The header over an artist's discography.
	 *
	 * The picture comes from the entry the Library row handed over, which already carried
	 * it, so opening an artist costs no extra request. Servers that list artists without
	 * a picture simply leave the circle out - there is nothing sensible to put in it, and
	 * one of their album covers blown up would be an arbitrary pick from the grid below.
	 */
	private View createArtistHeader() {
		View header = LayoutInflater.from(context).inflate(R.layout.select_artist_header_hero, null, false);

		TextView nameView = (TextView) header.findViewById(R.id.select_artist_name);
		nameView.setText(name);

		TextView countView = (TextView) header.findViewById(R.id.select_artist_count);
		countView.setText(getResources().getQuantityString(R.plurals.select_artist_albums, albums.size(), albums.size()));

		// Whatever the row that led here was able to hand over: the picture itself, or
		// the entry carrying it on servers that pass one.
		String art = artistArt != null ? artistArt : (directory != null ? directory.getCoverArt() : null);

		ImageView artView = (ImageView) header.findViewById(R.id.select_artist_art);
		if(art != null) {
			artView.setVisibility(View.VISIBLE);

			int size = getResources().getDimensionPixelSize(R.dimen.ArtistHeader_Art);
			getImageLoader().loadArtistImage(artView, id, name, art, size);
		}

		header.findViewById(R.id.select_artist_play).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				playNow(false, false);
			}
		});

		header.findViewById(R.id.select_artist_shuffle).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				playNow(true, false);
			}
		});

		return header;
	}

	private View createHeader() {
		int layout = usesHeroHeader() ? R.layout.select_album_header_hero : R.layout.select_album_header;
		View header = LayoutInflater.from(context).inflate(layout, null, false);

		setupCoverArt(header);
		setupTextDisplay(header);
		setupButtonEvents(header);
		setupReleaseDetails(header);
		setupOfflineDetails(header);
		setupAlbumExtras(header);
		setupPlaybackButtons(header);

		return header;
	}

	/**
	 * The play and add-to-queue pills under the album's details.
	 *
	 * Both defer to playNow, the same call the toolbar's play button makes: with tracks
	 * selected it acts on the selection, otherwise on the whole album. Offline they stay,
	 * since playing what is already downloaded is exactly what offline mode is for.
	 */
	private void setupPlaybackButtons(View header) {
		View playButton = header.findViewById(R.id.select_album_play);
		View queueButton = header.findViewById(R.id.select_album_queue);
		if(playButton == null || queueButton == null) {
			return;
		}

		playButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				playNow(false, false);
			}
		});

		queueButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				playNow(false, true);
			}
		});
	}

	/**
	 * Whether this screen shows the contents of one album rather than a set of albums or
	 * somebody's biography.
	 *
	 * Artist and podcast screens put a long description where the artist line goes and
	 * let it flow around a small image, which the hero layout has no room for and whose
	 * expand handler rewrites RelativeLayout rules the hero header does not have. A
	 * listing that still has directories under it is a grid of albums, where one cover
	 * blown up to fill the header would be an arbitrary pick from among them. Both keep
	 * the older side-by-side header.
	 */
	private boolean usesHeroHeader() {
		return artistInfo == null && artistInfoDelayed == null && podcastDescription == null
				&& albums != null && albums.isEmpty()
				&& entries != null && !entries.isEmpty();
	}

	/**
	 * The artist this album is credited to, or null when the tracks do not agree on one.
	 *
	 * Servers that have a real album artist tag repeat it on every track, so the count is
	 * unanimous and the answer obvious. Where the field falls back to the track's own
	 * performer a compilation gives every track a different name, and the most common of
	 * them is whoever happens to appear twice - so it only counts as the album's artist
	 * if it covers more than half the listing.
	 */
	private String findAlbumArtist() {
		Map<String, Integer> counts = new HashMap<String, Integer>();
		int songCount = 0;
		for(Entry entry: entries) {
			if(entry.isDirectory()) {
				continue;
			}

			songCount++;
			String artist = entry.getAlbumArtist();
			if(artist == null || artist.isEmpty()) {
				continue;
			}

			Integer count = counts.get(artist);
			counts.put(artist, count == null ? 1 : count + 1);
		}

		String best = null;
		int bestCount = 0;
		for(Map.Entry<String, Integer> count: counts.entrySet()) {
			if(count.getValue() > bestCount) {
				best = count.getKey();
				bestCount = count.getValue();
			}
		}

		return bestCount * 2 > songCount ? best : null;
	}

	/**
	 * Fills in the original release date and label under the title.
	 *
	 * Loaded separately and after the fact because neither travels with the track listing
	 * -- the header is drawn immediately and gains the line a moment later rather than
	 * making the whole screen wait on a second request. Servers that cannot answer return
	 * nothing and the line stays hidden, which is every server but Plex today.
	 */
	private void setupReleaseDetails(View header) {
		final TextView releaseView = (TextView) header.findViewById(R.id.select_album_release);
		if(releaseView == null || !usesHeroHeader() || id == null
				|| playlistName != null || share != null || Util.isOffline(context)) {
			return;
		}

		final TextView artistView = (TextView) header.findViewById(R.id.select_album_artist);

		final String albumId = id;
		final Entry firstSong = getFirstSong();
		final Integer year = getAlbumYear();
		new SilentBackgroundTask<AlbumDetails>(context) {
			@Override
			protected AlbumDetails doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				Map<String, AlbumDetails> details = musicService.getAlbumDetails(
						Arrays.asList(albumId), context, null);
				AlbumDetails result = (details == null) ? null : details.get(albumId);

				// Kept for the same header offline, where there is nobody to ask.
				if(firstSong != null) {
					File folder = FileUtil.getAlbumDirectory(context, firstSong);
					AlbumSnapshots.rememberDetails(context, folder, year, result);
					AlbumSnapshots.rememberAlbum(context, folder, Util.getRestUrlHash(context),
							DownloadedAlbums.albumEntryOf(firstSong), null);
				}
				return result;
			}

			@Override
			protected void done(AlbumDetails result) {
				showReleaseDetails(releaseView, artistView, result);
			}

			@Override
			protected void error(Throwable error) {
				// A header line is not worth a toast; the rest of the screen is already up.
				Log.w(TAG, "Failed to load album details for " + albumId, error);
			}
		}.execute();
	}

	/** Writes the release date and label under the title, whichever of them is known. */
	private void showReleaseDetails(TextView releaseView, TextView artistView, AlbumDetails result) {
		if(result == null) {
			return;
		}

		// Through the activity rather than Fragment.getString: this lands whenever
		// the request does, which may be after the fragment has been detached.
		String separator = context.getResources().getString(R.string.select_album_detail_separator);
		StringBuilder text = new StringBuilder();
		String released = formatOriginalRelease(result);
		if(released != null) {
			text.append(released);
		}
		if(result.getRecordLabel() != null) {
			if(text.length() > 0) {
				text.append(separator);
			}
			text.append(result.getRecordLabel());
		}

		if(text.length() > 0) {
			releaseView.setText(text.toString());
			releaseView.setVisibility(View.VISIBLE);
		}

		// The artist line is written as "Artist - Year" off the tracks before this
		// request lands. Once a real release date is on screen the year is saying
		// the same thing twice, so it goes -- but only then, which is what leaves
		// the year in place on servers that never answer with a date.
		if(released != null && artistView != null && headerArtistWithoutYear != null) {
			artistView.setText(headerArtistWithoutYear);
		}
	}

	/**
	 * Puts back offline what the header shows online: the year, the release line and when
	 * the album was last played.
	 *
	 * Offline the album is a folder, so there is no server to ask. What the online screen
	 * learned is kept against that folder and wins where it exists, since it is what the
	 * screen said then. The files' own tags stand in for anything never learned, which is
	 * every album downloaded before this was kept and not opened online since. They are a
	 * fallback rather than a peer because they can disagree with the server - a reissue
	 * tagged with its own date, say - and the header should not change its story with the
	 * signal. Last played has no such stand-in: a file cannot know when it was heard.
	 */
	private void setupOfflineDetails(View header) {
		if(!usesHeroHeader() || id == null || playlistName != null || share != null
				|| podcastId != null || !Util.isOffline(context)) {
			return;
		}

		final TextView artistView = (TextView) header.findViewById(R.id.select_album_artist);
		final TextView releaseView = (TextView) header.findViewById(R.id.select_album_release);
		final TextView lastPlayedView = (TextView) header.findViewById(R.id.select_album_last_played);
		final View separatorView = header.findViewById(R.id.select_album_last_played_separator);

		final File folder = new File(id);
		new SilentBackgroundTask<AlbumSnapshot>(context) {
			@Override
			protected AlbumSnapshot doInBackground() throws Throwable {
				return AlbumSnapshots.forOfflineAlbum(context, folder);
			}

			@Override
			protected void done(AlbumSnapshot snapshot) {
				if(snapshot == null) {
					return;
				}

				// Year first, so a release date landing with it takes it back off, as online.
				if(snapshot.getYear() != null && artistView != null && headerArtistWithoutYear != null) {
					artistView.setText(headerArtistWithoutYear + " - " + snapshot.getYear());
				}
				if(releaseView != null) {
					showReleaseDetails(releaseView, artistView, snapshot.getDetails());
				}
				if(snapshot.isLastPlayedKnown() && lastPlayedView != null && separatorView != null) {
					albumLastPlayed = snapshot.getLastPlayed();
					albumLastPlayedResolved = true;
					showLastPlayed(lastPlayedView, separatorView);
				}
			}

			@Override
			protected void error(Throwable error) {
				Log.w(TAG, "Failed to load offline details for " + folder, error);
			}
		}.execute();
	}

	/** The first track listed, which says where the album downloads to. */
	private Entry getFirstSong() {
		if(entries != null) {
			for(Entry entry: entries) {
				if(!entry.isDirectory()) {
					return entry;
				}
			}
		}
		return null;
	}

	/** The year every track agrees on, or null - the same rule the artist line uses. */
	private Integer getAlbumYear() {
		Set<Integer> years = new HashSet<Integer>();
		if(entries != null) {
			for(Entry entry: entries) {
				if(!entry.isDirectory() && entry.getYear() != null) {
					years.add(entry.getYear());
				}
			}
		}
		return (years.size() == 1) ? years.iterator().next() : null;
	}

	/**
	 * An ISO release date as "Apr 23, 1976", matching how the rest of the app writes
	 * dates. Falls back to the bare year where the server gave something else -- the year
	 * is the part worth showing, and a malformed date should not blank the line.
	 */
	private String formatOriginalRelease(AlbumDetails details) {
		String raw = details.getOriginalReleaseDate();
		if(raw == null) {
			return null;
		}

		try {
			SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
			iso.setLenient(false);
			return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(iso.parse(raw));
		} catch(ParseException e) {
			return details.getOriginalReleaseYear();
		}
	}

	private void setupCoverArt(View header) {
		setupCoverArtImpl((ImageView) header.findViewById(R.id.select_album_art));
	}
	private void setupCoverArtImpl(ImageView coverArtView) {
		final ImageLoader imageLoader = getImageLoader();

		// Try a few times to get a random cover art
		if(artistInfo != null) {
			final String url = artistInfo.getImageUrl();
			coverArtView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (url == null) {
						return;
					}

					AlertDialog.Builder builder = new AlertDialog.Builder(context);
					ImageView fullScreenView = new ImageView(context);
					imageLoader.loadImage(fullScreenView, url, true);
					builder.setCancelable(true);

					AlertDialog imageDialog = builder.create();
					// Set view here with unecessary 0's to remove top/bottom border
					imageDialog.setView(fullScreenView, 0, 0, 0, 0);
					imageDialog.show();
				}
			});
			imageLoader.loadImage(coverArtView, url, false);
		} else if(entries.size() > 0) {
			coverArtRep = null;
			this.coverArtView = coverArtView;
			for (int i = 0; (i < 3) && (coverArtRep == null || coverArtRep.getCoverArt() == null); i++) {
				coverArtRep = entries.get(random.nextInt(entries.size()));
			}

			coverArtView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (coverArtRep == null || coverArtRep.getCoverArt() == null) {
						return;
					}

					AlertDialog.Builder builder = new AlertDialog.Builder(context);
					ImageView fullScreenView = new ImageView(context);
					imageLoader.loadImage(fullScreenView, coverArtRep, true, true);
					builder.setCancelable(true);

					AlertDialog imageDialog = builder.create();
					// Set view here with unecessary 0's to remove top/bottom border
					imageDialog.setView(fullScreenView, 0, 0, 0, 0);
					imageDialog.show();
				}
			});
			synchronized (coverArtRep) {
				coverArtId = coverArtRep.getCoverArt();
				updateCoverArtTask = loadHeaderCoverArt(imageLoader, coverArtView);
			}
			watchHeaderArtwork(coverArtView);
		}
	}

	/**
	 * Washes the screen in the colour of the header's cover, the way Now Playing is washed
	 * in the playing track's.
	 *
	 * The cover arrives on its own time - straight from the cache, after a download, or by
	 * way of a crossfade that is not a bitmap until it finishes - and the loader says nothing
	 * when it lands, so the view is checked until it holds one. Only the hero header is
	 * washed: it is the one led by a single album's cover, where the other headers show an
	 * artist or one pick from a grid of albums. The White theme stays light, as Now Playing
	 * does.
	 */
	private void watchHeaderArtwork(final ImageView artView) {
		if(rootView == null) {
			return;
		}
		if(headerArtworkWatch != null) {
			rootView.removeCallbacks(headerArtworkWatch);
			headerArtworkWatch = null;
		}

		if(!usesHeroHeader() || coverArtRep == null || coverArtRep.getCoverArt() == null
				|| ThemeUtil.THEME_WHITE.equals(ThemeUtil.getTheme(context))) {
			setScreenBackdrop(null);
			return;
		}

		headerArtworkWatch = new Runnable() {
			// Ten seconds covers a cover downloaded over a slow connection; past that the
			// screen keeps its plain background rather than check forever.
			private int triesLeft = 40;

			@Override
			public void run() {
				if(rootView == null || headerArtworkWatch != this) {
					return;
				}

				Bitmap bitmap = ArtworkColors.bitmapOf(artView);
				if(bitmap == null) {
					if(--triesLeft > 0) {
						rootView.postDelayed(this, 250L);
					} else {
						headerArtworkWatch = null;
					}
					return;
				}

				headerArtworkWatch = null;
				setScreenBackdrop(ArtworkColors.washOf(bitmap, 0.38f));
			}
		};
		rootView.post(headerArtworkWatch);
	}

	private void setScreenBackdrop(Integer wash) {
		screenBackdropColor = wash;
		if(rootView != null) {
			rootView.setBackground(wash == null ? null : ArtworkColors.backdropOf(wash));
		}
		publishScreenBackdropColor();
	}

	@Override
	protected Integer getScreenBackdropColor() {
		return screenBackdropColor;
	}

	/**
	 * The header's cover, decoded at the size it is actually drawn at.
	 *
	 * The default image size is the one behind a list thumbnail, a few hundred pixels at
	 * most, which the hero header then stretched over a third of the screen - visibly so.
	 * The art cached on disk is already kept at the narrow side of the screen for the now
	 * playing screen, so asking for more here costs a decode and no extra download, and
	 * there is nothing past that size to ask for.
	 */
	private SilentBackgroundTask loadHeaderCoverArt(ImageLoader imageLoader, ImageView coverArtView) {
		if(!usesHeroHeader()) {
			return imageLoader.loadImage(coverArtView, coverArtRep, false, true);
		}

		int size = Math.min(getResources().getDimensionPixelSize(R.dimen.AlbumArt_Hero), imageLoader.getMaxImageSize());
		return imageLoader.loadImage(coverArtView, coverArtRep, false, size, true);
	}

	private void setupTextDisplay(final View header) {
		final TextView titleView = (TextView) header.findViewById(R.id.select_album_title);
		if(playlistName != null) {
			titleView.setText(playlistName);
		} else if(podcastName != null) {
			titleView.setText(podcastName);
			titleView.setPadding(0, 6, 4, 8);
		} else if(name != null) {
			titleView.setText(name);

			if(artistInfo != null) {
				titleView.setPadding(0, 6, 4, 8);
			}
		} else if(share != null) {
			titleView.setVisibility(View.GONE);
		}

		int songCount = 0;

		Set<String> artists = new HashSet<String>();
		Set<Integer> years = new HashSet<Integer>();
		Integer totalDuration = 0;
		for (Entry entry : entries) {
			if (!entry.isDirectory()) {
				songCount++;
				if (entry.getArtist() != null) {
					artists.add(entry.getArtist());
				}
				if(entry.getYear() != null) {
					years.add(entry.getYear());
				}
				Integer duration = entry.getDuration();
				if(duration != null) {
					totalDuration += duration;
				}
			}
		}

		final TextView artistView = (TextView) header.findViewById(R.id.select_album_artist);
		headerArtistWithoutYear = null;
		if(podcastDescription != null || artistInfo != null) {
			artistView.setVisibility(View.VISIBLE);
			String text = podcastDescription != null ? podcastDescription : artistInfo.getBiography();
			Spanned spanned = null;
			if(text != null) {
				spanned = Html.fromHtml(text);
			}
			artistView.setText(spanned);
			artistView.setSingleLine(false);
			final int minLines = context.getResources().getInteger(R.integer.TextDescriptionLength);
			artistView.setLines(minLines);
			artistView.setTextAppearance(context, android.R.style.TextAppearance_Small);

			final Spanned spannedText = spanned;
			artistView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if(artistView.getMaxLines() == minLines) {
						// Use LeadingMarginSpan2 to try to make text flow around image
						Display display = context.getWindowManager().getDefaultDisplay();
						ImageView coverArtView = (ImageView) header.findViewById(R.id.select_album_art);
						coverArtView.measure(display.getWidth(), display.getHeight());

						int height, width;
						ViewGroup.MarginLayoutParams vlp = (ViewGroup.MarginLayoutParams) coverArtView.getLayoutParams();
						if(coverArtView.getDrawable() != null) {
							height = coverArtView.getMeasuredHeight() + coverArtView.getPaddingBottom();
							width = coverArtView.getWidth() + coverArtView.getPaddingRight();
						} else {
							height = coverArtView.getHeight();
							width = coverArtView.getWidth() + coverArtView.getPaddingRight();
						}
						float textLineHeight = artistView.getPaint().getTextSize();
						int lines = (int) Math.ceil(height / textLineHeight);

						SpannableString ss = new SpannableString(spannedText);
						ss.setSpan(new MyLeadingMarginSpan2(lines, width), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

						View linearLayout = header.findViewById(R.id.select_album_text_layout);
						RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) linearLayout.getLayoutParams();
						int[]rules = params.getRules();
						rules[RelativeLayout.RIGHT_OF] = 0;
						params.leftMargin = vlp.rightMargin;

						artistView.setText(ss);
						artistView.setMaxLines(100);

						vlp = (ViewGroup.MarginLayoutParams) titleView.getLayoutParams();
						vlp.leftMargin = width;
					} else {
						artistView.setMaxLines(minLines);
					}
				}
			});
			artistView.setMovementMethod(LinkMovementMethod.getInstance());
		} else if(topTracks) {
			artistView.setText(R.string.menu_top_tracks);
			artistView.setVisibility(View.VISIBLE);
		} else if(showAll) {
			artistView.setText(R.string.menu_show_all);
			artistView.setVisibility(View.VISIBLE);
		} else if (artists.size() == 1) {
			String artistText = artists.iterator().next();
			// Kept so setupReleaseDetails can drop back to the bare name once it knows the
			// original release date, which says the same thing as the year but better.
			headerArtistWithoutYear = artistText;
			if(years.size() == 1) {
				artistText += " - " + years.iterator().next();
			}
			artistView.setText(artistText);
			artistView.setVisibility(View.VISIBLE);
		} else {
			artistView.setVisibility(View.GONE);
		}

		TextView songCountView = (TextView) header.findViewById(R.id.select_album_song_count);
		TextView songLengthView = (TextView) header.findViewById(R.id.select_album_song_length);
		if(podcastDescription != null || artistInfo != null) {
			songCountView.setVisibility(View.GONE);
			songLengthView.setVisibility(View.GONE);
		} else {
			String s = context.getResources().getQuantityString(R.plurals.select_album_n_songs, songCount, songCount);
			songCountView.setText(s.toUpperCase());
			songLengthView.setText(Util.formatDuration(totalDuration));
			setupLastPlayed(header);
		}
	}

	/**
	 * Fills in what the dashboard knows about the album: its rating under the details,
	 * and a heart against each track loved on Last.fm.
	 *
	 * One request for both, and it lands after the screen is already up. That is
	 * deliberate: these decorate a header and rows that read perfectly well without them,
	 * and waiting on a service that may not be reachable would hold up the album itself.
	 *
	 * The stars and the number are both shown because they say different things: the
	 * stars are readable at a glance, and the number is what actually separates two
	 * albums that both round to three.
	 */
	private void setupAlbumExtras(View header) {
		final View ratingRow = header.findViewById(R.id.select_album_rating_row);
		if(ratingRow == null || !usesHeroHeader() || id == null
				|| Util.isOffline(context) || !LoveClient.isConfigured(context)) {
			return;
		}

		final RatingBar ratingBar = (RatingBar) header.findViewById(R.id.select_album_rating);
		final TextView ratingValueView = (TextView) header.findViewById(R.id.select_album_rating_value);

		final String albumId = id;
		final EntryGridAdapter adapter = entryGridAdapter;
		new SilentBackgroundTask<LoveClient.AlbumSummary>(context) {
			@Override
			protected LoveClient.AlbumSummary doInBackground() throws Throwable {
				return LoveClient.getAlbumSummary(context, albumId);
			}

			@Override
			protected void done(LoveClient.AlbumSummary summary) {
				if(summary == null) {
					return;
				}

				if(!summary.hasNoRating()) {
					ratingBar.setRating(summary.getStars());
					ratingValueView.setText(String.format(Locale.getDefault(), "%.1f", summary.getRating()));
					ratingRow.setVisibility(View.VISIBLE);
				}

				// Nothing loved draws the same as not knowing, so an empty answer is not
				// worth rebinding every row for. A stale adapter means the screen has
				// reloaded under the request and these ids are about the old listing.
				Set<String> loved = summary.getLovedIds();
				if(loved != null && !loved.isEmpty() && adapter == entryGridAdapter) {
					adapter.setLovedIds(loved);
				}
			}

			@Override
			protected void error(Throwable error) {
				// A dashboard nobody but its author runs is the normal case, and the
				// screen reads fine without either of these.
				Log.w(TAG, "Failed to load album extras for " + albumId, error);
			}
		}.execute();
	}

	/**
	 * Adds when the album was last listened to on the end of the count and length line.
	 *
	 * Only on the hero header, which is the one an album gets; the older header belongs to
	 * artist and podcast screens, where there is no single album to have been played.
	 */
	private void setupLastPlayed(View header) {
		final TextView lastPlayedView = (TextView) header.findViewById(R.id.select_album_last_played);
		final View separatorView = header.findViewById(R.id.select_album_last_played_separator);
		if(lastPlayedView == null || separatorView == null) {
			return;
		}

		showLastPlayed(lastPlayedView, separatorView);
		refreshLastPlayed(lastPlayedView, separatorView);
	}

	/** Writes whatever is known so far onto the line, or takes the line away. */
	private void showLastPlayed(TextView lastPlayedView, View separatorView) {
		Long lastPlayed = resolveLastPlayed();
		if(lastPlayed == null || lastPlayed <= 0) {
			lastPlayedView.setVisibility(View.GONE);
			separatorView.setVisibility(View.GONE);
			return;
		}

		// Plex reports epoch seconds; DateUtils wants milliseconds. Left in the case
		// DateUtils writes it in: past about a week this is a date, and it should read
		// like the release date above it rather than like the song count beside it.
		CharSequence when = DateUtils.getRelativeTimeSpanString(lastPlayed * 1000L,
				System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
		lastPlayedView.setText(when);
		lastPlayedView.setVisibility(View.VISIBLE);
		separatorView.setVisibility(View.VISIBLE);
	}

	/**
	 * Asks the server when this album was last played, and redraws the line if it says.
	 *
	 * Browsing a collection gets this for free: every album on that listing carries its
	 * own lastViewedAt, which is what the filter rests and sorts on. An album screen sees
	 * no such row -- it asks for the album's tracks -- and the row it was handed on the way
	 * in goes stale in the cache the moment anything is played. This reads the album's own
	 * row, the same field from the same server, so the header says what a collection
	 * listing would, down to saying nothing for a record never played.
	 *
	 * Lands after the screen is up and is quiet about failure, like the rest of the
	 * header's extras: a server that cannot be reached leaves the line as the listing had
	 * it rather than making something up.
	 */
	private void refreshLastPlayed(final TextView lastPlayedView, final View separatorView) {
		final String albumId = id;
		if(!usesHeroHeader() || albumId == null || playlistName != null || share != null
				|| podcastId != null || Util.isOffline(context)) {
			return;
		}

		final Entry firstSong = getFirstSong();
		new SilentBackgroundTask<Long>(context) {
			@Override
			protected Long doInBackground() throws Throwable {
				// The factory hands back a CachedMusicService wrapper, and this is
				// deliberately not cached - it is the one thing about an album that
				// changes every time it is listened to.
				MusicService service = MusicServiceFactory.getMusicService(context);
				if(service instanceof CachedMusicService) {
					service = ((CachedMusicService) service).getUnderlyingService();
				}
				if(!(service instanceof PlexMusicService)) {
					return null;
				}
				Long lastPlayed = ((PlexMusicService) service).getAlbumLastPlayed(context, albumId);

				// The last word the server had, for the header offline. Never played is kept too.
				if(firstSong != null) {
					AlbumSnapshots.rememberLastPlayed(context, FileUtil.getAlbumDirectory(context, firstSong), lastPlayed);
				}
				return lastPlayed;
			}

			@Override
			protected void done(Long lastPlayed) {
				// Null is an answer, not a failure to answer: Plex leaves lastViewedAt off
				// a record that has never been played, and the line goes away for it.
				albumLastPlayed = (lastPlayed != null && lastPlayed > 0) ? lastPlayed : null;
				albumLastPlayedResolved = true;
				showLastPlayed(lastPlayedView, separatorView);
			}

			@Override
			protected void error(Throwable error) {
				// A header line is not worth a toast; the rest of the screen is already up.
				Log.w(TAG, "Failed to load last played for " + albumId, error);
			}
		}.execute();
	}

	/**
	 * When this album was last listened to, or null where nothing says.
	 *
	 * The album's own row is the whole answer, and a row without a lastViewedAt on it means
	 * the record has never been played -- which is how the rested filter reads it too, and
	 * here it takes the line away rather than putting a date on it.
	 *
	 * The tracks are not consulted at all, and that is the point. Plex does not roll a
	 * single track's play up into its album: pull one song into a mix and that song's
	 * lastViewedAt moves while the album's stays where the last real listen left it, so
	 * reading the tracks had a record nobody has ever sat through claiming the day of the
	 * mix, months from the date the same album carried on a collection listing. Hearing one
	 * song off a record is not listening to it.
	 *
	 * Until the answer lands there is the row the screen was handed, which is the very row
	 * a collection listing reads and carries the same field. It is missing entirely when
	 * the screen was reached by id, and then the line waits rather than guessing.
	 */
	private Long resolveLastPlayed() {
		if(albumLastPlayedResolved) {
			return albumLastPlayed;
		}

		return (directory != null) ? directory.getLastPlayed() : null;
	}
	private void setupButtonEvents(View header) {
		ImageView shareButton = (ImageView) header.findViewById(R.id.select_album_share);
		if(share != null || podcastId != null || !Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_MENU_SHARED, true) || Util.isOffline(context) || !UserUtil.canShare() || artistInfo != null) {
			shareButton.setVisibility(View.GONE);
		} else {
			shareButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					createShare(SelectDirectoryFragment.this.entries);
				}
			});
		}

		final ImageButton starButton = (ImageButton) header.findViewById(R.id.select_album_star);
		if(directory != null && Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_MENU_STAR, true) && artistInfo == null) {
			if(directory.isStarred()) {
				starButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_toggle_star));
			} else {
				starButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.star_outline));
			}
			starButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					UpdateHelper.toggleStarred(context, directory, new UpdateHelper.OnStarChange() {
						@Override
						public void starChange(boolean starred) {
							if (directory.isStarred()) {
								starButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.star_outline));
								starButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_toggle_star));
							} else {
								starButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.star_outline));
							}
						}

						@Override
						public void starCommited(boolean starred) {

						}
					});
				}
			});
		} else {
			starButton.setVisibility(View.GONE);
		}

		View ratingBarWrapper = header.findViewById(R.id.select_album_rate_wrapper);
		final RatingBar ratingBar = (RatingBar) header.findViewById(R.id.select_album_rate);
		if(directory != null && Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_MENU_RATING, true) && !Util.isOffline(context)  && artistInfo == null) {
			ratingBar.setRating(directory.getRating());
			ratingBarWrapper.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					UpdateHelper.setRating(context, directory, new UpdateHelper.OnRatingChange() {
						@Override
						public void ratingChange(int rating) {
							ratingBar.setRating(directory.getRating());
						}
					});
				}
			});
		} else {
			ratingBar.setVisibility(View.GONE);
		}
	}
}
