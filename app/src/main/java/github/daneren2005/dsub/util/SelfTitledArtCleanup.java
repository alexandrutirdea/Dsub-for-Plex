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

/**
 * Drops cached art that an artist and one of their albums were sharing.
 *
 * Art is cached under the folder the album would be downloaded to, "<artist>/<album>",
 * and an artist's picture was filed the same way with the artist's name standing in for
 * both. A self-titled album is that very folder, so the picture and the cover were one
 * file: whichever was drawn first answered for both afterwards, and The Beatles' White
 * Album showed a photograph of the band.
 *
 * Artist pictures are now filed under the picture itself, but that only applies to what
 * is fetched from here on - the file already on disk is still consulted before the server
 * (see CachedMusicService.getCoverArt) and would go on answering with the wrong picture.
 *
 * Which of the cached files are the shared ones cannot be told apart from the rest: the
 * ones outside a downloaded album are named after a hash of the folder, and nothing on
 * disk says whose folder that was. So the art cache goes in its entirety, along with the
 * covers inside self-titled album folders, which can be picked out by name. All of it is
 * fetched again the next time it is drawn.
 */
public final class SelfTitledArtCleanup {
	private static final String TAG = SelfTitledArtCleanup.class.getSimpleName();

	private SelfTitledArtCleanup() {}

	/** Runs once per install, on a thread of its own. */
	public static void runIfNeeded(final Context context) {
		final SharedPreferences prefs = Util.getPreferences(context);
		if(prefs.getBoolean(Constants.PREFERENCES_KEY_SELF_TITLED_ART_CLEARED, false)) {
			return;
		}

		new Thread("SelfTitledArtCleanup") {
			@Override
			public void run() {
				try {
					int deleted = clearArtCache(FileUtil.getAlbumArtDirectory(context));
					deleted += clearSelfTitledAlbums(FileUtil.getMusicDirectory(context));
					Log.i(TAG, "Dropped " + deleted + " cached pictures an artist and an album shared.");

					prefs.edit().putBoolean(Constants.PREFERENCES_KEY_SELF_TITLED_ART_CLEARED, true).commit();
				} catch(Throwable x) {
					// Left unmarked so it is tried again next time. Nothing is lost by
					// running twice - the art comes back the next time it is asked for.
					Log.w(TAG, "Could not finish clearing the shared album art.", x);
				}
			}
		}.start();
	}

	/** Everything the art cache holds for albums that were never downloaded. */
	private static int clearArtCache(File artDirectory) {
		File[] children = artDirectory.listFiles();
		if(children == null) {
			return 0;
		}

		int deleted = 0;
		for(File child : children) {
			// .nomedia is kept: it is what stops the gallery from picking the cache up.
			if(child.isFile() && child.getName().endsWith(".jpeg") && child.delete()) {
				deleted++;
			}
		}
		return deleted;
	}

	/**
	 * The cover inside every downloaded "<artist>/<artist>" folder. Downloaded songs are
	 * left alone - only the one picture in the folder is in question.
	 */
	private static int clearSelfTitledAlbums(File musicDirectory) {
		File[] artists = musicDirectory.listFiles();
		if(artists == null) {
			return 0;
		}

		int deleted = 0;
		for(File artist : artists) {
			if(!artist.isDirectory()) {
				continue;
			}

			File art = new File(new File(artist, artist.getName()), Constants.ALBUM_ART_FILE);
			if(art.isFile() && art.delete()) {
				deleted++;
			}
		}
		return deleted;
	}
}
