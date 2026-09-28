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

package github.daneren2005.dsub.util;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;

/**
 * Keeps the play queue topped up by drawing whole albums at random from a Plex
 * collection, until every album in it has had a turn and autoplay ends.
 *
 * This is the album-granularity sibling of {@link ArtistRadioBuffer}, and it follows the
 * same contract: a background thread refills a buffer, {@link #get(int)} hands songs to
 * DownloadService, and the executor shuts itself down once the buffer is full.
 *
 * The difference is that the unit of selection is an album but the unit of delivery is a
 * song. A drawn album's tracks are appended in order and taken off the front, because an
 * album played out of order is not an album.
 */
public class CollectionAutoplayBuffer {
	private static final String TAG = CollectionAutoplayBuffer.class.getSimpleName();

	/**
	 * How many recently played artists are blocked from being drawn again. At 3, an artist
	 * cannot come back around until three other albums have gone by.
	 */
	private static final int ARTIST_BUFFER = 3;

	/** Albums with no artist tag all share this key, so they block each other like any artist. */
	private static final String UNKNOWN_ARTIST_KEY = " unknown-artist";

	/**
	 * Ceiling on albums fetched per refill pass. Without it an album that yields no
	 * playable tracks could spin the loop against the server.
	 */
	private static final int MAX_ALBUMS_PER_REFILL = 5;

	private ScheduledExecutorService executorService;
	private final Runnable runnable;
	private final ArrayList<MusicDirectory.Entry> buffer = new ArrayList<MusicDirectory.Entry>();

	/** Every album in the collection, as it was when autoplay started. */
	private final List<MusicDirectory.Entry> albumPool = new ArrayList<MusicDirectory.Entry>();
	/** Every album drawn this run. Never cleared - a drawn album does not come back. */
	private final Set<String> playedAlbumIds = new HashSet<String>();
	/** Artist keys, most-recent-last, capped at {@link #ARTIST_BUFFER}. */
	private final LinkedList<String> recentArtistKeys = new LinkedList<String>();

	private final Random random = new Random();
	private final DownloadService context;

	private String collectionId;
	/** Only needed so curation can tell whether this collection empties itself. */
	private String collectionName;
	private boolean awaitingResults = false;
	/** Set once the collection is known to be unusable, which stops the executor for good. */
	private boolean finished = false;
	private final int capacity;
	private final int refillThreshold;

	public CollectionAutoplayBuffer(DownloadService context) {
		this.context = context;
		runnable = new Runnable() {
			@Override
			public void run() {
				refill();
			}
		};

		// Matched to the artist radio buffer so both feel the same in the queue.
		int shuffleListSize = Math.max(1, Integer.parseInt(Util.getPreferences(context).getString(Constants.PREFERENCES_KEY_RANDOM_SIZE, "20")));
		capacity = Math.min(500, shuffleListSize * 5 / 2);
		refillThreshold = capacity * 4 / 5;
	}

	/**
	 * Starts autoplay over {@code albums}, which is the list the user is actually looking
	 * at — already narrowed by any active browse filter. Passing it in rather than
	 * re-fetching is what makes "filter to the 90s, then autoplay" mean what it says.
	 */
	public void setCollection(String collectionId, String collectionName, List<MusicDirectory.Entry> albums) {
		synchronized (buffer) {
			buffer.clear();
			albumPool.clear();
			playedAlbumIds.clear();
			recentArtistKeys.clear();
			finished = false;

			if (albums != null) {
				for (MusicDirectory.Entry album : albums) {
					if (album != null && album.getId() != null) {
						albumPool.add(album);
					}
				}
			}
		}

		this.collectionId = collectionId;
		this.collectionName = collectionName;
		awaitingResults = true;
		refill();
	}

	/**
	 * Resumes autoplay after a restart. The filter that was active is not persisted, so
	 * the pool is re-fetched whole on the first refill — autoplay continuing over the
	 * entire collection beats it silently not resuming.
	 */
	public void restoreCollection(String collectionId, String collectionName) {
		synchronized (buffer) {
			buffer.clear();
			albumPool.clear();
			playedAlbumIds.clear();
			recentArtistKeys.clear();
			finished = false;
		}

		this.collectionId = collectionId;
		this.collectionName = collectionName;
		awaitingResults = false;
		restart();
	}

