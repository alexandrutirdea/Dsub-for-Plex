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

package github.daneren2005.dsub.view;

import android.app.Dialog;
import android.os.Bundle;

import androidx.mediarouter.app.MediaRouteChooserDialogFragment;

/**
 * The cast button's sheet while the audio is on the phone. See {@link RouteSheet}.
 *
 * Still a MediaRouteChooserDialogFragment so MediaRouteButton can hand it its selector and
 * find it again by tag. Only the dialog is replaced; the parent leaves one it did not
 * build alone.
 */
public class RoutePickerSheet extends MediaRouteChooserDialogFragment {
	private RouteSheet sheet;

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		sheet = new RouteSheet(this, getRouteSelector());
		return sheet.create(requireContext());
	}

	@Override
	public void onStart() {
		super.onStart();
		sheet.start();
	}

	@Override
	public void onStop() {
		sheet.stop();
		super.onStop();
	}
}
