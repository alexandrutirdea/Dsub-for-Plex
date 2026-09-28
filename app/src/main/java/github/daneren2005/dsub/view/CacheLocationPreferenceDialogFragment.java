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

 Copyright 2026 (C) Scott Jackson
*/
package github.daneren2005.dsub.view;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.preference.EditTextPreferenceDialogFragmentCompat;

import java.io.File;

import github.daneren2005.dsub.R;

/**
 * The dialog behind a {@link CacheLocationPreference}: a path to type in, with a button
 * for each of the two places worth pointing it at.
 */
public class CacheLocationPreferenceDialogFragment extends EditTextPreferenceDialogFragmentCompat {
	public static CacheLocationPreferenceDialogFragment newInstance(String key) {
		CacheLocationPreferenceDialogFragment fragment = new CacheLocationPreferenceDialogFragment();
		Bundle args = new Bundle(1);
		args.putString(ARG_KEY, key);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);

		view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		final EditText editText = (EditText) view.findViewById(android.R.id.edit);
		ViewGroup vg = (ViewGroup) editText.getParent();

		LinearLayout cacheButtonsWrapper = (LinearLayout) LayoutInflater.from(getContext()).inflate(R.layout.cache_location_buttons, vg, true);
		Button internalLocation = (Button) cacheButtonsWrapper.findViewById(R.id.location_internal);
		Button externalLocation = (Button) cacheButtonsWrapper.findViewById(R.id.location_external);

		File[] candidates = CacheLocationPreference.getCandidateDirectories(getContext());
		final File internalDir = candidates[0];
		final File externalDir = candidates[1];

		if(internalDir != null && (internalDir.exists() || internalDir.mkdirs())) {
			internalLocation.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					editText.setText(internalDir.getPath());
				}
			});
		} else {
			internalLocation.setEnabled(false);
		}

		if(externalDir != null && !externalDir.equals(internalDir) && (externalDir.exists() || externalDir.mkdirs())) {
			externalLocation.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					editText.setText(externalDir.getPath());
				}
			});
		} else {
			externalLocation.setEnabled(false);
		}
	}
}
