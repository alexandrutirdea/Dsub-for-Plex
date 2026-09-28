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

package github.daneren2005.dsub.provider;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import github.daneren2005.dsub.domain.RemoteControlState;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.SonosController;
import github.daneren2005.dsub.service.sonos.SonosDiscovery;
import github.daneren2005.dsub.service.sonos.SonosRoom;

/**
 * Publishes each Sonos room as a route, so speakers turn up in the same picker as the
 * jukebox and any Cast devices rather than needing a screen of their own.
 *
 * Discovery is not continuous. SSDP means broadcasting to the whole network, so it runs
 * when the picker asks for it - on a scan - and the results stand until the next one.
 */
public class SonosRouteProvider extends MediaRouteProvider {
	private static final String TAG = SonosRouteProvider.class.getSimpleName();
	public static final String CATEGORY_SONOS_ROUTE = "github.daneren2005.dsub.SONOS";
	private static final int DISCOVERY_TIMEOUT = 3000;
	private static final int MAX_VOLUME = 100;

	private final DownloadService downloadService;
	private final Handler handler;
	private final SonosDiscovery discovery;
	private final Map<String, SonosRoom> rooms = new HashMap<String, SonosRoom>();
	private boolean scanning = false;

	public SonosRouteProvider(Context context) {
		super(context);
		this.downloadService = (DownloadService) context;
		this.handler = new Handler();
		this.discovery = new SonosDiscovery(context);

		broadcastDescriptor();
		scan();
	}

	/** Refreshes the published routes from a fresh SSDP sweep, off the main thread. */
	public synchronized void scan() {
		if(scanning) {
			return;
		}
		scanning = true;

		new Thread("SonosDiscovery") {
			@Override
			public void run() {
				List<SonosRoom> found;
				try {
					found = discovery.discoverRooms(DISCOVERY_TIMEOUT);
				} catch(Exception x) {
					Log.w(TAG, "Sonos discovery failed: " + x);
					found = new ArrayList<SonosRoom>();
				}

				final List<SonosRoom> discovered = found;
				handler.post(new Runnable() {
					@Override
					public void run() {
						synchronized(SonosRouteProvider.this) {
							scanning = false;
							// Speakers already published are kept when a sweep comes back
							// empty, since a dropped UDP reply is far more likely than a
							// speaker actually vanishing mid-session.
							for(SonosRoom room : discovered) {
								rooms.put(room.getRouteId(), room);
							}
						}
						broadcastDescriptor();
					}
				});
			}
		}.start();
	}

	private synchronized void broadcastDescriptor() {
		IntentFilter routeIntentFilter = new IntentFilter();
		routeIntentFilter.addCategory(CATEGORY_SONOS_ROUTE);
		routeIntentFilter.addAction(MediaControlIntent.ACTION_START_SESSION);
		routeIntentFilter.addAction(MediaControlIntent.ACTION_GET_SESSION_STATUS);
		routeIntentFilter.addAction(MediaControlIntent.ACTION_END_SESSION);

		MediaRouteProviderDescriptor.Builder providerBuilder = new MediaRouteProviderDescriptor.Builder();
		for(SonosRoom room : rooms.values()) {
			MediaRouteDescriptor.Builder routeBuilder =
					new MediaRouteDescriptor.Builder(room.getRouteId(), room.getName());
			routeBuilder.addControlFilter(routeIntentFilter)
					.setPlaybackStream(AudioManager.STREAM_MUSIC)
					.setPlaybackType(android.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
					.setDescription("Sonos")
					.setVolume(currentVolumeOf(room))
					.setVolumeMax(MAX_VOLUME)
					.setVolumeHandling(android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE);
			providerBuilder.addRoute(routeBuilder.build());
		}
		setDescriptor(providerBuilder.build());
	}

	private int currentVolumeOf(SonosRoom room) {
		SonosController controller = activeControllerFor(room);
		return controller == null ? MAX_VOLUME / 4 : (int) (controller.getVolume() * MAX_VOLUME);
	}

	private SonosController activeControllerFor(SonosRoom room) {
		if(!(downloadService.getRemoteController() instanceof SonosController)) {
			return null;
		}

		SonosController controller = (SonosController) downloadService.getRemoteController();
		return controller.getRoom().equals(room) ? controller : null;
	}

	@Override
	public void onDiscoveryRequestChanged(androidx.mediarouter.media.MediaRouteDiscoveryRequest request) {
		if(request != null && request.isActiveScan()) {
			scan();
		}
	}

	@Override
	public MediaRouteProvider.RouteController onCreateRouteController(String routeId) {
		SonosRoom room;
		synchronized(this) {
			room = rooms.get(routeId);
		}
		return room == null ? null : new SonosRouteController(room);
	}

	private class SonosRouteController extends RouteController {
		private final SonosRoom room;

		SonosRouteController(SonosRoom room) {
			this.room = room;
		}

		@Override
		public boolean onControlRequest(Intent intent, androidx.mediarouter.media.MediaRouter.ControlRequestCallback callback) {
			return intent.hasCategory(CATEGORY_SONOS_ROUTE);
		}

		@Override
		public void onSelect() {
			downloadService.setRemoteEnabled(RemoteControlState.SONOS,
					new SonosController(downloadService, handler, room));
		}

		@Override
		public void onUnselect() {
			downloadService.setRemoteEnabled(RemoteControlState.LOCAL);
		}

		@Override
		public void onRelease() {
			downloadService.setRemoteEnabled(RemoteControlState.LOCAL);
		}

		@Override
		public void onUpdateVolume(int delta) {
			SonosController controller = activeControllerFor(room);
			if(controller != null) {
				controller.updateVolume(delta > 0);
			}
			broadcastDescriptor();
		}

		@Override
		public void onSetVolume(int volume) {
			SonosController controller = activeControllerFor(room);
			if(controller != null) {
				controller.setVolume(volume);
			}
			broadcastDescriptor();
		}
	}
}
