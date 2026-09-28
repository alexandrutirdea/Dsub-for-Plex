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

 Copyright 2015 (C) Scott Jackson
*/
package github.daneren2005.dsub.view;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.AttributeSet;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;

import java.io.File;

/**
 * Where downloaded music is kept, typed in by hand or picked off one of two buttons.
 *
 * The buttons are added to the dialog by {@link CacheLocationPreferenceDialogFragment};
 * what belongs here is working out which two directories they should offer, which is a
 * question about storage rather than about dialogs.
 */
public class CacheLocationPreference extends EditTextPreference {
	private static final String TAG = CacheLocationPreference.class.getSimpleName();

	public CacheLocationPreference(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}
	public CacheLocationPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
	}
	public CacheLocationPreference(Context context) {
		super(context);
	}

	/**
	 * The music directory on built-in storage and the one on a card, in that order. Either
	 * can be null when there is nothing sensible to point at.
	 */
	public static File[] getCandidateDirectories(Context context) {
		File[] dirs;
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			dirs = context.getExternalMediaDirs();
		} else {
			dirs = ContextCompat.getExternalFilesDirs(context, null);
		}

		// Ask directly for the SD Card
		File internalDir = null, externalDir = null;
		for(int i = 0; i < dirs.length; i++) {
			try {
				if (dirs[i] != null) {
					if(Environment.isExternalStorageRemovable(dirs[i])) {
						if(externalDir != null) {
							externalDir = dirs[i];
						}
					} else {
						internalDir = dirs[i];
					}

					if(internalDir != null && externalDir != null) {
						break;
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "Failed to check if is external", e);
			}
		}

		// Nothing said it was removable, so guess.  Most of the time the SD card is last
		if(externalDir == null) {
			for (int i = dirs.length - 1; i >= 0; i--) {
				if (dirs[i] != null) {
					externalDir = dirs[i];
					break;
				}
			}
		}
		if(internalDir == null) {
			for (int i = 0; i < dirs.length; i++) {
				if (dirs[i] != null) {
					internalDir = dirs[i];
					break;
				}
			}
		}

		return new File[] {
			internalDir == null ? null : new File(internalDir, "music"),
			externalDir == null ? null : new File(externalDir, "music")
		};
	}
}
