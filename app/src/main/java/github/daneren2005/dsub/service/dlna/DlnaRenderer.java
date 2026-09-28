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

import java.io.Serializable;

/**
 * A UPnP MediaRenderer: a TV, receiver or speaker that will play a URL it is handed.
 *
 * Unlike Sonos, where every model answers on the same fixed paths, a renderer publishes
 * where its services live and each vendor puts them somewhere different. So the control
 * URLs are read out of the device description at discovery and carried around here rather
 * than being built from a base address.
 */
public class DlnaRenderer implements Serializable {
	private final String name;
	private final String udn;
	private final String avTransportUrl;
	private final String avTransportScpdUrl;
	private final String renderingControlUrl;

	public DlnaRenderer(String name, String udn, String avTransportUrl, String avTransportScpdUrl,
			String renderingControlUrl) {
		this.name = name;
		this.udn = udn;
		this.avTransportUrl = avTransportUrl;
		this.avTransportScpdUrl = avTransportScpdUrl;
		this.renderingControlUrl = renderingControlUrl;
	}

	public String getName() {
		return name;
	}

	public String getAvTransportUrl() {
		return avTransportUrl;
	}

	/**
	 * Where AVTransport lists the actions it implements. Half of them are optional in the
	 * spec, so this is the only way to find out what a given renderer will actually do
	 * short of trying it and reading the failure.
	 */
	public String getAvTransportScpdUrl() {
		return avTransportScpdUrl;
	}

	/** Null on a renderer with no RenderingControl service, which leaves volume to it. */
	public String getRenderingControlUrl() {
		return renderingControlUrl;
	}

	public boolean hasVolumeControl() {
		return renderingControlUrl != null;
	}

	/**
	 * Stable across restarts and IP changes, so a route the user picked can be found
	 * again after a rescan.
	 */
	public String getRouteId() {
		return "dlna:" + udn;
	}

	@Override
	public boolean equals(Object other) {
		if(this == other) {
			return true;
		}
		if(!(other instanceof DlnaRenderer)) {
			return false;
		}
		return udn.equals(((DlnaRenderer) other).udn);
	}

	@Override
	public int hashCode() {
		return udn.hashCode();
	}

	@Override
	public String toString() {
		return name + " (" + avTransportUrl + ")";
	}
}
