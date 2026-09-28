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
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaRouter.RouteInfo;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.util.DrawableTint;

/**
 * The sheet behind every cast button, as one list of everywhere the audio can play, in
 * place of the platform's two centred dialogs.
 *
 * The cast button opens a chooser while the audio is on the phone and a controller once
 * it is on a device. Both are this: the phone leads the list, every device the button's
 * selector matches follows, and whichever has the audio is marked in the accent - with its
 * volume under it when it is a device that has one. Tapping another row moves the audio
 * there, and tapping the phone while casting stops the cast, which is what the old
 * controller's button did. The sheet goes once the move has taken.
 *
 * {@link RoutePickerSheet} and {@link RouteControllerSheet} put this inside the two
 * fragments MediaRouteButton knows how to show.
 */
final class RouteSheet {
	private final DialogFragment owner;
	private final MediaRouteSelector selector;
	private MediaRouter router;
	private LinearLayout list;
	private View searching;
	private SeekBar volume;
	private boolean draggingVolume;
	private boolean scanning;

	private final MediaRouter.Callback callback = new MediaRouter.Callback() {
		@Override
		public void onRouteAdded(MediaRouter router, RouteInfo route) {
			refresh();
		}

		@Override
		public void onRouteRemoved(MediaRouter router, RouteInfo route) {
			refresh();
		}

		@Override
		public void onRouteChanged(MediaRouter router, RouteInfo route) {
			refresh();
		}

		/**
		 * Only the slider moves. Rebuilding the list would pull it out from under a finger
		 * dragging it, and the device answers every step of the drag with one of these.
		 */
		@Override
		public void onRouteVolumeChanged(MediaRouter router, RouteInfo route) {
			if(volume != null && !draggingVolume && route.isSelected()) {
				volume.setProgress(route.getVolume());
			}
		}

		@Override
		public void onRouteSelected(MediaRouter router, RouteInfo route) {
			owner.dismissAllowingStateLoss();
		}

		@Override
		public void onRouteUnselected(MediaRouter router, RouteInfo route) {
			owner.dismissAllowingStateLoss();
		}
	};

	RouteSheet(DialogFragment owner, MediaRouteSelector selector) {
		this.owner = owner;
		this.selector = resolveSelector(selector);
	}

	/**
	 * The button hands both fragments its selector, but a fragment rebuilt after a restart
	 * can come back without one, and an empty selector matches nothing - the sheet would
	 * list the phone alone.
	 */
	private static MediaRouteSelector resolveSelector(MediaRouteSelector selector) {
		if(selector != null && !selector.isEmpty()) {
			return selector;
		}
		DownloadService service = DownloadService.getInstance();
		MediaRouteSelector fallback = (service == null) ? null : service.getRemoteSelector();
		return (fallback == null) ? MediaRouteSelector.EMPTY : fallback;
	}

