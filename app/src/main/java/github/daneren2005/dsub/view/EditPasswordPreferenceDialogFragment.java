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
import android.widget.EditText;

import androidx.preference.EditTextPreferenceDialogFragmentCompat;

/**
 * The dialog behind an {@link EditPasswordPreference}.
 *
 * The plaintext password lives in this dialog's text box and nowhere else: it is decrypted
 * on the way in and encrypted again on the way out, so what reaches SharedPreferences is
 * always the ciphertext.
 */
public class EditPasswordPreferenceDialogFragment extends EditTextPreferenceDialogFragmentCompat {
	private EditText editText;

	public static EditPasswordPreferenceDialogFragment newInstance(String key) {
		EditPasswordPreferenceDialogFragment fragment = new EditPasswordPreferenceDialogFragment();
		Bundle args = new Bundle(1);
		args.putString(ARG_KEY, key);
		fragment.setArguments(args);
		return fragment;
	}

	private EditPasswordPreference getPasswordPreference() {
		return (EditPasswordPreference) getPreference();
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);

		editText = (EditText) view.findViewById(android.R.id.edit);
		if(editText != null) {
			// super has just filled this in with what is stored, which is the ciphertext.
			String plaintext = getPasswordPreference().plaintext();
			editText.setText(plaintext);
			editText.setSelection(plaintext == null ? 0 : plaintext.length());
		}
	}

	@Override
	public void onDialogClosed(boolean positiveResult) {
		if(!positiveResult || editText == null) {
			return;
		}

		String plaintext = editText.getText().toString();
		if(getPasswordPreference().callChangeListener(plaintext)) {
			getPasswordPreference().store(plaintext);
		}
	}
}
