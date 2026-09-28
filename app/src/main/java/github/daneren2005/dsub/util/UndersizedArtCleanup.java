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
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;

/**
 * Drops album art that was cached too small to draw.
 *
 * The Plex backend used to ask the server to scale art to whatever size was being drawn
 * at that moment, but there is only one cached file per album and every request wrote it.
 * An album first seen as a grid thumbnail was left on disk at 225 pixels, and since the
 * cache is consulted before the server (see CachedMusicService.getCoverArt), the now
 * playing screen went on stretching that same thumbnail across the whole width for good.
 *
 * The backend now caches art at the size of the largest view instead, but that only
 * applies to art fetched from here on. This clears out what the old behaviour left
 * behind, once, so those albums are fetched again at a size worth showing.
 */
public final class UndersizedArtCleanup {
	private static final String TAG = UndersizedArtCleanup.class.getSimpleName();

	private UndersizedArtCleanup() {}

	/** Runs once per install, on a thread of its own. */
	public static void runIfNeeded(final Context context) {
		final SharedPreferences prefs = Util.getPreferences(context);
		if(prefs.getBoolean(Constants.PREFERENCES_KEY_UNDERSIZED_ART_CLEARED, false)) {
			return;
		}

		new Thread("UndersizedArtCleanup") {
			@Override
			public void run() {
				try {
					DisplayMetrics metrics = context.getResources().getDisplayMetrics();
					int wanted = Math.min(metrics.widthPixels, metrics.heightPixels);

					int deleted = clear(FileUtil.getAlbumArtDirectory(context), wanted);
					deleted += clear(FileUtil.getMusicDirectory(context), wanted);
					Log.i(TAG, "Dropped " + deleted + " album art files too small to draw.");

					prefs.edit().putBoolean(Constants.PREFERENCES_KEY_UNDERSIZED_ART_CLEARED, true).commit();
				} catch(Throwable x) {
					// Left unmarked so it is tried again next time. Nothing is lost by
					// running twice - the art comes back the next time it is asked for.
					Log.w(TAG, "Could not finish clearing undersized album art.", x);
				}
			}
		}.start();
	}

	private static int clear(File dir, int wanted) {
		File[] children = dir.listFiles();
		if(children == null) {
			return 0;
		}

		int deleted = 0;
		for(File child : children) {
			if(child.isDirectory()) {
				deleted += clear(child, wanted);
			} else if(isArt(child) && isSmallerThan(child, wanted) && child.delete()) {
				deleted++;
			}
		}
		return deleted;
	}

	private static boolean isArt(File file) {
		return Constants.ALBUM_ART_FILE.equals(file.getName()) || file.getName().endsWith(".jpeg");
	}

	/** Reads the header only - the pixels are of no interest, just how many there are. */
	private static boolean isSmallerThan(File file, int wanted) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(file.getPath(), options);
		return options.outWidth > 0 && options.outWidth < wanted && options.outHeight < wanted;
	}
}
