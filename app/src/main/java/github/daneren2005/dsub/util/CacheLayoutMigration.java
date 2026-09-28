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
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import github.daneren2005.dsub.service.plex.PlexMusicService;

/**
 * Moves songs downloaded before the cache knew a Plex key from a path.
 *
 * Plex hands back a streaming key where Subsonic hands back the file's path on the server,
 * and the cache used to lay itself out along whichever it was given. Under Plex that put
 * every track in a numbered folder of its own - music/library/parts/486073/1765619608 -
 * with the artist and the album nowhere in it, which is what offline browsing had to show.
 *
 * Everything downloaded before that was fixed still sits there, and the app will no longer
 * look for it. Rather than have the user download it all again, this walks what is there
 * once and files it under artist and album, which is where it will now be looked for.
 *
 * The artist and album come from the file's own tags, since the old layout kept nothing
 * else to go on. A file whose tags cannot be read stays where it is: it will be downloaded
 * again if it is wanted, and leaving it costs only the space it already occupies.
 */
public final class CacheLayoutMigration {
	private static final String TAG = CacheLayoutMigration.class.getSimpleName();
	private static final String COMPLETE_MARKER = ".complete.";
	private static final String PARTIAL_MARKER = ".partial.";

	private CacheLayoutMigration() {}

	/**
	 * Runs once per install, on a thread of its own. Cheap to call when there is nothing
	 * to do: the old layout has one well-known directory, and it is usually not there.
	 *
	 * The song database is put right after the move, under a flag of its own: that came
	 * later, and installs that had already moved everything still have rows pointing into
	 * the old layout.
	 */
	public static void runIfNeeded(final Context context) {
		final SharedPreferences prefs = Util.getPreferences(context);
		final boolean migrated = prefs.getBoolean(Constants.PREFERENCES_KEY_CACHE_LAYOUT_MIGRATED, false);
		final boolean repaired = prefs.getBoolean(Constants.PREFERENCES_KEY_SONG_PATHS_REPAIRED, false);
		if(migrated && repaired) {
			return;
		}

		final File oldRoot = oldRootOf(context);
		new Thread("CacheLayoutMigration") {
			@Override
			public void run() {
				try {
					if(!migrated) {
						// Nothing downloaded under the old layout means nothing to move and no
						// reason to look again on the next start.
						if(oldRoot.isDirectory()) {
							int moved = migrate(context, oldRoot);
							Log.i(TAG, "Moved " + moved + " cached songs into artist and album folders.");
						}
						markDone(prefs);
					}

					if(!repaired) {
						int followed = repairSongPaths(context, oldRoot);
						Log.i(TAG, "Pointed " + followed + " registered songs at where they were moved.");
						prefs.edit().putBoolean(Constants.PREFERENCES_KEY_SONG_PATHS_REPAIRED, true).commit();
					}
				} catch(Throwable x) {
					// Left unmarked so it is tried again next time rather than leaving a
					// half-moved cache behind for good.
					Log.w(TAG, "Could not finish moving the cache to the new layout.", x);
				}
			}
		}.start();
	}

	/**
	 * Points rows still registered under the old layout at the file that was moved out of it.
	 *
	 * A move reached the database only when this class made it, and not even then when the
	 * song had been downloaded again in the meantime - the old copy was dropped and its row
	 * left pointing at it. Nothing but the file name survives from the old layout, so that
	 * is what pairs them; see {@link SongPathRepair} for how carefully.
	 */
	private static int repairSongPaths(Context context, File oldRoot) {
		SongDBHandler songs = SongDBHandler.getHandler(context);
		List<String> stale = songs.getPathsUnder(oldRoot.getAbsolutePath());
		if(stale.isEmpty()) {
			return 0;
		}

		List<String> unregistered = new ArrayList<String>();
		savedSongsUnder(FileUtil.getMusicDirectory(context), oldRoot, unregistered);
		for(Iterator<String> it = unregistered.iterator(); it.hasNext(); ) {
			if(songs.isPathRegistered(it.next())) {
				it.remove();
			}
		}

		Map<String, String> moves = SongPathRepair.match(stale, unregistered);
		for(Map.Entry<String, String> move: moves.entrySet()) {
			songs.updateSavePath(move.getKey(), move.getValue());
		}
		return moves.size();
	}

	/** The saved path of every finished song outside the old layout. */
	private static void savedSongsUnder(File dir, File oldRoot, List<String> out) {
		File[] children = dir.listFiles();
		if(children == null) {
			return;
		}

		for(File child : children) {
			if(child.isDirectory()) {
				if(!child.equals(oldRoot)) {
					savedSongsUnder(child, oldRoot, out);
				}
			} else if(!child.getName().contains(PARTIAL_MARKER) && FileUtil.isMusicFile(new File(saveNameOf(child.getName())))) {
				out.add(saveNameOf(child.getAbsolutePath()));
			}
		}
	}

