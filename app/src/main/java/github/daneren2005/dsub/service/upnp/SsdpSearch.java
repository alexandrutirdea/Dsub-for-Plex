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

package github.daneren2005.dsub.service.upnp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The SSDP half of UPnP discovery: asks the network who is out there and collects the
 * addresses of their device descriptions.
 *
 * Shared because Sonos and plain DLNA renderers are found the same way and differ only in
 * what they are asked for. What comes back is a set of description URLs; making sense of
 * those is each caller's business, since they want different things out of them.
 */
public final class SsdpSearch {
	private static final String TAG = SsdpSearch.class.getSimpleName();
	private static final String SSDP_ADDRESS = "239.255.255.250";
	private static final int SSDP_PORT = 1900;
	/** UDP, so a lost search is simply lost; asking three times covers the usual drop. */
	private static final int SEARCH_ATTEMPTS = 3;

	private SsdpSearch() {}

	/**
	 * Blocks for up to roughly {@code timeoutMillis} while devices answer, so this must
	 * not be called from the main thread.
	 *
	 * @param searchTarget the SSDP ST, e.g. {@code urn:schemas-upnp-org:device:MediaRenderer:1}
	 */
	public static Set<String> locations(Context context, String searchTarget, int timeoutMillis) {
		Set<String> locations = new LinkedHashSet<String>();
		String search = "M-SEARCH * HTTP/1.1\r\n"
				+ "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
				+ "MAN: \"ssdp:discover\"\r\n"
				+ "MX: 2\r\n"
				+ "ST: " + searchTarget + "\r\n\r\n";

		WifiManager.MulticastLock lock = acquireMulticastLock(context);
		DatagramSocket socket = null;
		try {
			byte[] payload = search.getBytes("UTF-8");
			InetAddress target = InetAddress.getByName(SSDP_ADDRESS);

			socket = new DatagramSocket();
			socket.setBroadcast(true);
			socket.setSoTimeout(timeoutMillis);

			for(int i = 0; i < SEARCH_ATTEMPTS; i++) {
				socket.send(new DatagramPacket(payload, payload.length, target, SSDP_PORT));
			}

			long deadline = System.currentTimeMillis() + timeoutMillis;
			while(System.currentTimeMillis() < deadline) {
				int remaining = (int) Math.max(deadline - System.currentTimeMillis(), 250);
				socket.setSoTimeout(remaining);

				byte[] buffer = new byte[8192];
				DatagramPacket response = new DatagramPacket(buffer, buffer.length);
				try {
					socket.receive(response);
				} catch(SocketTimeoutException x) {
					break;
				}

				String location = headerValue(new String(response.getData(), 0, response.getLength(), "UTF-8"), "LOCATION");
				if(location != null) {
					locations.add(location);
				}
			}
		} catch(Exception x) {
			Log.w(TAG, "SSDP search for " + searchTarget + " failed: " + x);
		} finally {
			if(socket != null) {
				socket.close();
			}
			releaseMulticastLock(lock);
		}

		return locations;
	}

	/**
	 * SSDP replies are multicast, which Android drops unless a multicast lock is held.
	 * Without this discovery quietly finds nothing on most devices.
	 */
	private static WifiManager.MulticastLock acquireMulticastLock(Context context) {
		try {
			WifiManager wifiManager = (WifiManager) context.getApplicationContext()
					.getSystemService(Context.WIFI_SERVICE);
			if(wifiManager == null) {
				return null;
			}

			WifiManager.MulticastLock lock = wifiManager.createMulticastLock("dsub-upnp-discovery");
			lock.setReferenceCounted(false);
			lock.acquire();
			return lock;
		} catch(Exception x) {
			Log.w(TAG, "Could not take a multicast lock: " + x);
			return null;
		}
	}

	private static void releaseMulticastLock(WifiManager.MulticastLock lock) {
		if(lock != null && lock.isHeld()) {
			try {
				lock.release();
			} catch(Exception x) {
				Log.w(TAG, "Could not release the multicast lock: " + x);
			}
		}
	}

	/** The value of an SSDP response header, or null when it is absent or empty. */
	public static String headerValue(String payload, String header) {
		for(String line : payload.split("\r\n")) {
			if(line.regionMatches(true, 0, header + ":", 0, header.length() + 1)) {
				String value = line.substring(header.length() + 1).trim();
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}
}
