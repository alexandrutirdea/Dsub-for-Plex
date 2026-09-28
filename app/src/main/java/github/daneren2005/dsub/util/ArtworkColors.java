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

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.widget.ImageView;

import androidx.palette.graphics.Palette;

/**
 * The colour a piece of artwork lends the screen behind it.
 *
 * Saturated swatches are preferred to muted ones, which read as grey once darkened and are
 * then indistinguishable from no colour at all. Hue is kept and brightness is not: the
 * colour is scaled down to a ceiling the caller picks, so white text stays legible over
 * artwork of any brightness and the art stays the brightest thing on the screen.
 */
public final class ArtworkColors {
	private static final String TAG = ArtworkColors.class.getSimpleName();

	private ArtworkColors() {
	}

	/**
	 * The bitmap a view is showing, or null while it is showing anything else. Artwork
	 * arrives asynchronously, and the view it was aimed at is the only place it is certain
	 * to have landed.
	 */
	public static Bitmap bitmapOf(ImageView view) {
		Drawable drawable = (view == null) ? null : view.getDrawable();
		if(!(drawable instanceof BitmapDrawable)) {
			return null;
		}

		Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
		return (bitmap == null || bitmap.isRecycled()) ? null : bitmap;
	}

	/**
	 * The artwork's colour, brought down to at most {@code maxLuminance} (0-1). Null for
	 * artwork with no colour to give, which leaves the caller's own background in place.
	 */
	public static Integer washOf(Bitmap bitmap, float maxLuminance) {
		if(bitmap == null || bitmap.isRecycled()) {
			return null;
		}

		try {
			Palette palette = Palette.from(bitmap).clearFilters().generate();

			Palette.Swatch swatch = palette.getDarkVibrantSwatch();
			if(swatch == null) {
				swatch = palette.getVibrantSwatch();
			}
			if(swatch == null) {
				swatch = palette.getDarkMutedSwatch();
			}
			if(swatch == null) {
				swatch = palette.getMutedSwatch();
			}
			if(swatch == null) {
				swatch = palette.getDominantSwatch();
			}
			if(swatch == null) {
				return null;
			}

			return capLuminance(swatch.getRgb(), maxLuminance);
		} catch(Exception e) {
			Log.w(TAG, "Failed to derive a colour from artwork", e);
			return null;
		}
	}

	/**
	 * Scales a colour down until its perceived brightness is at or below {@code max}
	 * (0-1), leaving its hue alone. Colours already dark enough are returned as they are,
	 * so dark artwork keeps its own depth instead of being lifted.
	 */
	public static int capLuminance(int color, float max) {
		float luminance = (0.299f * Color.red(color)
				+ 0.587f * Color.green(color)
				+ 0.114f * Color.blue(color)) / 255f;
		if(luminance <= max || luminance == 0f) {
			return color;
		}
		return darken(color, max / luminance);
	}

	/**
	 * The backdrop a screen led by artwork sits on: the wash at the top, fading to black.
	 *
	 * Four stops rather than three, and a higher ceiling than the colour needs on its own:
	 * evenly spaced, they hold the wash at close to full strength across the top third -
	 * the band the artwork occupies - and spend the fade to black on the lower half instead
	 * of losing the colour by the time it clears the cover. Two stops of that were spent on
	 * a gradient nobody could see.
	 */
	public static GradientDrawable backdropOf(int wash) {
		return new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[] { wash, darken(wash, 0.72f), darken(wash, 0.28f), Color.BLACK });
	}

	public static int darken(int color, float factor) {
		return Color.rgb(
				(int) (Color.red(color) * factor),
				(int) (Color.green(color) * factor),
				(int) (Color.blue(color) * factor));
	}
}