	private static void markDone(SharedPreferences prefs) {
		prefs.edit().putBoolean(Constants.PREFERENCES_KEY_CACHE_LAYOUT_MIGRATED, true).commit();
	}

	/** music/library/parts, the root of everything the old layout produced. */
	private static File oldRootOf(Context context) {
		String parts = PlexMusicService.PART_KEY_PREFIX;
		while(parts.startsWith("/")) {
			parts = parts.substring(1);
		}
		while(parts.endsWith("/")) {
			parts = parts.substring(0, parts.length() - 1);
		}
		return new File(FileUtil.getMusicDirectory(context), parts);
	}

	private static int migrate(Context context, File oldRoot) {
		int moved = 0;
		for(File song : songsUnder(oldRoot)) {
			if(move(context, song)) {
				moved++;
			}
		}

		// Whatever could not be moved keeps its folder; the rest go, along with the
		// library/parts skeleton itself once it has nothing left in it.
		deleteEmptyDirectories(oldRoot);
		deleteIfEmpty(oldRoot.getParentFile());
		return moved;
	}

	private static java.util.List<File> songsUnder(File dir) {
		java.util.List<File> songs = new java.util.ArrayList<File>();
		File[] children = dir.listFiles();
		if(children == null) {
			return songs;
		}

		for(File child : children) {
			if(child.isDirectory()) {
				songs.addAll(songsUnder(child));
			} else if(child.getName().contains(PARTIAL_MARKER)) {
				// An interrupted download. Not worth carrying over - it would have to be
				// finished against a path the app no longer asks for.
				child.delete();
			} else if(FileUtil.isMusicFile(new File(saveNameOf(child.getName())))) {
				songs.add(child);
			}
		}
		return songs;
	}

	private static boolean move(Context context, File song) {
		String artist = null;
		String album = null;

		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		try {
			retriever.setDataSource(song.getAbsolutePath());
			// The track's own performer first, which is what the app files a song under -
			// on a compilation that is the singer rather than whoever the album is by.
			artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
			if(isBlank(artist)) {
				artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
			}
			album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
		} catch(Throwable x) {
			Log.w(TAG, "Could not read the tags of " + song.getName() + ": " + x);
		} finally {
			try {
				retriever.release();
			} catch(Throwable ignored) {}
		}

		if(isBlank(artist) || isBlank(album)) {
			Log.w(TAG, "Leaving " + song.getName() + " where it is: it has no artist or album tag.");
			return false;
		}

		File albumDir = new File(FileUtil.getMusicDirectory(context),
				FileUtil.fileSystemSafe(artist) + "/" + FileUtil.fileSystemSafe(album));
		File destination = new File(albumDir, song.getName());
		if(destination.exists()) {
			// Already downloaded again under the new layout. The copy here is the same
			// song taking up the same space twice, so it goes.
			Log.i(TAG, "Dropping the old copy of " + song.getName() + "; it has been downloaded again.");
			song.delete();
			// The row still has to follow, or its plays stay with a file that is gone.
			SongDBHandler.getHandler(context).updateSavePath(
					saveNameOf(song.getAbsolutePath()), saveNameOf(destination.getAbsolutePath()));
			return false;
		}

		if(!FileUtil.ensureDirectoryExistsAndIsReadWritable(albumDir) || !song.renameTo(destination)) {
			Log.w(TAG, "Could not move " + song.getName() + " to " + albumDir);
			return false;
		}

		moveAlbumArt(song.getParentFile(), albumDir);
		// The play history is keyed by where the file is, so a move it does not hear about
		// loses what has been played offline and never scrobbled.
		SongDBHandler.getHandler(context).updateSavePath(
				saveNameOf(song.getAbsolutePath()), saveNameOf(destination.getAbsolutePath()));
		return true;
	}

	/**
	 * The path the song is known by, which is the one without the marker that says the
	 * download finished.
	 */
	static String saveNameOf(String path) {
		int marker = path.lastIndexOf(COMPLETE_MARKER);
		return marker == -1 ? path : path.substring(0, marker) + "." + path.substring(marker + COMPLETE_MARKER.length());
	}

	private static void moveAlbumArt(File oldDir, File albumDir) {
		File art = new File(oldDir, Constants.ALBUM_ART_FILE);
		if(!art.exists()) {
			return;
		}

		File destination = new File(albumDir, Constants.ALBUM_ART_FILE);
		if(destination.exists()) {
			// The old layout kept a copy per track, so all but the first are redundant.
			art.delete();
		} else {
			art.renameTo(destination);
		}
	}

	private static void deleteEmptyDirectories(File dir) {
		File[] children = dir.listFiles();
		if(children == null) {
			return;
		}

		for(File child : children) {
			if(child.isDirectory()) {
				deleteEmptyDirectories(child);
				deleteIfEmpty(child);
			}
		}
	}

	private static void deleteIfEmpty(File dir) {
		if(dir == null || !dir.isDirectory()) {
			return;
		}

		File[] children = dir.listFiles();
		if(children != null && children.length == 0) {
			dir.delete();
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
