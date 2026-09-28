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

import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.annotation.TargetApi;
import android.text.format.DateFormat;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import androidx.core.view.MenuItemCompat;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ViewFlipper;
import com.shehabic.droppy.DroppyClickCallbackInterface;
import com.shehabic.droppy.DroppyMenuPopup;
import com.shehabic.droppy.animations.DroppyFadeInAnimation;
import github.daneren2005.dsub.R;
import github.daneren2005.dsub.activity.SubsonicFragmentActivity;
import github.daneren2005.dsub.adapter.SectionAdapter;
import github.daneren2005.dsub.audiofx.EqualizerController;
import github.daneren2005.dsub.domain.Bookmark;
import github.daneren2005.dsub.domain.PlayerState;
import github.daneren2005.dsub.domain.RepeatMode;
import github.daneren2005.dsub.domain.ServerInfo;
import github.daneren2005.dsub.domain.SyncedLyrics;
import github.daneren2005.dsub.service.DownloadFile;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.DownloadService.OnSongChangedListener;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;
import github.daneren2005.dsub.service.OfflineException;
import github.daneren2005.dsub.service.ServerTooOldException;
import github.daneren2005.dsub.util.ArtworkColors;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.LoveClient;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.adapter.DownloadFileAdapter;
import github.daneren2005.dsub.view.compat.CustomMediaRouteDialogFactory;
import github.daneren2005.dsub.view.FadeOutAnimation;
import github.daneren2005.dsub.view.FastScroller;
import github.daneren2005.dsub.view.UpdateView;
import github.daneren2005.dsub.util.Util;

import static github.daneren2005.dsub.domain.MusicDirectory.Entry;
import static github.daneren2005.dsub.domain.PlayerState.*;
import github.daneren2005.dsub.util.*;
import github.daneren2005.dsub.view.AutoRepeatButton;
import java.util.ArrayList;
import java.util.concurrent.ScheduledFuture;

public class NowPlayingFragment extends SubsonicFragment implements OnGestureListener, SectionAdapter.OnItemClickedListener<DownloadFile>, OnSongChangedListener {
	private static final String TAG = NowPlayingFragment.class.getSimpleName();
	private static final int PERCENTAGE_OF_SCREEN_FOR_SWIPE = 10;

	private static final int ACTION_PREVIOUS = 1;
	private static final int ACTION_NEXT = 2;
	private static final int ACTION_REWIND = 3;
	private static final int ACTION_FORWARD = 4;

	private ViewFlipper playlistFlipper;
	private TextView emptyTextView;
	private TextView songTitleTextView;
	private TextView songArtistTextView;
	private TextView songFormatTextView;
	private TextView songStatsTextView;
	private final Set<Entry> formatRefreshAttempted = new HashSet<Entry>();
	private View backdropView;
	private String backdropSongId;
	private static final int PAGE_ART = 0;
	private static final int PAGE_PLAYLIST = 1;
	private static final int PAGE_LYRICS = 2;
	private ScrollView lyricsScrollView;
	private LinearLayout lyricsContainer;
	private TextView lyricsEmptyView;
	private SyncedLyrics currentLyrics;
	private String lyricsSongId;
	private int lyricsActiveIndex = -1;
	private ImageView albumArtImageView;
	private RecyclerView playlistView;
	private TextView positionTextView;
	private TextView durationTextView;
	private TextView statusTextView;
	private SeekBar progressBar;
	private AutoRepeatButton previousButton;
	private AutoRepeatButton nextButton;
	private AutoRepeatButton rewindButton;
	private AutoRepeatButton fastforwardButton;
	private View pauseButton;
	private View stopButton;
	private View startButton;
	private ImageButton repeatButton;
	private View toggleListButton;
	private ImageButton starButton;
	private ImageButton bookmarkButton;
	private ImageButton rateBadButton;
	private ImageButton rateGoodButton;
	private ImageButton playbackSpeedButton;

	private ScheduledExecutorService executorService;
	private DownloadFile currentPlaying;
	private int swipeDistance;
	private int swipeVelocity;
	private ScheduledFuture<?> hideControlsFuture;
	private List<DownloadFile> songList;
	private DownloadFileAdapter songListAdapter;
	private boolean seekInProgress = false;
	private boolean startFlipped = false;
	private boolean scrollWhenLoaded = false;
	private int lastY = 0;
	private int currentPlayingSize = 0;
	private int currentPlayingIndex = -1;
	private TextView queueSummaryView;
	/** Last summary written, so the per-second progress tick only redraws on a change. */
	private String lastQueueSummary;
	private MenuItem timerMenu;
	private MenuItem loveMenuItem;
	private Boolean currentLoved;
    private DroppySpeedControl speed;

