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
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceDialogFragmentCompat;

import github.daneren2005.dsub.R;

/**
 * The dialog behind a {@link SeekBarPreference}.
 *
 * AndroidX shows a dialog preference through a DialogFragment rather than from inside the
 * preference itself, so that the dialog survives a rotation on its own. The slider's
 * position while the dialog is open belongs here rather than to the preference: nothing is
 * persisted until the dialog is closed with its positive button.
 */
public class SeekBarPreferenceDialogFragment extends PreferenceDialogFragmentCompat {
	private static final String SAVE_STATE_VALUE = "SeekBarPreferenceDialogFragment.value";

	/** The slider position while the dialog is open, which is not persisted until OK. */
	private String value;
	private TextView valueText;

	public static SeekBarPreferenceDialogFragment newInstance(String key) {
		SeekBarPreferenceDialogFragment fragment = new SeekBarPreferenceDialogFragment();
		Bundle args = new Bundle(1);
		args.putString(ARG_KEY, key);
		fragment.setArguments(args);
		return fragment;
	}

	private SeekBarPreference getSeekBarPreference() {
		return (SeekBarPreference) getPreference();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if(savedInstanceState == null) {
			value = getSeekBarPreference().getValue();
		} else {
			value = savedInstanceState.getString(SAVE_STATE_VALUE);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(SAVE_STATE_VALUE, value);
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);

		final SeekBarPreference preference = getSeekBarPreference();

		valueText = (TextView) view.findViewById(R.id.value);
		valueText.setText(preference.displayValue(value));

		SeekBar seekBar = (SeekBar) view.findViewById(R.id.seek_bar);
		seekBar.setMax(preference.getRange());
		try {
			seekBar.setProgress(Integer.parseInt(value));
		} catch(Exception e) {
			seekBar.setProgress(0);
		}
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if(fromUser) {
					value = String.valueOf(progress);
					valueText.setText(preference.displayValue(value));
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
	}

	@Override
	public void onDialogClosed(boolean positiveResult) {
		if(positiveResult) {
			getSeekBarPreference().setValue(value);
		}
	}
}
