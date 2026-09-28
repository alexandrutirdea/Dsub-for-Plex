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
 * Finds Sonos rooms on the local network over SSDP.
 *
 * Two steps, because a speaker is not a room. SSDP answers with every ZonePlayer
 * individually; the ZoneGroupTopology service then says which of them are grouped and
 * which speaker coordinates each group. Only coordinators can be driven, so grouped
 * speakers have to collapse into one entry or the user is offered targets that ignore
 * every command sent to them.
 *
 * Ported from the Sonos handling in MusicBridge, which this app is a companion to.
 */
public class SonosDiscovery {
	private static final String TAG = SonosDiscovery.class.getSimpleName();
	private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1";

	private final Context context;

	public SonosDiscovery(Context context) {
		this.context = context.getApplicationContext();
	}

	/**
	 * Blocks for up to roughly {@code timeoutMillis} while speakers answer, so this must
	 * not be called from the main thread.
	 */
	public List<SonosRoom> discoverRooms(int timeoutMillis) {
		Map<String, Speaker> speakers = new LinkedHashMap<String, Speaker>();
		for(String location : SsdpSearch.locations(context, SEARCH_TARGET, timeoutMillis)) {
			Speaker speaker = describe(location);
			if(speaker != null) {
				speakers.put(speaker.uuid, speaker);
			}
		}

		if(speakers.isEmpty()) {
			return new ArrayList<SonosRoom>();
		}

		// Any speaker can answer for the whole household, so the first that manages
		// it settles the topology; the rest would only repeat the same answer.
		for(Speaker speaker : speakers.values()) {
			try {
				List<SonosRoom> rooms = readTopology(speaker.baseUrl, speakers);
				if(!rooms.isEmpty()) {
					return rooms;
				}
			} catch(Exception x) {
				Log.w(TAG, "Could not read zone topology from " + speaker.baseUrl + ": " + x);
			}
		}

		// No topology anywhere: fall back on treating each speaker as its own room.
		// Wrong for grouped speakers, but better than offering nothing at all.
		List<SonosRoom> rooms = new ArrayList<SonosRoom>();
		for(Speaker speaker : speakers.values()) {
			rooms.add(new SonosRoom(speaker.zoneName, speaker.uuid, speaker.baseUrl, null));
		}
		return rooms;
	}

	private Speaker describe(String location) {
		try {
			Element root = UpnpSoap.parseXml(UpnpSoap.get(location));
			String uuid = UpnpSoap.normalizeUuid(UpnpSoap.textOf(root, "UDN"));
			String zoneName = UpnpSoap.textOf(root, "roomName");
			if(zoneName == null) {
				zoneName = UpnpSoap.textOf(root, "friendlyName");
			}
			if(uuid.isEmpty() || zoneName == null) {
				return null;
			}

			return new Speaker(uuid, zoneName, UpnpSoap.originOf(location));
		} catch(Exception x) {
			Log.w(TAG, "Could not describe the speaker at " + location + ": " + x);
			return null;
		}
	}

	private List<SonosRoom> readTopology(String baseUrl, Map<String, Speaker> speakers) throws Exception {
		String response = UpnpSoap.request(baseUrl + "/ZoneGroupTopology/Control",
				UpnpSoap.SERVICE_TOPOLOGY, "GetZoneGroupState", "");

		// The state arrives as an escaped XML document inside the response, so it has to
		// be parsed a second time before the groups are reachable.
		String state = UpnpSoap.textOf(UpnpSoap.parseXml(response), "ZoneGroupState");
		if(state == null) {
			return new ArrayList<SonosRoom>();
		}

		List<SonosRoom> rooms = new ArrayList<SonosRoom>();
		NodeList groups = UpnpSoap.parseXml(state).getElementsByTagName("ZoneGroup");
		for(int i = 0; i < groups.getLength(); i++) {
			if(!(groups.item(i) instanceof Element)) {
				continue;
			}

			SonosRoom room = readGroup((Element) groups.item(i), speakers);
			if(room != null) {
				rooms.add(room);
			}
		}
		return rooms;
	}

	private SonosRoom readGroup(Element group, Map<String, Speaker> speakers) {
		String coordinatorUuid = UpnpSoap.normalizeUuid(group.getAttribute("Coordinator"));
		if(coordinatorUuid.isEmpty()) {
			return null;
		}

		String name = null;
		String coordinatorBaseUrl = null;
		List<String> memberBaseUrls = new ArrayList<String>();

		NodeList members = group.getElementsByTagName("ZoneGroupMember");
		for(int i = 0; i < members.getLength(); i++) {
			if(!(members.item(i) instanceof Element)) {
				continue;
			}

			Element member = (Element) members.item(i);
			// A paired speaker - the right half of a stereo pair, or a sub - is marked
			// invisible and is not separately addressable.
			if("1".equals(member.getAttribute("Invisible"))) {
				continue;
			}

			String uuid = UpnpSoap.normalizeUuid(member.getAttribute("UUID"));
			Speaker known = speakers.get(uuid);
			String memberBaseUrl = known != null
					? known.baseUrl : UpnpSoap.originOf(member.getAttribute("Location"));
			if(memberBaseUrl.isEmpty()) {
				continue;
			}

			if(!memberBaseUrls.contains(memberBaseUrl)) {
				memberBaseUrls.add(memberBaseUrl);
			}
			if(uuid.equals(coordinatorUuid)) {
				coordinatorBaseUrl = memberBaseUrl;
				name = member.getAttribute("ZoneName");
				if(name == null || name.trim().isEmpty()) {
					name = known != null ? known.zoneName : null;
				}
			}
		}

		if(coordinatorBaseUrl == null || name == null || name.trim().isEmpty()) {
			return null;
		}

		// A group takes the coordinator's name plus a count, the way the Sonos app shows
		// it, so two speakers playing together do not read as just one of them.
		String label = memberBaseUrls.size() > 1
				? name.trim() + " + " + (memberBaseUrls.size() - 1) : name.trim();
		return new SonosRoom(label, coordinatorUuid, coordinatorBaseUrl, memberBaseUrls);
	}

	private static class Speaker {
		final String uuid;
		final String zoneName;
		final String baseUrl;

		Speaker(String uuid, String zoneName, String baseUrl) {
			this.uuid = uuid;
			this.zoneName = zoneName;
			this.baseUrl = baseUrl;
		}
	}
}
