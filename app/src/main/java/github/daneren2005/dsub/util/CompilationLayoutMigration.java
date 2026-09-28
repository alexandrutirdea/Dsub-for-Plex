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
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Gathers a downloaded compilation into the one folder its album artist now has.
 *
 * A song used to be filed under its own performer, so an album by several of them landed
 * in a folder each: fifteen singers, fifteen folders of one or two tracks, and nothing
 * left to say they were one album. The offline Library showed fifteen artists, and the
 * downloaded albums row measured each folder against the album's twenty-four tracks and
 * left every one of them out.
 *
 * Downloads now go under the album's own artist, and this walks what is already on the
 * phone once and puts it where it will be looked for. Only songs that disagree with the
 * folder they are in move; everything by one artist is already where it belongs and is
 * left untouched.
 *
 * The album artist comes from the file's own tags. A file that has none stays where it is
 * and will be downloaded again if it is wanted, which costs a download rather than a
 * wrongly filed album.
 */
public final class CompilationLayoutMigration {
	private static final String TAG = CompilationLayoutMigration.class.getSimpleName();
	private static final String PARTIAL_MARKER = ".partial.";

	private CompilationLayoutMigration() {}

	/**
	 * Runs once per install, on a thread of its own. It reads a tag off every downloaded
	 * song, so it is worth doing only the once.
	 */
	public static void runIfNeeded(final Context context) {
		final SharedPreferences prefs = Util.getPreferences(context);
		if(prefs.getBoolean(Constants.PREFERENCES_KEY_COMPILATIONS_REFILED, false)) {
			return;
		}

		new Thread("CompilationLayoutMigration") {
			@Override
			public void run() {
				try {
					int moved = refile(context);
					Log.i(TAG, "Filed " + moved + " cached songs under their album's artist.");
					prefs.edit().putBoolean(Constants.PREFERENCES_KEY_COMPILATIONS_REFILED, true).commit();
				} catch(Throwable x) {
					// Left unmarked so it is tried again next time rather than leaving an album
					// half in one place and half in another for good.
					Log.w(TAG, "Could not finish filing compilations under their album's artist.", x);
				}
			}
		}.start();
	}

	private static int refile(Context context) {
		File root = FileUtil.getMusicDirectory(context);
		int moved = 0;

		// Listed before anything moves: the walk would otherwise meet the folders it is
		// creating and ask the same questions of songs it has just answered for.
		for(File albumDir: albumFoldersUnder(root)) {
			moved += refileAlbum(context, root, albumDir);
		}
		return moved;
	}

	private static List<File> albumFoldersUnder(File root) {
		List<File> albumDirs = new ArrayList<File>();
		for(File artistDir: FileUtil.listFiles(root)) {
			if(!artistDir.isDirectory()) {
				continue;
			}

			for(File albumDir: FileUtil.listFiles(artistDir)) {
				if(albumDir.isDirectory()) {
					albumDirs.add(albumDir);
				}
			}
		}
		return albumDirs;
	}

	private static int refileAlbum(Context context, File root, File albumDir) {
		File artistDir = albumDir.getParentFile();
		int moved = 0;
		File destination = null;

		for(File song: FileUtil.listFiles(albumDir)) {
			if(!song.isFile() || song.getName().contains(PARTIAL_MARKER) || !FileUtil.isMusicFile(song)) {
				continue;
			}

			Pair<String, String> album = AlbumTags.albumIdentityOf(song);
			if(album == null) {
				Log.w(TAG, "Leaving " + song.getName() + " where it is: it names no album artist.");
				continue;
			}

			// The album keeps the folder name it was given, which is what the server calls it;
			// only which artist it sits under is in question here.
			String albumArtist = FileUtil.fileSystemSafe(album.getFirst());
			if(albumArtist.equals(artistDir.getName())) {
				continue;
			}

			File albumArtistDir = new File(root, albumArtist);
			destination = new File(albumArtistDir, albumDir.getName());
			if(move(context, song, destination)) {
				moved++;
			}
		}

		if(destination != null) {
			moveAlbumArt(albumDir, destination);
			AlbumSnapshots.moveFolder(context, albumDir, destination);
			deleteIfEmpty(albumDir);
			deleteIfEmpty(artistDir);
		}
		return moved;
	}

	private static boolean move(Context context, File song, File albumDir) {
		File destination = new File(albumDir, song.getName());
		if(destination.exists()) {
			// The same song under both names, taking up the space twice, so this copy goes.
			Log.i(TAG, "Dropping the spare copy of " + song.getName() + ".");
			song.delete();
			registerMove(context, song, destination);
			return false;
		}

		if(!FileUtil.ensureDirectoryExistsAndIsReadWritable(albumDir) || !song.renameTo(destination)) {
			Log.w(TAG, "Could not move " + song.getName() + " to " + albumDir);
			return false;
		}

		registerMove(context, song, destination);
		return true;
	}

	/**
	 * The play history is keyed by where the file is, so a move it does not hear about loses
	 * what has been played offline and never scrobbled.
	 */
	private static void registerMove(Context context, File song, File destination) {
		SongDBHandler.getHandler(context).updateSavePath(
				CacheLayoutMigration.saveNameOf(song.getAbsolutePath()),
				CacheLayoutMigration.saveNameOf(destination.getAbsolutePath()));
	}

	private static void moveAlbumArt(File albumDir, File destination) {
		File art = new File(albumDir, Constants.ALBUM_ART_FILE);
		if(!art.exists()) {
			return;
		}

		File moved = new File(destination, Constants.ALBUM_ART_FILE);
		if(moved.exists()) {
			// Every performer's folder kept a copy of the one cover, so all but the first go.
			art.delete();
		} else {
			art.renameTo(moved);
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
}
