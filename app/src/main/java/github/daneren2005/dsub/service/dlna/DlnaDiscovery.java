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

package github.daneren2005.dsub.service.dlna;

import android.content.Context;
import android.util.Log;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import github.daneren2005.dsub.service.upnp.SsdpSearch;
import github.daneren2005.dsub.service.upnp.UpnpSoap;

/**
 * Finds UPnP MediaRenderers on the local network.
 *
 * One step, unlike Sonos: a renderer is its own target, so the SSDP answers only have to
 * be described and kept. What the description is read for is the AVTransport control URL,
 * without which a renderer cannot be driven at all, and the RenderingControl one, without
 * which it can be driven but not turned down.
 */
public class DlnaDiscovery {
	private static final String TAG = DlnaDiscovery.class.getSimpleName();
	private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
	private static final String SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
	private static final String SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

	private final Context context;

	public DlnaDiscovery(Context context) {
		this.context = context.getApplicationContext();
	}

	/**
	 * Blocks for up to roughly {@code timeoutMillis} while renderers answer, so this must
	 * not be called from the main thread.
	 */
	public List<DlnaRenderer> discoverRenderers(int timeoutMillis) {
		Map<String, DlnaRenderer> renderers = new LinkedHashMap<String, DlnaRenderer>();
		for(String location : SsdpSearch.locations(context, SEARCH_TARGET, timeoutMillis)) {
			DlnaRenderer renderer = describe(location);
			if(renderer != null) {
				// Keyed by route id so a device answering the search several times - which
				// they do, once per address they hold - lands as one entry.
				renderers.put(renderer.getRouteId(), renderer);
			}
		}
		return new ArrayList<DlnaRenderer>(renderers.values());
	}

	private DlnaRenderer describe(String location) {
		try {
			String body = UpnpSoap.get(location);
			Element root = UpnpSoap.parseXml(body);

			// A device may declare its own base for relative URLs; when it does not, they
			// are relative to where the description itself was fetched from.
			String declaredBase = UpnpSoap.textOf(root, "URLBase");
			String base = (declaredBase == null) ? location : declaredBase;

			String udn = UpnpSoap.normalizeUuid(UpnpSoap.textOf(root, "UDN"));
			String name = UpnpSoap.textOf(root, "friendlyName");
			if(udn.isEmpty() || name == null) {
				return null;
			}

			// Sonos speakers are MediaRenderers too and answer this search. They have a
			// controller of their own that plays them gaplessly, so listing them here
			// again would offer the same speaker twice, the second time worse.
			if(isSonos(root)) {
				return null;
			}

			Element avTransport = serviceElement(root, SERVICE_AV_TRANSPORT);
			if(avTransport == null) {
				Log.w(TAG, "Ignoring " + name + ": it publishes no AVTransport service.");
				return null;
			}

			String avTransportUrl = UpnpSoap.resolveUrl(base, UpnpSoap.textOf(avTransport, "controlURL"));
			if(avTransportUrl == null) {
				Log.w(TAG, "Ignoring " + name + ": its AVTransport service has no control URL.");
				return null;
			}

			Element renderingControl = serviceElement(root, SERVICE_RENDERING_CONTROL);
			return new DlnaRenderer(name.trim(), udn, avTransportUrl,
					UpnpSoap.resolveUrl(base, UpnpSoap.textOf(avTransport, "SCPDURL")),
					renderingControl == null ? null
							: UpnpSoap.resolveUrl(base, UpnpSoap.textOf(renderingControl, "controlURL")));
		} catch(Exception x) {
			Log.w(TAG, "Could not describe the renderer at " + location + ": " + x);
			return null;
		}
	}

	/**
	 * A service's entry anywhere in the description.
	 *
	 * The whole document is searched rather than one device's own service list, because a
	 * MediaRenderer is often an embedded device inside some larger root device and its
	 * services then sit a level or two down.
	 */
	private static Element serviceElement(Element root, String serviceType) {
		NodeList services = root.getElementsByTagName("service");
		for(int i = 0; i < services.getLength(); i++) {
			if(!(services.item(i) instanceof Element)) {
				continue;
			}

			Element service = (Element) services.item(i);
			String type = UpnpSoap.textOf(service, "serviceType");
			if(type != null && type.trim().equalsIgnoreCase(serviceType)) {
				return service;
			}
		}
		return null;
	}

	private static boolean isSonos(Element root) {
		String manufacturer = UpnpSoap.textOf(root, "manufacturer");
		return manufacturer != null && manufacturer.toLowerCase().contains("sonos");
	}
}
