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
import github.daneren2005.dsub.service.DlnaController;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.dlna.DlnaDiscovery;
import github.daneren2005.dsub.service.dlna.DlnaRenderer;

/**
 * Publishes each UPnP MediaRenderer as a route, so a TV or receiver turns up in the same
 * picker as the Sonos speakers, the jukebox and any Cast devices.
 *
 * Discovery is not continuous, for the same reason as Sonos: SSDP means broadcasting to
 * the whole network, so it runs when the picker asks for it and the results stand until
 * the next one.
 */
public class DlnaRouteProvider extends MediaRouteProvider {
	private static final String TAG = DlnaRouteProvider.class.getSimpleName();
	public static final String CATEGORY_DLNA_ROUTE = "github.daneren2005.dsub.DLNA";
	private static final int DISCOVERY_TIMEOUT = 3000;
	private static final int MAX_VOLUME = 100;

	private final DownloadService downloadService;
	private final Handler handler;
	private final DlnaDiscovery discovery;
	private final Map<String, DlnaRenderer> renderers = new HashMap<String, DlnaRenderer>();
	private boolean scanning = false;

	public DlnaRouteProvider(Context context) {
		super(context);
		this.downloadService = (DownloadService) context;
		this.handler = new Handler();
		this.discovery = new DlnaDiscovery(context);

		broadcastDescriptor();
		scan();
	}

	/** Refreshes the published routes from a fresh SSDP sweep, off the main thread. */
	public synchronized void scan() {
		if(scanning) {
			return;
		}
		scanning = true;

		new Thread("DlnaDiscovery") {
			@Override
			public void run() {
				List<DlnaRenderer> found;
				try {
					found = discovery.discoverRenderers(DISCOVERY_TIMEOUT);
				} catch(Exception x) {
					Log.w(TAG, "DLNA discovery failed: " + x);
					found = new ArrayList<DlnaRenderer>();
				}

				final List<DlnaRenderer> discovered = found;
				handler.post(new Runnable() {
					@Override
					public void run() {
						synchronized(DlnaRouteProvider.this) {
							scanning = false;
							// Renderers already published are kept when a sweep comes back
							// empty, since a dropped UDP reply is far more likely than a
							// device actually vanishing mid-session.
							for(DlnaRenderer renderer : discovered) {
								renderers.put(renderer.getRouteId(), renderer);
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
		routeIntentFilter.addCategory(CATEGORY_DLNA_ROUTE);
		routeIntentFilter.addAction(MediaControlIntent.ACTION_START_SESSION);
		routeIntentFilter.addAction(MediaControlIntent.ACTION_GET_SESSION_STATUS);
		routeIntentFilter.addAction(MediaControlIntent.ACTION_END_SESSION);

		MediaRouteProviderDescriptor.Builder providerBuilder = new MediaRouteProviderDescriptor.Builder();
		for(DlnaRenderer renderer : renderers.values()) {
			MediaRouteDescriptor.Builder routeBuilder =
					new MediaRouteDescriptor.Builder(renderer.getRouteId(), renderer.getName());
			routeBuilder.addControlFilter(routeIntentFilter)
					.setPlaybackStream(AudioManager.STREAM_MUSIC)
					.setPlaybackType(android.media.MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
					.setDescription("DLNA")
					.setVolume(currentVolumeOf(renderer))
					.setVolumeMax(MAX_VOLUME)
					// A renderer without a RenderingControl service has no volume to set,
					// so the picker is told to leave its slider alone rather than offer
					// one that does nothing.
					.setVolumeHandling(renderer.hasVolumeControl()
							? android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
							: android.media.MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED);
			providerBuilder.addRoute(routeBuilder.build());
		}
		setDescriptor(providerBuilder.build());
	}

	private int currentVolumeOf(DlnaRenderer renderer) {
		DlnaController controller = activeControllerFor(renderer);
		return controller == null ? MAX_VOLUME / 4 : (int) (controller.getVolume() * MAX_VOLUME);
	}

	private DlnaController activeControllerFor(DlnaRenderer renderer) {
		if(!(downloadService.getRemoteController() instanceof DlnaController)) {
			return null;
		}

		DlnaController controller = (DlnaController) downloadService.getRemoteController();
		return controller.getRenderer().equals(renderer) ? controller : null;
	}

	@Override
	public void onDiscoveryRequestChanged(androidx.mediarouter.media.MediaRouteDiscoveryRequest request) {
		if(request != null && request.isActiveScan()) {
			scan();
		}
	}

	@Override
	public MediaRouteProvider.RouteController onCreateRouteController(String routeId) {
		DlnaRenderer renderer;
		synchronized(this) {
			renderer = renderers.get(routeId);
		}
		return renderer == null ? null : new DlnaRouteController(renderer);
	}

	private class DlnaRouteController extends RouteController {
		private final DlnaRenderer renderer;

		DlnaRouteController(DlnaRenderer renderer) {
			this.renderer = renderer;
		}

		@Override
		public boolean onControlRequest(Intent intent, androidx.mediarouter.media.MediaRouter.ControlRequestCallback callback) {
			return intent.hasCategory(CATEGORY_DLNA_ROUTE);
		}

		@Override
		public void onSelect() {
			downloadService.setRemoteEnabled(RemoteControlState.DLNA,
					new DlnaController(downloadService, handler, renderer));
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
			DlnaController controller = activeControllerFor(renderer);
			if(controller != null) {
				controller.updateVolume(delta > 0);
			}
			broadcastDescriptor();
		}

		@Override
		public void onSetVolume(int volume) {
			DlnaController controller = activeControllerFor(renderer);
			if(controller != null) {
				controller.setVolume(volume);
			}
			broadcastDescriptor();
		}
	}
}
