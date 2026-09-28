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
*/

package github.daneren2005.dsub.util;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import github.daneren2005.dsub.domain.MusicDirectory.Entry;
import github.daneren2005.dsub.service.CachedMusicService;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;
import github.daneren2005.dsub.service.plex.PlexMusicService;

/**
 * Empties the queue collections as their albums get played.
 *
 * Mirrors AUTO_REMOVE_COLLECTIONS in the Plex Dashboard (plex-love): playing or queueing
 * an album out of 01A-05A takes it out of the collection, so those lists are worked
 * through rather than browsed. The Dashboard does this server-side for playback it
 * starts; this is the same rule for playback started on the phone.
 *
 * Every failure is swallowed. A collection that will not empty is a far smaller problem
 * than playback that will not start, which is the same call the Dashboard makes.
 */
public final class CollectionCuration {
	private static final String TAG = CollectionCuration.class.getSimpleName();

	/** Collection titles that empty themselves. Kept in step with the Dashboard by hand. */
	private static final Set<String> SELF_EMPTYING =
			Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("01A", "02A", "03A", "04A", "05A")));

	private CollectionCuration() {}

	/** True when playing out of this collection should take the album out of it. */
	public static boolean isSelfEmptying(String collectionName) {
		return collectionName != null && SELF_EMPTYING.contains(collectionName.trim());
	}

	/**
	 * Removes the albums on a thread of its own and returns immediately, so no play or
	 * queue action ever waits on the server.
	 */
	public static void removeAlbums(final Context context, final String collectionId,
			final String collectionName, List<String> albumIds) {
		final List<String> ids = usableIds(context, collectionId, collectionName, albumIds);
		if(ids.isEmpty()) {
			return;
		}

		new Thread(new Runnable() {
			@Override
			public void run() {
				remove(context, collectionId, ids);
			}
		}, "CollectionCuration").start();
	}

	public static void removeAlbum(Context context, String collectionId, String collectionName, String albumId) {
		removeAlbums(context, collectionId, collectionName, Arrays.asList(albumId));
	}

	/**
	 * The blocking form, for callers already off the main thread - the autoplay buffer
	 * draws its albums on a background thread of its own.
	 */
	public static void removeAlbumsNow(Context context, String collectionId,
			String collectionName, List<String> albumIds) {
		List<String> ids = usableIds(context, collectionId, collectionName, albumIds);
		if(!ids.isEmpty()) {
			remove(context, collectionId, ids);
		}
	}

	/** The album ids worth sending, or empty when this is not a case for curation at all. */
	private static List<String> usableIds(Context context, String collectionId,
			String collectionName, List<String> albumIds) {
		List<String> ids = new ArrayList<String>();
		if(collectionId == null || albumIds == null || !isSelfEmptying(collectionName)) {
			return ids;
		}
		// Offline there is no server to tell, and the play is coming out of the cache.
		if(Util.isOffline(context)) {
			return ids;
		}

		for(String albumId: albumIds) {
			if(albumId != null && !albumId.isEmpty() && !ids.contains(albumId)) {
				ids.add(albumId);
			}
		}
		return ids;
	}

	private static void remove(Context context, String collectionId, List<String> albumIds) {
		// The factory hands back a CachedMusicService wrapper; the collection edit only
		// exists on the Plex backend underneath it.
		MusicService service = MusicServiceFactory.getMusicService(context);
		CachedMusicService cache = (service instanceof CachedMusicService) ? (CachedMusicService) service : null;
		if(cache != null) {
			service = cache.getUnderlyingService();
		}
		if(!(service instanceof PlexMusicService)) {
			return;
		}

		PlexMusicService plex = (PlexMusicService) service;
		boolean removedAny = false;
		for(String albumId: albumIds) {
			try {
				plex.removeAlbumFromCollection(context, collectionId, albumId);
				removedAny = true;
				Log.i(TAG, "Removed album " + albumId + " from collection " + collectionId + ".");
			} catch(Exception x) {
				Log.w(TAG, "Failed to remove album " + albumId + " from collection " + collectionId + ".", x);
			}
		}

		// The collection listing just changed on the server, and the cache cannot see it.
		if(removedAny && cache != null) {
			cache.invalidateDirectory(context, collectionId);
		}
	}

	/** The album ids out of a mixed list of rows, ignoring anything that is not an album. */
	public static List<String> albumIdsOf(List<Entry> entries) {
		List<String> ids = new ArrayList<String>();
		if(entries == null) {
			return ids;
		}

		for(Entry entry: entries) {
			if(entry != null && entry.isDirectory() && entry.getId() != null) {
				ids.add(entry.getId());
			}
		}
		return ids;
	}
}
