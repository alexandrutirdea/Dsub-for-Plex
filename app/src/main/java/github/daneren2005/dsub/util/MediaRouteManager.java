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
	Copyright 2014 (C) Scott Jackson
*/

package github.daneren2005.dsub.util;

import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import java.util.ArrayList;
import java.util.List;

import github.daneren2005.dsub.domain.RemoteControlState;
import github.daneren2005.dsub.provider.DlnaRouteProvider;
import github.daneren2005.dsub.provider.JukeboxRouteProvider;
import github.daneren2005.dsub.provider.SonosRouteProvider;
import github.daneren2005.dsub.service.DownloadService;
import github.daneren2005.dsub.service.RemoteController;
import github.daneren2005.dsub.util.compat.GoogleCompat;

import static androidx.mediarouter.media.MediaRouter.RouteInfo;

/**
 * Created by owner on 2/8/14.
 */
public class MediaRouteManager extends MediaRouter.Callback {
	private static final String TAG = MediaRouteManager.class.getSimpleName();
	private static boolean castAvailable = false;

	private DownloadService downloadService;
	private MediaRouter router;
	private MediaRouteSelector selector;
	private List<MediaRouteProvider> providers = new ArrayList<MediaRouteProvider>();
	private List<MediaRouteProvider> onlineProviders = new ArrayList<MediaRouteProvider>();
	private SonosRouteProvider sonosProvider;
	private DlnaRouteProvider dlnaProvider;

	public MediaRouteManager(DownloadService downloadService) {
		this.downloadService = downloadService;
		router = MediaRouter.getInstance(downloadService);
		castAvailable = GoogleCompat.playServicesAvailable(downloadService) && GoogleCompat.castAvailable();

		addProviders();
		buildSelector();
	}

	public void destroy() {
		for(MediaRouteProvider provider: providers) {
			router.removeProvider(provider);
		}
	}

	@Override
	public void onRouteSelected(MediaRouter router, RouteInfo info) {
		if(castAvailable) {
			RemoteController controller = GoogleCompat.getController(downloadService, info);
			if(controller != null) {
				downloadService.setRemoteEnabled(RemoteControlState.CHROMECAST, controller);
			}
		}

		if(downloadService.isRemoteEnabled()) {
			downloadService.registerRoute(router);
		}
	}
	@Override
	public void onRouteUnselected(MediaRouter router, RouteInfo info) {
		if(downloadService.isRemoteEnabled()) {
			downloadService.unregisterRoute(router);
		}

		downloadService.setRemoteEnabled(RemoteControlState.LOCAL);
	}

	public void setDefaultRoute() {
		router.selectRoute(router.getDefaultRoute());
	}

	public void startScan() {
		router.addCallback(selector, this, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
		if(sonosProvider != null) {
			sonosProvider.scan();
		}
		if(dlnaProvider != null) {
			dlnaProvider.scan();
		}
	}
	public void stopScan() {
		router.removeCallback(this);
	}

	public MediaRouteSelector getSelector() {
		return selector;
	}

	public RouteInfo getSelectedRoute() {
		return router.getSelectedRoute();
	}
	public RouteInfo getRouteForId(String id) {
		if(id == null) {
			return null;
		}

		// Try to find matching id
		for(RouteInfo info: router.getRoutes()) {
			if(id.equals(info.getId())) {
				router.selectRoute(info);
				return info;
			}
		}

		return null;
	}
	public RemoteController getRemoteController(RouteInfo info) {
		if(castAvailable) {
			return GoogleCompat.getController(downloadService, info);
		} else {
			return null;
		}
	}

	public void addOnlineProviders() {
		// Plex has no jukebox mode, so don't advertise a route for it.
		if(Util.isPlex(downloadService)) {
			return;
		}

		JukeboxRouteProvider jukeboxProvider = new JukeboxRouteProvider(downloadService);
		router.addProvider(jukeboxProvider);
		providers.add(jukeboxProvider);
		onlineProviders.add(jukeboxProvider);
	}
	public void removeOnlineProviders() {
		for(MediaRouteProvider provider: onlineProviders) {
			router.removeProvider(provider);
		}
	}

	private void addProviders() {
		// Neither of these is an "online" provider: the devices are on the local network
		// and can play cached songs through the file proxy with no server reachable at all.
		sonosProvider = new SonosRouteProvider(downloadService);
		router.addProvider(sonosProvider);
		providers.add(sonosProvider);

		dlnaProvider = new DlnaRouteProvider(downloadService);
		router.addProvider(dlnaProvider);
		providers.add(dlnaProvider);

		if(!Util.isOffline(downloadService)) {
			addOnlineProviders();
		}
	}
	public void buildSelector() {
		MediaRouteSelector.Builder builder = new MediaRouteSelector.Builder();
		if(UserUtil.canJukebox() && !Util.isPlex(downloadService)) {
			builder.addControlCategory(JukeboxRouteProvider.CATEGORY_JUKEBOX_ROUTE);
		}
		if(castAvailable) {
			builder.addControlCategory(GoogleCompat.getCastControlCategory());
		}
		builder.addControlCategory(SonosRouteProvider.CATEGORY_SONOS_ROUTE);
		builder.addControlCategory(DlnaRouteProvider.CATEGORY_DLNA_ROUTE);
		selector = builder.build();
	}

}