	/**
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if(savedInstanceState != null) {
			if(savedInstanceState.getInt(Constants.FRAGMENT_DOWNLOAD_FLIPPER) == 1) {
				startFlipped = true;
			}
		}
		primaryFragment = false;
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(Constants.FRAGMENT_DOWNLOAD_FLIPPER, playlistFlipper.getDisplayedChild());
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
		rootView = inflater.inflate(R.layout.download, container, false);
		setTitle(R.string.button_bar_now_playing);

		WindowManager w = context.getWindowManager();
		Display d = w.getDefaultDisplay();
		swipeDistance = (d.getWidth() + d.getHeight()) * PERCENTAGE_OF_SCREEN_FOR_SWIPE / 100;
		swipeVelocity = (d.getWidth() + d.getHeight()) * PERCENTAGE_OF_SCREEN_FOR_SWIPE / 100;
		gestureScanner = new GestureDetector(this);

		playlistFlipper = (ViewFlipper)rootView.findViewById(R.id.download_playlist_flipper);
		emptyTextView = (TextView)rootView.findViewById(R.id.download_empty);
		songTitleTextView = (TextView)rootView.findViewById(R.id.download_song_title);
		songArtistTextView = (TextView)rootView.findViewById(R.id.download_song_artist);
		songFormatTextView = (TextView)rootView.findViewById(R.id.download_song_format);
		songStatsTextView = (TextView)rootView.findViewById(R.id.download_song_stats);
		backdropView = rootView.findViewById(R.id.download_backdrop);
		lyricsScrollView = (ScrollView) rootView.findViewById(R.id.download_lyrics_scroll);
		lyricsContainer = (LinearLayout) rootView.findViewById(R.id.download_lyrics_container);
		lyricsEmptyView = (TextView) rootView.findViewById(R.id.download_lyrics_empty);
		albumArtImageView = (ImageView)rootView.findViewById(R.id.download_album_art_image);
		positionTextView = (TextView)rootView.findViewById(R.id.download_position);
		durationTextView = (TextView)rootView.findViewById(R.id.download_duration);
		statusTextView = (TextView)rootView.findViewById(R.id.download_status);
		progressBar = (SeekBar)rootView.findViewById(R.id.download_progress_bar);
		previousButton = (AutoRepeatButton)rootView.findViewById(R.id.download_previous);
		nextButton = (AutoRepeatButton)rootView.findViewById(R.id.download_next);
		rewindButton = (AutoRepeatButton) rootView.findViewById(R.id.download_rewind);
		fastforwardButton = (AutoRepeatButton) rootView.findViewById(R.id.download_fastforward);
		pauseButton =rootView.findViewById(R.id.download_pause);
		stopButton =rootView.findViewById(R.id.download_stop);
		startButton =rootView.findViewById(R.id.download_start);
		repeatButton = (ImageButton)rootView.findViewById(R.id.download_repeat);
		bookmarkButton = (ImageButton) rootView.findViewById(R.id.download_bookmark);
		rateBadButton = (ImageButton) rootView.findViewById(R.id.download_rating_bad);
		rateGoodButton = (ImageButton) rootView.findViewById(R.id.download_rating_good);
		playbackSpeedButton = (ImageButton) rootView.findViewById(R.id.download_playback_speed);
		toggleListButton =rootView.findViewById(R.id.download_toggle_list);

		queueSummaryView = (TextView) rootView.findViewById(R.id.download_queue_summary);
		playlistView = (RecyclerView)rootView.findViewById(R.id.download_list);
		FastScroller fastScroller = (FastScroller) rootView.findViewById(R.id.download_fast_scroller);
		fastScroller.attachRecyclerView(playlistView);
		setupLayoutManager(playlistView, false);
		ItemTouchHelper touchHelper = new ItemTouchHelper(new DownloadFileItemHelperCallback(this, true));
		touchHelper.attachToRecyclerView(playlistView);

		starButton = (ImageButton)rootView.findViewById(R.id.download_star);
		if(Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_MENU_STAR, true)) {
			starButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					getDownloadService().toggleStarred();
					setControlsVisible(true);
				}
			});
		} else {
			starButton.setVisibility(View.GONE);
		}

		View.OnTouchListener touchListener = new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent me) {
				return gestureScanner.onTouchEvent(me);
			}
		};
		pauseButton.setOnTouchListener(touchListener);
		stopButton.setOnTouchListener(touchListener);
		startButton.setOnTouchListener(touchListener);
		bookmarkButton.setOnTouchListener(touchListener);
		rateBadButton.setOnTouchListener(touchListener);
		rateGoodButton.setOnTouchListener(touchListener);
		playbackSpeedButton.setOnTouchListener(touchListener);
		emptyTextView.setOnTouchListener(touchListener);
		albumArtImageView.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent me) {
				if (me.getAction() == MotionEvent.ACTION_DOWN) {
					lastY = (int) me.getRawY();
				}
				return gestureScanner.onTouchEvent(me);
			}
		});

		previousButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				warnIfStorageUnavailable();
				new SilentBackgroundTask<Void>(context) {
					@Override
					protected Void doInBackground() throws Throwable {
						getDownloadService().previous();
						return null;
					}
				}.execute();
				setControlsVisible(true);
			}
		});
		previousButton.setOnRepeatListener(new Runnable() {
			public void run() {
				changeProgress(true);
			}
		});

		nextButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				warnIfStorageUnavailable();
				new SilentBackgroundTask<Boolean>(context) {
					@Override
					protected Boolean doInBackground() throws Throwable {
						getDownloadService().next();
						return true;
					}
				}.execute();
				setControlsVisible(true);
			}
		});
		nextButton.setOnRepeatListener(new Runnable() {
			public void run() {
				changeProgress(false);
			}
		});

		rewindButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				changeProgress(true);
			}
		});
		rewindButton.setOnRepeatListener(new Runnable() {
			public void run() {
				changeProgress(true);
			}
		});

		fastforwardButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				changeProgress(false);
			}
		});
		fastforwardButton.setOnRepeatListener(new Runnable() {
			public void run() {
				changeProgress(false);
			}
		});


		pauseButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				new SilentBackgroundTask<Void>(context) {
					@Override
					protected Void doInBackground() throws Throwable {
						getDownloadService().pause();
						return null;
					}
				}.execute();
			}
		});

		// Long press is a full stop rather than a pause: the queue ends there, instead
		// of being held ready to resume. Both buttons take it because they share the
		// one spot on screen, so whichever the player state is showing is the one the
		// press lands on.
		View.OnLongClickListener stopQueueListener = new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View view) {
				stopQueue();
				return true;
			}
		};
		pauseButton.setOnLongClickListener(stopQueueListener);

		stopButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				new SilentBackgroundTask<Void>(context) {
					@Override
					protected Void doInBackground() throws Throwable {
						getDownloadService().reset();
						return null;
					}
				}.execute();
			}
		});

		startButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				warnIfStorageUnavailable();
				new SilentBackgroundTask<Void>(context) {
					@Override
					protected Void doInBackground() throws Throwable {
						start();
						return null;
					}
				}.execute();
			}
		});
		startButton.setOnLongClickListener(stopQueueListener);

		repeatButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				RepeatMode repeatMode = getDownloadService().getRepeatMode().next();
				getDownloadService().setRepeatMode(repeatMode);
				switch (repeatMode) {
					case OFF:
						Util.toast(context, R.string.download_repeat_off);
						break;
					case ALL:
						Util.toast(context, R.string.download_repeat_all);
						break;
					case SINGLE:
						Util.toast(context, R.string.download_repeat_single);
						break;
					default:
						break;
				}
				updateRepeatButton();
				// Repeat decides whether the queue ends at all, and this can be tapped
				// while paused, when no progress tick would come along to notice.
				refreshQueueSummary();
				setControlsVisible(true);
			}
		});

		bookmarkButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				createBookmark();
				setControlsVisible(true);
			}
		});

		rateBadButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				DownloadService downloadService = getDownloadService();
				if(downloadService == null) {
					return;
				}
				downloadService.toggleRating(1);
				setControlsVisible(true);
			}
		});
		rateGoodButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				DownloadService downloadService = getDownloadService();
				if(downloadService == null) {
					return;
				}
				downloadService.toggleRating(5);
				setControlsVisible(true);
			}
		});

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
		    setPlaybackSpeed();
		} else {
			playbackSpeedButton.setVisibility(View.GONE);
		}

		toggleListButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				toggleFullscreenAlbumArt();
				setControlsVisible(true);
			}
		});

		View overlay = rootView.findViewById(R.id.download_overlay_buttons);
		final int overlayHeight = overlay != null ? overlay.getHeight() : -1;
		albumArtImageView.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (overlayHeight == -1 || lastY < (view.getBottom() - overlayHeight)) {
					toggleFullscreenAlbumArt();
					setControlsVisible(true);
				}
			}
		});

		progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(final SeekBar seekBar) {
				new SilentBackgroundTask<Void>(context) {
					@Override
					protected Void doInBackground() throws Throwable {
						getDownloadService().seekTo(progressBar.getProgress());
						return null;
					}

					@Override
					protected void done(Void result) {
						seekInProgress = false;
					}
				}.execute();
			}

			@Override
			public void onStartTrackingTouch(final SeekBar seekBar) {
				seekInProgress = true;
			}

			@Override
			public void onProgressChanged(final SeekBar seekBar, final int position, final boolean fromUser) {
				if (fromUser) {
					positionTextView.setText(Util.formatDuration(position / 1000));
					setControlsVisible(true);
				}
			}
		});

		return rootView;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		DownloadService downloadService = getDownloadService();
		if(Util.isOffline(context)) {
			menuInflater.inflate(R.menu.nowplaying_offline, menu);
		} else {
			menuInflater.inflate(R.menu.nowplaying, menu);
		}
		if(downloadService != null && downloadService.getSleepTimer()) {
			int timeRemaining = downloadService.getSleepTimeRemaining();
			timerMenu = menu.findItem(R.id.menu_toggle_timer);
			if(timeRemaining > 1){
				timerMenu.setTitle(context.getResources().getString(R.string.download_stop_time_remaining, Util.formatDuration(timeRemaining)));
			} else {
				timerMenu.setTitle(R.string.menu_set_timer);
			}
		}
		if(downloadService != null && downloadService.getKeepScreenOn()) {
			menu.findItem(R.id.menu_screen_on_off).setChecked(true);
		}

		// Nothing to navigate to before something is playing, or for a song the server gave
		// no album for - a stream, say. Hidden rather than left to fail silently on tap.
		MenuItem showAlbumItem = menu.findItem(R.id.menu_show_album);
		MenuItem showArtistItem = menu.findItem(R.id.menu_show_artist);
		if(showAlbumItem != null && showArtistItem != null) {
			DownloadFile current = (downloadService == null) ? null : downloadService.getCurrentPlaying();
			Entry currentSong = (current == null) ? null : current.getSong();
			boolean hasParent = currentSong != null && currentSong.getParent() != null
					&& !currentSong.getParent().isEmpty();

			showAlbumItem.setVisible(hasParent);
			showArtistItem.setVisible(hasParent);
		}

		loveMenuItem = menu.findItem(R.id.menu_love);
		if(loveMenuItem != null) {
			loveMenuItem.setVisible(LoveClient.isConfigured(context));
			applyLovedState();
			refreshLovedState();
		}
		if(downloadService != null && downloadService.isRemovePlayed()) {
			menu.findItem(R.id.menu_remove_played).setChecked(true);
		}

		boolean equalizerAvailable = downloadService != null && downloadService.getEqualizerAvailable();
		boolean isRemoteEnabled = downloadService != null && downloadService.isRemoteEnabled();
		if(equalizerAvailable && !isRemoteEnabled) {
			SharedPreferences prefs = Util.getPreferences(context);
			boolean equalizerOn = prefs.getBoolean(Constants.PREFERENCES_EQUALIZER_ON, false);
			if (equalizerOn && downloadService != null) {
				if(downloadService.getEqualizerController() != null && downloadService.getEqualizerController().isEnabled()) {
					menu.findItem(R.id.menu_equalizer).setChecked(true);
				}
			}
		} else {
			menu.removeItem(R.id.menu_equalizer);
		}

		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isRemoteEnabled) {
			playbackSpeedButton.setVisibility(View.GONE);
		} else {
			playbackSpeedButton.setVisibility(View.VISIBLE);
		}

		if(downloadService != null) {
			MenuItem mediaRouteItem = menu.findItem(R.id.menu_mediaroute);
			if(mediaRouteItem != null) {
				MediaRouteButton mediaRouteButton = (MediaRouteButton) MenuItemCompat.getActionView(mediaRouteItem);
				mediaRouteButton.setDialogFactory(new CustomMediaRouteDialogFactory());
				mediaRouteButton.setRouteSelector(downloadService.getRemoteSelector());
			}

			if(downloadService.isCurrentPlayingSingle()) {
				if(!Util.isOffline(context)) {
					menu.removeItem(R.id.menu_save_playlist);
				}

				menu.removeItem(R.id.menu_batch_mode);
				menu.removeItem(R.id.menu_remove_played);
			}
		}

		if(Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_BATCH_MODE, false)) {
			menu.findItem(R.id.menu_batch_mode).setChecked(true);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem menuItem) {
		if(menuItemSelected(menuItem.getItemId(), null)) {
			return true;
		}

		return super.onOptionsItemSelected(menuItem);
	}

	@Override
	public void onCreateContextMenu(Menu menu, MenuInflater menuInflater, UpdateView<DownloadFile> updateView, DownloadFile downloadFile) {
		if(Util.isOffline(context)) {
			menuInflater.inflate(R.menu.nowplaying_context_offline, menu);
		} else {
			menuInflater.inflate(R.menu.nowplaying_context, menu);
			menu.findItem(R.id.song_menu_star).setTitle(downloadFile.getSong().isStarred() ? R.string.common_unstar : R.string.common_star);
		}

		if (downloadFile.getSong().getParent() == null) {
			menu.findItem(R.id.menu_show_album).setVisible(false);
			menu.findItem(R.id.menu_show_artist).setVisible(false);
		}

		MenuUtil.hideMenuItems(context, menu, updateView);
	}

	@Override
	public boolean onContextItemSelected(MenuItem menuItem, UpdateView<DownloadFile> updateView, DownloadFile downloadFile) {
		if(onContextItemSelected(menuItem, downloadFile.getSong())) {
			return true;
		}

		return menuItemSelected(menuItem.getItemId(), downloadFile);
	}

	private boolean menuItemSelected(int menuItemId, final DownloadFile song) {
		List<Entry> songs;
		switch (menuItemId) {
			case R.id.menu_show_album: case R.id.menu_show_artist:
				// The queue's context menu passes the row that was long-pressed; the screen's
				// own overflow passes nothing and means whatever is playing.
				DownloadFile target = song;
				if(target == null) {
					DownloadService downloadService = getDownloadService();
					target = (downloadService == null) ? null : downloadService.getCurrentPlaying();
				}
				if(target == null) {
					return true;
				}
				Entry entry = target.getSong();

				Intent intent = new Intent(context, SubsonicFragmentActivity.class);
				intent.putExtra(Constants.INTENT_EXTRA_VIEW_ALBUM, true);
				String albumId;
				String albumName;
				if(menuItemId == R.id.menu_show_album) {
					if(Util.isTagBrowsing(context)) {
						albumId = entry.getAlbumId();
					} else {
						albumId = entry.getParent();
					}
					albumName = entry.getAlbum();
				} else {
					if(Util.isTagBrowsing(context)) {
						albumId = entry.getArtistId();
					} else {
						albumId = entry.getGrandParent();
						if(albumId == null) {
							intent.putExtra(Constants.INTENT_EXTRA_NAME_CHILD_ID, entry.getParent());
						}
					}
					albumName = entry.getArtist();
					intent.putExtra(Constants.INTENT_EXTRA_NAME_ARTIST, true);
				}
				intent.putExtra(Constants.INTENT_EXTRA_NAME_ID, albumId);
				intent.putExtra(Constants.INTENT_EXTRA_NAME_NAME, albumName);
				intent.putExtra(Constants.INTENT_EXTRA_FRAGMENT_TYPE, "Artist");

				if(Util.isOffline(context)) {
					try {
						// This should only be successful if this is a online song in offline mode
						Integer.parseInt(entry.getParent());
						String root = FileUtil.getMusicDirectory(context).getPath();
						String id = root + "/" + entry.getPath();
						id = id.substring(0, id.lastIndexOf("/"));
						if(menuItemId == R.id.menu_show_album) {
							intent.putExtra(Constants.INTENT_EXTRA_NAME_ID, id);
						}
						id = id.substring(0, id.lastIndexOf("/"));
						if(menuItemId != R.id.menu_show_album) {
							intent.putExtra(Constants.INTENT_EXTRA_NAME_ID, id);
							intent.putExtra(Constants.INTENT_EXTRA_NAME_NAME, entry.getArtist());
							intent.removeExtra(Constants.INTENT_EXTRA_NAME_CHILD_ID);
						}
					} catch(Exception e) {
						// Do nothing, entry.getParent() is fine
					}
				}

				intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
				Util.startActivityWithoutTransition(context, intent);
				return true;
			case R.id.menu_love:
				loveCurrentTrack();
				return true;
			case R.id.menu_lyrics_synced:
				toggleLyrics();
				return true;
			case R.id.menu_lyrics: {
				SubsonicFragment fragment = new LyricsFragment();
				Bundle args = new Bundle();
				args.putString(Constants.INTENT_EXTRA_NAME_ARTIST, song.getSong().getArtist());
				args.putString(Constants.INTENT_EXTRA_NAME_TITLE, song.getSong().getTitle());
				fragment.setArguments(args);

				replaceFragment(fragment);
				return true;
			}
			case R.id.menu_remove_all:
				Util.confirmDialog(context, R.string.download_menu_remove_all, "", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						new SilentBackgroundTask<Void>(context) {
							@Override
							protected Void doInBackground() throws Throwable {
								getDownloadService().setShufflePlayEnabled(false);
								getDownloadService().clear();
								return null;
							}

							@Override
							protected void done(Void result) {
								context.closeNowPlaying();
							}
						}.execute();
					}
				});
				return true;
			case R.id.menu_screen_on_off:
				if (getDownloadService().getKeepScreenOn()) {
					context.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
					getDownloadService().setKeepScreenOn(false);
				} else {
					context.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
					getDownloadService().setKeepScreenOn(true);
				}
				context.supportInvalidateOptionsMenu();
				return true;
			case R.id.menu_remove_played:
				if (getDownloadService().isRemovePlayed()) {
					getDownloadService().setRemovePlayed(false);
				} else {
					getDownloadService().setRemovePlayed(true);
				}
				context.supportInvalidateOptionsMenu();
				return true;
			case R.id.menu_shuffle:
				new SilentBackgroundTask<Void>(context) {
					@Override
					protected Void doInBackground() throws Throwable {
						getDownloadService().shuffle();
						return null;
					}

					@Override
					protected void done(Void result) {
						Util.toast(context, R.string.download_menu_shuffle_notification);
					}
				}.execute();
				return true;
			case R.id.menu_save_playlist:
				List<Entry> entries = new LinkedList<Entry>();
				for (DownloadFile downloadFile : getDownloadService().getSongs()) {
					entries.add(downloadFile.getSong());
				}
				createNewPlaylist(entries, true);
				return true;
			case R.id.menu_rate:
				UpdateHelper.setRating(context, song.getSong());
				return true;
			case R.id.menu_toggle_timer:
				if(getDownloadService().getSleepTimer()) {
					getDownloadService().stopSleepTimer();
					context.supportInvalidateOptionsMenu();
				} else {
					startTimer();
				}
				return true;
			case R.id.menu_info:
				displaySongInfo(song.getSong());
				return true;
			case R.id.menu_share:
				songs = new ArrayList<Entry>(1);
				songs.add(song.getSong());
				createShare(songs);
				return true;
			case R.id.menu_equalizer: {
				DownloadService downloadService = getDownloadService();
				if (downloadService != null) {
					EqualizerController controller = downloadService.getEqualizerController();
					if(controller != null) {
						SubsonicFragment fragment = new EqualizerFragment();
						replaceFragment(fragment);
						setControlsVisible(true);

						return true;
					}
				}

				// Any failed condition will get here
				Util.toast(context, "Failed to start equalizer.  Try restarting.");
				return true;
			}case R.id.menu_batch_mode:
				if(Util.isBatchMode(context)) {
					Util.setBatchMode(context, false);
					songListAdapter.notifyDataSetChanged();
				} else {
					Util.setBatchMode(context, true);
					songListAdapter.notifyDataSetChanged();
				}
				context.supportInvalidateOptionsMenu();

				return true;
			default:
				return false;
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		if(this.primaryFragment) {
			onResumeHandlers();
		} else {
			update();
		}
	}
	private void onResumeHandlers() {
		executorService = Executors.newSingleThreadScheduledExecutor();
		setControlsVisible(true);

		// Coming back to a track whose loved state was never settled - the retries were
		// cancelled on the way out - is worth one more look.
		if(currentLoved == null) {
			refreshLovedState();
		}

		final DownloadService downloadService = getDownloadService();
		if (downloadService == null || downloadService.getCurrentPlaying() == null || startFlipped) {
			playlistFlipper.setDisplayedChild(1);
			startFlipped = false;
		}
		if (downloadService != null && downloadService.getKeepScreenOn()) {
			context.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			context.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		updateButtons();

		if(currentPlaying == null && downloadService != null && currentPlaying == downloadService.getCurrentPlaying()) {
			getImageLoader().loadImage(albumArtImageView, (Entry) null, true, false);
		}

		context.runWhenServiceAvailable(new Runnable() {
			@Override
			public void run() {
				if (primaryFragment) {
					DownloadService downloadService = getDownloadService();
					downloadService.startRemoteScan();
					downloadService.addOnSongChangedListener(NowPlayingFragment.this, true);
				}
				updateRepeatButton();
				updateTitle();
			}
		});
	}

	@Override
	public void onStop() {
		super.onStop();
		onPauseHandlers();
	}
	private void onPauseHandlers() {
		// A pending love retry would otherwise fire against a screen that has gone.
		loveGeneration++;
		loveHandler.removeCallbacksAndMessages(null);

		if(executorService != null) {
			DownloadService downloadService = getDownloadService();
			if (downloadService != null) {
				downloadService.stopRemoteScan();
				downloadService.removeOnSongChangeListener(this);
			}
			playlistFlipper.setDisplayedChild(0);
		}
	}

	@Override
	public void setPrimaryFragment(boolean primary) {
		super.setPrimaryFragment(primary);
		if(rootView != null) {
			if(primary) {
				onResumeHandlers();
			} else {
				onPauseHandlers();
			}
		}
	}

	/**
	 * Now Playing hands its colour over by setNowPlayingBackdropColor instead. It comes to the
	 * front over the screen behind it without that screen ever leaving, and nothing tells
	 * that screen to hand its own wash back on collapse, so clearing it here would lose it.
	 */
	@Override
	protected void publishScreenBackdropColor() {
	}