	/** The collection being drawn from, so a screen can tell whether it is the one running. */
	public String getCollectionId() {
		return collectionId;
	}

	/**
	 * True once every album has played and the last of them has been handed to the queue.
	 * Both halves matter: the pool runs dry well before the tracks already buffered have
	 * been played, and those still have to make it out.
	 */
	public boolean isExhausted() {
		synchronized (buffer) {
			return finished && buffer.isEmpty();
		}
	}

	/**
	 * Hands over the whole album sitting at the front of the buffer. Tracks are buffered
	 * album by album and taken in order, so this is everything up to the first track that
	 * belongs to a different album.
	 *
	 * Empty means the buffer has not caught up yet, not that the collection is spent; the
	 * caller is woken again once a refill lands.
	 */
	public List<MusicDirectory.Entry> getNextAlbum() {
		restart();

		List<MusicDirectory.Entry> result = new ArrayList<MusicDirectory.Entry>();
		synchronized (buffer) {
			String albumKey = null;
			while (!buffer.isEmpty()) {
				String key = albumKeyOf(buffer.get(0));
				if (albumKey == null) {
					albumKey = key;
				} else if (!albumKey.equals(key)) {
					break;
				}
				result.add(buffer.remove(0));
			}
		}

		Log.i(TAG, "Taking an album of " + result.size() + " songs from collection autoplay buffer. " + buffer.size() + " tracks remaining.");
		if (result.isEmpty()) {
			awaitingResults = true;
		}
		return result;
	}

	/** Album id where there is one, falling back to whatever else identifies the album. */
	private String albumKeyOf(MusicDirectory.Entry song) {
		if (song.getAlbumId() != null) {
			return song.getAlbumId();
		} else if (song.getParent() != null) {
			return song.getParent();
		} else {
			return (song.getAlbum() != null) ? song.getAlbum() : "";
		}
	}

	public List<MusicDirectory.Entry> get(int size) {
		restart();

		List<MusicDirectory.Entry> result = new ArrayList<MusicDirectory.Entry>(size);
		synchronized (buffer) {
			// Off the front, unlike the artist radio buffer: these are album tracks and
			// their order is the point.
			while (!buffer.isEmpty() && result.size() < size) {
				result.add(buffer.remove(0));
			}
		}

		Log.i(TAG, "Taking " + result.size() + " songs from collection autoplay buffer. " + buffer.size() + " remaining.");
		if (result.isEmpty()) {
			awaitingResults = true;
		}
		return result;
	}

	public void shutdown() {
		if (executorService != null) {
			executorService.shutdown();
		}
	}

	private void restart() {
		synchronized (buffer) {
			if (buffer.size() <= refillThreshold && !finished && (executorService == null || executorService.isShutdown())) {
				executorService = Executors.newSingleThreadScheduledExecutor();
				executorService.scheduleWithFixedDelay(runnable, 0, 10, TimeUnit.SECONDS);
			}
		}
	}

