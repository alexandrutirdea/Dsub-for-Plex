/*
	This file is part of ServerProxy.
	SocketProxy is free software: you can redistribute it and/or modify
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

package github.daneren2005.serverproxy;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

public class WebProxy extends ServerProxy {
	private static String TAG = WebProxy.class.getSimpleName();
	private static List REMOVE_REQUEST_HEADERS = Arrays.asList("Host", "Accept-Encoding", "Referer");
	private static List REMOVE_RESPONSE_HEADERS = Arrays.asList("Transfer-Encoding");
	private SSLSocketFactory sslSocketFactory;
	private HostnameVerifier hostnameVerifier;

	public WebProxy(Context context) {
		super(context);
	}
	public WebProxy(Context context, SSLSocketFactory sslSocketFactory, HostnameVerifier hostnameVerifier) {
		super(context);
		this.sslSocketFactory = sslSocketFactory;
		this.hostnameVerifier = hostnameVerifier;
	}

	@Override
	ProxyTask getTask(Socket client) {
		return new StreamSiteTask(client);
	}

	/**
	 * What came of one relayed request.
	 *
	 * A relay that ends early is not a failure the client ever sees - it has been handed a
	 * Content-Length and simply gets fewer bytes than that - so what happened has to be
	 * reported back rather than only logged, for a caller that can do something about it.
	 */
	static class Relay {
		/** Body bytes written to the client; the header is not counted. */
		long written;
		/** False when the server's answer ended early, or never arrived at all. */
		boolean complete;
		/** What the server said the body would be, or -1 where it did not say. */
		long contentLength = -1;
		/**
		 * True when it was the client that broke off rather than the server - a renderer
		 * being stopped drops the fetch it was in the middle of, which is a short relay
		 * that nothing is wrong with and nobody needs telling about.
		 */
		boolean clientGone;
	}

	/**
	 * Relays one request to the server it names and writes the answer back to the client.
	 *
	 * Static, and taking everything it works on, so that it can be used away from a
	 * {@link StreamSiteTask} - {@link CacheAwareProxy} falls back on it for a song it does
	 * not have on disk, and there should not be a second copy of this.
	 *
	 * The stream is the caller's: it is neither closed nor flushed here, so that a caller
	 * which can carry on where a broken relay left off is able to write into the same
	 * response rather than having to start another one.
	 */
	static Relay relay(String url, Map<String, String> requestHeaders, OutputStream output,
			SSLSocketFactory sslSocketFactory, HostnameVerifier hostnameVerifier) {
		Relay result = new Relay();
		try {
			// Open new connection to destination and add existing headers
			HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			for(Map.Entry<String, String> header: requestHeaders.entrySet()) {
				if(!REMOVE_REQUEST_HEADERS.contains(header.getKey()) && !("Content-Length".equals(header.getKey()) && "0".equals(header.getValue()))  ) {
					connection.setRequestProperty(header.getKey(), header.getValue());
				}
			}
			if(connection instanceof HttpsURLConnection) {
				HttpsURLConnection sslConnection = (HttpsURLConnection) connection;

				if(sslSocketFactory != null) {
					sslConnection.setSSLSocketFactory(sslSocketFactory);
				}

				if(hostnameVerifier != null) {
					sslConnection.setHostnameVerifier(hostnameVerifier);
				}
			}

			if(connection.getResponseCode() == HttpURLConnection.HTTP_OK || connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL) {
				Map<String, String> responseHeaders = getHeaders(connection.getHeaderFields());
				result.contentLength = contentLengthOf(responseHeaders);

				InputStream input = null;
				try {
					byte[] header = getHeaderString(connection.getResponseCode(), responseHeaders).getBytes();
					writeToClient(result, output, header, header.length);

					input = connection.getInputStream();
					byte[] buffer = new byte[1024 * 32];
					int n = 0;
					while (-1 != (n = input.read(buffer))) {
						writeToClient(result, output, buffer, n);
						result.written += n;
					}
					flushToClient(result, output);
					result.complete = true;
				} finally {
					try {
						if(input != null) {
							input.close();
						}
					} catch(Exception e) {
						Log.w(TAG, "Error closing input stream");
					}
				}
			} else {
				connection.disconnect();
				throw new IOException(connection.getResponseMessage());
			}
		} catch (IOException e) {
			Log.e(TAG, "Failed to get data from url: " + url, e);
		} catch(Exception e) {
			Log.e(TAG, "Exception thrown from web proxy task", e);
		}
		return result;
	}

	/**
	 * Writes to the client, noting that it was the client that failed.
	 *
	 * Which end gave way is the whole of the difference between a stream that was cut off
	 * and a renderer that was switched off, and the exception alone does not say.
	 */
	private static void writeToClient(Relay result, OutputStream output, byte[] buffer, int count)
			throws IOException {
		try {
			output.write(buffer, 0, count);
		} catch(IOException e) {
			result.clientGone = true;
			throw e;
		}
	}

	private static void flushToClient(Relay result, OutputStream output) throws IOException {
		try {
			output.flush();
		} catch(IOException e) {
			result.clientGone = true;
			throw e;
		}
	}

	/** The length the server promised, or -1 where it promised nothing. */
	private static long contentLengthOf(Map<String, String> responseHeaders) {
		for(Map.Entry<String, String> header: responseHeaders.entrySet()) {
			if("Content-Length".equalsIgnoreCase(header.getKey())) {
				try {
					return Long.parseLong(header.getValue().trim());
				} catch(NumberFormatException e) {
					return -1;
				}
			}
		}
		return -1;
	}

	static Map<String, String> getHeaders(Map<String, List<String>> rawHeaders) {
		Map<String, String> headers = new HashMap<>();
		for(Map.Entry<String, List<String>> entry: rawHeaders.entrySet()) {
			String name = entry.getKey();
			List<String> values = entry.getValue();
			String value;
			if(values.isEmpty()) {
				value = "";
			} else {
				value = values.get(0);
			}

			if(!"Server".equals(name)) {
				headers.put(name, value);
			}
		}

		return headers;
	}

	private static String getHeaderString(int response, Map<String, String> headers) {
		StringBuilder sb = new StringBuilder();

		sb.append("HTTP/1.0 ");
		sb.append(response);
		sb.append(" OK\r\n");

		boolean addContentType = true;
		for(Map.Entry<String, String> header: headers.entrySet()) {
			if(REMOVE_RESPONSE_HEADERS.contains(header.getKey())) {
				continue;
			}

			sb.append(header.getKey());
			sb.append(": ");

			// Make sure that connection is close, not keep-alive
			if("Connection".equals(header.getKey())) {
				sb.append("close");
			} else {
				sb.append(header.getValue());
			}

			if("Content-Type".equals(header.getKey())) {
				addContentType = false;
			}

			sb.append("\r\n");
		}
		if(addContentType) {
			sb.append("Content-Type: application/octet-stream\r\n");
		}
		sb.append("\r\n");

		return sb.toString();
	}

	protected class StreamSiteTask extends ProxyTask {
		public StreamSiteTask(Socket client) {
			super(client);
		}

		@Override
		public void run() {
			OutputStream output = null;
			try {
				output = new BufferedOutputStream(client.getOutputStream(), 64*1024);
				relay(path, requestHeaders, output, sslSocketFactory, hostnameVerifier);
			} catch(Exception e) {
				Log.e(TAG, "Exception thrown from web proxy task", e);
			} finally {
				closeQuietly(output);
				closeQuietly(client);
			}
		}
	}

	static void closeQuietly(Closeable closeable) {
		try {
			if(closeable != null) {
				closeable.close();
			}
		} catch(Exception e) {
			Log.w(TAG, "Error closing " + closeable);
		}
	}
}
