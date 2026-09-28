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

 Copyright 2014 (C) Scott Jackson
*/
package github.daneren2005.dsub.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.view.CacheLocationPreference;
import github.daneren2005.dsub.view.CacheLocationPreferenceDialogFragment;
import github.daneren2005.dsub.view.EditPasswordPreference;
import github.daneren2005.dsub.view.EditPasswordPreferenceDialogFragment;
import github.daneren2005.dsub.view.SeekBarPreference;
import github.daneren2005.dsub.view.SeekBarPreferenceDialogFragment;

/**
 * A settings screen that is still one of DSub's own fragments.
 *
 * The awkwardness this exists to absorb is that AndroidX puts preferences in a
 * {@link PreferenceFragmentCompat}, which is a Fragment in its own right, while every
 * screen in DSub is a {@link SubsonicFragment} - it is what the drawer, the toolbar title,
 * the back stack and the now-playing panel all expect to be handed. A fragment can only
 * extend one of the two.
 *
 * So this extends SubsonicFragment and <em>hosts</em> a PreferenceFragmentCompat inside
 * itself as a child fragment, forwarding the handful of calls a subclass needs. The
 * version this replaced solved the same problem by reflecting into seven private methods
 * of the framework's PreferenceManager, which is exactly the kind of thing that stops
 * working on a new Android release.
 *
 * A subclass builds its hierarchy in {@link #onCreatePreferenceScreen()} - by default,
 * whatever XML resource was named in {@link Constants#INTENT_EXTRA_FRAGMENT_TYPE} - and
 * then gets {@link #onInitPreferences} once it exists.
 */
public abstract class PreferenceCompatFragment extends SubsonicFragment {
	private static final String HOST_TAG = "preference-host";

	private PreferenceHost host;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		rootView = inflater.inflate(R.layout.preferences, container, false);

		host = (PreferenceHost) getChildFragmentManager().findFragmentByTag(HOST_TAG);
		if(host == null) {
			host = new PreferenceHost();
			getChildFragmentManager().beginTransaction()
					.replace(R.id.preferences_container, host, HOST_TAG)
					.commitNow();
		}

		return rootView;
	}

	/**
	 * Builds this screen's preference hierarchy and returns its root, or null when there
	 * is nothing to show. Called once the host fragment exists, which is the earliest
	 * point at which {@link #getPreferenceManager()} can be asked for anything.
	 */
	protected PreferenceScreen onCreatePreferenceScreen() {
		int res = getArguments() == null ? 0 : getArguments().getInt(Constants.INTENT_EXTRA_FRAGMENT_TYPE, 0);
		return res == 0 ? null : addPreferencesFromResource(res);
	}

	/** Called once the hierarchy exists, for a subclass to wire it up. */
	protected void onInitPreferences(PreferenceScreen preferenceScreen) {

	}

	/**
	 * Where a nested {@link PreferenceScreen} in the XML leads. The screens in DSub's
	 * settings XML are empty markers - each one is a whole fragment of its own - so
	 * tapping one is a navigation rather than something AndroidX should open in place.
	 */
	protected void onStartNewFragment(String name) {

	}

	/**
	 * Called by the host as it builds its preferences.
	 *
	 * The host is passed in rather than read off the field because the two do not always
	 * happen in the same order: on a fresh screen this fragment creates the host and the
	 * field is set first, but when state is restored the host is brought back with the
	 * child fragment manager and reaches this before {@link #onCreateView} has run.
	 */
	private void onHostReady(PreferenceHost host) {
		this.host = host;

		PreferenceScreen screen = onCreatePreferenceScreen();
		if(screen == null) {
			return;
		}

		for(int i = 0; i < screen.getPreferenceCount(); i++) {
			Preference preference = screen.getPreference(i);
			if(preference instanceof PreferenceScreen && preference.getKey() != null) {
				preference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
					@Override
					public boolean onPreferenceClick(Preference preference) {
						onStartNewFragment(preference.getKey());
						// Consumed: the nested screen in the XML is empty, and letting
						// AndroidX open it as well would put a blank screen underneath the
						// one being navigated to.
						return true;
					}
				});
			}
		}

		onInitPreferences(screen);
	}

	// ---------------------------------------------------------- host delegation

	public PreferenceManager getPreferenceManager() {
		return host == null ? null : host.getPreferenceManager();
	}

	public PreferenceScreen getPreferenceScreen() {
		return host == null ? null : host.getPreferenceScreen();
	}

	public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
		if(host != null) {
			host.setPreferenceScreen(preferenceScreen);
		}
	}

	public PreferenceScreen addPreferencesFromResource(int resId) {
		host.addPreferencesFromResource(resId);
		return host.getPreferenceScreen();
	}

	public Preference findPreference(CharSequence key) {
		return host == null ? null : host.findPreference(key);
	}

	/**
	 * The fragment that actually owns the preferences.
	 *
	 * Public and static because the framework re-creates fragments by name, and a private
	 * or inner class cannot be re-created that way.
	 */
	public static class PreferenceHost extends PreferenceFragmentCompat {
		private static final String DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG";

		@Override
		public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
			PreferenceCompatFragment parent = (PreferenceCompatFragment) getParentFragment();
			if(parent != null) {
				parent.onHostReady(this);
			}
		}

		/**
		 * Routes the three preferences that show a dialog of their own to the dialog
		 * fragment that knows how to show it, and leaves everything else to AndroidX.
		 * CacheLocation and EditPassword are both EditTextPreferences, so they have to be
		 * asked about before the general case.
		 */
		@Override
		public void onDisplayPreferenceDialog(@NonNull Preference preference) {
			if(getParentFragmentManager().findFragmentByTag(DIALOG_TAG) != null) {
				return;
			}

			androidx.fragment.app.DialogFragment dialog = null;
			if(preference instanceof SeekBarPreference) {
				dialog = SeekBarPreferenceDialogFragment.newInstance(preference.getKey());
			} else if(preference instanceof CacheLocationPreference) {
				dialog = CacheLocationPreferenceDialogFragment.newInstance(preference.getKey());
			} else if(preference instanceof EditPasswordPreference) {
				dialog = EditPasswordPreferenceDialogFragment.newInstance(preference.getKey());
			}

			if(dialog == null) {
				super.onDisplayPreferenceDialog(preference);
				return;
			}

			// How a PreferenceDialogFragmentCompat finds the preference it is showing.
			// Deprecated, and still the only route AndroidX offers as of preference 1.2.1.
			dialog.setTargetFragment(this, 0);
			dialog.show(getParentFragmentManager(), DIALOG_TAG);
		}
	}
}
