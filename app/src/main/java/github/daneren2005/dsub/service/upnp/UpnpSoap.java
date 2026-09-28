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

import android.util.Log;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The bare minimum of UPnP needed to drive a Sonos speaker: a SOAP call and enough XML
 * handling to read the replies.
 *
 * Sonos speaks plain UPnP over HTTP on port 1400, so nothing here needs a UPnP stack -
 * the actions used are few and their arguments are simple enough to build as strings.
 * Namespace awareness is deliberately off: Sonos nests an escaped XML document inside
 * {@code ZoneGroupState}, and reading both layers the same way is far simpler than
 * carrying namespaces through a re-parse.
 */
public final class UpnpSoap {
	private static final String TAG = UpnpSoap.class.getSimpleName();
	private static final int CONNECT_TIMEOUT = 5000;
	private static final int READ_TIMEOUT = 10000;

	public static final String SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
	public static final String SERVICE_RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1";
	public static final String SERVICE_GROUP_RENDERING = "urn:schemas-upnp-org:service:GroupRenderingControl:1";
	public static final String SERVICE_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";

	private UpnpSoap() {}

	/**
	 * Posts a SOAP action and hands back the response body.
	 *
	 * A SOAP fault comes back as HTTP 500 with a body explaining itself, so the body is
	 * read from the error stream and put in the exception message - a bare "HTTP 500"
	 * says nothing about which argument the speaker objected to.
	 */
	public static String request(String controlUrl, String serviceType, String action, String innerXml) throws IOException {
		String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
				+ "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
				+ " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
				+ "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
				+ innerXml
				+ "</u:" + action + "></s:Body></s:Envelope>";
		byte[] payload = body.getBytes("UTF-8");

		HttpURLConnection connection = (HttpURLConnection) new URL(controlUrl).openConnection();
		try {
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT);
			connection.setReadTimeout(READ_TIMEOUT);
			connection.setDoOutput(true);
			connection.setDoInput(true);
			connection.setFixedLengthStreamingMode(payload.length);
			connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
			connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
			connection.setRequestProperty("Connection", "close");

			OutputStream output = connection.getOutputStream();
			try {
				output.write(payload);
				output.flush();
			} finally {
				output.close();
			}

			int status = connection.getResponseCode();
			InputStream stream = (status >= 200 && status < 300)
					? connection.getInputStream() : connection.getErrorStream();
			String response = readFully(stream);
			if(status < 200 || status >= 300) {
				throw new UpnpException("Sonos " + action + " failed with HTTP " + status + ": " + trim(response),
						errorCodeOf(response));
			}
			return response;
		} finally {
			connection.disconnect();
		}
	}

	/**
	 * A fault the speaker answered with, carrying the UPnP error code where it gave one.
	 *
	 * The HTTP status is 500 for every one of them, so the code inside the body is the only
	 * thing that says whether something actually went wrong or the speaker was merely asked
	 * for something it was not in a position to do.
	 */
	public static class UpnpException extends IOException {
		private final int errorCode;

		public UpnpException(String message, int errorCode) {
			super(message);
			this.errorCode = errorCode;
		}

		/** The UPnP errorCode, or 0 where the fault carried none. */
		public int getErrorCode() {
			return errorCode;
		}
	}

	/** The transport was asked for something its current state does not allow. */
	public static final int ERROR_TRANSITION_NOT_AVAILABLE = 701;

	private static final Pattern ERROR_CODE = Pattern.compile("<errorCode>\\s*(\\d+)\\s*</errorCode>");

	private static int errorCodeOf(String response) {
		if(response == null) {
			return 0;
		}

		Matcher matcher = ERROR_CODE.matcher(response);
		if(!matcher.find()) {
			return 0;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch(NumberFormatException x) {
			return 0;
		}
	}

	public static String get(String url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT);
			connection.setReadTimeout(READ_TIMEOUT);
			connection.setRequestProperty("Accept", "application/xml,text/xml");

			int status = connection.getResponseCode();
			if(status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " from " + url);
			}
			return readFully(connection.getInputStream());
		} finally {
			connection.disconnect();
		}
	}

	private static String readFully(InputStream stream) throws IOException {
		if(stream == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		byte[] buffer = new byte[8192];
		try {
			int read;
			while((read = stream.read(buffer)) != -1) {
				builder.append(new String(buffer, 0, read, "UTF-8"));
			}
		} finally {
			stream.close();
		}
		return builder.toString();
	}

	private static String trim(String value) {
		return value.length() > 300 ? value.substring(0, 300) : value;
	}

	public static Element parseXml(String body) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			return factory.newDocumentBuilder()
					.parse(new ByteArrayInputStream(body.getBytes("UTF-8")))
					.getDocumentElement();
		} catch(Exception x) {
			throw new IOException("Could not parse Sonos response", x);
		}
	}

	/** Text of the first element with this tag name, or null when it is absent or empty. */
	public static String textOf(Element root, String tagName) {
		NodeList nodes = root.getElementsByTagName(tagName);
		if(nodes.getLength() == 0) {
			return null;
		}

		Node node = nodes.item(0);
		if(node == null || node.getTextContent() == null) {
			return null;
		}

		String text = node.getTextContent().trim();
		return text.isEmpty() ? null : text;
	}

	public static String escape(String value) {
		if(value == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(value.length());
		for(int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			switch(character) {
				case '&': builder.append("&amp;"); break;
				case '<': builder.append("&lt;"); break;
				case '>': builder.append("&gt;"); break;
				case '"': builder.append("&quot;"); break;
				case '\'': builder.append("&apos;"); break;
				default: builder.append(character);
			}
		}
		return builder.toString();
	}

	/** "http://192.168.1.2:1400/xml/device_description.xml" to "http://192.168.1.2:1400". */
	public static String originOf(String url) {
		if(url == null || url.isEmpty()) {
			return "";
		}

		try {
			URI uri = new URI(url);
			if(uri.getScheme() == null || uri.getHost() == null) {
				return "";
			}
			return uri.getPort() >= 0
					? uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort()
					: uri.getScheme() + "://" + uri.getHost();
		} catch(Exception x) {
			Log.w(TAG, "Could not read origin of " + url);
			return "";
		}
	}

	/**
	 * Absolute form of a URL taken out of a device description.
	 *
	 * Renderers write control URLs every way the spec allows - absolute, rooted at the
	 * host, or relative to the description itself - so none of the three can be assumed.
	 * {@code base} is the description's own location, or the {@code URLBase} it declares.
	 */
	public static String resolveUrl(String base, String url) {
		if(url == null || url.trim().isEmpty()) {
			return null;
		}

		String trimmed = url.trim();
		if(trimmed.regionMatches(true, 0, "http://", 0, 7) || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
			return trimmed;
		}

		try {
			return new URI(base).resolve(trimmed).toString();
		} catch(Exception x) {
			Log.w(TAG, "Could not resolve " + url + " against " + base);
			return null;
		}
	}

	/** Strips the "uuid:" prefix the device description carries but the topology does not. */
	public static String normalizeUuid(String raw) {
		if(raw == null) {
			return "";
		}

		String uuid = raw.trim();
		int prefix = uuid.indexOf("uuid:");
		if(prefix != -1) {
			uuid = uuid.substring(prefix + "uuid:".length());
		}

		int separator = uuid.indexOf(':');
		if(separator != -1) {
			uuid = uuid.substring(0, separator);
		}
		return uuid.trim();
	}

	/** Seconds to the "H:MM:SS" that AVTransport wants for a Seek target. */
	public static String formatDuration(int seconds) {
		int safe = Math.max(seconds, 0);
		return String.format("%02d:%02d:%02d", safe / 3600, (safe % 3600) / 60, safe % 60);
	}

	/** The same shape coming back, or -1 when the speaker reports a placeholder. */
	public static int parseDuration(String value) {
		if(value == null) {
			return -1;
		}

		String[] parts = value.trim().split(":");
		if(parts.length != 3) {
			return -1;
		}

		try {
			return Integer.parseInt(parts[0]) * 3600
					+ Integer.parseInt(parts[1]) * 60
					+ Integer.parseInt(parts[2]);
		} catch(NumberFormatException x) {
			return -1;
		}
	}
}
