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

package github.daneren2005.dsub.service.sonos;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A Sonos zone group - what the Sonos app calls a room - addressed through its
 * coordinator.
 *
 * Grouped speakers are one target, not several: only the coordinator holds the queue and
 * accepts transport commands, and the others follow it. Sending Play to a member of a
 * group does nothing useful, so everything here is aimed at {@link #getBaseUrl()}. The
 * members are still worth keeping for volume, which falls back to setting each speaker
 * when the group-wide call is not available.
 */
public class SonosRoom implements Serializable {
	private final String name;
	private final String coordinatorUuid;
	private final String baseUrl;
	private final List<String> memberBaseUrls;

	public SonosRoom(String name, String coordinatorUuid, String baseUrl, List<String> memberBaseUrls) {
		this.name = name;
		this.coordinatorUuid = coordinatorUuid;
		this.baseUrl = baseUrl;
		this.memberBaseUrls = memberBaseUrls == null || memberBaseUrls.isEmpty()
				? new ArrayList<String>() : new ArrayList<String>(memberBaseUrls);
		if(this.memberBaseUrls.isEmpty()) {
			this.memberBaseUrls.add(baseUrl);
		}
	}

	public String getName() {
		return name;
	}

	public String getCoordinatorUuid() {
		return coordinatorUuid;
	}

	/** Origin of the coordinator, e.g. "http://192.168.1.2:1400". */
	public String getBaseUrl() {
		return baseUrl;
	}

	public List<String> getMemberBaseUrls() {
		return memberBaseUrls;
	}

	public String getControlUrl(String service) {
		return baseUrl + service;
	}

	/**
	 * Stable across restarts and IP changes, so a route the user picked can be found
	 * again. The coordinator can change when speakers are regrouped, but a group that
	 * has been regrouped is a different target anyway.
	 */
	public String getRouteId() {
		return "sonos:" + coordinatorUuid;
	}

	@Override
	public boolean equals(Object other) {
		if(this == other) {
			return true;
		}
		if(!(other instanceof SonosRoom)) {
			return false;
		}
		return coordinatorUuid.equals(((SonosRoom) other).coordinatorUuid);
	}

	@Override
	public int hashCode() {
		return coordinatorUuid.hashCode();
	}

	@Override
	public String toString() {
		return name + " (" + baseUrl + ")";
	}
}
