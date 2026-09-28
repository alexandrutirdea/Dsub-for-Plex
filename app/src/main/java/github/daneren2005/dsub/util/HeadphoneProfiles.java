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
 */
package github.daneren2005.dsub.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import github.daneren2005.dsub.audiofx.AutoEqProfile;

/**
 * Which output the music is going to, and the headphone correction kept for it.
 *
 * Corrections are stored as the text they were imported as, under the name of the output
 * they belong to, and parsed when they are applied. Keeping the text rather than a parsed
 * curve means a profile can be read back and checked, and that a change to how the curve is
 * worked out reaches profiles that were already imported.
 */
public class HeadphoneProfiles {
	private static final String TAG = HeadphoneProfiles.class.getSimpleName();
	private static final String PREFIX = "headphoneEq.";

	/** What to call the outputs whose own name says nothing useful. */
	public static final String WIRED = "Wired headphones";
	public static final String SPEAKER = "Phone speaker";

	/**
	 * The output the music is playing out of, named the way a person would name it - the
	 * headphones' own name over Bluetooth, since that is what a correction is chosen for.
	 *
	 * Asked of the audio framework rather than the Bluetooth stack: a product name from
	 * here needs no permission, while reading the same name off a BluetoothDevice needs
	 * BLUETOOTH_CONNECT from Android 12 on.
	 */
	public static String currentDevice(Context context) {
		AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if(audioManager == null) {
			return null;
		}

		// From Android 13 the framework will say where media actually goes, which settles
		// it when several outputs are connected at once. Tried on its own so that a failure
		// here falls through to the scan below rather than answering "no output at all":
		// getAudioDevicesForAttributes is public API from TIRAMISU, not from S as the
		// version this replaced assumed, so on Android 12 this was throwing
		// NoSuchMethodError and taking the fallback down with it.
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			try {
				AudioAttributes media = new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
						.build();
				List<AudioDeviceInfo> routed = audioManager.getAudioDevicesForAttributes(media);
				if(routed != null && !routed.isEmpty()) {
					return nameOf(routed.get(0));
				}
			} catch(Throwable x) {
				Log.w(TAG, "Could not ask where media is routed, falling back on what is connected", x);
			}
		}

		try {
			// Otherwise take the most likely of what is connected, in the order the platform
			// itself prefers them.
			AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
			AudioDeviceInfo best = null;
			for(AudioDeviceInfo device : devices) {
				if(best == null || rank(device) > rank(best)) {
					best = device;
				}
			}
			return best == null ? null : nameOf(best);
		} catch(Throwable x) {
			Log.w(TAG, "Failed to work out which output is playing", x);
			return null;
		}
	}

	/** Higher wins when several outputs are connected and the platform will not say which. */
	private static int rank(AudioDeviceInfo device) {
		switch(device.getType()) {
			case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
				return 5;
			case AudioDeviceInfo.TYPE_HEARING_AID:
				return 5;
			case AudioDeviceInfo.TYPE_USB_HEADSET:
			case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
			case AudioDeviceInfo.TYPE_WIRED_HEADSET:
				return 4;
			case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
				return 1;
			default:
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
						&& device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
					return 5;
				}
				return 0;
		}
	}

	private static String nameOf(AudioDeviceInfo device) {
		switch(device.getType()) {
			case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
			case AudioDeviceInfo.TYPE_WIRED_HEADSET:
			case AudioDeviceInfo.TYPE_USB_HEADSET:
				// The product name of a wired output is the phone's own, which is no use for
				// telling one pair of headphones from another.
				return WIRED;
			case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
				return SPEAKER;
			default:
				CharSequence product = device.getProductName();
				return product == null ? null : product.toString().trim();
		}
	}

	/**
	 * Every output a correction has been stored for, in name order.
	 *
	 * Read out of the preference keys rather than kept as a list of its own, so there is one
	 * place a profile lives and no second copy to fall out of step with it. The outputs
	 * themselves need not be connected - most of them will not be, which is the point of
	 * being able to see them at all.
	 */
	public static List<String> storedDevices(Context context) {
		List<String> devices = new ArrayList<String>();
		for(String key : Util.getPreferences(context).getAll().keySet()) {
			if(key.startsWith(PREFIX)) {
				devices.add(key.substring(PREFIX.length()));
			}
		}
		Collections.sort(devices, String.CASE_INSENSITIVE_ORDER);
		return devices;
	}

	public static String getText(Context context, String device) {
		if(device == null) {
			return null;
		}
		return Util.getPreferences(context).getString(PREFIX + device, null);
	}

	/** @throws IllegalArgumentException when the text is not a profile; nothing is stored. */
	public static void put(Context context, String device, String text) {
		AutoEqProfile.parse(text);

		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.putString(PREFIX + device, text);
		editor.commit();
	}

	public static void remove(Context context, String device) {
		SharedPreferences.Editor editor = Util.getPreferences(context).edit();
		editor.remove(PREFIX + device);
		editor.commit();
	}

	/** The correction for an output, or null when it has none or the stored text has rotted. */
	public static AutoEqProfile profileFor(Context context, String device) {
		String text = getText(context, device);
		if(text == null) {
			return null;
		}

		try {
			return AutoEqProfile.parse(text);
		} catch(IllegalArgumentException x) {
			Log.w(TAG, "Stored profile for " + device + " no longer reads: " + x.getMessage());
			return null;
		}
	}
}
