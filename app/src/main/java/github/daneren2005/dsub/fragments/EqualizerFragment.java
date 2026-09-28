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

 Copyright 2010 (C) Sindre Mehus
 */
package github.daneren2005.dsub.fragments;

import android.content.ClipboardManager;
import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.media.audiofx.BassBoost;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.audiofx.AutoEqIndex;
import github.daneren2005.dsub.audiofx.AutoEqProfile;
import github.daneren2005.dsub.audiofx.DynamicsEqualizer;
import github.daneren2005.dsub.audiofx.EqualizerController;
import github.daneren2005.dsub.audiofx.GraphicEqualizer;
import github.daneren2005.dsub.audiofx.LegacyEqualizer;
import github.daneren2005.dsub.audiofx.LoudnessEnhancerController;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.util.HeadphoneProfiles;
import github.daneren2005.dsub.util.LoadingTask;
import github.daneren2005.dsub.util.Util;

/**
 * Created by Scott on 10/27/13.
 */
public class EqualizerFragment extends SubsonicFragment {
	private static final String TAG = EqualizerFragment.class.getSimpleName();

	private static final int MENU_GROUP_PRESET = 100;
	/** A two word query matches hundreds of headphones; a list is only useful if it is short. */
	private static final int SEARCH_LIMIT = 40;

	/**
	 * The bands, in the order they are drawn. Held as a list rather than the map this used
	 * to be: the bands are 0..n with no gaps, and the preamp is no longer band -1 pretending
	 * to be one of them.
	 */
	private final List<SeekBar> bandBars = new ArrayList<SeekBar>();
	private SeekBar preampBar;
	private SeekBar bassBar;
	private SeekBar loudnessBar;

	private GraphicEqualizer graphicEqualizer;
	private EqualizerController equalizerController;
	private BassBoost bass;
	private LoudnessEnhancerController loudnessEnhancer;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
		rootView = inflater.inflate(R.layout.equalizer, container, false);

		try {
			// Asked for rather than taken: reached from the drawer this screen may be the
			// thing that starts the service, and getInstance() would be null for it.
			DownloadService service = getDownloadService();
			if(service != null) {
				equalizerController = service.getEqualizerController();
				if(equalizerController != null) {
					bass = equalizerController.getBassBoost();
					loudnessEnhancer = equalizerController.getLoudnessEnhancerController();
				}
			}

			graphicEqualizer = chooseEqualizer(service);
			if(graphicEqualizer == null) {
				throw new Exception("No equalizer on this device");
			}

			initEqualizer();
		} catch(Exception e) {
			Log.e(TAG, "Failed to initialize EQ", e);
			Util.toast(context, "Failed to initialize EQ");
			context.onBackPressed();
			return rootView;
		}