	@Override
	public void setTitle(int title) {
		this.title = context.getResources().getString(title);
		if(this.primaryFragment) {
			context.setTitle(this.title);
		}
	}
	@Override
	public void setSubtitle(CharSequence title) {
		this.subtitle = title;
		if(this.primaryFragment) {
			context.setSubtitle(title);
		}
	}

	@Override
	public SectionAdapter getCurrentAdapter() {
		return songListAdapter;
	}

	private void scheduleHideControls() {
		if (hideControlsFuture != null) {
			hideControlsFuture.cancel(false);
		}

		final Handler handler = new Handler();
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				handler.post(new Runnable() {
					@Override
					public void run() {
						setControlsVisible(false);
					}
				});
			}
		};
		hideControlsFuture = executorService.schedule(runnable, 3000L, TimeUnit.MILLISECONDS);
	}

	private void setControlsVisible(boolean visible) {
		DownloadService downloadService = getDownloadService();
		if(downloadService != null && downloadService.isCurrentPlayingSingle()) {
			return;
		}

		try {
			long duration = 1700L;
			FadeOutAnimation.createAndStart(rootView.findViewById(R.id.download_overlay_buttons), !visible, duration);

			if (visible) {
				scheduleHideControls();
			}
		} catch(Exception e) {

		}
	}

	private void updateButtons() {
		if(context == null) {
			return;
		}

		if(Util.isOffline(context)) {
			bookmarkButton.setVisibility(View.GONE);
			rateBadButton.setVisibility(View.GONE);
			rateGoodButton.setVisibility(View.GONE);
		} else {
			if(ServerInfo.canBookmark(context)) {
				bookmarkButton.setVisibility(View.VISIBLE);
			} else {
				bookmarkButton.setVisibility(View.GONE);
			}
			rateBadButton.setVisibility(View.VISIBLE);
			rateGoodButton.setVisibility(View.VISIBLE);
		}

		// Resuming can land on either page, so the toggle's label is settled from
		// whatever is showing rather than assumed to be the artwork.
		updateToggleListDescription();
	}

	// Scroll to current playing/downloading.
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private void scrollToCurrent() {
		if (getDownloadService() == null || songListAdapter == null) {
			scrollWhenLoaded = true;
			return;
		}

		// Try to get position of current playing/downloading
		int position = songListAdapter.getItemPosition(currentPlaying);
		if(position == -1) {
			DownloadFile currentDownloading = getDownloadService().getCurrentDownloading();
			position = songListAdapter.getItemPosition(currentDownloading);
		}

		// If found, scroll to it
		if(position != -1) {
			// RecyclerView.scrollToPosition just puts it on the screen (ie: bottom if scrolled below it)
			LinearLayoutManager layoutManager = (LinearLayoutManager) playlistView.getLayoutManager();
			layoutManager.scrollToPositionWithOffset(position, 0);
		}
	}

	private void update() {
		if(startFlipped) {
			startFlipped = false;
			scrollToCurrent();
		}
	}

	protected void startTimer() {
		View dialogView = context.getLayoutInflater().inflate(R.layout.start_timer, null);

		// Setup length label
		final TextView lengthBox = (TextView) dialogView.findViewById(R.id.timer_length_label);
		final SharedPreferences prefs = Util.getPreferences(context);
		String lengthString = prefs.getString(Constants.PREFERENCES_KEY_SLEEP_TIMER_DURATION, "5");
		int length = Integer.parseInt(lengthString);
		lengthBox.setText(Util.formatDuration(length));

		// Setup length slider
		final SeekBar lengthBar = (SeekBar) dialogView.findViewById(R.id.timer_length_bar);
		lengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					int length = getMinutes(progress);
					lengthBox.setText(Util.formatDuration(length));
					seekBar.setProgress(progress);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
		lengthBar.setProgress(length - 1);

		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setTitle(R.string.menu_set_timer)
				.setView(dialogView)
				.setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						int length = getMinutes(lengthBar.getProgress());

						SharedPreferences.Editor editor = prefs.edit();
						editor.putString(Constants.PREFERENCES_KEY_SLEEP_TIMER_DURATION, Integer.toString(length));
						editor.commit();

						getDownloadService().setSleepTimerDuration(length);
						getDownloadService().startSleepTimer();
						context.supportInvalidateOptionsMenu();
					}
				})
				.setNegativeButton(R.string.common_cancel, null);
		AlertDialog dialog = builder.create();
		dialog.show();
	}

	private int getMinutes(int progress) {
		if(progress < 30) {
			return progress + 1;
		} else if(progress < 49) {
			return (progress - 30) * 5 + getMinutes(29);
		} else if(progress < 57) {
			return (progress - 48) * 30 + getMinutes(48);
		} else if(progress < 81) {
			return (progress - 56) * 60 + getMinutes(56);
		} else {
			return (progress - 80) * 150 + getMinutes(80);
		}
	}

	/**
	 * Ends playback and empties the queue with it, so nothing is left behind implying
	 * there is something to come back to: clearing the queue takes the mini player away
	 * as well, and the saved queue with it, since the service writes the empty one out.
	 * Shuffle play and radio are the service's own to turn off, which clearing does.
	 */
	private void stopQueue() {
		new SilentBackgroundTask<Void>(context) {
			@Override
			protected Void doInBackground() throws Throwable {
				DownloadService downloadService = getDownloadService();
				if(downloadService != null) {
					downloadService.clear();
				}
				return null;
			}

			@Override
			protected void done(Void result) {
				Util.toast(context, R.string.download_stopped);
			}
		}.execute();
	}

	/**
	 * Shows or hides the lyrics page. Lyrics live as a third page of the same flipper
	 * as the artwork and the queue, so the transport controls stay on screen.
	 */
	private void toggleLyrics() {
		if(lyricsContainer == null) {
			// Landscape has no lyrics page.
			Util.toast(context, R.string.download_lyrics_none);
			return;
		}

		if(playlistFlipper.getDisplayedChild() == PAGE_LYRICS) {
			playlistFlipper.setDisplayedChild(PAGE_ART);
			updateToggleListDescription();
			return;
		}

		playlistFlipper.setDisplayedChild(PAGE_LYRICS);
		updateToggleListDescription();
		loadLyrics();
	}

	/**
	 * Fetches lyrics for whatever is playing, unless they are already loaded. Failure
	 * and absence are treated the same: the page says so rather than erroring, because
	 * plenty of tracks simply have none.
	 */
	private void loadLyrics() {
		final DownloadFile playing = currentPlaying;
		final Entry song = (playing == null) ? null : playing.getSong();
		if(song == null || song.getId() == null) {
			showLyrics(null);
			return;
		}
		if(song.getId().equals(lyricsSongId)) {
			showLyrics(currentLyrics);
			return;
		}

		lyricsSongId = song.getId();
		currentLyrics = null;
		showLoadingLyrics();

		new SilentBackgroundTask<SyncedLyrics>(context) {
			@Override
			protected SyncedLyrics doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				return musicService.getSyncedLyrics(song, context);
			}

			@Override
			protected void done(SyncedLyrics result) {
				// A track change while this was in flight makes the result stale.
				if(!song.getId().equals(lyricsSongId)) {
					return;
				}

				currentLyrics = result;
				showLyrics(result);
			}

			@Override
			public void error(Throwable error) {
				Log.w(TAG, "Failed to load lyrics", error);
				if(song.getId().equals(lyricsSongId)) {
					currentLyrics = null;
					showLyrics(null);
				}
			}
		}.execute();
	}

	private void showLoadingLyrics() {
		if(lyricsContainer == null) {
			return;
		}
		lyricsContainer.removeAllViews();
		lyricsActiveIndex = -1;
		lyricsEmptyView.setText(R.string.progress_wait);
		lyricsEmptyView.setVisibility(View.VISIBLE);
	}

	private void showLyrics(SyncedLyrics lyrics) {
		if(lyricsContainer == null) {
			return;
		}

		lyricsContainer.removeAllViews();
		lyricsActiveIndex = -1;

		if(lyrics == null || lyrics.isEmpty()) {
			lyricsEmptyView.setText(R.string.download_lyrics_none);
			lyricsEmptyView.setVisibility(View.VISIBLE);
			return;
		}

		lyricsEmptyView.setVisibility(View.GONE);
		for(SyncedLyrics.Line line: lyrics.getLines()) {
			TextView view = new TextView(context);
			view.setTextAppearance(context, R.style.NowPlaying_LyricLine);
			// An empty stamp marks an instrumental gap; keep the spacing, drop nothing.
			view.setText(line.text);
			lyricsContainer.addView(view);
		}

		DownloadService downloadService = getDownloadService();
		if(downloadService != null) {
			updateLyricsPosition(downloadService.getPlayerPosition());
		}
	}

	/**
	 * Highlights the line for the current playback position and keeps it near the
	 * middle of the page. Does nothing unless the lyrics page is actually showing, so
	 * the common case costs one integer comparison per tick.
	 */
	private void updateLyricsPosition(int millisPlayed) {
		if(lyricsContainer == null || currentLyrics == null
				|| playlistFlipper.getDisplayedChild() != PAGE_LYRICS) {
			return;
		}

		int index = currentLyrics.indexAt(millisPlayed);
		if(index == lyricsActiveIndex || index < 0 || index >= lyricsContainer.getChildCount()) {
			return;
		}

		if(lyricsActiveIndex >= 0 && lyricsActiveIndex < lyricsContainer.getChildCount()) {
			styleLyricLine((TextView) lyricsContainer.getChildAt(lyricsActiveIndex), false);
		}

		final TextView active = (TextView) lyricsContainer.getChildAt(index);
		styleLyricLine(active, true);
		lyricsActiveIndex = index;

		lyricsScrollView.post(new Runnable() {
			@Override
			public void run() {
				int target = active.getTop() - (lyricsScrollView.getHeight() / 2) + (active.getHeight() / 2);
				lyricsScrollView.smoothScrollTo(0, Math.max(0, target));
			}
		});
	}

	private void styleLyricLine(TextView view, boolean active) {
		view.setTextColor(themeColor(active ? R.attr.npTextPrimary : R.attr.npTextTertiary));
		view.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
	}

	// Resolves a Now Playing colour attribute against the active theme, so the lyric
	// colours track the White theme's red the same way the styled text does.
	private int themeColor(int attr) {
		TypedValue value = new TypedValue();
		context.getTheme().resolveAttribute(attr, value, true);
		return value.data;
	}

	private void toggleFullscreenAlbumArt() {
		if (playlistFlipper.getDisplayedChild() == 1) {
			playlistFlipper.setInAnimation(AnimationUtils.loadAnimation(context, R.anim.push_down_in));
			playlistFlipper.setOutAnimation(AnimationUtils.loadAnimation(context, R.anim.push_down_out));
			playlistFlipper.setDisplayedChild(0);
		} else {
			scrollToCurrent();
			playlistFlipper.setInAnimation(AnimationUtils.loadAnimation(context, R.anim.push_up_in));
			playlistFlipper.setOutAnimation(AnimationUtils.loadAnimation(context, R.anim.push_up_out));
			playlistFlipper.setDisplayedChild(1);

			UpdateView.triggerUpdate();
		}

		// The subtitle says more on the queue than on the art, so it changes with the page.
		refreshQueueSummary();
		updateToggleListDescription();
	}

	/**
	 * The queue toggle's icon is the same whichever page is showing, so its spoken
	 * label has to carry which way the button goes. Called after every flip,
	 * including the ones that come from tapping the artwork rather than the button.
	 *
	 * Only the queue page sends the button back to the artwork; from the lyrics page
	 * it goes to the queue, the same as it does from the artwork itself. The label
	 * follows that rather than simply inverting the current page.
	 */
	private void updateToggleListDescription() {
		if(toggleListButton == null) {
			return;
		}

		boolean showingQueue = playlistFlipper.getDisplayedChild() == PAGE_PLAYLIST;
		toggleListButton.setContentDescription(context.getString(showingQueue
				? R.string.download_action_show_album_art
				: R.string.download_action_show_queue));
	}

	private void start() {
		DownloadService service = getDownloadService();
		PlayerState state = service.getPlayerState();
		if (state == PAUSED || state == COMPLETED || state == STOPPED) {
			service.start();
		} else if (state == STOPPED || state == IDLE) {
			warnIfStorageUnavailable();
			int current = service.getCurrentPlayingIndex();
			// TODO: Use play() method.
			if (current == -1) {
				service.play(0);
			} else {
				service.play(current);
			}
		}
	}

	private void changeProgress(final boolean rewind) {
		final DownloadService downloadService = getDownloadService();
		if(downloadService == null) {
			return;
		}

		new SilentBackgroundTask<Void>(context) {
			int seekTo;

			@Override
			protected Void doInBackground() throws Throwable {
				if(rewind) {
					seekTo = downloadService.rewind();
				} else {
					seekTo = downloadService.fastForward();
				}
				return null;
			}

			@Override
			protected void done(Void result) {
				progressBar.setProgress(seekTo);
			}
		}.execute();
	}

	private void createBookmark() {
		DownloadService downloadService = getDownloadService();
		if(downloadService == null) {
			return;
		}

		final DownloadFile currentDownload = downloadService.getCurrentPlaying();
		if(currentDownload == null) {
			return;
		}

		View dialogView = context.getLayoutInflater().inflate(R.layout.create_bookmark, null);
		final EditText commentBox = (EditText)dialogView.findViewById(R.id.comment_text);

		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setTitle(R.string.download_save_bookmark_title)
				.setView(dialogView)
				.setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						String comment = commentBox.getText().toString();

						createBookmark(currentDownload, comment);
					}
				})
				.setNegativeButton(R.string.common_cancel, null);
		AlertDialog dialog = builder.create();
		dialog.show();
	}
	private void createBookmark(final DownloadFile currentDownload, final String comment) {
		DownloadService downloadService = getDownloadService();
		if(downloadService == null) {
			return;
		}

		final Entry currentSong = currentDownload.getSong();
		final int position = downloadService.getPlayerPosition();
		final Bookmark oldBookmark = currentSong.getBookmark();
		currentSong.setBookmark(new Bookmark(position));
		bookmarkButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_menu_bookmark_selected));

		new SilentBackgroundTask<Void>(context) {
			@Override
			protected Void doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				musicService.createBookmark(currentSong, position, comment, context, null);

				new UpdateHelper.EntryInstanceUpdater(currentSong) {
					@Override
					public void update(Entry found) {
						found.setBookmark(new Bookmark(position));
					}
				}.execute();

				return null;
			}

			@Override
			protected void done(Void result) {
				Util.toast(context, R.string.download_save_bookmark);
				setControlsVisible(true);
			}

			@Override
			protected void error(Throwable error) {
				Log.w(TAG, "Failed to create bookmark", error);
				currentSong.setBookmark(oldBookmark);

				// If no bookmark at start, then return to no bookmark
				if(oldBookmark == null) {
					int bookmark;
					if(context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
						bookmark = R.drawable.ic_menu_bookmark_dark;
					} else {
						bookmark = DrawableTint.getDrawableRes(context, R.attr.bookmark);
					}
					bookmarkButton.setImageResource(bookmark);
				}

				String msg;
				if(error instanceof OfflineException || error instanceof ServerTooOldException) {
					msg = getErrorMessage(error);
				} else {
					msg = context.getResources().getString(R.string.download_save_bookmark_failed) + getErrorMessage(error);
				}

				Util.toast(context, msg, false);
			}
		}.execute();
	}

	@Override
	public boolean onDown(MotionEvent me) {
		setControlsVisible(true);
		return false;
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
		final DownloadService downloadService = getDownloadService();
		if (downloadService == null || e1 == null || e2 == null) {
			return false;
		}

		// Right to Left swipe
		int action = 0;
		if (e1.getX() - e2.getX() > swipeDistance && Math.abs(velocityX) > swipeVelocity) {
			action = ACTION_NEXT;
		}
		// Left to Right swipe
		else if (e2.getX() - e1.getX() > swipeDistance && Math.abs(velocityX) > swipeVelocity) {
			action = ACTION_PREVIOUS;
		}
		// Top to Bottom swipe
		else if (e2.getY() - e1.getY() > swipeDistance && Math.abs(velocityY) > swipeVelocity) {
			action = ACTION_FORWARD;
		}
		// Bottom to Top swipe
		else if (e1.getY() - e2.getY() > swipeDistance && Math.abs(velocityY) > swipeVelocity) {
			action = ACTION_REWIND;
		}

		if(action > 0) {
			final int performAction = action;
			warnIfStorageUnavailable();
			new SilentBackgroundTask<Void>(context) {
				@Override
				protected Void doInBackground() throws Throwable {
					switch(performAction) {
						case ACTION_NEXT:
							downloadService.next();
							break;
						case ACTION_PREVIOUS:
							downloadService.previous();
							break;
						case ACTION_FORWARD:
							downloadService.fastForward();
							break;
						case ACTION_REWIND:
							downloadService.rewind();
							break;
					}
					return null;
				}
			}.execute();

			return true;
		} else {
			return false;
		}
	}

	@Override
	public void onLongPress(MotionEvent e) {
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		return false;
	}

	@Override
	public void onShowPress(MotionEvent e) {
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		return false;
	}

	@Override
	public void onItemClicked(UpdateView<DownloadFile> updateView, final DownloadFile item) {
		warnIfStorageUnavailable();
		new SilentBackgroundTask<Void>(context) {
			@Override
			protected Void doInBackground() throws Throwable {
				getDownloadService().play(item);
				return null;
			}
		}.execute();
	}

	@Override
	public void onSongChanged(DownloadFile currentPlaying, int currentPlayingIndex, boolean shouldFastForward) {
		this.currentPlaying = currentPlaying;
		setupSubtitle(currentPlayingIndex);

		updateMediaButton(shouldFastForward);
		updateTitle();
		setPlaybackSpeed();
	}

	private void updateMediaButton(boolean shouldFastForward) {
		DownloadService downloadService = getDownloadService();
		if(downloadService.isCurrentPlayingSingle()) {
			previousButton.setVisibility(View.GONE);
			nextButton.setVisibility(View.GONE);
			rewindButton.setVisibility(View.GONE);
			fastforwardButton.setVisibility(View.GONE);
		} else {
			if (downloadService.shouldFastForward()) {
				previousButton.setVisibility(View.GONE);
				nextButton.setVisibility(View.GONE);

				rewindButton.setVisibility(View.VISIBLE);
				fastforwardButton.setVisibility(View.VISIBLE);
			} else {
				previousButton.setVisibility(View.VISIBLE);
				nextButton.setVisibility(View.VISIBLE);

				rewindButton.setVisibility(View.GONE);
				fastforwardButton.setVisibility(View.GONE);
			}
		}
	}

	private void setupSubtitle(int currentPlayingIndex) {
		if (currentPlaying != null) {
			Entry song = currentPlaying.getSong();
			songTitleTextView.setText(song.getTitle());
			setSongArtist(song.getArtist());
			setSongFormat(song);
			setSongStats(song);
			currentLoved = null;
			applyLovedState();
			refreshLovedState();
			getImageLoader().loadImage(albumArtImageView, song, true, true);

			this.currentPlayingIndex = currentPlayingIndex;
			DownloadService downloadService = getDownloadService();
			if(downloadService.isCurrentPlayingSingle()) {
				setSubtitle(null);
			} else if(downloadService.isShufflePlayEnabled()) {
				setSubtitle(context.getResources().getString(R.string.download_playerstate_playing_shuffle));
			} else if(downloadService.isArtistRadio()) {
				setSubtitle(context.getResources().getString(R.string.download_playerstate_playing_artist_radio));
			} else if(downloadService.isSonicRadio()) {
				setSubtitle(context.getResources().getString(R.string.download_playerstate_playing_sonic_radio));
			} else {
				setSubtitle(context.getResources().getString(R.string.download_playing_out_of, currentPlayingIndex + 1, currentPlayingSize));
			}
			refreshQueueSummary();
		} else {
			songTitleTextView.setText(null);
			setSongArtist(null);
			setSongFormat(null);
			setSongStats(null);
			getImageLoader().loadImage(albumArtImageView, (Entry) null, true, false);
			setSubtitle(null);
			currentPlayingIndex = -1;
			refreshQueueSummary();
		}
	}

	/**
	 * Keeps the line above the queue - "51 min left · ends 01:31" - current as the song
	 * plays out.
	 *
	 * Only writes when the text actually changed: this runs every second, but the minute
	 * it shows does not, and repainting for an identical string is churn for nothing.
	 * Hidden outright rather than left stale whenever the end cannot be worked out.
	 */
	private void refreshQueueSummary() {
		if(queueSummaryView == null) {
			return;
		}

		Integer remaining = queueRemainingSeconds(currentPlayingIndex);
		if(remaining == null) {
			queueSummaryView.setVisibility(View.GONE);
			lastQueueSummary = null;
			return;
		}

		String summary = context.getResources().getString(R.string.download_queue_summary,
				formatRemaining(remaining), formatEndTime(remaining));
		if(!summary.equals(lastQueueSummary)) {
			lastQueueSummary = summary;
			queueSummaryView.setText(summary);
		}
		queueSummaryView.setVisibility(View.VISIBLE);
	}

	/**
	 * How much of the queue is still to play, or null when that cannot be said.
	 *
	 * Null rather than a guess in three cases, because a wrong finishing time is worse
	 * than none: repeat makes the queue never end, a song of unknown length cannot be
	 * added up, and nothing can be totalled before the current song reports its duration.
	 */
	private Integer queueRemainingSeconds(int currentPlayingIndex) {
		DownloadService downloadService = getDownloadService();
		if(downloadService == null || currentPlayingIndex < 0) {
			return null;
		}
		// Repeat never ends, and the three self-feeding modes keep appending to the queue,
		// so what is currently in it is not what is left to play.
		if(downloadService.getRepeatMode() != RepeatMode.OFF || downloadService.isShufflePlayEnabled()
				|| downloadService.isArtistRadio() || downloadService.isCollectionAutoplay()) {
			return null;
		}

		Integer duration = downloadService.getPlayerDuration();
		if(duration == null || duration <= 0) {
			return null;
		}

		int remaining = Math.max(0, duration - downloadService.getPlayerPosition()) / 1000;
		List<DownloadFile> songs = downloadService.getSongs();
		for(int i = currentPlayingIndex + 1; i < songs.size(); i++) {
			Integer songDuration = songs.get(i).getSong().getDuration();
			if(songDuration == null || songDuration <= 0) {
				return null;
			}
			remaining += songDuration;
		}
		return remaining;
	}

	/** Rounded up to the minute: a queue with 40 seconds left has a minute left, not none. */
	private String formatRemaining(int seconds) {
		int minutes = (seconds + 59) / 60;
		if(minutes < 60) {
			return context.getResources().getString(R.string.download_queue_left_minutes, minutes);
		}
		return context.getResources().getString(R.string.download_queue_left_hours, minutes / 60, minutes % 60);
	}

	/** Local wall clock, in whichever of 12 or 24 hour the device is set to. */
	private String formatEndTime(int secondsFromNow) {
		return DateFormat.getTimeFormat(context)
				.format(new Date(System.currentTimeMillis() + secondsFromNow * 1000L));
	}

	/**
	 * The technical line: "FLAC · 16 bit · 44.1 kHz · 794 kbps".
	 *
	 * Every part is optional and simply left out when the server does not report it -
	 * Plex gives bit depth and sample rate, Subsonic gives neither - so this degrades
	 * to just the codec and bitrate rather than showing blanks.
	 */
	private static String formatFormatLine(Entry song) {
		if(song == null) {
			return null;
		}

		List<String> parts = new ArrayList<String>(4);

		String codec = song.getSuffix();
		if(codec == null || codec.isEmpty()) {
			// Falls back to the content type, whose second half is the codec name.
			String contentType = song.getContentType();
			if(contentType != null && contentType.contains("/")) {
				codec = contentType.substring(contentType.indexOf('/') + 1);
			}
		}
		if(codec != null && !codec.isEmpty()) {
			parts.add(codec.toUpperCase());
		}

		Integer bitDepth = song.getBitDepth();
		if(bitDepth != null && bitDepth > 0) {
			parts.add(bitDepth + " bit");
		}

		Integer samplingRate = song.getSamplingRate();
		if(samplingRate != null && samplingRate > 0) {
			parts.add(formatSampleRate(samplingRate));
		}

		Integer bitRate = song.getBitRate();
		if(bitRate != null && bitRate > 0) {
			parts.add(bitRate + " kbps");
		}

		if(parts.isEmpty()) {
			return null;
		}

		StringBuilder builder = new StringBuilder();
		for(String part: parts) {
			if(builder.length() > 0) {
				builder.append(" · ");
			}
			builder.append(part);
		}
		return builder.toString();
	}

	/** 44100 reads as "44.1 kHz", 48000 as "48 kHz" - no pointless trailing zero. */
	private static String formatSampleRate(int hz) {
		if(hz % 1000 == 0) {
			return (hz / 1000) + " kHz";
		}
		return String.format(Locale.US, "%.1f kHz", hz / 1000f);
	}

	/**
	 * "Played 12× · 3 weeks ago". Both halves are optional: a server that reports
	 * neither leaves the line hidden, and a track the server has never seen played
	 * says so rather than showing a zero.
	 */
	/**
	 * Asks the dashboard whether the playing track is already loved, so the button
	 * shows the right state without the user tapping it. Left alone on failure - an
	 * unknown state should not be drawn as "not loved".
	 */
	/**
	 * When to ask the dashboard again, in milliseconds after the song changed.
	 *
	 * Asking once at the change is asking at the worst moment: the dashboard polls Plex
	 * every few seconds, and resolves the loved flag itself in yet another background
	 * fetch, so a brand new track reliably answers "not loved" before anyone has looked.
	 * The first request also tells the dashboard someone is watching, which speeds its
	 * own polling up, so the later attempts are answered from a fresher view.
	 */
	private static final int[] LOVE_ATTEMPT_DELAYS = {0, 2000, 5000, 9000, 14000};

	/** Bumped on every song change so answers for a previous track are discarded. */
	private int loveGeneration;
	private final Handler loveHandler = new Handler();

	private void refreshLovedState() {
		if(loveMenuItem == null || !LoveClient.isConfigured(context)) {
			return;
		}

		Entry song = (currentPlaying != null) ? currentPlaying.getSong() : null;
		if(song == null) {
			return;
		}

		loveGeneration++;
		loveHandler.removeCallbacksAndMessages(null);
		queryLovedState(loveGeneration, song.getArtist(), song.getTitle(), 0);
	}

	/**
	 * Asks the dashboard, keeping the answer only if it names the song being played, and
	 * trying again until it does - or until it says loved, which cannot become truer.
	 */
	private void queryLovedState(final int generation, final String artist, final String title, final int attempt) {
		if(attempt >= LOVE_ATTEMPT_DELAYS.length || generation != loveGeneration) {
			return;
		}

		loveHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if(generation != loveGeneration) {
					return;
				}

				new SilentBackgroundTask<LoveClient.LoveState>(context) {
					@Override
					protected LoveClient.LoveState doInBackground() throws Throwable {
						return LoveClient.getCurrentState(context);
					}

					@Override
					protected void done(LoveClient.LoveState result) {
						if(generation != loveGeneration) {
							return;
						}

						if(result != null && result.isAbout(artist, title)) {
							currentLoved = result.isLoved();
							applyLovedState();
							if(result.isLoved()) {
								return;
							}
						}

						queryLovedState(generation, artist, title, attempt + 1);
					}
				}.execute();
			}
		}, LOVE_ATTEMPT_DELAYS[attempt]);
	}

	private void applyLovedState() {
		if(loveMenuItem == null) {
			return;
		}

		boolean loved = Boolean.TRUE.equals(currentLoved);
		loveMenuItem.setIcon(loved ? R.drawable.ic_love_filled : R.drawable.ic_love_outline);
		loveMenuItem.setTitle(loved ? R.string.download_loved : R.string.download_love);
	}

	private void loveCurrentTrack() {
		new SilentBackgroundTask<Boolean>(context) {
			@Override
			protected Boolean doInBackground() throws Throwable {
				return LoveClient.loveCurrent(context);
			}

			@Override
			protected void done(Boolean result) {
				if(Boolean.TRUE.equals(result)) {
					currentLoved = true;
					applyLovedState();
				} else {
					Util.toast(context, R.string.download_love_failed);
				}
			}
		}.execute();
	}

	private void setSongStats(Entry song) {
		if(songStatsTextView == null) {
			return;
		}

		String stats = formatStatsLine(song);
		songStatsTextView.setText(stats);
		songStatsTextView.setVisibility(stats == null ? View.GONE : View.VISIBLE);
	}

	private String formatStatsLine(Entry song) {
		if(song == null) {
			return null;
		}

		Integer playCount = song.getPlayCount();
		Long lastPlayed = song.getLastPlayed();
		if(playCount == null && lastPlayed == null) {
			return null;
		}

		if((playCount == null || playCount == 0) && lastPlayed == null) {
			return context.getResources().getString(R.string.download_stats_never);
		}

		StringBuilder builder = new StringBuilder();
		if(playCount != null && playCount > 0) {
			builder.append(playCount == 1
					? context.getResources().getString(R.string.download_stats_plays_once)
					: context.getResources().getString(R.string.download_stats_plays, playCount));
		}

		if(lastPlayed != null && lastPlayed > 0) {
			if(builder.length() > 0) {
				builder.append(" · ");
			}
			// Plex reports epoch seconds; DateUtils wants milliseconds.
			builder.append(DateUtils.getRelativeTimeSpanString(lastPlayed * 1000L,
					System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
		}

		return builder.length() == 0 ? null : builder.toString();
	}

	private void setSongFormat(Entry song) {
		renderSongFormat(song);
		refreshFormatIfIncomplete(song);
	}

	private void renderSongFormat(Entry song) {
		if(songFormatTextView == null) {
			return;
		}

		String format = formatFormatLine(song);
		songFormatTextView.setText(format);
		songFormatTextView.setVisibility(format == null ? View.GONE : View.VISIBLE);
	}

	/**
	 * Some listing endpoints - Plex's Hub-based ones especially, which back the home
	 * screen and search - answer with a trimmed copy that leaves out bit depth and
	 * sample rate, even though the server has them. Rather than let the format line
	 * quietly stay shorter than it should be, go back for the full track details
	 * whenever both are missing, and redraw the line if they come back.
	 *
	 * Tried at most once per song: the refetched copy is applied whether or not it
	 * actually had the fields, so a song the server truly has nothing more for is
	 * not asked about again every time this runs.
	 */
	private void refreshFormatIfIncomplete(final Entry song) {
		if(song == null || Util.isOffline(context) || formatRefreshAttempted.contains(song)
				|| (song.getBitDepth() != null && song.getBitDepth() > 0)
				|| (song.getSamplingRate() != null && song.getSamplingRate() > 0)) {
			return;
		}
		formatRefreshAttempted.add(song);

		new SilentBackgroundTask<Entry>(context) {
			@Override
			protected Entry doInBackground() throws Throwable {
				return MusicServiceFactory.getMusicService(context).getSongDetails(song, context);
			}

			@Override
			protected void done(Entry refreshed) {
				if(refreshed == null || currentPlaying == null || currentPlaying.getSong() != song) {
					return;
				}

				song.setBitDepth(refreshed.getBitDepth());
				song.setSamplingRate(refreshed.getSamplingRate());
				renderSongFormat(song);
			}

			@Override
			protected void error(Throwable error) {
				// Quiet: the format line simply stays as short as the listing had it.
			}
		}.execute();
	}

	/**
	 * "Album (year)", or just the album when the year is unknown. Null when there is
	 * no album to show, so the line collapses rather than sitting empty.
	 */
	private static String formatAlbumLine(Entry song) {
		if(song == null || song.getAlbum() == null || song.getAlbum().isEmpty()) {
			return null;
		}

		Integer year = song.getYear();
		if(year == null || year <= 0) {
			return song.getAlbum();
		}
		return song.getAlbum() + " (" + year + ")";
	}

	/**
	 * The artist line under the track title. Only the portrait layout carries this
	 * view, so landscape simply skips it.
	 */
	private void setSongArtist(String artist) {
		if(songArtistTextView == null) {
			return;
		}

		songArtistTextView.setText(artist);
		songArtistTextView.setVisibility((artist == null || artist.isEmpty()) ? View.GONE : View.VISIBLE);
	}

	/**
	 * Recolours the backdrop from the album art, so the screen takes on the mood of
	 * whatever is playing.
	 *
	 * The art is read back off the ImageView rather than passed in, because it
	 * arrives asynchronously and the view is the only place it is guaranteed to have
	 * landed. Anything unexpected - no art yet, a non-bitmap placeholder, no colour
	 * dark enough to sit text on - simply leaves the static gradient in place.
	 */
	private boolean updateBackdrop() {
		if(backdropView == null || albumArtImageView == null) {
			return false;
		}

		// The White theme keeps Now Playing light: leave the theme's light gradient in
		// place instead of tinting it dark from the art. Returning true stops the
		// per-tick retry, since there is nothing more to apply.
		if(ThemeUtil.THEME_WHITE.equals(ThemeUtil.getTheme(context))) {
			setToolbarBackdropColor(null);
			return true;
		}

		Integer wash = ArtworkColors.washOf(ArtworkColors.bitmapOf(albumArtImageView), 0.38f);
		if(wash == null) {
			return false;
		}

		backdropView.setBackground(ArtworkColors.backdropOf(wash));

		// The toolbar and status bar sit above this view, so they have to be told the
		// colour too or the wash starts with a black bar across the top of it.
		setToolbarBackdropColor(wash);
		return true;
	}

	/**
	 * Hands the backdrop's top colour to the activity, which owns the toolbar and the
	 * status bar band. Null puts both back to the theme's own colours - the White
	 * theme and anything without usable artwork keep the plain bar they had.
	 */
	private void setToolbarBackdropColor(Integer color) {
		if(context instanceof SubsonicFragmentActivity) {
			((SubsonicFragmentActivity) context).setNowPlayingBackdropColor(color);
		}
	}

	/**
	 * Derives the backdrop once per track, retrying on later ticks while the artwork
	 * is still loading. Called from the progress tick, which is already running every
	 * second; once the colour is applied this costs a string comparison.
	 */
	private void maybeUpdateBackdrop(DownloadFile playing) {
		String songId = (playing == null || playing.getSong() == null) ? null : playing.getSong().getId();
		if(songId == null || songId.equals(backdropSongId)) {
			return;
		}

		if(updateBackdrop()) {
			backdropSongId = songId;
		}
	}

	@Override
	public void onSongsChanged(List<DownloadFile> songs, DownloadFile currentPlaying, int currentPlayingIndex, boolean shouldFastForward) {
		currentPlayingSize = songs.size();

		DownloadService downloadService = getDownloadService();
		if(downloadService.isShufflePlayEnabled()) {
			emptyTextView.setText(R.string.download_shuffle_loading);
		}
		else {
			emptyTextView.setText(R.string.download_empty);
		}

		if(songListAdapter == null) {
			songList = new ArrayList<>();
			songList.addAll(songs);
			playlistView.setAdapter(songListAdapter = new DownloadFileAdapter(context, songList, NowPlayingFragment.this));
		} else {
			songList.clear();
			songList.addAll(songs);
			songListAdapter.notifyDataSetChanged();
		}

		emptyTextView.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);

		if(scrollWhenLoaded) {
			scrollToCurrent();
			scrollWhenLoaded = false;
		}

		if(this.currentPlaying != currentPlaying) {
			onSongChanged(currentPlaying, currentPlayingIndex, shouldFastForward);
			onMetadataUpdate(currentPlaying != null ? currentPlaying.getSong() : null, DownloadService.METADATA_UPDATED_ALL);
		} else {
			updateMediaButton(shouldFastForward);
			setupSubtitle(currentPlayingIndex);
		}

		if(downloadService.isCurrentPlayingSingle()) {
			toggleListButton.setVisibility(View.GONE);
			repeatButton.setVisibility(View.GONE);
		} else {
			toggleListButton.setVisibility(View.VISIBLE);
			repeatButton.setVisibility(View.VISIBLE);
		}
        setPlaybackSpeed();
	}

	@Override
	public void onSongProgress(DownloadFile currentPlaying, int millisPlayed, Integer duration, boolean isSeekable) {
		if (currentPlaying != null) {
			int millisTotal = duration == null ? 0 : duration;

			maybeUpdateBackdrop(currentPlaying);
			updateLyricsPosition(millisPlayed);
			refreshQueueSummary();

			positionTextView.setText(Util.formatDuration(millisPlayed / 1000));
			if(millisTotal > 0) {
				// Time left rather than total length: paired with the elapsed time on
				// the left, it answers "how much of this is still to come".
				int millisLeft = Math.max(0, millisTotal - millisPlayed);
				durationTextView.setText("-" + Util.formatDuration(millisLeft / 1000));
			} else {
				durationTextView.setText("-:--");
			}
			progressBar.setMax(millisTotal == 0 ? 100 : millisTotal); // Work-around for apparent bug.
			if(!seekInProgress) {
				progressBar.setProgress(millisPlayed);
			}
			progressBar.setEnabled(isSeekable);
		} else {
			positionTextView.setText("0:00");
			durationTextView.setText("-:--");
			progressBar.setProgress(0);
			progressBar.setEnabled(false);
		}

		DownloadService downloadService = getDownloadService();
		if(downloadService != null && downloadService.getSleepTimer() && timerMenu != null) {
			int timeRemaining = downloadService.getSleepTimeRemaining();
			if(timeRemaining > 1){
				timerMenu.setTitle(context.getResources().getString(R.string.download_stop_time_remaining, Util.formatDuration(timeRemaining)));
			} else {
				timerMenu.setTitle(R.string.menu_set_timer);
			}
		}
	}

	@Override
	public void onStateUpdate(DownloadFile downloadFile, PlayerState playerState) {
		switch (playerState) {
			case DOWNLOADING:
				if(currentPlaying != null) {
					if(Util.isWifiRequiredForDownload(context) || Util.isLocalNetworkRequiredForDownload(context)) {
						statusTextView.setText(context.getResources().getString(R.string.download_playerstate_mobile_disabled));
					} else {
						long bytes = currentPlaying.getPartialFile().length();
						statusTextView.setText(context.getResources().getString(R.string.download_playerstate_downloading, Util.formatLocalizedBytes(bytes, context)));
					}
				}
				break;
			case PREPARING:
				statusTextView.setText(R.string.download_playerstate_buffering);
				break;
			default:
				// Idle state: this is the third line under the artist, so it carries
				// the album and its year. The artist is not repeated here - it already
				// has a line of its own.
				if(currentPlaying != null) {
					statusTextView.setText(formatAlbumLine(currentPlaying.getSong()));
				} else {
					statusTextView.setText(null);
				}
				break;
		}

		switch (playerState) {
			case STARTED:
				pauseButton.setVisibility(View.VISIBLE);
				stopButton.setVisibility(View.INVISIBLE);
				startButton.setVisibility(View.INVISIBLE);
				break;
			case DOWNLOADING:
			case PREPARING:
				pauseButton.setVisibility(View.INVISIBLE);
				stopButton.setVisibility(View.VISIBLE);
				startButton.setVisibility(View.INVISIBLE);
				break;
			default:
				pauseButton.setVisibility(View.INVISIBLE);
				stopButton.setVisibility(View.INVISIBLE);
				startButton.setVisibility(View.VISIBLE);
				break;
		}
	}

	@Override
	public void onMetadataUpdate(Entry song, int fieldChange) {
		if(song != null && song.isStarred()) {
			starButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_toggle_star));
			starButton.setContentDescription(context.getString(R.string.common_unstar));
		} else {
			if(context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
				starButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.star_outline));
			} else {
				starButton.setImageResource(R.drawable.ic_toggle_star_outline_dark);
			}
			starButton.setContentDescription(context.getString(R.string.common_star));
		}

		int badRating, goodRating, bookmark;
		if(song != null && song.getRating() == 1) {
			rateBadButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_action_rating_bad_selected));
		} else {
			if(context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
				badRating = R.drawable.ic_action_rating_bad_dark;
			} else {
				badRating = DrawableTint.getDrawableRes(context, R.attr.rating_bad);
			}
			rateBadButton.setImageResource(badRating);
		}

		if(song != null && song.getRating() == 5) {
			rateGoodButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_action_rating_good_selected));
		} else {
			if(context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
				goodRating = R.drawable.ic_action_rating_good_dark;
			} else {
				goodRating = DrawableTint.getDrawableRes(context, R.attr.rating_good);
			}
			rateGoodButton.setImageResource(goodRating);
		}

		if(song != null && song.getBookmark() != null) {
			bookmarkButton.setImageDrawable(DrawableTint.getTintedDrawable(context, R.drawable.ic_menu_bookmark_selected));
		} else {
			if(context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
				bookmark = R.drawable.ic_menu_bookmark_dark;
			} else {
				bookmark = DrawableTint.getDrawableRes(context, R.attr.bookmark);
			}
			bookmarkButton.setImageResource(bookmark);
		}

		if(song != null && albumArtImageView != null && fieldChange == DownloadService.METADATA_UPDATED_COVER_ART) {
			getImageLoader().loadImage(albumArtImageView, song, true, true);
		}
	}

	public void updateRepeatButton() {
		DownloadService downloadService = getDownloadService();
		switch (downloadService.getRepeatMode()) {
			case OFF:
				repeatButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.media_button_repeat_off));
				repeatButton.setContentDescription(context.getString(R.string.download_repeat_off));
				break;
			case ALL:
				repeatButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.media_button_repeat_all));
				repeatButton.setContentDescription(context.getString(R.string.download_repeat_all));
				break;
			case SINGLE:
				repeatButton.setImageResource(DrawableTint.getDrawableRes(context, R.attr.media_button_repeat_single));
				repeatButton.setContentDescription(context.getString(R.string.download_repeat_single));
				break;
			default:
				break;
		}
	}
	private void updateTitle() {
		DownloadService downloadService = getDownloadService();
		float playbackSpeed = downloadService.getPlaybackSpeed();

		String title = context.getResources().getString(R.string.button_bar_now_playing);
		int stringRes = -1;
		if(playbackSpeed == 0.5f) {
			stringRes = R.string.download_playback_speed_half;
		} else if(playbackSpeed == 1.5f) {
			stringRes = R.string.download_playback_speed_one_half;
		} else if(playbackSpeed == 2.0f) {
			stringRes = R.string.download_playback_speed_double;
		} else if(playbackSpeed == 3.0f) {
			stringRes = R.string.download_playback_speed_tripple;
		}

		String playbackSpeedText = null;
		if(stringRes != -1) {
			playbackSpeedText = context.getResources().getString(stringRes);
		} else if(Math.abs(playbackSpeed - 1.0) > 0.01) {
			playbackSpeedText = Float.toString(playbackSpeed) + "x";
		}

		if(playbackSpeedText != null) {
			title += " (" + playbackSpeedText + ")";
		}
		setTitle(title);
	}

	@Override
	protected List<Entry> getSelectedEntries() {
		List<DownloadFile> selected = getCurrentAdapter().getSelected();
		List<Entry> entries = new ArrayList<>();

		for(DownloadFile downloadFile: selected) {
			if(downloadFile.getSong() != null) {
				entries.add(downloadFile.getSong());
			}
		}

		return entries;
	}

	private void setPlaybackSpeed() {
        if (playbackSpeedButton.getVisibility() == View.GONE)
            return;
        speed = new DroppySpeedControl(R.layout.set_playback_speed);
        DroppyMenuPopup.Builder builder = new DroppyMenuPopup.Builder(context,playbackSpeedButton);
        speed.setClickable(true);
        float playbackSpeed;

        playbackSpeed = getDownloadService() != null ? getDownloadService().getPlaybackSpeed() : 1.0f;

        final DroppyMenuPopup popup = builder.triggerOnAnchorClick(true).addMenuItem(speed).setPopupAnimation(new DroppyFadeInAnimation()).build();
        speed.setOnSeekBarChangeListener(context, new DroppyClickCallbackInterface() {
            @Override
            public void call(View v, int id) {
                SeekBar playbackSpeedBar = (SeekBar) v;
                int playbackSpeed = playbackSpeedBar.getProgress() +5 ;
                setPlaybackSpeed(playbackSpeed/10f);
            }
        },R.id.playback_speed_bar,R.id.playback_speed_label,playbackSpeed);
        speed.setOnClicks(context,
                new DroppyClickCallbackInterface() {
                    @Override
                    public void call(View v, int id) {
                        float playbackSpeed = 1.0f;
                        switch (id) {
                            case R.id.playback_speed_one_half:
                                playbackSpeed = 1.5f;
                                break;
                            case R.id.playback_speed_double:
                                playbackSpeed = 2.0f;
                                break;
                            case R.id.playback_speed_triple:
                                playbackSpeed = 3.0f;
                                break;
                            default:
                                break;
                        }
                        setPlaybackSpeed(playbackSpeed);
                        speed.updateSeekBar(playbackSpeed);
                        popup.dismiss(true);
                    }
                }
                ,R.id.playback_speed_normal,R.id.playback_speed_one_half,R.id.playback_speed_double,
                R.id.playback_speed_triple);
        speed.updateSeekBar(playbackSpeed);

    }
	private void setPlaybackSpeed(float playbackSpeed) {
		DownloadService downloadService = getDownloadService();
		if (downloadService == null) {
			return;
		}

		downloadService.setPlaybackSpeed(playbackSpeed);
		updateTitle();
	}
}
