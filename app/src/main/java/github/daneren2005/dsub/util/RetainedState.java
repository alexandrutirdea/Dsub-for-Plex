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

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Where a fragment leaves its listing while its screen is being rebuilt.
 *
 * Saved instance state travels to the system in a Bundle, and it travels over a Binder
 * transaction with about a megabyte to spend on the whole process. A listing does not
 * fit in that: an album's tracks, a playlist, a library's artists all run to thousands
 * of entries, and every Entry is a thirty-odd field Serializable, so a large enough
 * library ended the app on TransactionTooLargeException the moment a screen stopped.
 *
 * The listing never needed to make that trip. It is wanted again on a rotation or on
 * the way back along the back stack, and both of those happen inside the process, which
 * is exactly what a ViewModel outlives. So the listing waits here and the Bundle carries
 * only what is small enough to be worth carrying.
 *
 * What this deliberately does not survive is the death of the process. A fragment coming
 * back to an empty holder reads null, which is the same thing a fragment opened for the
 * first time reads, and it loads its listing again from the cache on disk down the path
 * it already had for that case.
 */
public class RetainedState extends ViewModel {
	private final Map<String, Object> values = new HashMap<>();

	public static RetainedState of(Fragment fragment) {
		return new ViewModelProvider(fragment).get(RetainedState.class);
	}

	@SuppressWarnings("unchecked")
	public <T> T get(String key) {
		return (T) values.get(key);
	}

	public void put(String key, Object value) {
		if(value == null) {
			values.remove(key);
		} else {
			values.put(key, value);
		}
	}
}