		final View presetButton = rootView.findViewById(R.id.equalizer_preset);
		registerForContextMenu(presetButton);
		presetButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				presetButton.showContextMenu();
			}
		});

		CheckBox enabledCheckBox = (CheckBox) rootView.findViewById(R.id.equalizer_enabled);
		enabledCheckBox.setChecked(graphicEqualizer.isEnabled());
		enabledCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
				try {
					graphicEqualizer.setEnabled(b);
					updateBars(!b);
				} catch(Exception e) {
					Log.e(TAG, "Failed to set EQ enabled", e);
					Util.toast(context, "Failed to set EQ enabled");
					context.onBackPressed();
				}
			}
		});

		setTitle(R.string.equalizer_label);
		updateProfileSubtitle();

		return rootView;
	}

	/**
	 * Says which correction is on the music, under the title.
	 *
	 * The bands on this screen are the user's own and a correction never writes them, so
	 * without this the screen looks exactly the same whether a profile is loaded or not -
	 * and the preamp slider shows only the user's half of the input gain, not the headroom
	 * the correction took as well.
	 */
	private void updateProfileSubtitle() {
		if(!(graphicEqualizer instanceof DynamicsEqualizer)) {
			// The correction runs on an effect the older devices do not carry, so on those
			// there is nothing to be had rather than nothing loaded.
			setSubtitle(context.getResources().getString(R.string.equalizer_profile_unsupported));
			return;
		}

		String device = HeadphoneProfiles.currentDevice(context);
		if(device == null) {
			setSubtitle(context.getResources().getString(R.string.equalizer_profile_no_device));
			return;
		}

		AutoEqProfile profile = HeadphoneProfiles.profileFor(context, device);
		if(profile == null) {
			setSubtitle(context.getResources().getString(R.string.equalizer_profile_none, device));
			return;
		}

		String preamp = String.format(Locale.getDefault(), "%.1f dB", profile.getPreampDb());
		setSubtitle(context.getResources().getString(R.string.equalizer_profile_active,
				device, profile.getFilterCount(), preamp));
	}

	/**
	 * Ten bands where the platform will give them, and the device's own bands where it will
	 * not. {@link DynamicsEqualizer} lives on the service because the headphone correction
	 * shares it and has to outlive this screen; the fallback is built here because nothing
	 * but this screen ever touches it.
	 */
	private GraphicEqualizer chooseEqualizer(DownloadService service) {
		DynamicsEqualizer dynamics = service == null ? null : service.getDynamicsEqualizer();
		if(dynamics != null && dynamics.isAvailable()) {
			silenceLegacyEqualizer();
			return dynamics;
		}

		if(equalizerController != null && equalizerController.getEqualizer() != null) {
			return new LegacyEqualizer(context, equalizerController);
		}

		return null;
	}

	/**
	 * The device's equalizer effect is still around for its bass booster, and on an upgrade
	 * its saved settings will have switched it back on. Two equalizers on one session would
	 * both be shaping the sound and only one of them would be on the screen.
	 */
	private void silenceLegacyEqualizer() {
		if(equalizerController == null) {
			return;
		}

		try {
			if(equalizerController.getEqualizer() != null) {
				equalizerController.getEqualizer().setEnabled(false);
			}
		} catch(Exception e) {
			Log.w(TAG, "Failed to switch off the device equalizer", e);
		}
	}

	@Override
	public void onStop() {
		super.onStop();

		try {
			if(graphicEqualizer != null) {
				graphicEqualizer.save();
			}

			if(equalizerController != null) {
				// Kept for the bass booster, which is the device effect's either way.
				equalizerController.saveSettings();

				boolean bassOn = bass != null && bass.getEnabled();
				if(!graphicEqualizer.isEnabled() && !bassOn) {
					equalizerController.release();
				}
			}
		} catch(Exception e) {
			Log.w(TAG, "Failed to release controller", e);
		}
	}

	@Override
	public void onStart() {
		super.onStart();

		DownloadService service = getDownloadService();
		if(service == null) {
			return;
		}

		if(equalizerController == null) {
			equalizerController = service.getEqualizerController();
		}
		if(equalizerController != null && bass == null) {
			bass = equalizerController.getBassBoost();
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		menuInflater.inflate(R.menu.equalizer, menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(item.getItemId() == R.id.menu_search_autoeq) {
			searchAutoEqProfile();
			return true;
		} else if(item.getItemId() == R.id.menu_paste_autoeq) {
			pasteAutoEqProfile();
			return true;
		} else if(item.getItemId() == R.id.menu_list_autoeq) {
			showStoredProfiles();
			return true;
		} else if(item.getItemId() == R.id.menu_remove_autoeq) {
			removeAutoEqProfile();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	/**
	 * Looks the headphones up in AutoEq by name and puts the chosen correction on whatever
	 * the music is playing out of, so the browser is not part of it.
	 */
	private void searchAutoEqProfile() {
		final String device = HeadphoneProfiles.currentDevice(context);
		if(device == null) {
			Util.toast(context, R.string.equalizer_autoeq_no_device);
			return;
		}

		final EditText queryBox = new EditText(context);
		queryBox.setHint(R.string.equalizer_autoeq_search_hint);
		// The output's own name is the likeliest thing to be looking for.
		queryBox.setText(device);
		queryBox.setSelectAllOnFocus(true);

		new AlertDialog.Builder(context)
				.setTitle(R.string.equalizer_autoeq_search)
				.setView(queryBox)
				.setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						runAutoEqSearch(device, queryBox.getText().toString());
					}
				})
				.setNegativeButton(R.string.common_cancel, null)
				.show();
	}

	private void runAutoEqSearch(final String device, final String query) {
		if(query.trim().isEmpty()) {
			return;
		}

		new LoadingTask<List<AutoEqIndex.Match>>(context, true) {
			@Override
			protected List<AutoEqIndex.Match> doInBackground() throws Throwable {
				return AutoEqIndex.search(context, query, SEARCH_LIMIT);
			}


			/** The generic handler blames the Subsonic server, which is not who said no. */
			@Override
			protected String getErrorMessage(Throwable error) {
				if(error instanceof AutoEqIndex.DownloadException) {
					return error.getMessage();
				}
				return super.getErrorMessage(error);
			}

			@Override
			protected void done(List<AutoEqIndex.Match> matches) {
				if(matches.isEmpty()) {
					Util.toast(context, context.getResources().getString(R.string.equalizer_autoeq_no_match, query));
					return;
				}

				showAutoEqMatches(device, matches);
			}
		}.execute();
	}

	private void showAutoEqMatches(final String device, final List<AutoEqIndex.Match> matches) {
		final String[] labels = new String[matches.size()];
		for(int i = 0; i < matches.size(); i++) {
			labels[i] = matches.get(i).getLabel();
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.equalizer_autoeq_search)
				.setItems(labels, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						applyAutoEqMatch(device, matches.get(which));
					}
				})
				.show();
	}

	private void applyAutoEqMatch(final String device, final AutoEqIndex.Match match) {
		new LoadingTask<AutoEqProfile>(context, true) {
			@Override
			protected AutoEqProfile doInBackground() throws Throwable {
				String text = AutoEqIndex.fetchProfile(match);
				// Stored as fetched, and parsed to be sure it reads before it is kept.
				HeadphoneProfiles.put(context, device, text);
				return AutoEqProfile.parse(text);
			}


			/** The generic handler blames the Subsonic server, which is not who said no. */
			@Override
			protected String getErrorMessage(Throwable error) {
				if(error instanceof AutoEqIndex.DownloadException) {
					return error.getMessage();
				}
				return super.getErrorMessage(error);
			}

			@Override
			protected void done(AutoEqProfile profile) {
				DownloadService downloadService = DownloadService.getInstance();
				if(downloadService != null) {
					downloadService.applyHeadphoneEqualizer();
				}

				updateProfileSubtitle();
				Util.toast(context, context.getResources().getString(R.string.equalizer_autoeq_applied,
						profile.getFilterCount(), device));
			}
		}.execute();
	}

	/**
	 * Takes an AutoEq ParametricEQ file off the clipboard and keeps it for whatever the
	 * music is playing out of right now - copy it in the browser, put the headphones on,
	 * paste it here. The correction goes on as soon as it is stored, and from then on
	 * whenever those headphones are the ones connected.
	 */
	private void pasteAutoEqProfile() {
		String device = HeadphoneProfiles.currentDevice(context);
		if(device == null) {
			Util.toast(context, R.string.equalizer_autoeq_no_device);
			return;
		}

		CharSequence pasted = null;
		ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if(clipboard != null && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
			pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
		}

		if(pasted == null || pasted.toString().trim().isEmpty()) {
			Util.toast(context, R.string.equalizer_autoeq_empty);
			return;
		}

		try {
			HeadphoneProfiles.put(context, device, pasted.toString());
		} catch(IllegalArgumentException x) {
			// Says which part of it did not read, which is the useful half of a failed paste.
			Util.toast(context, x.getMessage());
			return;
		}

		AutoEqProfile profile = HeadphoneProfiles.profileFor(context, device);
		DownloadService downloadService = DownloadService.getInstance();
		if(downloadService != null) {
			downloadService.applyHeadphoneEqualizer();
		}

		updateProfileSubtitle();
		Util.toast(context, context.getResources().getString(R.string.equalizer_autoeq_applied,
				profile == null ? 0 : profile.getFilterCount(), device));
	}

	/**
	 * Every correction that has been stored, and which of them is on the music right now.
	 *
	 * Profiles are kept per output and only the connected one is otherwise reachable, so
	 * without this there is no way to see that a pair of headphones in a drawer still has a
	 * correction waiting for it, or to clear one out without connecting them again.
	 */
	private void showStoredProfiles() {
		final List<String> devices = HeadphoneProfiles.storedDevices(context);
		if(devices.isEmpty()) {
			Util.toast(context, R.string.equalizer_autoeq_no_profiles);
			return;
		}

		final String current = HeadphoneProfiles.currentDevice(context);
		final String[] labels = new String[devices.size()];
		for(int i = 0; i < devices.size(); i++) {
			String device = devices.get(i);
			AutoEqProfile profile = HeadphoneProfiles.profileFor(context, device);

			if(profile == null) {
				labels[i] = context.getResources().getString(R.string.equalizer_autoeq_profile_unreadable, device);
			} else if(device.equals(current)) {
				labels[i] = context.getResources().getString(R.string.equalizer_autoeq_profile_active,
						device, profile.getFilterCount());
			} else {
				labels[i] = context.getResources().getString(R.string.equalizer_autoeq_profile,
						device, profile.getFilterCount());
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.equalizer_autoeq_profiles)
				.setItems(labels, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						confirmRemoveProfile(devices.get(which));
					}
				})
				.setNegativeButton(R.string.common_close, null)
				.show();
	}

	private void confirmRemoveProfile(final String device) {
		new AlertDialog.Builder(context)
				.setTitle(device)
				.setMessage(R.string.equalizer_autoeq_remove_confirm)
				.setPositiveButton(R.string.common_delete, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						removeProfile(device);
					}
				})
				.setNegativeButton(R.string.common_cancel, null)
				.show();
	}

	private void removeAutoEqProfile() {
		String device = HeadphoneProfiles.currentDevice(context);
		if(device == null) {
			Util.toast(context, R.string.equalizer_autoeq_no_device);
			return;
		}

		if(HeadphoneProfiles.getText(context, device) == null) {
			Util.toast(context, context.getResources().getString(R.string.equalizer_autoeq_none, device));
			return;
		}

		removeProfile(device);
	}

	private void removeProfile(String device) {
		HeadphoneProfiles.remove(context, device);

		// Only the connected output's correction is actually on the effect, but working out
		// whether this was that one is what applyHeadphoneEqualizer already does.
		DownloadService downloadService = DownloadService.getInstance();
		if(downloadService != null) {
			downloadService.applyHeadphoneEqualizer();
		}

		updateProfileSubtitle();
		Util.toast(context, context.getResources().getString(R.string.equalizer_autoeq_removed, device));
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, view, menuInfo);
		if(!primaryFragment) {
			return;
		}

		int currentPreset = graphicEqualizer.getCurrentPreset();
		String[] names = graphicEqualizer.getPresetNames();
		for(int preset = 0; preset < names.length; preset++) {
			MenuItem menuItem = menu.add(MENU_GROUP_PRESET, preset, preset, names[preset]);
			if(preset == currentPreset) {
				menuItem.setChecked(true);
			}
		}
		menu.setGroupCheckable(MENU_GROUP_PRESET, true, true);
	}

	@Override
	public boolean onContextItemSelected(MenuItem menuItem) {
		graphicEqualizer.usePreset(menuItem.getItemId());
		updateBars(false);
		return true;
	}

	/**
	 * Puts back on the screen whatever the equalizer says it is now.
	 *
	 * @param justSwitchedOff the equalizer has this moment been switched off, as opposed to
	 *		having been off all along. The bass and voice boosters are wound down only then:
	 *		they are effects of their own, and picking a preset on a switched-off equalizer
	 *		is no reason to take them away.
	 */
	private void updateBars(boolean justSwitchedOff) {
		try {
			boolean isEnabled = graphicEqualizer.isEnabled();

			for(int band = 0; band < bandBars.size(); band++) {
				SeekBar bar = bandBars.get(band);
				bar.setEnabled(isEnabled);
				bar.setProgress(progressOf(graphicEqualizer.getBandGainDb(band), graphicEqualizer.getMinGainDb()));
			}

			if(preampBar != null) {
				preampBar.setEnabled(isEnabled);
				preampBar.setProgress(progressOf(graphicEqualizer.getPreampDb(), graphicEqualizer.getMinPreampDb()));
			}

			if(bassBar != null) {
				bassBar.setEnabled(isEnabled);
			}
			if(loudnessBar != null) {
				loudnessBar.setEnabled(isEnabled);
			}

			if(justSwitchedOff && !isEnabled) {
				if(bass != null) {
					bass.setStrength((short) 0);
					bass.setEnabled(false);
				}
				if(bassBar != null) {
					bassBar.setProgress(0);
				}
				if(loudnessBar != null && loudnessEnhancer != null) {
					loudnessEnhancer.setGain(0);
					loudnessBar.setProgress(0);
				}
			}
		} catch(Exception e) {
			Log.e(TAG, "Failed to update bars", e);
		}
	}

	private void initEqualizer() {
		LinearLayout layout = (LinearLayout) rootView.findViewById(R.id.equalizer_layout);
		final boolean isEnabled = graphicEqualizer.isEnabled();

		// The layout is freshly inflated on every onCreateView but this list is not.
		bandBars.clear();

		initPreamp(layout, isEnabled);

		final float minGain = graphicEqualizer.getMinGainDb();
		final float maxGain = graphicEqualizer.getMaxGainDb();

		for(int i = 0; i < graphicEqualizer.getBandCount(); i++) {
			final int band = i;

			View bandBar = LayoutInflater.from(context).inflate(R.layout.equalizer_bar, null);
			TextView freqTextView = (TextView) bandBar.findViewById(R.id.equalizer_frequency);
			final TextView levelTextView = (TextView) bandBar.findViewById(R.id.equalizer_level);
			SeekBar bar = (SeekBar) bandBar.findViewById(R.id.equalizer_bar);

			freqTextView.setText(frequencyLabel(graphicEqualizer.getCenterFrequency(band)));

			bandBars.add(bar);
			// One step per dB: the effect will take finer than that, but nobody can hear the
			// difference between a slider at 3.0 and one at 3.1.
			bar.setMax(Math.round(maxGain - minGain));
			bar.setProgress(progressOf(graphicEqualizer.getBandGainDb(band), minGain));
			bar.setEnabled(isEnabled);
			updateLevelText(levelTextView, graphicEqualizer.getBandGainDb(band));

			bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					try {
						float gain = progress + minGain;
						if(fromUser) {
							graphicEqualizer.setBandGainDb(band, gain);
						}
						updateLevelText(levelTextView, gain);
					} catch(Exception e) {
						Log.e(TAG, "Failed to change equalizer", e);
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			});
			layout.addView(bandBar);
		}

		initSpecialEffects(isEnabled);
	}

	private void initPreamp(LinearLayout layout, boolean isEnabled) {
		View bandBar = LayoutInflater.from(context).inflate(R.layout.equalizer_bar, null);
		TextView freqTextView = (TextView) bandBar.findViewById(R.id.equalizer_frequency);
		final TextView levelTextView = (TextView) bandBar.findViewById(R.id.equalizer_level);
		preampBar = (SeekBar) bandBar.findViewById(R.id.equalizer_bar);

		freqTextView.setText(R.string.equalizer_preamp);

		final float minPreamp = graphicEqualizer.getMinPreampDb();
		final float maxPreamp = graphicEqualizer.getMaxPreampDb();

		preampBar.setMax(Math.round(maxPreamp - minPreamp));
		preampBar.setProgress(progressOf(graphicEqualizer.getPreampDb(), minPreamp));
		preampBar.setEnabled(isEnabled);
		updateLevelText(levelTextView, graphicEqualizer.getPreampDb());

		preampBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				try {
					float gain = progress + minPreamp;
					if(fromUser) {
						graphicEqualizer.setPreampDb(gain);
					}
					updateLevelText(levelTextView, gain);
				} catch(Exception e) {
					Log.e(TAG, "Failed to change preamp", e);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
		layout.addView(bandBar);
	}

	/**
	 * The bass booster and the voice booster, which are effects of their own and not bands
	 * of the equalizer - they stay where they were whichever equalizer is underneath.
	 */
	private void initSpecialEffects(boolean isEnabled) {
		LinearLayout specialLayout = (LinearLayout) rootView.findViewById(R.id.special_effects_layout);

		if(bass != null) {
			View bandBar = LayoutInflater.from(context).inflate(R.layout.equalizer_bar, null);
			TextView freqTextView = (TextView) bandBar.findViewById(R.id.equalizer_frequency);
			final TextView bassTextView = (TextView) bandBar.findViewById(R.id.equalizer_level);
			bassBar = (SeekBar) bandBar.findViewById(R.id.equalizer_bar);

			freqTextView.setText(R.string.equalizer_bass_booster);
			bassBar.setEnabled(isEnabled);
			short bassLevel = 0;
			if(bass.getEnabled()) {
				bassLevel = bass.getRoundedStrength();
			}
			bassTextView.setText(context.getResources().getString(R.string.equalizer_bass_size, bassLevel));
			bassBar.setMax(1000);
			bassBar.setProgress(bassLevel);
			bassBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					try {
						bassTextView.setText(context.getResources().getString(R.string.equalizer_bass_size, progress));
						if (fromUser) {
							if (progress > 0) {
								if (!bass.getEnabled()) {
									bass.setEnabled(true);
								}
								bass.setStrength((short) progress);
							} else if (progress == 0 && bass.getEnabled()) {
								bass.setStrength((short) progress);
								bass.setEnabled(false);
							}
						}
					} catch(Exception e) {
						Log.w(TAG, "Error on changing bass: ", e);
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {

				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {

				}
			});
			specialLayout.addView(bandBar);
		}

		if(loudnessEnhancer != null && loudnessEnhancer.isAvailable()) {
			View bandBar = LayoutInflater.from(context).inflate(R.layout.equalizer_bar, null);
			TextView freqTextView = (TextView) bandBar.findViewById(R.id.equalizer_frequency);
			final TextView loudnessTextView = (TextView) bandBar.findViewById(R.id.equalizer_level);
			loudnessBar = (SeekBar) bandBar.findViewById(R.id.equalizer_bar);

			freqTextView.setText(R.string.equalizer_voice_booster);
			loudnessBar.setEnabled(isEnabled);
			int loudnessLevel = 0;
			if(loudnessEnhancer.isEnabled()) {
				loudnessLevel = (int) loudnessEnhancer.getGain();
			}
			loudnessBar.setProgress(loudnessLevel / 100);
			loudnessTextView.setText(context.getResources().getString(R.string.equalizer_db_size, loudnessLevel / 100));
			loudnessBar.setMax(15);
			loudnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					try {
						loudnessTextView.setText(context.getResources().getString(R.string.equalizer_db_size, progress));
						if(fromUser) {
							if(progress > 0) {
								if(!loudnessEnhancer.isEnabled()) {
									loudnessEnhancer.enable();
								}
								loudnessEnhancer.setGain(progress * 100);
							} else if(progress == 0 && loudnessEnhancer.isEnabled()) {
								loudnessEnhancer.setGain(progress * 100);
								loudnessEnhancer.disable();
							}
						}
					} catch(Exception e) {
						Log.w(TAG, "Error on changing loudness: ", e);
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {

				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {

				}
			});
			specialLayout.addView(bandBar);
		}
	}

	private int progressOf(float gainDb, float minDb) {
		return Math.round(gainDb - minDb);
	}

	/** kHz once the numbers get long enough for the Hz to stop being readable. */
	private String frequencyLabel(int hz) {
		if(hz >= 1000) {
			int khz = hz / 1000;
			int remainder = (hz % 1000) / 100;
			return remainder == 0 ? (khz + " kHz") : (khz + "." + remainder + " kHz");
		}
		return hz + " Hz";
	}

	private void updateLevelText(TextView levelTextView, float gainDb) {
		int rounded = Math.round(gainDb);
		levelTextView.setText((rounded > 0 ? "+" : "")
				+ context.getResources().getString(R.string.equalizer_db_size, rounded));
	}
}