	Dialog create(Context context) {
		router = MediaRouter.getInstance(context);

		View root = LayoutInflater.from(context).inflate(R.layout.route_picker_sheet, null, false);
		list = (LinearLayout) root.findViewById(R.id.route_picker_list);
		searching = root.findViewById(R.id.route_picker_searching);

		BottomSheetDialog dialog = new BottomSheetDialog(context);
		dialog.setContentView(root);

		// The sheet draws its own rounded surface; the container Material wraps it in would
		// otherwise show square corners behind it.
		View container = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
		if(container != null) {
			container.setBackgroundColor(Color.TRANSPARENT);
		}

		// A handful of rows never needs a half-open state, and with one a tap on the last
		// device could land on the scrim instead.
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

		// The sheet has the key events while it is up, so the volume keys would otherwise
		// turn the phone up while the music plays somewhere else. The old controller passed
		// them on to the device, and so does this.
		dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
			@Override
			public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
				if(keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
					return false;
				}
				RouteInfo selected = router.getSelectedRoute();
				if(selected.isDefaultOrBluetooth()
						|| selected.getVolumeHandling() != RouteInfo.PLAYBACK_VOLUME_VARIABLE) {
					return false;
				}
				if(event.getAction() == KeyEvent.ACTION_DOWN) {
					selected.requestUpdateVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
				}
				return true;
			}
		});

		refresh();
		return dialog;
	}

	/** An active scan is what wakes the DLNA and Sonos providers up. */
	void start() {
		router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
		scanning = true;
		refresh();
	}

	void stop() {
		if(scanning) {
			router.removeCallback(callback);
			scanning = false;
		}
	}

	private void refresh() {
		if(list == null || router == null || draggingVolume) {
			return;
		}

		List<RouteInfo> routes = new ArrayList<>();
		for(RouteInfo route : router.getRoutes()) {
			if(!route.isDefaultOrBluetooth() && route.isEnabled() && route.matchesSelector(selector)) {
				routes.add(route);
			}
		}
		Collections.sort(routes, new Comparator<RouteInfo>() {
			@Override
			public int compare(RouteInfo a, RouteInfo b) {
				return a.getName().compareToIgnoreCase(b.getName());
			}
		});

		list.removeAllViews();
		volume = null;
		addLocalRow();
		for(RouteInfo route : routes) {
			addRouteRow(route);
		}
		searching.setVisibility(routes.isEmpty() ? View.VISIBLE : View.GONE);
	}

	/**
	 * The phone, or the headphones it is playing through. Bluetooth is where the audio is
	 * while it is connected, so the row goes by that name rather than claiming the speaker.
	 */
	private void addLocalRow() {
		RouteInfo selected = router.getSelectedRoute();
		boolean local = selected.isDefaultOrBluetooth();
		boolean bluetooth = local && !selected.isDefault();

		String name = bluetooth ? selected.getName() : owner.getString(R.string.route_picker_this_phone);
		int icon = bluetooth ? R.drawable.ic_route_headphones : R.drawable.ic_route_phone;
		addRow(icon, name, null, local, false, new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if(router.getSelectedRoute().isDefaultOrBluetooth()) {
					owner.dismissAllowingStateLoss();
				} else {
					router.unselect(MediaRouter.UNSELECT_REASON_STOPPED);
				}
			}
		});
	}

	private void addRouteRow(final RouteInfo route) {
		boolean connecting = route.getConnectionState() == RouteInfo.CONNECTION_STATE_CONNECTING;
		String description = connecting ? owner.getString(R.string.route_picker_connecting) : route.getDescription();
		boolean selected = route.isSelected() && !connecting;
		addRow(iconFor(route), route.getName(), description, selected, connecting,
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if(route.isSelected()) {
							owner.dismissAllowingStateLoss();
						} else {
							route.select();
						}
					}
				});

		if(selected && route.getVolumeHandling() == RouteInfo.PLAYBACK_VOLUME_VARIABLE) {
			addVolumeRow(route);
		}
	}

	private void addVolumeRow(final RouteInfo route) {
		View row = LayoutInflater.from(list.getContext()).inflate(R.layout.route_picker_volume, list, false);
		volume = (SeekBar) row.findViewById(R.id.route_volume);
		volume.setMax(route.getVolumeMax());
		volume.setProgress(route.getVolume());
		volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if(fromUser) {
					route.requestSetVolume(progress);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				draggingVolume = true;
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				draggingVolume = false;
				// Anything that changed while the list was held still shows up now.
				refresh();
			}
		});
		list.addView(row);
	}

	private void addRow(int icon, String name, String description, boolean selected, boolean connecting,
						View.OnClickListener onClick) {
		View row = LayoutInflater.from(list.getContext()).inflate(R.layout.route_picker_item, list, false);
		ImageView iconView = (ImageView) row.findViewById(R.id.route_icon);
		TextView nameView = (TextView) row.findViewById(R.id.route_name);
		TextView descriptionView = (TextView) row.findViewById(R.id.route_description);

		nameView.setText(name);
		// A Sonos speaker left with its default name is called what its provider calls
		// itself, and saying it twice tells nobody anything.
		if(description != null && description.length() > 0 && !description.equalsIgnoreCase(name)) {
			descriptionView.setText(description);
			descriptionView.setVisibility(View.VISIBLE);
		}

		// The one place the accent goes is the device the audio is on.
		ColorStateList tint = selected
				? ColorStateList.valueOf(DrawableTint.getColorRes(list.getContext(), R.attr.colorAccent))
				: nameView.getTextColors();
		iconView.setImageResource(icon);
		iconView.setImageTintList(tint);
		if(selected) {
			nameView.setTextColor(tint);
			ImageView check = (ImageView) row.findViewById(R.id.route_check);
			check.setImageTintList(tint);
			check.setVisibility(View.VISIBLE);
		}
		row.findViewById(R.id.route_connecting).setVisibility(connecting ? View.VISIBLE : View.GONE);

		row.setOnClickListener(onClick);
		list.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	/**
	 * What kind of thing a route is, as far as it says. DLNA and Sonos routes do not say,
	 * so anything that is not a group or a television is drawn as a speaker.
	 */
	private static int iconFor(RouteInfo route) {
		if(route.isGroup()) {
			return R.drawable.ic_route_group;
		}
		if(route.getDeviceType() == RouteInfo.DEVICE_TYPE_TV) {
			return R.drawable.ic_route_tv;
		}
		return R.drawable.ic_route_speaker;
	}
}