	private void refill() {
		if (executorService != null && (buffer.size() > refillThreshold || finished
				|| (!Util.isNetworkConnected(context) && !Util.isOffline(context)))) {
			executorService.shutdown();
			return;
		}

		try {
			MusicService service = MusicServiceFactory.getMusicService(context);

			if (albumPool.isEmpty()) {
				loadPool(service);
			}
			if (albumPool.isEmpty()) {
				Log.w(TAG, "Collection " + collectionId + " has no albums to autoplay.");
				finished = true;
				return;
			}

			for (int i = 0; i < MAX_ALBUMS_PER_REFILL && buffer.size() <= refillThreshold; i++) {
				MusicDirectory.Entry album = selectNextAlbum();
				if (album == null) {
					// The pool is spent. Nothing will ever be drawn again, so stop the
					// executor rather than letting it wake every ten seconds forever.
					finished = true;
					break;
				}

				// Marked before the fetch so an album that fails or is empty is not
				// redrawn on the very next pass.
				markDrawn(album);

				MusicDirectory tracks = service.getAlbum(album.getId(), album.getTitle(), false, context, null);
				int added = 0;
				synchronized (buffer) {
					for (MusicDirectory.Entry song : tracks.getChildren(false, true)) {
						buffer.add(song);
						added++;
					}
				}
				Log.i(TAG, "Autoplay drew \"" + album.getTitle() + "\" (" + added + " tracks).");

				// An album that made it into the queue counts as played. Already on a
				// background thread, so the blocking form is the right one here.
				if(added > 0) {
					CollectionCuration.removeAlbumsNow(context, collectionId, collectionName,
							Arrays.asList(album.getId()));
				}
			}
		} catch (Exception x) {
			Log.w(TAG, "Failed to refill collection autoplay buffer.", x);
		}

		if (awaitingResults) {
			awaitingResults = false;
			context.checkDownloads();
		}
	}

	private void loadPool(MusicService service) throws Exception {
		MusicDirectory collection = service.getAlbum(collectionId, null, false, context, null);
		synchronized (buffer) {
			for (MusicDirectory.Entry album : collection.getChildren(true, false)) {
				if (album.getId() != null) {
					albumPool.add(album);
				}
			}
		}
		Log.i(TAG, "Loaded " + albumPool.size() + " albums for collection autoplay.");
	}

	/**
	 * Draws the next album, or null when there is nothing to draw from.
	 *
	 * Albums already drawn are excluded, so the collection is worked through rather than
	 * sampled with replacement. Once every album has had a turn this returns null for
	 * good: autoplay is finished, not restarted. Replaying the pool would undo the point
	 * of a queue collection, where an album that has played is one you are done with.
	 *
	 * The artist buffer then excludes the last few artists, but gives way when that would
	 * leave no candidates at all — repeating an artist early is a smaller failure than
	 * silence.
	 */
	private MusicDirectory.Entry selectNextAlbum() {
		synchronized (buffer) {
			if (albumPool.isEmpty()) {
				return null;
			}

			List<MusicDirectory.Entry> unplayed = new ArrayList<MusicDirectory.Entry>();
			for (MusicDirectory.Entry album : albumPool) {
				if (!playedAlbumIds.contains(album.getId())) {
					unplayed.add(album);
				}
			}
			if (unplayed.isEmpty()) {
				Log.i(TAG, "Collection exhausted; every album has played, so autoplay stops drawing.");
				return null;
			}

			Set<String> blocked = new HashSet<String>(recentArtistKeys);
			List<MusicDirectory.Entry> byFreshArtist = new ArrayList<MusicDirectory.Entry>();
			for (MusicDirectory.Entry album : unplayed) {
				if (!blocked.contains(artistKey(album))) {
					byFreshArtist.add(album);
				}
			}

			List<MusicDirectory.Entry> pool = byFreshArtist.isEmpty() ? unplayed : byFreshArtist;
			return pool.get(random.nextInt(pool.size()));
		}
	}

	private void markDrawn(MusicDirectory.Entry album) {
		synchronized (buffer) {
			playedAlbumIds.add(album.getId());
			recentArtistKeys.addLast(artistKey(album));
			while (recentArtistKeys.size() > ARTIST_BUFFER) {
				recentArtistKeys.removeFirst();
			}
		}
	}

	/**
	 * The identity the artist buffer compares on. Case and surrounding whitespace vary
	 * between Plex tags for what is plainly the same artist, and treating those as
	 * different would let the same name repeat back-to-back through the buffer.
	 */
	private static String artistKey(MusicDirectory.Entry album) {
		String artist = album.getAlbumArtist();
		if (artist == null) {
			artist = album.getArtist();
		}
		if (artist == null) {
			return UNKNOWN_ARTIST_KEY;
		}

		artist = artist.trim().toLowerCase();
		return artist.isEmpty() ? UNKNOWN_ARTIST_KEY : artist;
	}
}
