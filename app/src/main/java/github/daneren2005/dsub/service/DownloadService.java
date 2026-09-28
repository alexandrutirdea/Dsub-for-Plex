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

 Copyright 2009 (C) Sindre Mehus
 */
package github.daneren2005.dsub.service;

import static androidx.mediarouter.media.MediaRouter.RouteInfo;
import static github.daneren2005.dsub.domain.PlayerState.COMPLETED;
import static github.daneren2005.dsub.domain.PlayerState.DOWNLOADING;
import static github.daneren2005.dsub.domain.PlayerState.IDLE;
import static github.daneren2005.dsub.domain.PlayerState.PAUSED;
import static github.daneren2005.dsub.domain.PlayerState.PAUSED_TEMP;
import static github.daneren2005.dsub.domain.PlayerState.PREPARED;
import static github.daneren2005.dsub.domain.PlayerState.PREPARING;
import static github.daneren2005.dsub.domain.PlayerState.STARTED;
import static github.daneren2005.dsub.domain.PlayerState.STOPPED;
import static github.daneren2005.dsub.domain.RemoteControlState.LOCAL;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.activity.SubsonicActivity;
import github.daneren2005.dsub.audiofx.AudioEffectsController;
import github.daneren2005.dsub.audiofx.AutoEqProfile;
import github.daneren2005.dsub.audiofx.DynamicsEqualizer;
import github.daneren2005.dsub.audiofx.EqualizerController;
import github.daneren2005.dsub.audiofx.LoudnessEnhancerController;
import github.daneren2005.dsub.domain.Bookmark;
import github.daneren2005.dsub.domain.InternetRadioStation;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.PlayerState;
import github.daneren2005.dsub.domain.PodcastEpisode;
import github.daneren2005.dsub.domain.RemoteControlState;
import github.daneren2005.dsub.domain.RepeatMode;
import github.daneren2005.dsub.domain.ServerInfo;
import github.daneren2005.dsub.service.player.CrossfadeEngine;
import github.daneren2005.dsub.service.player.ExoPlayerEngine;
import github.daneren2005.dsub.service.player.LocalPlayer;
import github.daneren2005.dsub.service.player.MediaPlayerEngine;
import github.daneren2005.dsub.service.player.OutputInfo;
import github.daneren2005.dsub.receiver.AudioNoisyReceiver;
import github.daneren2005.dsub.receiver.MediaButtonIntentReceiver;
import github.daneren2005.dsub.util.ArtistRadioBuffer;
import github.daneren2005.dsub.util.RadioBuffer;
import github.daneren2005.dsub.util.SonicRadioBuffer;
import github.daneren2005.dsub.util.CacheLayoutMigration;
import github.daneren2005.dsub.util.CompilationLayoutMigration;
import github.daneren2005.dsub.util.CollectionAutoplayBuffer;
import github.daneren2005.dsub.util.HeadphoneProfiles;
import github.daneren2005.dsub.util.ImageLoader;
import github.daneren2005.dsub.util.Notifications;
import github.daneren2005.dsub.util.SelfTitledArtCleanup;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.TrackEnvelope;
import github.daneren2005.dsub.util.TrackEnvelopeStore;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.MediaRouteManager;
import github.daneren2005.dsub.util.ShufflePlayBuffer;
import github.daneren2005.dsub.util.SimpleServiceBinder;
import github.daneren2005.dsub.util.UndersizedArtCleanup;
import github.daneren2005.dsub.util.UpdateHelper;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.dsub.util.compat.RemoteControlClientBase;
import github.daneren2005.dsub.util.tags.BastpUtil;
import github.daneren2005.dsub.view.UpdateView;
import github.daneren2005.serverproxy.BufferFile;
import github.daneren2005.serverproxy.BufferProxy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import android.util.Log;
import androidx.collection.LruCache;
import android.view.KeyEvent;
import androidx.core.content.ContextCompat;

/**
 * @author Sindre Mehus
 * @version $Id$
 */
public class DownloadService extends Service {
	private static final String TAG = DownloadService.class.getSimpleName();

	public static final String CMD_PLAY = "github.daneren2005.dsub.CMD_PLAY";
	public static final String CMD_TOGGLEPAUSE = "github.daneren2005.dsub.CMD_TOGGLEPAUSE";
	public static final String CMD_PAUSE = "github.daneren2005.dsub.CMD_PAUSE";
	public static final String CMD_STOP = "github.daneren2005.dsub.CMD_STOP";
	public static final String CMD_PREVIOUS = "github.daneren2005.dsub.CMD_PREVIOUS";
	public static final String CMD_NEXT = "github.daneren2005.dsub.CMD_NEXT";
	public static final String CANCEL_DOWNLOADS = "github.daneren2005.dsub.CANCEL_DOWNLOADS";
	public static final String START_PLAY = "github.daneren2005.dsub.START_PLAYING";
	private static final long DEFAULT_DELAY_UPDATE_PROGRESS = 1000L;
	private static final double DELETE_CUTOFF = 0.84;
	private static final int REMOTE_PLAYLIST_PREV = 10;
	private static final int REMOTE_PLAYLIST_NEXT = 40;
	private static final int SHUFFLE_MODE_NONE = 0;
	private static final int SHUFFLE_MODE_ALL = 1;
	private static final int SHUFFLE_MODE_ARTIST = 2;
	private static final int SHUFFLE_MODE_COLLECTION = 3;
	private static final int SHUFFLE_MODE_SONIC = 4;
	/**
	 * How long to wait for the playback engine to be built before giving up on it. Long
	 * enough that a slow cold start still restores a playing queue, short enough that a
	 * thread is never held for good by an engine that failed to appear.
	 */
	private static final int PLAYER_BUILD_TIMEOUT_SECONDS = 10;

	public static final int METADATA_UPDATED_ALL = 0;
	public static final int METADATA_UPDATED_STAR = 1;
	public static final int METADATA_UPDATED_RATING = 2;
	public static final int METADATA_UPDATED_BOOKMARK = 4;
	public static final int METADATA_UPDATED_COVER_ART = 8;

	private RemoteControlClientBase mRemoteControl;

	private final IBinder binder = new SimpleServiceBinder<>(this);
	private Looper mediaPlayerLooper;
	/**
	 * The engine, or null until the thread that builds it has got that far.
	 *
	 * The queue is read back off disk on its own thread as the service starts, and reaches
	 * playback control before that has happened. Nothing to control yet is not an error -
	 * there is no track loaded and nothing queued behind it - so the calls that can arrive
	 * that early check rather than assume.
	 *
	 * Volatile because it is written on the thread named after this service and read on
	 * every other one that controls playback. Without it a reader has no guarantee of ever
	 * seeing the engine appear, and {@link #playerBuilt} below is what a caller that
	 * cannot carry on without one waits on.
	 */
	private volatile LocalPlayer player;
	/**
	 * Counted down once {@link #player} has been built and is safe to read.
	 *
	 * Only {@link #restore} waits on it: restoring a queue that was playing has to hand a
	 * track to an engine, and there is no engine for the first moments of the service's
	 * life. Everything else on that path treats a null engine as nothing to control.
	 */
	private final CountDownLatch playerBuilt = new CountDownLatch(1);
	/** How {@link #player} was built, so a changed setting can be seen for what it is. */
	private String playerConfig;
	private int audioSessionId;
	private final List<DownloadFile> downloadList = new ArrayList<DownloadFile>();
	private final List<DownloadFile> backgroundDownloadList = new ArrayList<DownloadFile>();
	private final List<DownloadFile> toDelete = new ArrayList<DownloadFile>();
	private final Handler handler = new Handler();
	private Handler mediaPlayerHandler;
	private final DownloadServiceLifecycleSupport lifecycleSupport = new DownloadServiceLifecycleSupport(this);
	private ShufflePlayBuffer shufflePlayBuffer;
	private ArtistRadioBuffer artistRadioBuffer;
	private SonicRadioBuffer sonicRadioBuffer;
	/** How many tracks ahead one replay gain request covers. */
	private static final int GAIN_WINDOW = 10;
	/** Songs the server has already been asked about, so a silent answer is not asked twice. */
	private final Set<String> gainDetailsAsked = Collections.synchronizedSet(new HashSet<String>());
	private CollectionAutoplayBuffer collectionAutoplayBuffer;

	private final LruCache<MusicDirectory.Entry, DownloadFile> downloadFileCache = new LruCache<MusicDirectory.Entry, DownloadFile>(100);
	private final List<DownloadFile> cleanupCandidates = new ArrayList<DownloadFile>();
	private final Scrobbler scrobbler = new Scrobbler();
	private RemoteController remoteController;
	private DownloadFile currentPlaying;
	private int currentPlayingIndex = -1;
	private DownloadFile nextPlaying;
	private DownloadFile currentDownloading;
	private SilentBackgroundTask bufferTask;
	private SilentBackgroundTask nextPlayingTask;
	private PlayerState playerState = IDLE;
	/** The equalizer effect was refused even by a rebuilt player, so asking again only costs playback. */
	private boolean equalizerRebuildFailed;
	private PlayerState nextPlayerState = IDLE;
	private boolean removePlayed;
	private boolean shufflePlay;
	private boolean artistRadio;
	private boolean sonicRadio;
	private boolean collectionAutoplay;
	/**
	 * Set when playback ran off the end of the queue. Distinguishes "finished" from a
	 * queue that is merely idle - a restored one that has not been started yet - which
	 * the player state alone cannot tell apart, since both are IDLE.
	 */
	private boolean queueEnded;
	private final CopyOnWriteArrayList<OnSongChangedListener> onSongChangedListeners = new CopyOnWriteArrayList<>();
	private long revision;
	private static DownloadService instance;
	private String suggestedPlaylistName;
	private String suggestedPlaylistId;
	private PowerManager.WakeLock wakeLock;
	/**
	 * Held for as long as a remote device is playing, which is the job {@link #player}
	 * does for itself through setWakeMode when the music is coming out of this phone.
	 *
	 * Kept apart from {@link #wakeLock} rather than sharing it: that one is not reference
	 * counted and is taken with a timeout on every track that finishes, and a timed acquire
	 * of a lock already held schedules the release regardless - so a track boundary would
	 * quietly put a thirty second life on a hold that is meant to last the whole cast.
	 */
	private PowerManager.WakeLock remoteWakeLock;
	private WifiManager.WifiLock wifiLock;
	private boolean keepScreenOn;
	private int cachedPosition = 0;
	private boolean downloadOngoing = false;
	private float volume = 1.0f;
	private long delayUpdateProgress = DEFAULT_DELAY_UPDATE_PROGRESS;
	private static final long PLAYBACK_REPORT_INTERVAL = 10000L;
	private long lastPlaybackReport = 0;
	private boolean foregroundService = false;

	private AudioEffectsController effectsController;
	/** Follows the headphones rather than the music; null before Android 9. */
	private DynamicsEqualizer dynamicsEqualizer;
	private AudioDeviceCallback audioDeviceCallback;
	private RemoteControlState remoteState = LOCAL;
	private PositionCache positionCache;
	private BufferProxy proxy;

	private Timer sleepTimer;
	private int timerDuration;
	private long timerStart;
	private boolean autoPlayStart = false;
	private boolean runListenersOnInit = false;

	private IntentFilter audioNoisyIntent = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
	private AudioNoisyReceiver audioNoisyReceiver = null;

	private MediaRouteManager mediaRouter;
	
	// Variables to manage getCurrentPosition sometimes starting from an arbitrary non-zero number
	private long subtractNextPosition = 0;
	private int subtractPosition = 0;

	/**
	 * What the current track's completion and error handling needs to know about it.
	 * Null when no track is loaded, which is what tells the engine's callbacks apart from
	 * a stray one arriving after the player was reset.
	 */
	private TrackHandlers trackHandlers;
	/** What {@link #doPlay} is waiting to do once the engine reports the track ready. */
	private PendingPlay pendingPlay;
	/** The song handed to the engine as the successor, for when it reports it ready. */
	private DownloadFile nextTrackFile;

	/**
	 * Reference to precreated BASTP Object
	 */
	private BastpUtil mBastpUtil;

	@Override
	public void onCreate() {
		super.onCreate();

		final SharedPreferences prefs = Util.getPreferences(this);
		new Thread(new Runnable() {
			public void run() {
				Looper.prepare();

				mBastpUtil = new BastpUtil();

				// We want to change audio session id's between upgrading Android versions.  Upgrading to Android 7.0 is broken (probably updated session id format)
				int id = prefs.getInt(Constants.CACHE_AUDIO_SESSION_ID, -1);
				int versionCode = prefs.getInt(Constants.CACHE_AUDIO_SESSION_VERSION_CODE, -1);
				int preferredSessionId = (versionCode == Build.VERSION.SDK_INT) ? id : -1;

				try {
					player = createPlayer(preferredSessionId);
					player.setListener(playerListener);
					audioSessionId = player.getAudioSessionId();
				} finally {
					// In a finally so that a build which threw releases whoever is waiting
					// on it at once, rather than leaving them to time out.
					playerBuilt.countDown();
				}

				if(audioSessionId != -1 && audioSessionId != preferredSessionId) {
					SharedPreferences.Editor editor = prefs.edit();
					editor.putInt(Constants.CACHE_AUDIO_SESSION_ID, audioSessionId);
					editor.putInt(Constants.CACHE_AUDIO_SESSION_VERSION_CODE, Build.VERSION.SDK_INT);
					editor.commit();
				}

				/*try {
					Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
					i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
					i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
					sendBroadcast(i);
				} catch(Throwable e) {
					// Froyo or lower
				}*/

				effectsController = new AudioEffectsController(DownloadService.this, audioSessionId);
				if(prefs.getBoolean(Constants.PREFERENCES_EQUALIZER_ON, false)) {
					getEqualizerController();
				}

				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
					dynamicsEqualizer = new DynamicsEqualizer(DownloadService.this, audioSessionId);
					applyHeadphoneEqualizer();
				}

				mediaPlayerLooper = Looper.myLooper();
				mediaPlayerHandler = new Handler(mediaPlayerLooper);

				if(runListenersOnInit) {
					onSongsChanged();
					onSongProgress();
					onStateUpdate();
				}

				Looper.loop();
			}
		}, "DownloadService").start();

		Util.registerMediaButtonEventReceiver(this);
		audioNoisyReceiver = new AudioNoisyReceiver();
		ContextCompat.registerReceiver(this, audioNoisyReceiver, audioNoisyIntent, ContextCompat.RECEIVER_NOT_EXPORTED);
		registerAudioDeviceCallback();

		if (mRemoteControl == null) {
			// Use the remote control APIs (if available) to set the playback state
			mRemoteControl = RemoteControlClientBase.createInstance();
			ComponentName mediaButtonReceiverComponent = new ComponentName(getPackageName(), MediaButtonIntentReceiver.class.getName());
			mRemoteControl.register(this, mediaButtonReceiverComponent);
		}

		PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
		wakeLock.setReferenceCounted(false);
		remoteWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName() + ".remote");
		remoteWakeLock.setReferenceCounted(false);

		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "downloadServiceLock");

		try {
			timerDuration = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_SLEEP_TIMER_DURATION, "5"));
		} catch(Throwable e) {
			timerDuration = 5;
		}
		sleepTimer = null;

		keepScreenOn = prefs.getBoolean(Constants.PREFERENCES_KEY_KEEP_SCREEN_ON, false);

		mediaRouter = new MediaRouteManager(this);

		instance = this;
		CacheLayoutMigration.runIfNeeded(this);
		CompilationLayoutMigration.runIfNeeded(this);
		UndersizedArtCleanup.runIfNeeded(this);
		SelfTitledArtCleanup.runIfNeeded(this);
		shufflePlayBuffer = new ShufflePlayBuffer(this);
		artistRadioBuffer = new ArtistRadioBuffer(this);
		sonicRadioBuffer = new SonicRadioBuffer(this);
		collectionAutoplayBuffer = new CollectionAutoplayBuffer(this);
		lifecycleSupport.onCreate();

		if(Build.VERSION.SDK_INT >= 26) {
			Notifications.shutGoogleUpNotification(this);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);
		lifecycleSupport.onStart(intent);

		String action = intent.getAction();
		if(Build.VERSION.SDK_INT >= 26 && !this.isForeground() && !"KEYCODE_MEDIA_START".equals(action)) {
			Notifications.shutGoogleUpNotification(this);
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onTrimMemory(int level) {
		ImageLoader imageLoader = SubsonicActivity.getStaticImageLoader(this);
		if(imageLoader != null) {
			Log.i(TAG, "Memory Trim Level: " + level);
			if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
				if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
					imageLoader.onLowMemory(0.75f);
				} else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
					imageLoader.onLowMemory(0.50f);
				} else if(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
					imageLoader.onLowMemory(0.25f);
				}
			} else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
				imageLoader.onLowMemory(0.25f);
			} else if(level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
				imageLoader.onLowMemory(0.75f);
			}
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		instance = null;

		if(currentPlaying != null) currentPlaying.setPlaying(false);
		// Nothing else lets this go once the service is gone, and what it holds onto is
		// the processor.
		if(remoteWakeLock != null && remoteWakeLock.isHeld()) {
			remoteWakeLock.release();
		}
		if(sleepTimer != null){
			sleepTimer.cancel();
			sleepTimer.purge();
		}
		lifecycleSupport.onDestroy();

		try {
			Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
			i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
			i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
			sendBroadcast(i);
		} catch(Throwable e) {
			// Froyo or lower
		}

		// All three are built on the thread named after this service, which the service
		// can be destroyed ahead of - a bind and unbind that never got as far as playing
		// is enough. Nothing to tear down is a state this passes through, not an error.
		if(player != null) {
			player.release();
		}
		if(mediaPlayerLooper != null) {
			mediaPlayerLooper.quit();
		}
		shufflePlayBuffer.shutdown();
		collectionAutoplayBuffer.shutdown();
		if(effectsController != null) {
			effectsController.release();
		}
		unregisterAudioDeviceCallback();
		if(dynamicsEqualizer != null) {
			dynamicsEqualizer.release();
			dynamicsEqualizer = null;
		}
		if (mRemoteControl != null) {
			mRemoteControl.unregister(this);
			mRemoteControl = null;
		}

		if(bufferTask != null) {
			bufferTask.cancel();
			bufferTask = null;
		}
		if(nextPlayingTask != null) {
			nextPlayingTask.cancel();
			nextPlayingTask = null;
		}
		if(remoteController != null) {
			remoteController.stop();
			remoteController.shutdown();
		}
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
		if (audioNoisyReceiver != null) {
			unregisterReceiver(audioNoisyReceiver);
		}
		mediaRouter.destroy();
		Notifications.hidePlayingNotification(this, this, handler);
		Notifications.hideDownloadingNotification(this, this, handler);
	}

	public static void startService(Context context) {
		startService(context, new Intent(context, DownloadService.class));
	}
	public static void startService(Context context, Intent intent) {
		// startForegroundService() obligates us to call startForeground() within
		// 5s, but Android 12+ refuses that call when the app is in the
		// background -- which crashes the service either way. While we are in
		// the foreground a plain start carries no such obligation, so prefer it.
		//
		// (The old check asked isIgnoringBatteryOptimizations(intent.getPackage()),
		// but a component Intent has no package, so it always took the
		// foreground branch.)
		if (Build.VERSION.SDK_INT < 26 || Util.isAppInForeground(context)) {
			context.startService(intent);
		} else {
			context.startForegroundService(intent);
		}
	}
	public static DownloadService getInstance() {
		return instance;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}
	
	public void post(Runnable r) {
		handler.post(r);
	}
	public void postDelayed(Runnable r, long millis) {
		handler.postDelayed(r, millis);
	}

	public synchronized void download(InternetRadioStation station) {
		clear();
		download(Arrays.asList((MusicDirectory.Entry) station), false, true, false, false);
	}
	public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoplay, boolean playNext, boolean shuffle) {
		download(songs, save, autoplay, playNext, shuffle, 0, 0);
	}
	public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoplay, boolean playNext, boolean shuffle, int start, int position) {
		setShufflePlayEnabled(false);
		setArtistRadio(null);
		setSonicRadio(null);
		setCollectionAutoplay(null, null, null);
		int offset = 1;
		boolean noNetwork = !Util.isOffline(this) && !Util.isNetworkConnected(this);
		boolean warnNetwork = false;

		if (songs.isEmpty()) {
			return;
		} else if(isCurrentPlayingSingle()) {
			clear();
		}

		if (playNext) {
			if (autoplay && getCurrentPlayingIndex() >= 0) {
				offset = 0;
			}
			for (MusicDirectory.Entry song : songs) {
				if(song != null) {
					DownloadFile downloadFile = new DownloadFile(this, song, save);
					addToDownloadList(downloadFile, getCurrentPlayingIndex() + offset);
					if(noNetwork && !warnNetwork) {
						if(!downloadFile.isCompleteFileAvailable()) {
							warnNetwork = true;
						}
					}
					offset++;
				}
			}

			if(remoteState == LOCAL || (remoteController != null && remoteController.isNextSupported())) {
				setNextPlaying();
			}
		} else {
			int size = size();
			int index = getCurrentPlayingIndex();
			for (MusicDirectory.Entry song : songs) {
				if(song == null) {
					continue;
				}

				DownloadFile downloadFile = new DownloadFile(this, song, save);
				addToDownloadList(downloadFile, -1);
				if(noNetwork && !warnNetwork) {
					if(!downloadFile.isCompleteFileAvailable()) {
						warnNetwork = true;
					}
				}
			}
			if(!autoplay && (size - 1) == index) {
				setNextPlaying();
			}
		}
		revision++;
		onSongsChanged();
		updateRemotePlaylist();

		if(shuffle) {
			shuffle();
		}
		if(warnNetwork) {
			Util.toast(this, R.string.select_album_no_network);
		}

		if (autoplay) {
			play(start, true, position);
		} else if(start != 0 || position != 0) {
			play(start, false, position);
		} else {
			if (currentPlaying == null) {
				currentPlaying = downloadList.get(0);
				currentPlayingIndex = 0;
				currentPlaying.setPlaying(true);
			} else {
				currentPlayingIndex = downloadList.indexOf(currentPlaying);
			}
			checkDownloads();
		}
		lifecycleSupport.serializeDownloadQueue();
	}
	private void addToDownloadList(DownloadFile file, int offset) {
		if(offset == -1) {
			downloadList.add(file);
		} else {
			downloadList.add(offset, file);
		}
	}
	public synchronized void downloadBackground(List<MusicDirectory.Entry> songs, boolean save) {
		for (MusicDirectory.Entry song : songs) {
			DownloadFile downloadFile = new DownloadFile(this, song, save);
			if(!downloadFile.isWorkDone() || (downloadFile.shouldSave() && !downloadFile.isSaved())) {
				// Only add to list if there is work to be done
				backgroundDownloadList.add(downloadFile);
			} else if(downloadFile.isSaved() && !save) {
				// Quickly unpin song instead of adding it to work to be done
				downloadFile.unpin();
			}
		}
		revision++;

		if(!Util.isOffline(this) && !Util.isNetworkConnected(this)) {
			Util.toast(this, R.string.select_album_no_network);
		}

		checkDownloads();
		lifecycleSupport.serializeDownloadQueue();
	}

	private synchronized void updateRemotePlaylist() {
		List<DownloadFile> playlist = new ArrayList<>();
		if(currentPlaying != null) {
			int startIndex = downloadList.indexOf(currentPlaying) - REMOTE_PLAYLIST_PREV;
			if(startIndex < 0) {
				startIndex = 0;
			}

			int size = size();
			int endIndex = downloadList.indexOf(currentPlaying) + REMOTE_PLAYLIST_NEXT;
			if(endIndex > size) {
				endIndex = size;
			}
			for(int i = startIndex; i < endIndex; i++) {
				playlist.add(downloadList.get(i));
			}
		}

		if (remoteState != LOCAL && remoteController != null) {
			remoteController.updatePlaylist();
		}
		mRemoteControl.updatePlaylist(playlist);
	}

	public synchronized void restore(List<MusicDirectory.Entry> songs, List<MusicDirectory.Entry> toDelete, int currentPlayingIndex, int currentPlayingPosition, boolean queueEnded) {
		SharedPreferences prefs = Util.getPreferences(this);
		RemoteControlState newState = RemoteControlState.values()[prefs.getInt(Constants.PREFERENCES_KEY_CONTROL_MODE, 0)];
		if(newState != LOCAL) {
			String id = prefs.getString(Constants.PREFERENCES_KEY_CONTROL_ID, null);
			setRemoteState(newState, null, id);
		}
		if(prefs.getBoolean(Constants.PREFERENCES_KEY_REMOVE_PLAYED, false)) {
			removePlayed = true;
		}
		int startShufflePlay = prefs.getInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, SHUFFLE_MODE_NONE);
		download(songs, false, false, false, false);
		if(startShufflePlay != SHUFFLE_MODE_NONE) {
			if(startShufflePlay == SHUFFLE_MODE_ALL) {
				shufflePlay = true;
			} else if(startShufflePlay == SHUFFLE_MODE_ARTIST) {
				artistRadio = true;
				artistRadioBuffer.restoreArtist(prefs.getString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, null));
			} else if(startShufflePlay == SHUFFLE_MODE_SONIC) {
				sonicRadio = true;
				sonicRadioBuffer.restoreSeed(prefs.getString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, null));
			} else if(startShufflePlay == SHUFFLE_MODE_COLLECTION) {
				collectionAutoplay = true;
				collectionAutoplayBuffer.restoreCollection(prefs.getString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, null),
						prefs.getString(Constants.PREFERENCES_KEY_COLLECTION_AUTOPLAY_NAME, null));
			}
			SharedPreferences.Editor editor = prefs.edit();
			editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, startShufflePlay);
			editor.commit();
		}
		if (currentPlayingIndex != -1) {
			// Restoring a queue that was playing means handing a track to an engine, and
			// the thread that builds one may not have got there yet. Waiting is bounded:
			// an engine that has not appeared by now is not going to, and a queue restored
			// but left stopped beats holding this thread for good.
			if(awaitPlayer()) {
				play(currentPlayingIndex, autoPlayStart, currentPlayingPosition);
				autoPlayStart = false;
				// play() above always clears queueEnded since the index is valid; restore
				// the persisted finished state so a queue that ended before the app closed
				// doesn't reappear as if it were still playable.
				this.queueEnded = queueEnded;
			} else {
				Log.w(TAG, "Gave up waiting for the playback engine; the queue is restored but stopped");
			}
		}

		if(toDelete != null) {
			for(MusicDirectory.Entry entry: toDelete) {
				this.toDelete.add(forSong(entry));
			}
		}
		
		suggestedPlaylistName = prefs.getString(Constants.PREFERENCES_KEY_PLAYLIST_NAME, null);
		suggestedPlaylistId = prefs.getString(Constants.PREFERENCES_KEY_PLAYLIST_ID, null);
	}

	/**
	 * Waits for {@link #player} to be built, for callers that have nothing to do without
	 * one. Returns false if it never turned up, which leaves the caller to carry on
	 * without playback rather than wait on a thread that is not coming.
	 */
	private boolean awaitPlayer() {
		try {
			return playerBuilt.await(PLAYER_BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS) && player != null;
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public boolean isInitialized() {
		return lifecycleSupport != null && lifecycleSupport.isInitialized();
	}

	public synchronized Date getLastStateChanged() {
		return lifecycleSupport.getLastChange();
	}

	public synchronized void setRemovePlayed(boolean enabled) {
		removePlayed = enabled;
		if(removePlayed) {
			checkDownloads();
			lifecycleSupport.serializeDownloadQueue();
		}
		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putBoolean(Constants.PREFERENCES_KEY_REMOVE_PLAYED, enabled);
		editor.commit();
	}
	public boolean isRemovePlayed() {
		return removePlayed;
	}

	public synchronized void setShufflePlayEnabled(boolean enabled) {
		shufflePlay = enabled;
		if (shufflePlay) {
			checkDownloads();
		}
		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, enabled ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE);
		editor.commit();
	}

	public boolean isShufflePlayEnabled() {
		return shufflePlay;
	}

	public void setArtistRadio(String artistId) {
		if(artistId == null) {
			artistRadio = false;
		} else {
			artistRadio = true;
			artistRadioBuffer.setArtist(artistId);
		}

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, (artistId != null) ? SHUFFLE_MODE_ARTIST : SHUFFLE_MODE_NONE);
		if(artistId != null) {
			editor.putString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, artistId);
		}
		editor.commit();
	}
	public boolean isArtistRadio() {
		return artistRadio;
	}

	/**
	 * Starts (or, with a null seed, stops) a queue that keeps going from whatever the given
	 * track sounds like. Only the seed's id is persisted, which is all picking the radio
	 * back up after a restart needs.
	 */
	public synchronized void setSonicRadio(MusicDirectory.Entry seed) {
		if(seed == null) {
			sonicRadio = false;
		} else {
			sonicRadio = true;
			// Seeding fetches the first songs before it returns, so there is something to
			// take by the time the queue is asked to top itself up. Without this the radio
			// is on with a full buffer and an empty queue, and nothing comes along later to
			// notice - the same call shuffle play makes when it is switched on.
			sonicRadioBuffer.setSeed(seed);
			checkDownloads();
		}

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, (seed != null) ? SHUFFLE_MODE_SONIC : SHUFFLE_MODE_NONE);
		if(seed != null) {
			editor.putString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, seed.getId());
		}
		editor.commit();
	}
	public boolean isSonicRadio() {
		return sonicRadio;
	}

	/**
	 * Starts (or, with a null id, stops) drawing albums at random from a Plex collection.
	 * {@code albums} is the list as the user sees it, so an active browse filter carries
	 * into what autoplay draws from.
	 */
	public void setCollectionAutoplay(String collectionId, String collectionName, List<MusicDirectory.Entry> albums) {
		if(collectionId == null) {
			collectionAutoplay = false;
		} else {
			collectionAutoplay = true;
			collectionAutoplayBuffer.setCollection(collectionId, collectionName, albums);
		}

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, (collectionId != null) ? SHUFFLE_MODE_COLLECTION : SHUFFLE_MODE_NONE);
		if(collectionId != null) {
			editor.putString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, collectionId);
			// The name rides along because curation is keyed on it, and a restart has to
			// keep emptying the collection it was already emptying.
			editor.putString(Constants.PREFERENCES_KEY_COLLECTION_AUTOPLAY_NAME, collectionName);
		}
		editor.commit();
	}
	/** True once playback has run off the end of the queue, until something plays again. */
	public boolean isQueueEnded() {
		return queueEnded;
	}

	public boolean isCollectionAutoplay() {
		return collectionAutoplay;
	}

	/** The collection autoplay is currently drawing from, or null when it is off. */
	public String getCollectionAutoplayId() {
		return collectionAutoplay ? collectionAutoplayBuffer.getCollectionId() : null;
	}

	public synchronized void shuffle() {
		Collections.shuffle(downloadList);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
		if (currentPlaying != null) {
			downloadList.remove(getCurrentPlayingIndex());
			downloadList.add(0, currentPlaying);
			currentPlayingIndex = 0;
		}
		revision++;
		onSongsChanged();
		lifecycleSupport.serializeDownloadQueue();
		updateRemotePlaylist();
		setNextPlaying();

		// Everything after the current song is a different song now
		checkDownloads();
	}

	public RepeatMode getRepeatMode() {
		return Util.getRepeatMode(this);
	}

	public void setRepeatMode(RepeatMode repeatMode) {
		Util.setRepeatMode(this, repeatMode);
		setNextPlaying();
	}

	public boolean getKeepScreenOn() {
		return keepScreenOn;
	}

	public void setKeepScreenOn(boolean keepScreenOn) {
		this.keepScreenOn = keepScreenOn;

		SharedPreferences prefs = Util.getPreferences(this);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(Constants.PREFERENCES_KEY_KEEP_SCREEN_ON, keepScreenOn);
		editor.commit();
	}

	public synchronized DownloadFile forSong(MusicDirectory.Entry song) {
		DownloadFile returnFile = null;
		for (DownloadFile downloadFile : downloadList) {
			if (downloadFile.getSong().equals(song)) {
				if(((downloadFile.isDownloading() && !downloadFile.isDownloadCancelled() && downloadFile.getPartialFile().exists()) || downloadFile.isWorkDone())) {
					// If downloading, return immediately
					return downloadFile;
				} else {
					// Otherwise, check to make sure there isn't a background download going on first
					returnFile = downloadFile;
				}
			}
		}
		for (DownloadFile downloadFile : backgroundDownloadList) {
			if (downloadFile.getSong().equals(song)) {
				return downloadFile;
			}
		}

		if(returnFile != null) {
			return returnFile;
		}

		DownloadFile downloadFile = downloadFileCache.get(song);
		if (downloadFile == null) {
			downloadFile = new DownloadFile(this, song, false);
			downloadFileCache.put(song, downloadFile);
		}
		return downloadFile;
	}

	public synchronized void clearBackground() {
		if(currentDownloading != null && backgroundDownloadList.contains(currentDownloading)) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}
		backgroundDownloadList.clear();
		revision++;
		Notifications.hideDownloadingNotification(this, this, handler);
	}

	public synchronized void clearIncomplete() {
		Iterator<DownloadFile> iterator = downloadList.iterator();
		while (iterator.hasNext()) {
			DownloadFile downloadFile = iterator.next();
			if (!downloadFile.isCompleteFileAvailable()) {
				iterator.remove();
				
				// Reset if the current playing song has been removed
				if(currentPlaying == downloadFile) {
					reset();
				}

				currentPlayingIndex = downloadList.indexOf(currentPlaying);
			}
		}
		lifecycleSupport.serializeDownloadQueue();
		updateRemotePlaylist();
		onSongsChanged();
	}

	public void setOnline(final boolean online) {
		if(online) {
			mediaRouter.addOnlineProviders();
		} else {
			mediaRouter.removeOnlineProviders();
		}
		if(shufflePlay) {
			setShufflePlayEnabled(false);
		}
		if(artistRadio) {
			setArtistRadio(null);
		}
		if(sonicRadio) {
			setSonicRadio(null);
		}
		if(collectionAutoplay) {
			setCollectionAutoplay(null, null, null);
		}

		lifecycleSupport.post(new Runnable() {
			@Override
			public void run() {
				if (online) {
					checkDownloads();
				} else {
					clearIncomplete();
				}
			}
		});
	}
	public void userSettingsChanged() {
		mediaRouter.buildSelector();
	}

	public synchronized int size() {
		return downloadList.size();
	}

	public synchronized void clear() {
		clear(true);
	}
	public synchronized void clear(boolean serialize) {
		// Delete podcast if fully listened to
		int position = getPlayerPosition();
		int duration = getPlayerDuration();
		boolean cutoff = isPastCutoff(position, duration, true);
		if(currentPlaying != null && currentPlaying.getSong() instanceof PodcastEpisode && !currentPlaying.isSaved()) {
			if(cutoff) {
				currentPlaying.delete();
			}
		}
		for(DownloadFile podcast: toDelete) {
			podcast.delete();
		}
		toDelete.clear();
		
		// Clear bookmarks from current playing if past a certain point
		if(cutoff) {
			clearCurrentBookmark(true);
		} else {
			// Check if we should be adding a new bookmark here
			checkAddBookmark();
		}
		if(currentPlaying != null) {
			scrobbler.conditionalScrobble(this, currentPlaying, position, duration, cutoff);
		}

		reset();
		downloadList.clear();
		onSongsChanged();
		if (currentDownloading != null && !backgroundDownloadList.contains(currentDownloading)) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}
		setCurrentPlaying(null, false);

		if (serialize) {
			lifecycleSupport.serializeDownloadQueue();
		}
		updateRemotePlaylist();
		setNextPlaying();
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}

		suggestedPlaylistName = null;
		suggestedPlaylistId = null;

		setShufflePlayEnabled(false);
		setArtistRadio(null);
		setSonicRadio(null);
		setCollectionAutoplay(null, null, null);
		queueEnded = false;
		checkDownloads();
	}

	public synchronized void remove(int which) {
		downloadList.remove(which);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
	}

	public synchronized void remove(DownloadFile downloadFile) {
		if (downloadFile == currentDownloading) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}
		if (downloadFile == currentPlaying) {
			reset();
			setCurrentPlaying(null, false);
		}
		downloadList.remove(downloadFile);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
		backgroundDownloadList.remove(downloadFile);
		revision++;
		onSongsChanged();
		lifecycleSupport.serializeDownloadQueue();
		updateRemotePlaylist();
		if(downloadFile == nextPlaying) {
			setNextPlaying();
		}

		checkDownloads();
	}
	public synchronized void removeBackground(DownloadFile downloadFile) {
		if (downloadFile == currentDownloading && downloadFile != currentPlaying && downloadFile != nextPlaying) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}

		backgroundDownloadList.remove(downloadFile);
		revision++;
		checkDownloads();
	}

	public synchronized void delete(List<MusicDirectory.Entry> songs) {
		for (MusicDirectory.Entry song : songs) {
			forSong(song).delete();
		}
	}

	public synchronized void unpin(List<MusicDirectory.Entry> songs) {
		for (MusicDirectory.Entry song : songs) {
			forSong(song).unpin();
		}
	}

	synchronized void setCurrentPlaying(int currentPlayingIndex, boolean showNotification) {
		try {
			setCurrentPlaying(downloadList.get(currentPlayingIndex), showNotification);
		} catch (IndexOutOfBoundsException x) {
			// Ignored
		}
	}

	synchronized void setCurrentPlaying(DownloadFile currentPlaying, boolean showNotification) {
		if(this.currentPlaying != null) {
			this.currentPlaying.setPlaying(false);
		}
		if(delayUpdateProgress != DEFAULT_DELAY_UPDATE_PROGRESS && !isNextPlayingSameAlbum(currentPlaying, this.currentPlaying)) {
//			resetPlaybackSpeed();
		}
		this.currentPlaying = currentPlaying;
		if(currentPlaying == null) {
			currentPlayingIndex = -1;
			setPlayerState(IDLE);
		} else {
			currentPlayingIndex = downloadList.indexOf(currentPlaying);
		}

		if (currentPlaying != null && currentPlaying.getSong() != null) {
			// Whatever is listening to the media session scrobbles this, so it has to carry
			// the tags rather than what the file name spelled out
			currentPlaying.loadMetadataIfNeeded();
			Util.broadcastNewTrackInfo(this, currentPlaying.getSong());

			if(mRemoteControl != null) {
				mRemoteControl.updateMetadata(this, currentPlaying.getSong());
			}
		} else {
			Util.broadcastNewTrackInfo(this, null);
			Notifications.hidePlayingNotification(this, this, handler);
		}
		onSongChanged();
	}

	synchronized void setNextPlaying() {
		SharedPreferences prefs = Util.getPreferences(DownloadService.this);

		// Only obey gapless playback for local
		if(remoteState == LOCAL) {
			boolean gaplessPlayback = prefs.getBoolean(Constants.PREFERENCES_KEY_GAPLESS_PLAYBACK, true);
			if (!gaplessPlayback) {
				nextPlaying = null;
				nextPlayerState = IDLE;
				return;
			}
		}
		setNextPlayerState(IDLE);

		int index = getNextPlayingIndex();

		if(nextPlayingTask != null) {
			nextPlayingTask.cancel();
			nextPlayingTask = null;
		}
		resetNext();

		if(index < size() && index != -1 && index != currentPlayingIndex) {
			nextPlaying = downloadList.get(index);

			if(remoteState == LOCAL) {
				nextPlayingTask = new CheckCompletionTask(nextPlaying);
				nextPlayingTask.execute();
			} else if(remoteController != null && remoteController.isNextSupported()) {
				remoteController.changeNextTrack(nextPlaying);
			}
		} else {
			// Cleared before the controller is told, not after: there is no next track here,
			// and handing over the previous one would queue whatever was already playing.
			// A renderer given that plays it again at the end of the queue instead of
			// stopping, and reports it as the track it moved to - a handover that never
			// happened, credited to whatever the queue had moved on to by then.
			nextPlaying = null;

			if(remoteState == LOCAL) {
				// resetNext();
			} else if(remoteController != null && remoteController.isNextSupported()) {
				remoteController.changeNextTrack(nextPlaying);
			}
		}
	}

	public int getCurrentPlayingIndex() {
		return currentPlayingIndex;
	}
	private int getNextPlayingIndex() {
		int index = getCurrentPlayingIndex();
		if (index != -1) {
			RepeatMode repeatMode = getRepeatMode();
			switch (repeatMode) {
				case OFF:
					index = index + 1;
					break;
				case ALL:
					index = (index + 1) % size();
					break;
				case SINGLE:
					break;
				default:
					break;
			}

			index = checkNextIndexValid(index, repeatMode);
		}
		return index;
	}
	private int checkNextIndexValid(int index, RepeatMode repeatMode) {
		int startIndex = index;
		int size = size();
		if(index < size && index != -1) {
			if(!Util.isAllowedToDownload(this)){
				DownloadFile next = downloadList.get(index);
				while(!next.isCompleteFileAvailable()) {
					index++;

					if (index >= size) {
						if(repeatMode == RepeatMode.ALL) {
							index = 0;
						} else {
							return -1;
						}
					} else if(index == startIndex) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								Util.toast(DownloadService.this, R.string.download_playerstate_mobile_disabled);
							}
						});
						return -1;
					}

					next = downloadList.get(index);
				}
			}
		}

		return index;
	}

	public DownloadFile getCurrentPlaying() {
		return currentPlaying;
	}

	public DownloadFile getCurrentDownloading() {
		return currentDownloading;
	}

	public DownloadFile getNextPlaying() {
		return nextPlaying;
	}

	public List<DownloadFile> getSongs() {
		return downloadList;
	}

	public List<DownloadFile> getToDelete() { return toDelete; }

	public boolean isCurrentPlayingSingle() {
		if(currentPlaying != null && currentPlaying.getSong() instanceof InternetRadioStation) {
			return true;
		} else {
			return false;
		}
	}
	public boolean isCurrentPlayingStream() {
		if(currentPlaying != null) {
			return currentPlaying.isStream();
		} else {
			return false;
		}
	}

	public synchronized boolean shouldFastForward() {
		return size() == 1 || (currentPlaying != null && !currentPlaying.isSong());
	}

	public synchronized boolean isForeground() {
		return this.foregroundService;
	}

	public synchronized void setIsForeground(boolean foreground) {
		this.foregroundService = foreground;
	}

	public synchronized List<DownloadFile> getDownloads() {
		List<DownloadFile> temp = new ArrayList<DownloadFile>();
		temp.addAll(downloadList);
		temp.addAll(backgroundDownloadList);
		return temp;
	}

	public synchronized List<DownloadFile> getRecentDownloads() {
		int from = Math.max(currentPlayingIndex - 10, 0);
		int songsToKeep = Math.min(Math.max(getPreloadCount(), 20), downloadList.size());
		int to = Math.min(currentPlayingIndex + songsToKeep, Math.max(downloadList.size() - 1, 0));
		// Copied rather than used directly: subList hands back a view onto downloadList, so
		// adding the background downloads to it would splice them into the play queue itself.
		List<DownloadFile> temp = new ArrayList<DownloadFile>(downloadList.subList(from, Math.max(from, to)));
		temp.addAll(backgroundDownloadList);
		return temp;
	}

	public List<DownloadFile> getBackgroundDownloads() {
		return backgroundDownloadList;
	}

	/** Plays either the current song (resume) or the first/next one in queue. */
	public synchronized void play()
	{
		int current = getCurrentPlayingIndex();
		if (current == -1) {
			play(0);
		} else {
			play(current);
		}
	}

	public synchronized void play(int index) {
		play(index, true);
	}
	public synchronized void play(DownloadFile downloadFile) {
		play(downloadList.indexOf(downloadFile));
	}
	private synchronized void play(int index, boolean start) {
		play(index, start, 0);
	}
	private synchronized void play(int index, boolean start, int position) {
		int size = this.size();
		cachedPosition = 0;
		if (index < 0 || index >= size) {
			reset();
			if(index >= size && size != 0) {
				// Ran off the end, so the queue is finished and is taken down rather than
				// left sitting on its first track.
				queueEnded = true;

				// Said before the queue is taken apart, never after. Everything below
				// this touches what the media session reports, and anything reading that
				// session - a scrobbler, the car, a watch - takes a track arriving while
				// the session still says playing as a track that has started. It does
				// still say playing on a remote: reset() above leaves the state alone
				// when the player is not ours, so the last thing pushed is the STARTED of
				// the track that just ended.
				//
				// The order also keeps the position that goes out with the state on the
				// track it was measured against, rather than reporting the end of one
				// track as progress through another.
				if(playerState != IDLE) {
					setPlayerState(IDLE);
				}

				if(isEndlessQueue()) {
					// Shuffle play and the radios are meant to run for as long as they
					// are left on, and are topped up in the background, so reaching the
					// end of the list is a top-up that has not caught up rather than a
					// queue that has finished. Emptying it would take the mode down with
					// it, so those are rewound and kept.
					setCurrentPlaying(0, false);
				} else {
					emptyFinishedQueue();
				}
				Notifications.hidePlayingNotification(this, this, handler);
			} else {
				setCurrentPlaying(null, false);
			}
			lifecycleSupport.serializeDownloadQueue();
		} else {
			queueEnded = false;
			if(nextPlayingTask != null) {
				nextPlayingTask.cancel();
				nextPlayingTask = null;
			}
			setCurrentPlaying(index, start);
			if (start && remoteState != LOCAL) {
				remoteController.changeTrack(index, currentPlaying);
			}
			if (remoteState == LOCAL) {
				bufferAndPlay(position, start);
				checkDownloads();
				setNextPlaying();
			} else {
				checkDownloads();
			}
		}
	}
	/**
	 * Whether the queue is one that tops itself up rather than one that ends.
	 */
	private boolean isEndlessQueue() {
		return shufflePlay || artistRadio || sonicRadio || collectionAutoplay;
	}

	/**
	 * Takes down a queue that has played out.
	 *
	 * A finished queue leaves nothing behind: the list is emptied rather than rewound to
	 * its first track, so there is no mini player over the library implying playback, and
	 * nothing left for anything else to pick up again. Handing a cast back to the phone
	 * did exactly that - a rewound queue was prepared at the position the last track
	 * ended on, which fails on a shorter track, and a failed track is answered by moving
	 * to the next one, so putting the speaker away started the album over on the phone.
	 *
	 * Everything here is what {@link #clear} does to the queue, minus what it does to the
	 * modes around it: shuffle play and the radios are not turned off from here, since
	 * this is not reached while one of them is on.
	 */
	private synchronized void emptyFinishedQueue() {
		downloadList.clear();
		onSongsChanged();

		if (currentDownloading != null && !backgroundDownloadList.contains(currentDownloading)) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}

		// Clears the notification and hands the media session a stopped state of its own,
		// with the metadata of the track that really did play still on it.
		setCurrentPlaying(null, false);
		setNextPlaying();
		updateRemotePlaylist();

		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
		checkDownloads();
	}

	private synchronized void playNext() {
		if(nextPlaying != null && nextPlayerState == PlayerState.PREPARED) {
			// An armed successor has already been taken up by the engine, so it only
			// needs starting here when it was never armed in the first place.
			playNext(!player.isNextArmed());
		} else {
			onSongCompleted();
		}
	}
	private synchronized void playNext(boolean start) {
		Util.broadcastPlaybackStatusChange(this, currentPlaying.getSong(), PlayerState.PREPARED);
		
		// Swap the tracks over since the next one is ready to play
		subtractPosition = 0;
		if(player.promoteNext(start)) {
			// It was already running, so the handover happened without us. Next time the
			// cachedPosition is updated, use that as position 0.
			subtractNextPosition = System.currentTimeMillis();
		}
		setCurrentPlaying(nextPlaying, true);
		setPlayerState(PlayerState.STARTED);
		setupHandlers(currentPlaying, false, start);
		applyPlaybackParamsMain();

		// The track that was waiting is the one being heard now, so the session's boost is
		// this track's to claim rather than the one that just ended. Its own volume was set
		// when it was prepared and is only being re-stated here.
		applyReplayGain(currentPlaying);
		setNextPlaying();

		// Proxy should not be being used here since the next player was already setup to play
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
		checkDownloads();
		updateRemotePlaylist();
	}

	/** Plays or resumes the playback, depending on the current player state. */
	public synchronized void togglePlayPause() {
		if (playerState == PAUSED || playerState == COMPLETED || playerState == STOPPED) {
			start();
		} else if (playerState == STOPPED || playerState == IDLE) {
			autoPlayStart = true;
			play();
		} else if (playerState == STARTED) {
			pause();
		}
	}

	public synchronized void seekTo(int position) {
		if(position < 0) {
			position = 0;
		}

		try {
			if (remoteState != LOCAL) {
				remoteController.changePosition(position / 1000);
			} else {
				if(proxy != null && currentPlaying.isCompleteFileAvailable()) {
					doPlay(currentPlaying, position, playerState == STARTED);
					return;
				}

				player.seekTo(position);
				subtractPosition = 0;
			}
			cachedPosition = position;

			onSongProgress();
			if(playerState == PAUSED) {
				lifecycleSupport.serializeDownloadQueue();
			}
		} catch (Exception x) {
			handleError(x);
		}
	}
	public synchronized int rewind() {
		return seekToWrapper(Integer.parseInt(Util.getPreferences(this).getString(Constants.PREFERENCES_KEY_REWIND_INTERVAL, "10"))*-1000);
	}
	public synchronized int fastForward() {
		return seekToWrapper(Integer.parseInt(Util.getPreferences(this).getString(Constants.PREFERENCES_KEY_FASTFORWARD_INTERVAL, "30"))*1000);
	}
	protected int seekToWrapper(int difference) {
		int msPlayed = Math.max(0, getPlayerPosition());
		Integer duration = getPlayerDuration();
		int msTotal = duration == null ? 0 : duration;

		int seekTo;
		if(msPlayed + difference > msTotal) {
			seekTo = msTotal;
		} else {
			seekTo = msPlayed + difference;
		}
		seekTo(seekTo);

		return seekTo;
	}

	public synchronized void previous() {
		int index = getCurrentPlayingIndex();
		if (index == -1) {
			return;
		}

		// If only one song, just skip within song
		if(shouldFastForward()) {
			rewind();
			return;
		} else if(playerState == PREPARING || playerState == PREPARED) {
			return;
		}

		// Restart song if played more than five seconds.
		if (getPlayerPosition() > 5000 || (index == 0 && getRepeatMode() != RepeatMode.ALL)) {
			seekTo(0);
		} else {
			if(index == 0) {
				index = size();
			}

			play(index - 1, playerState != PAUSED && playerState != STOPPED && playerState != IDLE);
		}
	}

	public synchronized void next() {
		next(false);
	}
	public synchronized void next(boolean forceCutoff) {
		next(forceCutoff, false);
	}
	public synchronized void next(boolean forceCutoff, boolean forceStart) {
		// If only one song, just skip within song
		if(shouldFastForward()) {
			fastForward();
			return;
		} else if(playerState == PREPARING || playerState == PREPARED) {
			return;
		}

		// Delete podcast if fully listened to
		int position = getPlayerPosition();
		int duration = getPlayerDuration();
		boolean cutoff;
		if(forceCutoff) {
			cutoff = true;
		} else {
			cutoff = isPastCutoff(position, duration);
		}
		if(currentPlaying != null && currentPlaying.getSong() instanceof PodcastEpisode && !currentPlaying.isSaved()) {
			if(cutoff) {
				toDelete.add(currentPlaying);
			}
		}
		if(cutoff) {
			clearCurrentBookmark(true);
		}
		if(currentPlaying != null) {
			scrobbler.conditionalScrobble(this, currentPlaying, position, duration, cutoff);
		}

		int index = getCurrentPlayingIndex();
		int nextPlayingIndex = getNextPlayingIndex();
		// Make sure to actually go to next when repeat song is on
		if(index == nextPlayingIndex) {
			nextPlayingIndex++;
		}
		if (index != -1 && nextPlayingIndex < size()) {
			play(nextPlayingIndex, playerState != PAUSED && playerState != STOPPED && playerState != IDLE || forceStart);
		}
	}

	public void onSongCompleted() {
		setPlayerStateCompleted();
		postPlayCleanup();
		play(getNextPlayingIndex());
	}
	public void onNextStarted(DownloadFile nextPlaying) {
		setPlayerStateCompleted();
		postPlayCleanup();
		setCurrentPlaying(nextPlaying, true);
		setPlayerState(STARTED);
		setNextPlayerState(IDLE);
	}

	public synchronized void pause() {
		pause(false);
	}
	public synchronized void pause(boolean temp) {
		try {
			if (playerState == STARTED) {
				if (remoteState != LOCAL) {
					remoteController.stop();
				} else {
					player.pause();
				}
				setPlayerState(temp ? PAUSED_TEMP : PAUSED);
			} else if(playerState == PAUSED_TEMP) {
				setPlayerState(temp ? PAUSED_TEMP : PAUSED);
			}
		} catch (Exception x) {
			handleError(x);
		}
	}

	public synchronized void stop() {
		try {
			if (playerState == STARTED) {
				if (remoteState != LOCAL) {
					remoteController.stop();
					setPlayerState(STOPPED);
					handler.post(new Runnable() {
						@Override
						public void run() {
							mediaRouter.setDefaultRoute();
						}
					});
				} else {
					player.pause();
					setPlayerState(STOPPED);
				}
			} else if(playerState == PAUSED) {
				setPlayerState(STOPPED);
			}
		} catch(Exception x) {
			handleError(x);
		}
	}

	public synchronized void start() {
		try {
			if (remoteState != LOCAL) {
				remoteController.start();
			} else {
				// Only start if done preparing
				if(playerState != PREPARING) {
					player.start();
					applyPlaybackParamsMain();
				} else {
					// Otherwise, we need to set it up to start when done preparing
					autoPlayStart = true;
				}
			}
			setPlayerState(STARTED);
		} catch (Exception x) {
			handleError(x);
		}
	}

	public synchronized void reset() {
		if (bufferTask != null) {
			bufferTask.cancel();
			bufferTask = null;
		}
		try {
			// Only set to idle if it's not being killed to start RemoteController
			if(remoteState == LOCAL) {
				setPlayerState(IDLE);
			}
			// Dropping the context is what stops a callback still owed for the track being
			// unloaded from landing on whatever is loaded next.
			trackHandlers = null;
			pendingPlay = null;
			if(player != null) {
				player.reset();
			}
			subtractPosition = 0;
		} catch (Exception x) {
			handleError(x);
		}
	}

	public synchronized void resetNext() {
		nextTrackFile = null;
		if(player != null) {
			player.clearNext();
		}
	}

	public int getPlayerPosition() {
		try {
			if (playerState == IDLE || playerState == DOWNLOADING || playerState == PREPARING) {
				return 0;
			}
			if (remoteState != LOCAL) {
				return remoteController.getRemotePosition() * 1000;
			} else {
				return Math.max(0, cachedPosition - subtractPosition);
			}
		} catch (Exception x) {
			handleError(x);
			return 0;
		}
	}

	/**
	 * Pushes playback state to the server. Transitions report immediately;
	 * while playing we also re-report periodically, since Plex expires a
	 * session that stops sending timeline updates.
	 */
	private void reportPlaybackState(final String state, boolean force) {
		final DownloadFile current = currentPlaying;
		if(current == null || current.getSong() == null || Util.isOffline(this)) {
			return;
		}

		long now = System.currentTimeMillis();
		if(!force && (now - lastPlaybackReport) < PLAYBACK_REPORT_INTERVAL) {
			return;
		}
		lastPlaybackReport = now;

		final MusicDirectory.Entry song = current.getSong();
		final int position = getPlayerPosition();
		new SilentBackgroundTask<Void>(this) {
			@Override
			protected Void doInBackground() {
				try {
					MusicServiceFactory.getMusicService(DownloadService.this)
							.updatePlaybackState(song, state, position, DownloadService.this);
				} catch(Exception e) {
					// Never let session reporting disrupt playback.
					Log.w(TAG, "Failed to report playback state", e);
				}
				return null;
			}
		}.execute();
	}

	public synchronized int getPlayerDuration() {
		if (playerState != IDLE && playerState != DOWNLOADING && playerState != PlayerState.PREPARING) {
			int duration = 0;
			if(remoteState == LOCAL) {
				duration = player.getDuration();
			} else {
				duration = remoteController.getRemoteDuration() * 1000;
			}

			// An engine reports a length of zero or less when it does not know one yet --
			// which is the normal case while streaming a transcode that has no
			// Content-Length. Treat anything non-positive as unknown so we fall
			// through to the duration from the server metadata below.
			if(duration > 0) {
				return duration;
			}
		}

		if (currentPlaying != null) {
			Integer duration = currentPlaying.getSong().getDuration();
			if (duration != null) {
				return duration * 1000;
			}
		}

		return 0;
	}

	public PlayerState getPlayerState() {
		return playerState;
	}

	public PlayerState getNextPlayerState() {
		return nextPlayerState;
	}

	public synchronized void setPlayerState(final PlayerState playerState) {
		Log.i(TAG, this.playerState.name() + " -> " + playerState.name() + " (" + currentPlaying + ")");

		if (playerState == PAUSED) {
			lifecycleSupport.serializeDownloadQueue();
			if(!isPastCutoff()) {
				checkAddBookmark(true);
			}
		}

		boolean show = playerState == PlayerState.STARTED;
		boolean pause = playerState == PlayerState.PAUSED;
		boolean hide = playerState == PlayerState.STOPPED;
		Util.broadcastPlaybackStatusChange(this, (currentPlaying != null) ? currentPlaying.getSong() : null, playerState);

		this.playerState = playerState;

		// Keep the server's session view in step with ours.
		if(playerState == PlayerState.STARTED) {
			reportPlaybackState("playing", true);
		} else if(playerState == PlayerState.PAUSED) {
			reportPlaybackState("paused", true);
		} else if(playerState == PlayerState.STOPPED || playerState == PlayerState.COMPLETED) {
			reportPlaybackState("stopped", true);
		}

		if(playerState == STARTED) {
			AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
			Util.requestAudioFocus(this, audioManager);
		}

		SharedPreferences prefs = Util.getPreferences(this);
		boolean usingMediaStyleNotification = prefs.getBoolean(Constants.PREFERENCES_KEY_MEDIA_STYLE_NOTIFICATION, true);

		if (show) {
			Notifications.showPlayingNotification(this, this, handler, currentPlaying.getSong(), usingMediaStyleNotification);
		} else if (pause) {
			if (prefs.getBoolean(Constants.PREFERENCES_KEY_PERSISTENT_NOTIFICATION, false)) {
				Notifications.showPlayingNotification(this, this, handler, currentPlaying.getSong(), usingMediaStyleNotification);
			} else {
				Notifications.hidePlayingNotification(this, this, handler);
			}
		} else if(hide) {
			Notifications.hidePlayingNotification(this, this, handler);
		}
		if(mRemoteControl != null) {
			mRemoteControl.setPlaybackState(playerState.getRemoteControlClientPlayState(), getCurrentPlayingIndex(), size());
		}

		if (playerState == STARTED) {
			scrobbler.scrobble(this, currentPlaying, false, false);
		} else if (playerState == COMPLETED) {
			scrobbler.scrobble(this, currentPlaying, true, true);
		}

		if(playerState == STARTED && positionCache == null) {
			if(remoteState == LOCAL) {
				positionCache = new LocalPositionCache();
			} else {
				positionCache = new PositionCache();
			}
			Thread thread = new Thread(positionCache, "PositionCache");
			thread.start();
		} else if(playerState != STARTED && positionCache != null) {
			positionCache.stop();
			positionCache = null;
		}


		if(remoteState != LOCAL) {
			if(playerState == STARTED) {
				if (!wifiLock.isHeld()) {
					wifiLock.acquire();
				}
				// The wifi lock keeps the radio associated but lets the processor sleep,
				// and everything holding a cast together is a plain sleep rather than an
				// alarm: the status poll that spots a track ending and the progress tick
				// that hands the renderer the next one both simply stop with the screen
				// off. The renderer plays out what it already holds and then stops.
				if (!remoteWakeLock.isHeld()) {
					remoteWakeLock.acquire();
				}
			} else {
				if(playerState == PAUSED && wifiLock.isHeld()) {
					wifiLock.release();
				}
				// Let go on anything that is not playing, not on pause alone: a hold left
				// behind on a state that never comes back keeps the processor awake with
				// nothing left to do for it.
				if(remoteWakeLock.isHeld()) {
					remoteWakeLock.release();
				}
			}
		}

		if(remoteController != null && remoteController.isNextSupported()) {
			if(playerState == PREPARING || playerState == IDLE) {
				nextPlayerState = IDLE;
			}
		}

		onStateUpdate();
	}

	public void setPlayerStateCompleted() {
		// Acquire a temporary wakelock
		acquireWakelock();

		Log.i(TAG, this.playerState.name() + " -> " + PlayerState.COMPLETED + " (" + currentPlaying + ")");
		this.playerState = PlayerState.COMPLETED;
		if(positionCache != null) {
			positionCache.stop();
			positionCache = null;
		}
		scrobbler.scrobble(this, currentPlaying, true, true);

		onStateUpdate();
	}

	private class PositionCache implements Runnable {
		boolean isRunning = true;

		public void stop() {
			isRunning = false;
		}

		@Override
		public void run() {
			// Stop checking position before the song reaches completion
			while(isRunning) {
				try {
					onSongProgress();
					Thread.sleep(delayUpdateProgress);
				}
				catch(Exception e) {
					isRunning = false;
					positionCache = null;
				}
			}
		}
	}
	private class LocalPositionCache extends PositionCache {
		boolean isRunning = true;

		public void stop() {
			isRunning = false;
		}

		@Override
		public void run() {
			// Stop checking position before the song reaches completion
			while(isRunning) {
				try {
					if(player != null && playerState == STARTED) {
						int newPosition = player.getCurrentPosition();

						// If sudden jump in position, something is wrong
						if(subtractNextPosition == 0 && newPosition > (cachedPosition + 5000)) {
							// Only 1 second should have gone by, subtract the rest
							subtractPosition += (newPosition - cachedPosition) - 1000;
						}

						cachedPosition = newPosition;

						if(subtractNextPosition > 0) {
							// Subtraction amount is current position - how long ago onCompletionListener was called
							subtractPosition = cachedPosition - (int) (System.currentTimeMillis() - subtractNextPosition);
							if(subtractPosition < 0) {
								subtractPosition = 0;
							}
							subtractNextPosition = 0;
						}
					}
					onSongProgress(cachedPosition < 2000 ? true: false);
					Thread.sleep(delayUpdateProgress);
				}
				catch(Exception e) {
					Log.w(TAG, "Crashed getting current position", e);
					isRunning = false;
					positionCache = null;
				}
			}
		}
	}

	public synchronized void setNextPlayerState(PlayerState playerState) {
		Log.i(TAG, "Next: " + this.nextPlayerState.name() + " -> " + playerState.name() + " (" + nextPlaying + ")");
		this.nextPlayerState = playerState;
	}

	public void setSuggestedPlaylistName(String name, String id) {
		this.suggestedPlaylistName = name;
		this.suggestedPlaylistId = id;

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putString(Constants.PREFERENCES_KEY_PLAYLIST_NAME, name);
		editor.putString(Constants.PREFERENCES_KEY_PLAYLIST_ID, id);
		editor.commit();

		// Callers set this after queueing the songs, so the preload window was sized without
		// knowing a playlist is what is playing
		checkDownloads();
	}

	public String getSuggestedPlaylistName() {
		return suggestedPlaylistName;
	}

	public String getSuggestedPlaylistId() {
		return suggestedPlaylistId;
	}

	private int getPreloadCount() {
		return Util.getPreloadCount(this, suggestedPlaylistName != null);
	}

	public boolean getEqualizerAvailable() {
		return effectsController.isAvailable();
	}

	/**
	 * The ten band equalizer, on the devices that carry the effect it needs. Null below
	 * Android 9, where the screen falls back to the device's own equalizer effect.
	 */
	public DynamicsEqualizer getDynamicsEqualizer() {
		return dynamicsEqualizer;
	}

	public EqualizerController getEqualizerController() {
		EqualizerController controller = null;
		try {
			controller = effectsController.getEqualizerController();
			if(controller.getEqualizer() == null) {
				throw new Exception("Failed to get EQ");
			}
		} catch(Exception e) {
			// The rebuild costs the track that is playing, and a session that refused the
			// effect once refuses it again: the effect is tied to the session, which does not
			// change for as long as the service lives. Now Playing asks every time it builds
			// its menu, so without this each of those asks stopped the music.
			if(equalizerRebuildFailed) {
				return null;
			}
			Log.w(TAG, "Failed to start EQ, retrying with a fresh player: " + e);

			// If we failed, we are going to try to reinitialize the player
			int pos = getPlayerPosition();
			boolean wasPlaying = playerState == STARTED;
			player.pause();
			Util.sleepQuietly(10L);
			reset();

			try {
				// Resetup the player, which is what gives the effect a live session to
				// attach to. What it loads does not matter - play() below starts over.
				player.prepare(currentPlaying.getFile().getCanonicalPath());

				controller = effectsController.getEqualizerController();
				if(controller.getEqualizer() == null) {
					throw new Exception("Failed to get EQ");
				}
			} catch(Exception e2) {
				Log.w(TAG, "Failed to setup EQ even after reinitialization");
				// Don't try again, just resetup media player and continue on
				controller = null;
				equalizerRebuildFailed = true;
			}

			// Restart from same position and state we left off in
			play(getCurrentPlayingIndex(), wasPlaying, pos);
		}

		return controller;
	}

	public MediaRouteManager getMediaRouter() {
		return mediaRouter;
	}
	public MediaRouteSelector getRemoteSelector() {
		return mediaRouter.getSelector();
	}

	public boolean isSeekable() {
		if(remoteState == LOCAL) {
			return currentPlaying != null && currentPlaying.isWorkDone() && playerState != PREPARING;
		} else if(remoteController != null) {
			return remoteController.isSeekable();
		} else {
			return false;
		}
	}

	public boolean isRemoteEnabled() {
		return remoteState != LOCAL;
	}

	public RemoteController getRemoteController() {
		return remoteController;
	}

	public void setRemoteEnabled(RemoteControlState newState) {
		if(instance != null) {
			setRemoteEnabled(newState, null);
		}
	}
	public void setRemoteEnabled(RemoteControlState newState, Object ref) {
		setRemoteState(newState, ref);

		RouteInfo info = mediaRouter.getSelectedRoute();
		String routeId = info.getId();

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_CONTROL_MODE, newState.getValue());
		editor.putString(Constants.PREFERENCES_KEY_CONTROL_ID, routeId);
		editor.commit();
	}
	private void setRemoteState(RemoteControlState newState, Object ref) {
		setRemoteState(newState, ref, null);
	}
	private void setRemoteState(final RemoteControlState newState, final Object ref, final String routeId) {
		// Don't try to do anything if already in the correct state
		if(remoteState == newState) {
			return;
		}

		boolean isPlaying = playerState == STARTED;
		// Read while the old state still stands, so a cast is asked where it had got to
		// rather than the idle local player.
		int position = getPlayerPosition();

		Log.i(TAG, remoteState.name() + " => " + newState.name() + " (" + currentPlaying + ")");
		// Claimed before anything that can come back round here. Dropping the route tells
		// the route provider, which answers by asking for LOCAL in turn, and while this
		// still reads as the old state that second pass gets past the guard above and
		// runs the whole handover again - preparing the track twice over.
		remoteState = newState;

		if(remoteController != null) {
			remoteController.stop();
			setPlayerState(PlayerState.IDLE);
			remoteController.shutdown();
			remoteController = null;

			if(newState == LOCAL) {
				mediaRouter.setDefaultRoute();
			}
		}

		switch(newState) {
			case JUKEBOX_SERVER:
				remoteController = new JukeboxController(this, handler);
				break;
			case CHROMECAST: case DLNA: case SONOS:
				if(ref == null) {
					remoteState = LOCAL;
					break;
				}
				remoteController = (RemoteController) ref;
				break;
			case LOCAL: default:
				if(wifiLock.isHeld()) {
					wifiLock.release();
				}
				break;
		}

		if(remoteState != LOCAL) {
			if(!wifiLock.isHeld()) {
				wifiLock.acquire();
			}
		} else {
			if(wifiLock.isHeld()) {
				wifiLock.release();
			}
			// Handed back to this phone, where the media player holds its own.
			if(remoteWakeLock.isHeld()) {
				remoteWakeLock.release();
			}
		}

		if(remoteController != null) {
			remoteController.create(isPlaying, position / 1000);
		} else if(queueEnded) {
			// There is nothing to hand back. The queue played out on the speaker, and
			// picking a track up here would be starting a session the moment it is put
			// away - with a position belonging to whatever played last, at that.
			Log.i(TAG, "Queue had already finished; nothing to hand back to the phone");
		} else {
			play(getCurrentPlayingIndex(), isPlaying, position);
		}

		if (remoteState != LOCAL) {
			reset();

			// Cancel current download, if necessary. Not when the speaker is to be served
			// from the cache: the download that was feeding local playback is the very
			// file the cast is about to wait on, and a cancelled one cannot be picked up
			// again without two tasks writing the same partial file.
			if (currentDownloading != null && !Util.shouldCastFromCache(this)) {
				currentDownloading.cancelDownload();
			}

			// Cancels current setup tasks
			if(bufferTask != null && bufferTask.isRunning()) {
				bufferTask.cancel();
				bufferTask = null;
			}
			if(nextPlayingTask != null && nextPlayingTask.isRunning()) {
				nextPlayingTask.cancel();
				nextPlayingTask = null;
			}

			if(nextPlayerState != IDLE) {
				setNextPlayerState(IDLE);
			}
		}
		checkDownloads();

		if(routeId != null) {
			final Runnable delayedReconnect = new Runnable() {
				@Override
				public void run() {
					RouteInfo info = mediaRouter.getRouteForId(routeId);
					if(info == null) {
						setRemoteState(LOCAL, null);
					} else if(newState == RemoteControlState.CHROMECAST) {
						RemoteController controller = mediaRouter.getRemoteController(info);
						if(controller != null) {
							setRemoteState(RemoteControlState.CHROMECAST, controller);
						}
					}
					mediaRouter.stopScan();
				}
			};

			handler.post(new Runnable() {
				@Override
				public void run() {
					mediaRouter.startScan();
					RouteInfo info = mediaRouter.getRouteForId(routeId);
					if(info == null) {
						handler.postDelayed(delayedReconnect, 2000L);
					} else if(newState == RemoteControlState.CHROMECAST) {
						RemoteController controller = mediaRouter.getRemoteController(info);
						if(controller != null) {
							setRemoteState(RemoteControlState.CHROMECAST, controller);
						}
					}
				}
			});
		}
	}

	public void registerRoute(MediaRouter router) {
		if (mRemoteControl != null) {
			mRemoteControl.registerRoute(router);
		}
	}
	public void unregisterRoute(MediaRouter router) {
		if(mRemoteControl != null) {
			mRemoteControl.unregisterRoute(router);
		}
	}

	public void updateRemoteVolume(boolean up) {
		AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		audioManager.adjustVolume(up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
	}

	public void startRemoteScan() {
		mediaRouter.startScan();
	}

	public void stopRemoteScan() {
		mediaRouter.stopScan();
	}

	/**
	 * Builds the engine that turns files into sound on this device, as the setting asks.
	 *
	 * Falls back to the platform player when the other one cannot be built, since an app
	 * that plays nothing is worse than one that plays through the engine it always had.
	 */
	private LocalPlayer createPlayer(int preferredSessionId) {
		SharedPreferences prefs = Util.getPreferences(this);
		String wanted = prefs.getString(Constants.PREFERENCES_KEY_AUDIO_ENGINE, Constants.AUDIO_ENGINE_MEDIA_PLAYER);

		if(Constants.AUDIO_ENGINE_EXO_PLAYER.equals(wanted)) {
			try {
				ExoPlayerEngine.Settings settings = new ExoPlayerEngine.Settings(
						prefs.getBoolean(Constants.PREFERENCES_KEY_AUDIO_FLOAT_OUTPUT, false),
						prefs.getBoolean(Constants.PREFERENCES_KEY_AUDIO_OFFLOAD, false),
						prefs.getBoolean(Constants.PREFERENCES_KEY_GAPLESS_PLAYBACK, true));

				int crossfade = crossfadeMillis();
				LocalPlayer built = (crossfade > 0)
						? new CrossfadeEngine(this, preferredSessionId, settings, crossfade)
						: new ExoPlayerEngine(this, preferredSessionId, settings);
				playerConfig = playerConfigSignature();
				return built;
			} catch(Throwable t) {
				Log.w(TAG, "Failed to build the ExoPlayer engine, falling back on the platform player", t);
			}
		}

		playerConfig = Constants.AUDIO_ENGINE_MEDIA_PLAYER;
		return new MediaPlayerEngine(this, preferredSessionId);
	}

	/**
	 * Everything about the settings that an engine is built around rather than told later.
	 * Two engines built from the same signature are the same engine; a different one is
	 * what {@link #reloadPlayer()} acts on.
	 */
	private String playerConfigSignature() {
		SharedPreferences prefs = Util.getPreferences(this);
		String engine = prefs.getString(Constants.PREFERENCES_KEY_AUDIO_ENGINE, Constants.AUDIO_ENGINE_MEDIA_PLAYER);
		if(!Constants.AUDIO_ENGINE_EXO_PLAYER.equals(engine)) {
			// The platform player is built around nothing that can be configured.
			return Constants.AUDIO_ENGINE_MEDIA_PLAYER;
		}

		return engine
				+ "|float=" + prefs.getBoolean(Constants.PREFERENCES_KEY_AUDIO_FLOAT_OUTPUT, false)
				+ "|offload=" + prefs.getBoolean(Constants.PREFERENCES_KEY_AUDIO_OFFLOAD, false)
				+ "|gapless=" + prefs.getBoolean(Constants.PREFERENCES_KEY_GAPLESS_PLAYBACK, true)
				// The length and not just whether there is one: the engine is built around
				// it, so changing it is a different engine rather than a different setting.
				+ "|crossfade=" + crossfadeMillis();
	}

	/** How long one track is to overlap the next, in milliseconds, or 0 for no overlap. */
	private int crossfadeMillis() {
		String seconds = Util.getPreferences(this)
				.getString(Constants.PREFERENCES_KEY_CROSSFADE_DURATION, "0");
		try {
			return Integer.parseInt(seconds) * 1000;
		} catch(NumberFormatException x) {
			return 0;
		}
	}

	/**
	 * Swaps the engine over after the setting behind it changed.
	 *
	 * What was playing is started again on the new engine from where it was: a loaded
	 * track belongs to the engine that loaded it and there is nothing to hand across. The
	 * audio session is carried over, so the equalizer and anything else attached to it
	 * stay where they are.
	 */
	public void reloadPlayer() {
		if(player == null || mediaPlayerHandler == null) {
			return;
		}

		mediaPlayerHandler.post(new Runnable() {
			public void run() {
				String wanted = playerConfigSignature();
				if(wanted.equals(playerConfig)) {
					return;
				}

				// Only ever starts something again when this device is the one making the
				// sound. A renderer holds its own copy of the queue and its own idea of
				// where it is; going through play() while it has them hands it the track
				// again from the top, and the engine being rebuilt here is not the one
				// playing anyway.
				boolean local = remoteState == LOCAL;
				int position = local ? getPlayerPosition() : 0;
				boolean wasPlaying = local && playerState == STARTED;

				reset();
				resetNext();

				LocalPlayer previous = player;
				player = createPlayer(audioSessionId);
				player.setListener(playerListener);
				previous.release();
				Log.i(TAG, "Playback engine is now " + playerConfig);

				if(wasPlaying && currentPlaying != null) {
					play(getCurrentPlayingIndex(), true, position);
				}
			}
		});
	}

	/**
	 * What the track being played is doing on its way to the speaker, in one line.
	 *
	 * The question worth answering is whether what leaves the file is what reaches the
	 * DAC, and on Android the usual answer is no: the platform mixer converts everything
	 * to the rate the output is running at. Which is only visible if something says so.
	 */
	public String getOutputPathDescription() {
		OutputInfo info = (player == null) ? null : player.getOutputInfo();
		if(info == null) {
			if(Constants.AUDIO_ENGINE_MEDIA_PLAYER.equals(playerConfig)) {
				// The platform player, which reports nothing about either end of itself.
				return getString(R.string.settings_audio_path_opaque);
			}
			return getString(R.string.settings_audio_path_idle);
		}

		String track = describeTrack(info);
		if(info.offloaded) {
			return getString(R.string.settings_audio_path_offloaded, track);
		}

		int outputRate = getDeviceOutputSampleRate();
		if(outputRate <= 0) {
			return getString(R.string.settings_audio_path_mixed, track);
		} else if(info.sampleRate > 0 && info.sampleRate != outputRate) {
			return getString(R.string.settings_audio_path_resampled, track, describeRate(outputRate));
		} else {
			return getString(R.string.settings_audio_path_matched, track, describeRate(outputRate));
		}
	}

	private String describeTrack(OutputInfo info) {
		StringBuilder description = new StringBuilder(describeCodec(info.codec));
		if(info.sampleRate > 0) {
			description.append(' ').append(describeRate(info.sampleRate));
		}
		if(info.bitDepth > 0) {
			description.append(' ').append(info.bitDepth).append("-bit");
		}
		if(info.channelCount == 1) {
			description.append(" mono");
		} else if(info.channelCount == 2) {
			description.append(" stereo");
		} else if(info.channelCount > 2) {
			description.append(' ').append(info.channelCount).append("ch");
		}

		// What the decoder handed over, when that is not what the recording holds. This is
		// the only place the difference shows: past the sink everything is whatever the
		// decoder made of it, and a file that was cut down on the way in looks native.
		String sink = info.sinkFormat;
		if(sink != null && info.bitDepth > 0 && !sink.equals(info.bitDepth + "-bit")) {
			boolean narrower = sink.endsWith("-bit")
					&& parseLeadingInt(sink) > 0
					&& parseLeadingInt(sink) < info.bitDepth;
			description.append(narrower ? ", cut to " : ", decoded to ").append(sink);
		}

		return description.toString();
	}

	/** The number a string like "16-bit" starts with, or 0 when it does not start with one. */
	private static int parseLeadingInt(String text) {
		int end = 0;
		while(end < text.length() && Character.isDigit(text.charAt(end))) {
			end++;
		}
		if(end == 0) {
			return 0;
		}
		try {
			return Integer.parseInt(text.substring(0, end));
		} catch(NumberFormatException e) {
			return 0;
		}
	}

	private static String describeCodec(String mimeType) {
		if(mimeType == null) {
			return "?";
		}
		switch(mimeType) {
			case "audio/mpeg": return "MP3";
			case "audio/mp4a-latm": return "AAC";
			case "audio/flac": return "FLAC";
			case "audio/opus": return "Opus";
			case "audio/vorbis": return "Vorbis";
			case "audio/raw": return "PCM";
			default:
				int slash = mimeType.indexOf('/');
				return (slash == -1 ? mimeType : mimeType.substring(slash + 1)).toUpperCase();
		}
	}

	private static String describeRate(int hz) {
		if(hz % 1000 == 0) {
			return (hz / 1000) + " kHz";
		}
		return String.format(Locale.US, "%.1f kHz", hz / 1000f);
	}

	/**
	 * The rate the device's own output runs at, which everything played through the mixer
	 * is converted to whether it started there or not. Zero when the platform will not say.
	 */
	private int getDeviceOutputSampleRate() {
		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		if(audioManager == null) {
			return 0;
		}

		try {
			String rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
			return rate == null ? 0 : Integer.parseInt(rate);
		} catch(Exception e) {
			return 0;
		}
	}

	/** Takes down the loopback proxy, for the paths that no longer need one. */
	private void stopProxy() {
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
	}

	private synchronized void bufferAndPlay() {
		bufferAndPlay(0);
	}
	private synchronized void bufferAndPlay(int position) {
		bufferAndPlay(position, true);
	}
	private synchronized void bufferAndPlay(int position, boolean start) {
		if(!currentPlaying.isCompleteFileAvailable() && !currentPlaying.isStream()) {
			if(Util.isAllowedToDownload(this)) {
				reset();

				bufferTask = new BufferTask(currentPlaying, position, start);
				bufferTask.execute();
			} else {
				next(false, start);
			}
		} else {
			doPlay(currentPlaying, position, start);
		}
	}

	private synchronized void doPlay(final DownloadFile downloadFile, final int position, final boolean start) {
		try {
			subtractPosition = 0;
			trackHandlers = null;
			pendingPlay = null;
			setPlayerState(IDLE);

			String dataSource;
			boolean isPartial = false;
			BufferFile bufferFile = null;
			if(downloadFile.isStream()) {
				dataSource = downloadFile.getStream();
				Log.i(TAG, "Data Source: " + dataSource);
			} else {
				downloadFile.setPlaying(true);
				final File file = downloadFile.isCompleteFileAvailable() ? downloadFile.getCompleteFile() : downloadFile.getPartialFile();
				isPartial = file.equals(downloadFile.getPartialFile());
				downloadFile.updateModificationDate();

				dataSource = file.getAbsolutePath();
				if (isPartial && !Util.isOffline(this)) {
					if (player.canReadGrowingFile()) {
						// The engine follows the file as it fills, so there is nothing for a
						// socket on this device to relay.
						bufferFile = downloadFile;
						stopProxy();
					} else {
						if (proxy == null) {
							proxy = new BufferProxy(this);
							proxy.start();
						}
						proxy.setBufferFile(downloadFile);
						dataSource = proxy.getPrivateAddress(dataSource);
						Log.i(TAG, "Data Source: " + dataSource);
					}
				} else {
					stopProxy();
				}
			}

			pendingPlay = new PendingPlay(downloadFile, position, start);
			trackHandlers = new TrackHandlers(downloadFile, isPartial, start);
			setPlayerState(PREPARING);

			// Told before the track is loaded, so that whatever the engine measures while
			// playing it comes back against the right id. A track started anywhere but at
			// its beginning is never measured, so it does not matter that this says nothing
			// about the position.
			player.setCurrentTrackId(downloadFile.getSong() == null
					? null : downloadFile.getSong().getId());
			player.prepare(dataSource, bufferFile);
		} catch (Exception x) {
			handleError(x);
		}
	}

	/**
	 * Takes up the track the engine has just reported ready: puts it where it is meant to
	 * start from, gives it its level, and either starts it or leaves it sitting.
	 */
	private void onTrackPrepared(PendingPlay pending) {
		try {
			setPlayerState(PREPARED);

			synchronized (DownloadService.this) {
				if (pending.position != 0) {
					Log.i(TAG, "Restarting player from position " + pending.position);
					player.seekTo(pending.position);
				}
				cachedPosition = pending.position;

				applyReplayGain(pending.downloadFile);

				if (pending.start || autoPlayStart) {
					player.start();
					applyPlaybackParamsMain();
					setPlayerState(STARTED);

					// Disable autoPlayStart after done
					autoPlayStart = false;
				} else {
					setPlayerState(PAUSED);
					onSongProgress();
				}

				updateRemotePlaylist();
			}

			// Only call when starting, setPlayerState(PAUSED) already calls this
			if(pending.start) {
				lifecycleSupport.serializeDownloadQueue();
			}
		} catch (Exception x) {
			handleError(x);
		}
	}

	private synchronized void setupNext(final DownloadFile downloadFile) {
		try {
			final File file = downloadFile.isCompleteFileAvailable() ? downloadFile.getCompleteFile() : downloadFile.getPartialFile();
			resetNext();

			// Exit when using remote controllers
			if(remoteState != LOCAL) {
				return;
			}

			nextTrackFile = downloadFile;
			setNextPlayerState(PREPARING);

			player.setNextTrackId(downloadFile.getSong() == null
					? null : downloadFile.getSong().getId());
			player.prepareNext(file.getPath());
		} catch (Exception x) {
			handleErrorNext(x);
		}
	}

	/**
	 * Arms the successor the engine has just reported ready, so it follows on without a
	 * gap, and gives it its level now rather than once it is already audible.
	 */
	private void onNextTrackPrepared() {
		final DownloadFile downloadFile = nextTrackFile;
		if(downloadFile == null) {
			return;
		}

		try {
			setNextPlayerState(PREPARED);

			if(playerState == PlayerState.STARTED || playerState == PlayerState.PAUSED) {
				player.setNextCrossfade(crossfadeForJoin(currentPlaying, downloadFile));
				player.armNext();
			}

			applyReplayGain(downloadFile, false);
		} catch (Exception x) {
			handleErrorNext(x);
		}
	}

	/**
	 * How long these two particular records should be overlapped for, in milliseconds.
	 *
	 * An album is meant to run one track into the next, so those are never faded together
	 * whatever they sound like. Everything else is decided on how the two records actually
	 * begin and end, where that has been heard before: a song that fades itself out, or in,
	 * already has the join the crossfade was going to provide, and laying another over it
	 * is what makes a song seem to start and then sink away. Two that start and stop dead
	 * are what the setting was turned on for and get all of it.
	 *
	 * A track neither of whose shapes is known gets the setting, which is what every join
	 * got before any of this - so the first time through a library nothing changes, and it
	 * settles as tracks come round again.
	 */
	private int crossfadeForJoin(DownloadFile from, DownloadFile to) {
		int asked = crossfadeMillis();
		if(asked <= 0 || continuesAlbum(from, to)) {
			return 0;
		}

		TrackEnvelope leaving = envelopeOf(from);
		TrackEnvelope arriving = envelopeOf(to);

		if(leaving != null && leaving.endsQuiet()) {
			Log.i(TAG, "No crossfade: " + name(from) + " takes itself away over "
					+ leaving.departureMs() + "ms");
			return 0;
		}
		if(arriving != null && arriving.startsQuiet()) {
			Log.i(TAG, "No crossfade: " + name(to) + " arrives on its own over "
					+ arriving.arrivalMs() + "ms");
			return 0;
		}

		if(leaving == null || arriving == null) {
			// Nothing heard of one of them yet. The setting stands.
			return asked;
		}

		if(leaving.endsCold() && arriving.startsCold()) {
			return asked;
		}

		// One of them eases in or out without going away altogether - a chord let ring, an
		// intro that builds. Something to fade across, but less of it.
		return asked / 2;
	}

	private TrackEnvelope envelopeOf(DownloadFile downloadFile) {
		if(downloadFile == null || downloadFile.getSong() == null) {
			return null;
		}
		return TrackEnvelopeStore.getInstance(this)
				.get(Util.getMostRecentActiveServer(this), downloadFile.getSong().getId());
	}

	private static String name(DownloadFile downloadFile) {
		return downloadFile == null || downloadFile.getSong() == null
				? "the track" : downloadFile.getSong().getTitle();
	}

	private void setupHandlers(final DownloadFile downloadFile, final boolean isPartial, final boolean isPlaying) {
		trackHandlers = new TrackHandlers(downloadFile, isPartial, isPlaying);
	}

	/**
	 * What the engine reports about the track being heard, and about the one queued behind
	 * it. Everything here is a decision the engine has no business making: what a track
	 * ending means depends on whether its file was finished downloading, which is the
	 * queue's business rather than the player's.
	 */
	private final LocalPlayer.Listener playerListener = new LocalPlayer.Listener() {
		@Override
		public void onPrepared() {
			PendingPlay pending = pendingPlay;
			if(pending == null) {
				// Reset while it was preparing, so nothing is owed for it any more.
				return;
			}
			pendingPlay = null;
			onTrackPrepared(pending);
		}

		@Override
		public void onCompletion() {
			TrackHandlers handlers = trackHandlers;
			if(handlers == null) {
				return;
			}
			onTrackCompleted(handlers);
		}

		@Override
		public void onError(Exception error) {
			TrackHandlers handlers = trackHandlers;
			if(handlers == null) {
				handleError(error);
				return;
			}
			onTrackError(handlers, error);
		}

		@Override
		public void onTrackMeasured(final String id, final TrackEnvelope envelope) {
			Log.i(TAG, "Measured " + id + ": " + envelope);
			// Off the thread a track ends on: this writes a file, and nothing about the
			// handover should wait on that.
			new SilentBackgroundTask<Void>(DownloadService.this) {
				@Override
				protected Void doInBackground() {
					TrackEnvelopeStore store = TrackEnvelopeStore.getInstance(DownloadService.this);
					store.put(Util.getMostRecentActiveServer(DownloadService.this), id, envelope);
					store.save();
					return null;
				}
			}.execute();
		}

		@Override
		public void onNextPrepared() {
			onNextTrackPrepared();
		}

		@Override
		public void onNextError(Exception error) {
			handleErrorNext(error);
		}
	};

	private void onTrackError(TrackHandlers handlers, Exception error) {
		final DownloadFile downloadFile = handlers.downloadFile;
		Log.w(TAG, "Error on playing file " + downloadFile + ": " + error);

		int pos = getPlayerPosition();
		reset();
		if (!handlers.isPartial || (downloadFile.isWorkDone() && (Math.abs(handlers.duration - pos) < 10000))) {
			playNext();
		} else {
			downloadFile.setPlaying(false);
			doPlay(downloadFile, pos, handlers.isPlaying);
			downloadFile.setPlaying(true);
		}
	}

	private void onTrackCompleted(TrackHandlers handlers) {
		final DownloadFile downloadFile = handlers.downloadFile;
		final int duration = handlers.duration;

		// Read before reset() below disarms it, since whether the successor was already
		// taken up is what says this end was a real one.
		boolean nextArmed = player.isNextArmed();

		setPlayerStateCompleted();

		int pos = getPlayerPosition();
		Log.i(TAG, "Ending position " + pos + " of " + duration);
		if (!handlers.isPartial || (downloadFile.isWorkDone() && (Math.abs(duration - pos) < 10000)) || nextArmed) {
			playNext();
			postPlayCleanup(downloadFile);
		} else {
			// If file is not completely downloaded, restart the playback from the current position.
			synchronized (DownloadService.this) {
				if (downloadFile.isWorkDone()) {
					// Complete was called early even though file is fully buffered
					Log.i(TAG, "Requesting restart from " + pos + " of " + duration);
					reset();
					downloadFile.setPlaying(false);
					doPlay(downloadFile, pos, true);
					downloadFile.setPlaying(true);
				} else {
					Log.i(TAG, "Requesting restart from " + pos + " of " + duration);
					reset();
					bufferTask = new BufferTask(downloadFile, pos, true);
					bufferTask.execute();
				}
			}
			checkDownloads();
		}
	}

	/** What the current track's completion and error handling needs to know about it. */
	private static class TrackHandlers {
		final DownloadFile downloadFile;
		final boolean isPartial;
		final boolean isPlaying;
		final int duration;

		TrackHandlers(DownloadFile downloadFile, boolean isPartial, boolean isPlaying) {
			this.downloadFile = downloadFile;
			this.isPartial = isPartial;
			this.isPlaying = isPlaying;
			this.duration = downloadFile.getSong().getDuration() == null ? 0 : downloadFile.getSong().getDuration() * 1000;
		}
	}

	/** What to do with a track once the engine reports it ready. */
	private static class PendingPlay {
		final DownloadFile downloadFile;
		final int position;
		final boolean start;

		PendingPlay(DownloadFile downloadFile, int position, boolean start) {
			this.downloadFile = downloadFile;
			this.position = position;
			this.start = start;
		}
	}

	public void setSleepTimerDuration(int duration){
		timerDuration = duration;
	}

	public void startSleepTimer(){
		if(sleepTimer != null){
			sleepTimer.cancel();
			sleepTimer.purge();
		}

		sleepTimer = new Timer();

		sleepTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				pause();
				sleepTimer.cancel();
				sleepTimer.purge();
				sleepTimer = null;
			}

		}, timerDuration * 60 * 1000);
		timerStart = System.currentTimeMillis();
	}

	public int getSleepTimeRemaining() {
		return (int) (timerStart + (timerDuration * 60 * 1000) - System.currentTimeMillis()) / 1000;
	}

	public void stopSleepTimer() {
		if(sleepTimer != null){
			sleepTimer.cancel();
			sleepTimer.purge();
		}
		sleepTimer = null;
	}

	public boolean getSleepTimer() {
		return sleepTimer != null;
	}

	public void setVolume(float volume) {
		if(player != null && (playerState == STARTED || playerState == PAUSED || playerState == STOPPED)) {
			try {
				this.volume = volume;
				player.setVolume(volume);

				// The session's boost is held back for as long as something else has us
				// ducked, so what the volume is turned down for decides whether the part
				// of the replay gain that cannot fit in a gain applies at all.
				reapplyVolume();
			} catch(Exception e) {
				Log.w(TAG, "Failed to set volume");
			}
		}
	}

	public void reapplyVolume() {
		applyReplayGain(currentPlaying);
	}

	public synchronized void swap(boolean mainList, DownloadFile from, DownloadFile to) {
		List<DownloadFile> list = mainList ? downloadList : backgroundDownloadList;
		swap(mainList, list.indexOf(from), list.indexOf(to));
	}
	public synchronized void swap(boolean mainList, int from, int to) {
		List<DownloadFile> list = mainList ? downloadList : backgroundDownloadList;
		int max = list.size();
		if(to >= max) {
			to = max - 1;
		}
		else if(to < 0) {
			to = 0;
		}

		DownloadFile movedSong = list.remove(from);
		list.add(to, movedSong);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
		if(mainList) {
			// Every renderer is told, not just the ones driven a track at a time. One that
			// holds the queue is playing the order it was given, and a move further down
			// the queue changes neither the current nor the next track - so the check below
			// never fires for it and the speaker would carry on with the old order, putting
			// out songs under the names of whatever DSub now has in those places.
			if(remoteState != LOCAL) {
				updateRemotePlaylist();
			}

			if(remoteState == LOCAL || (remoteController != null && remoteController.isNextSupported())) {
				// Moving next playing, current playing, or moving a song to be next playing
				if(movedSong == nextPlaying || movedSong == currentPlaying || (currentPlayingIndex + 1) == to) {
					setNextPlaying();
				}
			}
		}

		// Reordering changes which songs are within the preload window
		checkDownloads();
	}

	public synchronized void serializeQueue() {
		serializeQueue(true);
	}
	public synchronized void serializeQueue(boolean serializeRemote) {
		if(playerState == PlayerState.PAUSED) {
			lifecycleSupport.serializeDownloadQueue(serializeRemote);
		}
	}

	private void handleError(Exception x) {
		Log.w(TAG, "Media player error: " + x, x);
		if(player != null) {
			try {
				trackHandlers = null;
				pendingPlay = null;
				player.reset();
			} catch(Exception e) {
				Log.e(TAG, "Failed to reset player in error handler");
			}
		}
		setPlayerState(IDLE);
	}
	private void handleErrorNext(Exception x) {
		Log.w(TAG, "Next Media player error: " + x, x);
		try {
			nextTrackFile = null;
			player.clearNext();
		} catch(Exception e) {
			Log.e(TAG, "Failed to reset next media player", x);
		}
		setNextPlayerState(IDLE);
	}

	/**
	 * Whether the thing playing holds the queue itself, so that songs cannot be taken off
	 * the front of it without the music stopping.
	 *
	 * Shuffle play, artist radio and collection autoplay all drop what has been played as
	 * they top the queue up, which locally costs nothing. On a renderer holding its own
	 * queue the two no longer line up from the first entry, and the only answer
	 * {@link SonosController} has to that is to rebuild - taking the song being played
	 * down with it. Leaving the played songs where they are instead costs a queue that
	 * grows for as long as the cast lasts, which nobody hears.
	 */
	private boolean rendererHoldsQueue() {
		return remoteState != LOCAL && remoteController != null && remoteController.holdsQueue();
	}

	/**
	 * Starts downloading the song being played, whatever the ordinary scheduling would
	 * have chosen.
	 *
	 * Casting is otherwise a reason for the downloader not to hurry - the speaker is fed
	 * from the server, so filling the cache is a nicety, taken in queue order and subject
	 * to the preload count, which at 0 means the song is never reached at all. It stops
	 * being a nicety when something is waiting on the file itself: {@link SonosController}
	 * holds the start of a cast back until the first track is on disk.
	 */
	public synchronized void downloadCurrentPlaying() {
		DownloadFile downloadFile = currentPlaying;
		if(downloadFile == null || downloadFile.isWorkDone() || downloadFile.isStream()
				|| downloadFile.isFailedMax() || !Util.isAllowedToDownload(this)) {
			return;
		}
		// A task that has been queued but has not reached a thread yet reads as not
		// downloading, so this asks after the target rather than the state: starting a
		// second task for the same song would have both of them appending to one file.
		if(currentDownloading == downloadFile && !downloadFile.isFailed()) {
			return;
		}

		if(currentDownloading != null && currentDownloading != downloadFile) {
			currentDownloading.cancelDownload();
		}
		currentDownloading = downloadFile;
		currentDownloading.download();
		cleanupCandidates.add(currentDownloading);
	}

	public synchronized void checkDownloads() {
		if (!Util.isExternalStoragePresent() || !lifecycleSupport.isExternalStorageAvailable()) {
			return;
		}

		if(removePlayed && !rendererHoldsQueue()) {
			checkRemovePlayed();
		}
		if (shufflePlay) {
			checkShufflePlay();
		}
		if(artistRadio) {
			checkRadio(artistRadioBuffer);
		}
		if(sonicRadio) {
			checkRadio(sonicRadioBuffer);
		}
		if(collectionAutoplay) {
			checkCollectionAutoplay();
		}

		if (!Util.isAllowedToDownload(this)) {
			return;
		}

		if (downloadList.isEmpty() && backgroundDownloadList.isEmpty()) {
			return;
		}
		if(currentPlaying != null && currentPlaying.isStream()) {
			return;
		}

		// Need to download current playing and not casting?
		if (currentPlaying != null && remoteState == LOCAL && currentPlaying != currentDownloading && !currentPlaying.isWorkDone()) {
			// Cancel current download, if necessary.
			if (currentDownloading != null) {
				currentDownloading.cancelDownload();
			}

			currentDownloading = currentPlaying;
			currentDownloading.download();
			cleanupCandidates.add(currentDownloading);
		}

		// Find a suitable target for download.
		else if (currentDownloading == null || currentDownloading.isWorkDone() || currentDownloading.isFailed() && ((!downloadList.isEmpty() && remoteState == LOCAL) || !backgroundDownloadList.isEmpty())) {
			currentDownloading = null;
			int n = size();

			int preloaded = 0;

			if(n != 0 && (remoteState == LOCAL || Util.shouldCacheDuringCasting(this))) {
				int start = currentPlaying == null ? 0 : getCurrentPlayingIndex();
				if(start == -1) {
					start = 0;
				}
				int i = start;
				do {
					DownloadFile downloadFile = downloadList.get(i);
					if (!downloadFile.isWorkDone() && !downloadFile.isFailedMax()) {
						if (downloadFile.shouldSave() || preloaded < getPreloadCount()) {
							currentDownloading = downloadFile;
							currentDownloading.download();
							cleanupCandidates.add(currentDownloading);
							if(i == (start + 1)) {
								setNextPlayerState(DOWNLOADING);
							}
							break;
						}
					} else if (currentPlaying != downloadFile) {
						preloaded++;
					}

					i = (i + 1) % n;
				} while (i != start);
			}

			if((preloaded + 1 == n || preloaded >= getPreloadCount() || downloadList.isEmpty() || remoteState != LOCAL) && !backgroundDownloadList.isEmpty()) {
				for(int i = 0; i < backgroundDownloadList.size(); i++) {
					DownloadFile downloadFile = backgroundDownloadList.get(i);
					if(downloadFile.isWorkDone() && (!downloadFile.shouldSave() || downloadFile.isSaved()) || downloadFile.isFailedMax()) {
						// Don't need to keep list like active song list
						backgroundDownloadList.remove(i);
						revision++;
						i--;
					} else {
						currentDownloading = downloadFile;
						currentDownloading.download();
						cleanupCandidates.add(currentDownloading);
						break;
					}
				}
			}
		}

		if(!backgroundDownloadList.isEmpty()) {
			Notifications.showDownloadingNotification(this, this, handler, currentDownloading, backgroundDownloadList.size());
			downloadOngoing = true;
		} else if(backgroundDownloadList.isEmpty() && downloadOngoing) {
			Notifications.hideDownloadingNotification(this, this, handler);
			downloadOngoing = false;
		}

		// Delete obsolete .partial and .complete files.
		cleanup();
	}

	private synchronized void checkRemovePlayed() {
		boolean changed = false;
		SharedPreferences prefs = Util.getPreferences(this);
		int keepCount = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_KEEP_PLAYED_CNT, "0"));
		while(currentPlayingIndex > keepCount) {
			downloadList.remove(0);
			currentPlayingIndex = downloadList.indexOf(currentPlaying);
			changed = true;
		}

		if(changed) {
			revision++;
			onSongsChanged();
		}
	}

	private synchronized void checkShufflePlay() {

		// Get users desired random playlist size
		SharedPreferences prefs = Util.getPreferences(this);
		int listSize = Math.max(1, Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_RANDOM_SIZE, "20")));
		boolean wasEmpty = downloadList.isEmpty();

		long revisionBefore = revision;

		// First, ensure that list is at least 20 songs long.
		int size = size();
		if (size < listSize) {
			for (MusicDirectory.Entry song : shufflePlayBuffer.get(listSize - size)) {
				DownloadFile downloadFile = new DownloadFile(this, song, false);
				downloadList.add(downloadFile);
				revision++;
			}
		}

		int currIndex = currentPlaying == null ? 0 : getCurrentPlayingIndex();

		// Only shift playlist if playing song #5 or later.
		if (currIndex > 4 && !rendererHoldsQueue()) {
			int songsToShift = currIndex - 2;
			for (MusicDirectory.Entry song : shufflePlayBuffer.get(songsToShift)) {
				downloadList.add(new DownloadFile(this, song, false));
				downloadList.get(0).cancelDownload();
				downloadList.remove(0);
				revision++;
			}
		}
		currentPlayingIndex = downloadList.indexOf(currentPlaying);

		if (revisionBefore != revision) {
			onSongsChanged();
			updateRemotePlaylist();
		}

		if (wasEmpty && !downloadList.isEmpty()) {
			play(0);
		}
	}

	/**
	 * Keeps a radio's queue stocked, whichever radio it is: songs added ahead of where it
	 * has got to, and the ones well behind it dropped, so a queue that never ends does not
	 * grow without end either.
	 */
	private synchronized void checkRadio(RadioBuffer radioBuffer) {
		// Get users desired random playlist size
		SharedPreferences prefs = Util.getPreferences(this);
		int listSize = Math.max(1, Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_RANDOM_SIZE, "20")));
		boolean wasEmpty = downloadList.isEmpty();

		long revisionBefore = revision;

		// First, ensure that list is at least 20 songs long.
		int size = size();
		if (size < listSize) {
			for (MusicDirectory.Entry song : radioBuffer.get(listSize - size)) {
				DownloadFile downloadFile = new DownloadFile(this, song, false);
				downloadList.add(downloadFile);
				revision++;
			}
		}

		int currIndex = currentPlaying == null ? 0 : getCurrentPlayingIndex();

		// Only shift playlist if playing song #5 or later.
		if (currIndex > 4 && !rendererHoldsQueue()) {
			int songsToShift = currIndex - 2;
			for (MusicDirectory.Entry song : radioBuffer.get(songsToShift)) {
				downloadList.add(new DownloadFile(this, song, false));
				downloadList.get(0).cancelDownload();
				downloadList.remove(0);
				revision++;
			}
		}
		currentPlayingIndex = downloadList.indexOf(currentPlaying);

		if (revisionBefore != revision) {
			onSongsChanged();
			updateRemotePlaylist();
		}

		if (wasEmpty && !downloadList.isEmpty()) {
			play(0);
		}
	}

	/**
	 * Unlike shuffle play and artist radio, this tops the queue up an album at a time
	 * rather than to a song count, so the queue reads as the album being listened to.
	 *
	 * The next album is drawn as soon as the last track of the current one starts, which
	 * leaves a whole track's worth of time to buffer it. The buffer keeps albums ready in
	 * the background, so this is normally instant.
	 */
	private synchronized void checkCollectionAutoplay() {
		boolean wasEmpty = downloadList.isEmpty();
		long revisionBefore = revision;

		int currIndex = currentPlaying == null ? 0 : getCurrentPlayingIndex();
		if (size() - currIndex <= 1) {
			List<MusicDirectory.Entry> album = collectionAutoplayBuffer.getNextAlbum();
			if (album.isEmpty() && collectionAutoplayBuffer.isExhausted()) {
				// Every album has played and the buffer is drained, so autoplay is over.
				// Switching it off here rather than leaving the flag set is what lets the
				// pill go back to "Autoplay" and stops this running on every queue check.
				// What is already queued is untouched and plays out.
				Log.i(TAG, "Collection autoplay finished - the collection is spent.");
				setCollectionAutoplay(null, null, null);
			} else if (!album.isEmpty()) {
				// Drop the album just played, or the queue grows without bound. The song
				// still playing is kept so it can finish and so Previous still works.
				if (currentPlaying != null && downloadList.contains(currentPlaying) && !rendererHoldsQueue()) {
					while (downloadList.get(0) != currentPlaying) {
						downloadList.get(0).cancelDownload();
						downloadList.remove(0);
						revision++;
					}
				}

				for (MusicDirectory.Entry song : album) {
					downloadList.add(new DownloadFile(this, song, false));
					revision++;
				}
			}
		}
		currentPlayingIndex = downloadList.indexOf(currentPlaying);

		if (revisionBefore != revision) {
			onSongsChanged();
			updateRemotePlaylist();
		}

		if (wasEmpty && !downloadList.isEmpty()) {
			play(0);
		}
	}

	public long getDownloadListUpdateRevision() {
		return revision;
	}

	private synchronized void cleanup() {
		Iterator<DownloadFile> iterator = cleanupCandidates.iterator();
		while (iterator.hasNext()) {
			DownloadFile downloadFile = iterator.next();
			if (downloadFile != currentPlaying && downloadFile != currentDownloading) {
				if (downloadFile.cleanup()) {
					iterator.remove();
				}
			}
		}
	}

	public void postPlayCleanup() {
		postPlayCleanup(currentPlaying);
	}
	public void postPlayCleanup(DownloadFile downloadFile) {
		if(downloadFile == null) {
			return;
		}

		// Finished loading, delete when list is cleared
		if (downloadFile.getSong() instanceof PodcastEpisode) {
			toDelete.add(downloadFile);
		}
		clearCurrentBookmark(downloadFile.getSong(), true);
	}

	public RemoteControlClientBase getRemoteControlClient() {
		return mRemoteControl;
	}
	
	private boolean isPastCutoff() {
		return isPastCutoff(getPlayerPosition(), getPlayerDuration());
	}
	private boolean isPastCutoff(int position, int duration) {
		return isPastCutoff(position, duration, false);
	}
	private boolean isPastCutoff(int position, int duration, boolean allowSkipping) {
		if(currentPlaying == null) {
			return false;
		}

		// Make cutoff a maximum of 10 minutes
		int cutoffPoint = Math.max((int) (duration * DELETE_CUTOFF), duration - 10 * 60 * 1000);
		boolean isPastCutoff = duration > 0 && position > cutoffPoint;
		
		// Check to make sure song isn't within 10 seconds of where it was created
		MusicDirectory.Entry entry = currentPlaying.getSong();
		if(entry != null && entry.getBookmark() != null) {
			Bookmark bookmark = entry.getBookmark();
			if(position < (bookmark.getPosition() + 10000)) {
				isPastCutoff = false;
			}
		}
		
		// Check to make sure we aren't in a series of similar content before deleting bookmark
		if(isPastCutoff && allowSkipping) {
			// Check to make sure:
			// Is an audio book
			// Next playing exists and is not a wrap around or a shuffle
			// Next playing is from same context as current playing, so not at end of list
			if(entry.isAudioBook() && nextPlaying != null && downloadList.indexOf(nextPlaying) != 0 && !shufflePlay
					&& entry.getParent() != null && entry.getParent().equals(nextPlaying.getSong().getParent())) {
				isPastCutoff = false;
			}
		}
		
		return isPastCutoff;
	}
	
	private void clearCurrentBookmark() {
		clearCurrentBookmark(false);
	}
	private void clearCurrentBookmark(boolean checkDelete) {
		// If current is null, nothing to do
		if(currentPlaying == null) {
			return;
		}

		clearCurrentBookmark(currentPlaying.getSong(), checkDelete);
	}
	private void clearCurrentBookmark(final MusicDirectory.Entry entry, boolean checkDelete) {
		// If no bookmark, move on
		if(entry.getBookmark() == null) {
			return;
		}
		
		// If delete is not specified, check position
		if(!checkDelete) {
			checkDelete = isPastCutoff();
		}
		
		// If supposed to delete
		if(checkDelete) {
			new SilentBackgroundTask<Void>(this) {
				@Override
				public Void doInBackground() throws Throwable {
					MusicService musicService = MusicServiceFactory.getMusicService(DownloadService.this);
					entry.setBookmark(null);
					musicService.deleteBookmark(entry, DownloadService.this, null);

					MusicDirectory.Entry found = UpdateView.findEntry(entry);
					if(found != null) {
						found.setBookmark(null);
					}
					return null;
				}

				@Override
				public void error(Throwable error) {
					Log.e(TAG, "Failed to delete bookmark", error);
					
					String msg;
					if(error instanceof OfflineException || error instanceof ServerTooOldException) {
						msg = getErrorMessage(error);
					} else {
						msg = DownloadService.this.getResources().getString(R.string.bookmark_deleted_error, entry.getTitle()) + " " + getErrorMessage(error);
					}
					
					Util.toast(DownloadService.this, msg, false);
				}
			}.execute();
		}
	}

	private void checkAddBookmark() {
		checkAddBookmark(false);
	}
	private void checkAddBookmark(final boolean updateMetadata) {
		// Don't do anything if no current playing
		if(currentPlaying == null || !ServerInfo.canBookmark(this)) {
			return;
		}
		
		final MusicDirectory.Entry entry = currentPlaying.getSong();
		int duration = getPlayerDuration();
		
		// If song is podcast or long go ahead and auto add a bookmark
		if(entry.isPodcast() || entry.isAudioBook() || duration > (10L * 60L * 1000L)) {
			final Context context = this;
			final int position = getPlayerPosition();

			// Don't bother when at beginning
			if(position < 5000L) {
				return;
			}

			new SilentBackgroundTask<Void>(context) {
				@Override
				public Void doInBackground() throws Throwable {
					MusicService musicService = MusicServiceFactory.getMusicService(context);
					entry.setBookmark(new Bookmark(position));
					musicService.createBookmark(entry, position, "Auto created by DSub", context, null);

					MusicDirectory.Entry found = UpdateView.findEntry(entry);
					if(found != null) {
						found.setBookmark(new Bookmark(position));
					}
					if(updateMetadata) {
						onMetadataUpdate(METADATA_UPDATED_BOOKMARK);
					}
					
					return null;
				}
				
				@Override
				public void error(Throwable error) {
					Log.w(TAG, "Failed to create automatic bookmark", error);
					
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
	}

	/**
	 * The correction this song's tags ask for, in dB, or zero when replay gain is off.
	 *
	 * Both sliders read 0 dB when the preference behind them has never been written, which
	 * is the usual state of them: a SeekBarPreference only writes itself when its dialog
	 * is closed, so seeding defaults on startup does not reach them. An unset slider has
	 * to mean what it shows, which is no change.
	 */
	private float replayGainAdjust(DownloadFile downloadFile) {
		SharedPreferences prefs = Util.getPreferences(this);
		if(!prefs.getBoolean(Constants.PREFERENCES_KEY_REPLAY_GAIN, false)) {
			return 0f;
		}

		try {
			// The file, whether it is all here or still arriving: getFile() hands over the
			// part-downloaded one when there is nothing better, and a FLAC or an Ogg carries
			// its tags at the head, so a track being streamed is usually readable within the
			// first few hundred kilobytes - well inside the buffer playback waits for anyway.
			boolean whole = downloadFile.isCompleteFileAvailable();
			BastpUtil.GainValues rg = mBastpUtil.getReplayGainValues(
					downloadFile.getFile().getCanonicalPath(), !whole); /* track, album */

			if(!rg.found && applyServerGain(rg, downloadFile.getSong())) {
				// Nothing readable in the file. Either its first bytes have not arrived, or
				// it keeps its tags at the end where a part-downloaded copy cannot reach
				// them - an APE tag, or an ID3v1 one.
				Log.i(TAG, "Replay gain for " + downloadFile.getSong().getTitle()
						+ " came from the server rather than the file");
			}
			boolean singleAlbum = false;

			String replayGainType = prefs.getString(Constants.PREFERENCES_KEY_REPLAY_GAIN_TYPE, "1");
			// 1 => Smart replay gain
			if("1".equals(replayGainType)) {
				singleAlbum = isAlbumPlaythrough(downloadFile);
			}
			// 2 => Use album tags
			else if("2".equals(replayGainType)) {
				singleAlbum = true;
			}
			// 3 => Use track tags
			// Already false, no need to do anything here

			float adjust;

			// If playing a single album or no track gain, use album gain (if set)
			if((singleAlbum || rg.track == 0) && rg.album != 0) {
				adjust = rg.album;
			} else {
				// Otherwise, give priority to track gain
				adjust = rg.track;
			}

			if (!rg.found) {
				/* No RG value found: decrease volume for untagged song if requested by user */
				int untagged = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_REPLAY_GAIN_UNTAGGED, "150"));
				adjust = (untagged - 150) / 10f;
			} else {
				int bump = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_REPLAY_GAIN_BUMP, "150"));
				adjust += (bump - 150) / 10f;
			}

			return adjust;
		} catch(IOException e) {
			Log.w(TAG, "Failed to read replay gain values", e);
			return 0f;
		}
	}

	/**
	 * Asks the server for the replay gain of tracks that came out of a listing without it,
	 * and levels them again once it answers.
	 *
	 * A listing carries no gain of its own, so this is what is left for a track whose file
	 * could not be read - one that keeps its tags at the end, or one whose first bytes have
	 * not arrived yet. Fetched for a window of the queue rather than the one track, since the
	 * request costs the same either way and the track after this one is about to want it too.
	 *
	 * The track being heard right now may already have started by the time this comes back,
	 * so its level settles a moment in rather than at the downbeat. The track queued behind
	 * it is asked for long before it is audible, which is the case that matters.
	 */
	private void fetchGainDetails(DownloadFile downloadFile) {
		if(downloadFile == null || Util.isOffline(this)) {
			return;
		}

		final MusicDirectory.Entry song = downloadFile.getSong();
		if(song == null || song.getId() == null || song.getReplayGainTrack() != null
				|| song.getReplayGainAlbum() != null || gainDetailsAsked.contains(song.getId())) {
			return;
		}

		// A track already on this device is read from its own tags, so there is nothing
		// here worth a request for it.
		if(downloadFile.isCompleteFileAvailable()) {
			return;
		}

		final List<MusicDirectory.Entry> window = gainWindow(downloadFile);
		for(MusicDirectory.Entry entry : window) {
			gainDetailsAsked.add(entry.getId());
		}

		new SilentBackgroundTask<Void>(this) {
			@Override
			protected Void doInBackground() throws Throwable {
				MusicServiceFactory.getMusicService(DownloadService.this)
						.fillSongDetails(window, DownloadService.this);
				return null;
			}

			@Override
			protected void done(Void result) {
				// Whatever arrived is on the entries now, so the levels are worked out
				// again from them. Only for the two tracks the player is actually holding.
				if(currentPlaying != null && window.contains(currentPlaying.getSong())) {
					applyReplayGain(currentPlaying, true);
				}
				if(nextPlaying != null && window.contains(nextPlaying.getSong())) {
					applyReplayGain(nextPlaying, false);
				}
			}

			@Override
			protected void error(Throwable error) {
				// Quiet: the tracks stay at whatever their files said, which is where they
				// were before any of this.
				Log.w(TAG, "Could not fetch replay gain from the server", error);
			}
		}.execute();
	}

	/** The track asked about and the few behind it, which are the ones about to be played. */
	private synchronized List<MusicDirectory.Entry> gainWindow(DownloadFile from) {
		List<MusicDirectory.Entry> window = new ArrayList<MusicDirectory.Entry>();
		int start = downloadList.indexOf(from);
		if(start == -1) {
			window.add(from.getSong());
			return window;
		}

		for(int i = start; i < downloadList.size() && window.size() < GAIN_WINDOW; i++) {
			MusicDirectory.Entry song = downloadList.get(i).getSong();
			if(song != null && song.getId() != null) {
				window.add(song);
			}
		}
		return window;
	}

	/**
	 * Replay gain as the server worked it out, for a track whose file is not here to be read.
	 *
	 * Taken only when the file had nothing to say, so a downloaded track is still levelled
	 * by its own tags - those being what the bytes actually carry, and what every other
	 * player reading that file would use. Both figures are filled in together, so the album
	 * and track choice further down is made the same way whichever source they came from.
	 */
	private static boolean applyServerGain(BastpUtil.GainValues rg, MusicDirectory.Entry song) {
		if(song == null) {
			return false;
		}

		Float track = song.getReplayGainTrack();
		Float album = song.getReplayGainAlbum();
		if(track == null && album == null) {
			return false;
		}

		rg.track = (track == null) ? 0 : track;
		rg.album = (album == null) ? 0 : album;
		rg.found = true;
		return true;
	}

	/**
	 * Whether this track is being heard as part of its album rather than as one entry in a
	 * mix of them, which is what decides between album and track gain.
	 *
	 * Album gain keeps the levels the record was mastered with, so the quiet track stays
	 * quieter than the loud one either side of it. That is what an album is meant to sound
	 * like, and it is only wanted while the album is being played through - in a playlist
	 * the same thing reads as one track coming in wrong, so there each is levelled on its
	 * own.
	 *
	 * The queue is what gets looked at rather than what the user tapped, because the queue
	 * is all that is left once it has been round a restart: it comes back off disk as a
	 * list of songs, with nothing of the action that filled it. An album played through
	 * sits there as an unbroken run of one album in its own order; a playlist that happens
	 * to put two tracks of an album next to each other almost never does.
	 */
	private boolean isAlbumPlaythrough(DownloadFile downloadFile) {
		int index = downloadList.indexOf(downloadFile);
		if(index == -1) {
			return false;
		}

		String album = albumKeyOf(downloadList.get(index).getSong());
		if(album == null) {
			return false;
		}

		int start = index;
		while(start > 0 && album.equals(albumKeyOf(downloadList.get(start - 1).getSong()))) {
			start--;
		}
		int end = index;
		while(end + 1 < downloadList.size() && album.equals(albumKeyOf(downloadList.get(end + 1).getSong()))) {
			end++;
		}

		// One track of an album is never a playthrough of it, however it got here.
		if(end == start) {
			return false;
		}
		// The whole queue is this one album, so whatever order it is in and whatever its
		// tracks are numbered, an album is what is playing.
		if(start == 0 && end == downloadList.size() - 1) {
			return true;
		}

		// Sitting among other things, so the run has to look like the album itself rather
		// than a stretch of a playlist that happens to come from one: every track in
		// order, from the first.
		Integer first = downloadList.get(start).getSong().getTrack();
		if(first == null || first.intValue() != 1) {
			return false;
		}
		for(int i = start; i < end; i++) {
			if(!followsInAlbum(downloadList.get(i).getSong(), downloadList.get(i + 1).getSong())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * What decides that two queue entries are the same album. The id where there is one,
	 * since two albums can share a name, and the name where there is not - an offline song
	 * read off its tags has no id to go on.
	 */
	private static String albumKeyOf(MusicDirectory.Entry song) {
		if(song == null || song.isDirectory()) {
			return null;
		}

		String albumId = song.getAlbumId();
		if(albumId != null && albumId.length() > 0) {
			return "id:" + albumId;
		}

		String album = song.getAlbum();
		return (album != null && album.length() > 0) ? "name:" + album : null;
	}

	/** Whether one track of an album is the one the album puts after the other. */
	private static boolean followsInAlbum(MusicDirectory.Entry previous, MusicDirectory.Entry next) {
		Integer previousTrack = previous.getTrack();
		Integer track = next.getTrack();
		if(previousTrack == null || track == null) {
			return false;
		}
		if(track.intValue() == previousTrack.intValue() + 1) {
			return true;
		}

		// A second disc numbers its tracks from one again, so dropping back to the first
		// track is where the album carries on rather than where it stops.
		return track.intValue() == 1 && discOf(next) == discOf(previous) + 1;
	}

	/** A track's disc, with an untagged one taken to be the only disc there is. */
	private static int discOf(MusicDirectory.Entry song) {
		Integer disc = song.getDiscNumber();
		return disc == null ? 1 : disc.intValue();
	}

	/**
	 * Whether one track runs on into the other as part of the same album being played
	 * through, rather than the two of them just happening to follow each other in a queue.
	 */
	private boolean continuesAlbum(DownloadFile from, DownloadFile to) {
		if(from == null || to == null) {
			return false;
		}

		String album = albumKeyOf(from.getSong());
		return album != null && album.equals(albumKeyOf(to.getSong())) && isAlbumPlaythrough(to);
	}

	/**
	 * Follows the headphones: whichever output the music is going to, its own correction is
	 * put on, and an output with none gets a flat one.
	 *
	 * Called for every change of output rather than for every track, since it is the
	 * headphones this belongs to and not the music. Nothing of it reaches a speaker being
	 * cast to - that plays the file itself - which is no loss, a headphone correction being
	 * for headphones.
	 */
	public void applyHeadphoneEqualizer() {
		if(dynamicsEqualizer == null) {
			return;
		}

		String device = HeadphoneProfiles.currentDevice(this);
		AutoEqProfile profile = HeadphoneProfiles.profileFor(this, device);
		if(profile == null) {
			dynamicsEqualizer.clearProfile();
			Log.i(TAG, "No headphone correction for " + device);
		} else {
			dynamicsEqualizer.applyProfile(profile);
			Log.i(TAG, "Headphone correction for " + device + ": " + profile.getFilterCount()
					+ " filters, preamp " + profile.getPreampDb() + "dB");
		}
	}

	/**
	 * Watches the outputs coming and going. The audio framework is asked rather than the
	 * Bluetooth stack, so no permission is needed and nothing here is at the mercy of what
	 * the app is allowed to know in the background.
	 */
	private void registerAudioDeviceCallback() {
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return;
		}

		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		if(audioManager == null) {
			return;
		}

		audioDeviceCallback = new AudioDeviceCallback() {
			@Override
			public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
				applyHeadphoneEqualizer();
			}

			@Override
			public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
				applyHeadphoneEqualizer();
			}
		};
		audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
	}

	private void unregisterAudioDeviceCallback() {
		if(audioDeviceCallback == null) {
			return;
		}

		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		if(audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
		}
		audioDeviceCallback = null;
	}

	/**
	 * The correction to ask a renderer for, in dB, or zero when it is not wanted.
	 *
	 * Behind its own setting rather than the main one, because a speaker plays the file
	 * itself: the only lever is its volume dial, which is the user's dial moving under
	 * them, and by how much rests on an estimate of what a step on it is worth.
	 */
	float remoteReplayGainAdjust(DownloadFile downloadFile) {
		if(downloadFile == null || !Util.getPreferences(this).getBoolean(Constants.PREFERENCES_KEY_REPLAY_GAIN_CAST, false)) {
			return 0f;
		}

		return replayGainAdjust(downloadFile);
	}

	private void applyReplayGain(DownloadFile downloadFile) {
		applyReplayGain(downloadFile, true);
	}

	/**
	 * @param current whether this is the track being heard. The gain belongs to the track
	 *		rather than to the player, so the one waiting its turn can be given its own well
	 *		in advance - but the boost belongs to the audio session the two of them share,
	 *		and claiming it for a track that has not started yet lands it on the one still
	 *		playing. The handover in {@link #playNext(boolean)} hands the session over too.
	 */
	private void applyReplayGain(DownloadFile downloadFile, boolean current) {
		if(currentPlaying == null || player == null) {
			return;
		}

		fetchGainDetails(downloadFile);
		float adjust = replayGainAdjust(downloadFile);

		// Turning a track down is a gain on the track. Turning one up cannot be: a gain
		// above 1 has nowhere to go but into clipping, which is what the clamp is here for.
		// Whatever is left over is asked of the session instead.
		//
		// Nothing about how loud the user wants things comes into this. That is a volume
		// rather than a property of the recording, it changes for its own reasons, and the
		// engine multiplies the two together on the way out.
		float attenuate = Math.min(adjust, 0f);
		float trackGain = (float) Math.pow(10, (attenuate / 20));
		if (trackGain > 1.0f) {
			trackGain = 1.0f;
		} else if (trackGain < 0.0f) {
			trackGain = 0.0f;
		}

		player.setTrackGain(trackGain, current);
		Log.i(TAG, "Replay gain of " + adjust + "dB puts the track's gain at " + trackGain);

		if(current) {
			applyReplayGainBoost(Math.max(adjust, 0f));
		}
	}

	/**
	 * Adds the gain the player's own volume cannot, for the quiet recordings whose tags
	 * ask to be turned up rather than down. Asked of the session's loudness enhancer, which
	 * is shared with the slider on the equalizer screen and covers the gapless handover
	 * because both players share the session.
	 *
	 * The enhancer compresses what it lifts, so it is only ever asked for the part that
	 * has nowhere else to go, and never while something else has ducked us - a boost then
	 * would work against what the ducking is for.
	 */
	private void applyReplayGainBoost(float gainDb) {
		if(volume < 1.0f) {
			gainDb = 0f;
		}

		if(effectsController == null) {
			return;
		}

		LoudnessEnhancerController controller = effectsController.getLoudnessEnhancerController();
		if(controller != null) {
			controller.setReplayGain((int) (gainDb * 100));
		}
	}

	public void setPlaybackSpeed(float playbackSpeed) {
		if(currentPlaying.isSong())
			Util.getPreferences(this).edit().putFloat(Constants.PREFERENCES_KEY_SONG_PLAYBACK_SPEED, playbackSpeed).commit();
		else
			Util.getPreferences(this).edit().putFloat(Constants.PREFERENCES_KEY_PLAYBACK_SPEED, playbackSpeed).commit();
		if(player != null && (playerState == PREPARED || playerState == STARTED || playerState == PAUSED || playerState == PAUSED_TEMP)) {
			applyPlaybackParamsMain();
		}

		delayUpdateProgress = Math.round(DEFAULT_DELAY_UPDATE_PROGRESS / playbackSpeed);
	}
	private void resetPlaybackSpeed() {
		Util.getPreferences(this).edit().remove(Constants.PREFERENCES_KEY_PLAYBACK_SPEED).commit();
		Util.getPreferences(this).edit().remove(Constants.PREFERENCES_KEY_SONG_PLAYBACK_SPEED).commit();
	}

	public float getPlaybackSpeed() {
		if (currentPlaying == null)
			return  1.0f;
		else {
			if (currentPlaying.isSong())
				return Util.getPreferences(this).getFloat(Constants.PREFERENCES_KEY_SONG_PLAYBACK_SPEED, 1.0f);
			else
				return Util.getPreferences(this).getFloat(Constants.PREFERENCES_KEY_PLAYBACK_SPEED, 1.0f);
		}
	}

	private synchronized void applyPlaybackParamsMain() {
		if(player != null) {
			player.setPlaybackSpeed(getPlaybackSpeed());
		}
	}
	private synchronized boolean isNextPlayingSameAlbum() {
		return isNextPlayingSameAlbum(currentPlaying, nextPlaying);
	}
	private synchronized boolean isNextPlayingSameAlbum(DownloadFile currentPlaying, DownloadFile nextPlaying) {
		if(currentPlaying == null || nextPlaying == null) {
			return false;
		} else {
			return currentPlaying.getSong().getAlbum().equals(nextPlaying.getSong().getAlbum());
		}
	}

	public void toggleStarred() {
		final DownloadFile currentPlaying = this.currentPlaying;
		if(currentPlaying == null) {
			return;
		}

		UpdateHelper.toggleStarred(this, currentPlaying.getSong(), new UpdateHelper.OnStarChange() {
			@Override
			public void starChange(boolean starred) {
				if(currentPlaying == DownloadService.this.currentPlaying) {
					onMetadataUpdate(METADATA_UPDATED_STAR);
				}
			}

			@Override
			public void starCommited(boolean starred) {

			}
		});
	}
	public void toggleRating(int rating) {
		if(currentPlaying == null) {
			return;
		}

		MusicDirectory.Entry entry = currentPlaying.getSong();
		if(entry.getRating() == rating) {
			setRating(0);
		} else {
			setRating(rating);
		}
	}
	public void setRating(int rating) {
		final DownloadFile currentPlaying = this.currentPlaying;
		if (currentPlaying == null) {
			return;
		}
		MusicDirectory.Entry entry = currentPlaying.getSong();

		// Immediately skip to the next song if down thumbed
		if (rating == 1 && size() > 1) {
			next(true);
		} else if (rating == 1 && size() == 1) {
			stop();
		}

		UpdateHelper.setRating(this, entry, rating, new UpdateHelper.OnRatingChange() {
			@Override
			public void ratingChange(int rating) {
				if (currentPlaying == DownloadService.this.currentPlaying) {
					onMetadataUpdate(METADATA_UPDATED_RATING);
				}
			}
		});
	}
	public void acquireWakelock() {
		acquireWakelock(30000);
	}
	public void acquireWakelock(int ms) {
		wakeLock.acquire(ms);
	}

	public void handleKeyEvent(KeyEvent keyEvent) {
		lifecycleSupport.handleKeyEvent(keyEvent);
	}

	public void addOnSongChangedListener(OnSongChangedListener listener) {
		addOnSongChangedListener(listener, false);
	}
	public void addOnSongChangedListener(OnSongChangedListener listener, boolean run) {
		onSongChangedListeners.addIfAbsent(listener);

		if(run) {
			if(mediaPlayerHandler != null) {
				mediaPlayerHandler.post(new Runnable() {
					@Override
					public void run() {
						onSongsChanged();
						onSongProgress();
						onStateUpdate();
						onMetadataUpdate(METADATA_UPDATED_ALL);
					}
				});
			} else {
				runListenersOnInit = true;
			}
		}
	}
	public void removeOnSongChangeListener(OnSongChangedListener listener) {
		onSongChangedListeners.remove(listener);
	}

	private void onSongChanged() {
		final long atRevision = revision;
		final boolean shouldFastForward = shouldFastForward();
		for (final OnSongChangedListener listener : onSongChangedListeners) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (revision == atRevision && instance != null) {
						listener.onSongChanged(currentPlaying, currentPlayingIndex, shouldFastForward);

						MusicDirectory.Entry entry = currentPlaying != null ? currentPlaying.getSong() : null;
						listener.onMetadataUpdate(entry, METADATA_UPDATED_ALL);
					}
				}
			});
		}

		if (mediaPlayerHandler != null && !onSongChangedListeners.isEmpty()) {
			mediaPlayerHandler.post(new Runnable() {
				@Override
				public void run() {
					onSongProgress();
				}
			});
		}
	}
	private void onSongsChanged() {
		final long atRevision = revision;
		final boolean shouldFastForward = shouldFastForward();
		for (final OnSongChangedListener listener : onSongChangedListeners) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (revision == atRevision && instance != null) {
						listener.onSongsChanged(downloadList, currentPlaying, currentPlayingIndex, shouldFastForward);
					}
				}
			});
		}
	}

	private void onSongProgress() {
		onSongProgress(true);
	}
	private synchronized void onSongProgress(boolean manual) {
		if(playerState == PlayerState.STARTED) {
			reportPlaybackState("playing", false);
		}

		final long atRevision = revision;
		final Integer duration = getPlayerDuration();
		final boolean isSeekable = isSeekable();
		final int position = getPlayerPosition();
		final int index = getCurrentPlayingIndex();
		final int queueSize = size();

		for (final OnSongChangedListener listener : onSongChangedListeners) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (revision == atRevision && instance != null) {
						listener.onSongProgress(currentPlaying, position, duration, isSeekable);
					}
				}
			});
		}

		if(manual) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					if(mRemoteControl != null) {
						mRemoteControl.setPlaybackState(playerState.getRemoteControlClientPlayState(), index, queueSize);
					}
				}
			});
		}

		// Setup next playing at least a couple of seconds into the song since both Chromecast and some DLNA clients report PLAYING when still PREPARING
		if(position > 2000 && remoteController != null && remoteController.isNextSupported()) {
			if(playerState == STARTED && nextPlayerState == IDLE) {
				setNextPlaying();
			}
		}
	}
	private void onStateUpdate() {
		final long atRevision = revision;
		for (final OnSongChangedListener listener : onSongChangedListeners) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (revision == atRevision && instance != null) {
						listener.onStateUpdate(currentPlaying, playerState);
					}
				}
			});
		}
	}
	public void onMetadataUpdate() {
		onMetadataUpdate(METADATA_UPDATED_ALL);
	}
	public void onMetadataUpdate(final int updateType) {
		for (final OnSongChangedListener listener : onSongChangedListeners) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (instance != null) {
						MusicDirectory.Entry entry = currentPlaying != null ? currentPlaying.getSong() : null;
						listener.onMetadataUpdate(entry, updateType);
					}
				}
			});
		}

		handler.post(new Runnable() {
			@Override
			public void run() {
				if(currentPlaying != null) {
					mRemoteControl.metadataChanged(currentPlaying.getSong());
				}
			}
		});
	}

	private class BufferTask extends SilentBackgroundTask<Void> {
		private final DownloadFile downloadFile;
		private final int position;
		private final long expectedFileSize;
		private final File partialFile;
		private final boolean start;

		public BufferTask(DownloadFile downloadFile, int position, boolean start) {
			super(instance);
			this.downloadFile = downloadFile;
			this.position = position;
			partialFile = downloadFile.getPartialFile();
			this.start = start;

			// Calculate roughly how many bytes BUFFER_LENGTH_SECONDS corresponds to.
			int bitRate = downloadFile.getBitRate();
			long byteCount = Math.max(100000, bitRate * 1024L / 8L * 5L);

			// Find out how large the file should grow before resuming playback.
			Log.i(TAG, "Buffering from position " + position + " and bitrate " + bitRate);
			expectedFileSize = (position * bitRate / 8) + byteCount;
		}

		@Override
		public Void doInBackground() throws InterruptedException {
			setPlayerState(DOWNLOADING);

			while (!bufferComplete()) {
				Thread.sleep(1000L);
				if (isCancelled() || downloadFile.isFailedMax()) {
					return null;
				} else if(!downloadFile.isFailedMax() && !downloadFile.isDownloading()) {
					checkDownloads();
				}
			}
			doPlay(downloadFile, position, start);

			return null;
		}

		private boolean bufferComplete() {
			boolean completeFileAvailable = downloadFile.isWorkDone();
			long size = partialFile.length();

			Log.i(TAG, "Buffering " + partialFile + " (" + size + "/" + expectedFileSize + ", " + completeFileAvailable + ")");
			return completeFileAvailable || size >= expectedFileSize;
		}

		@Override
		public String toString() {
			return "BufferTask (" + downloadFile + ")";
		}
	}

	private class CheckCompletionTask extends SilentBackgroundTask<Void> {
		private final DownloadFile downloadFile;
		private final File partialFile;

		public CheckCompletionTask(DownloadFile downloadFile) {
			super(instance);
			this.downloadFile = downloadFile;
			if(downloadFile != null) {
				partialFile = downloadFile.getPartialFile();
			} else {
				partialFile = null;
			}
		}

		@Override
		public Void doInBackground()  throws InterruptedException {
			if(downloadFile == null) {
				return null;
			}

			// Do an initial sleep so this prepare can't compete with main prepare
			Thread.sleep(5000L);
			while (!bufferComplete()) {
				Thread.sleep(5000L);
				if (isCancelled()) {
					return null;
				}
			}

			// Start the setup of the next media player
			mediaPlayerHandler.post(new Runnable() {
				public void run() {
					if(!CheckCompletionTask.this.isCancelled()) {
						setupNext(downloadFile);
					}
				}
			});
			return null;
		}

		private boolean bufferComplete() {
			boolean completeFileAvailable = downloadFile.isWorkDone();
			Log.i(TAG, "Buffering next " + partialFile + " (" + partialFile.length() + "): " + completeFileAvailable);
			return completeFileAvailable && (playerState == PlayerState.STARTED || playerState == PlayerState.PAUSED);
		}

		@Override
		public String toString() {
			return "CheckCompletionTask (" + downloadFile + ")";
		}
	}

	public interface OnSongChangedListener {
		void onSongChanged(DownloadFile currentPlaying, int currentPlayingIndex, boolean shouldFastForward);
		void onSongsChanged(List<DownloadFile> songs, DownloadFile currentPlaying, int currentPlayingIndex, boolean shouldFastForward);
		void onSongProgress(DownloadFile currentPlaying, int millisPlayed, Integer duration, boolean isSeekable);
		void onStateUpdate(DownloadFile downloadFile, PlayerState playerState);
		void onMetadataUpdate(MusicDirectory.Entry entry, int fieldChange);
	}
}
