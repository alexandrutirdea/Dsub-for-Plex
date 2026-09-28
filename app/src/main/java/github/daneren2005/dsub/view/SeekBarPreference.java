/*
 * Copyright (C) 2012 Christopher Eby <kreed@kreed.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package github.daneren2005.dsub.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;

import github.daneren2005.dsub.R;

/**
 * A preference whose value is picked off a slider in a dialog.
 *
 * The value is persisted as the slider's own position - a string holding a whole number
 * from zero to {@code max - min} - rather than as the number it stands for. That is what
 * was stored before this was moved onto AndroidX preferences, and changing it would have
 * reset everyone's sleep timer, replay gain bump and chat refresh rate to their defaults.
 * {@link #displayValue} is what turns a position into the number a person reads.
 *
 * The dialog itself lives in {@link SeekBarPreferenceDialogFragment}: AndroidX splits a
 * dialog preference in two, the preference holding the value and a DialogFragment showing
 * it, where the framework class this replaced did both at once.
 */
public class SeekBarPreference extends DialogPreference {
	/** The slider position, as persisted. */
	private String value;
	private final int min;
	private final int max;
	private final float stepSize;
	private final String display;

	public SeekBarPreference(Context context, AttributeSet attrs) {
		super(context, attrs);

		TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference);
		try {
			min = a.getInteger(R.styleable.SeekBarPreference_min, 0);
			max = a.getInteger(R.styleable.SeekBarPreference_max, 100);
			stepSize = a.getFloat(R.styleable.SeekBarPreference_stepSize, 1f);
			String configured = a.getString(R.styleable.SeekBarPreference_display);
			display = configured == null ? "%.0f" : configured;
		} finally {
			a.recycle();
		}
	}

	/** How far the slider can travel, the position being an offset from {@link #min}. */
	public int getRange() {
		return max - min;
	}

	public String getValue() {
		return value;
	}

	/** Takes a slider position and persists it, leaving the summary showing the new one. */
	public void setValue(String value) {
		this.value = value;
		persistString(value);
		notifyChanged();
	}

	/** The number a slider position stands for, formatted the way this preference asks. */
	public String displayValue(String value) {
		try {
			int position = Integer.parseInt(value);
			return String.format(display, (position + min) / stepSize);
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	public CharSequence getSummary() {
		return displayValue(value);
	}

	@Override
	protected Object onGetDefaultValue(TypedArray a, int index) {
		return a.getString(index);
	}

	@Override
	protected void onSetInitialValue(Object defaultValue) {
		value = getPersistedString((String) defaultValue);
	}
}
