/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2026 (C) Scott Jackson
*/
package github.daneren2005.dsub.util;

import android.content.Context;
import android.util.Log;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Turning certificate checking off for one server that the user has said to trust anyway.
 *
 * A home server very often has a certificate nothing will accept - self-signed, or issued
 * for a name that is not the address it is reached at. DSub used to deal with that by
 * accepting <em>every</em> certificate from <em>every</em> server, unconditionally: an
 * empty {@link X509TrustManager} and a {@link HostnameVerifier} that returned true, applied
 * to every HTTPS connection the app made. Since the server password goes over that
 * connection as HTTP Basic auth and a Plex token goes as a header, that handed both to
 * anyone in a position to answer for the server on a shared network.
 *
 * So it is off unless a particular server is marked
 * {@link Constants#PREFERENCES_KEY_SERVER_ALLOW_SELF_SIGNED}, and the two ways to reach a
 * server safely - a certificate from a real CA, or the server's own certificate installed
 * on the phone, which {@code network_security_config.xml} makes DSub honour - both work
 * without it.
 */
public final class SelfSignedTls {
	private static final String TAG = SelfSignedTls.class.getSimpleName();

	private static SSLSocketFactory socketFactory;
	private static final HostnameVerifier ACCEPT_ANY_HOSTNAME = new HostnameVerifier() {
		@Override
		public boolean verify(String hostname, SSLSession session) {
			return true;
		}
	};

	private SelfSignedTls() {}

	/** Whether this server has been marked as one whose certificate not to check. */
	public static boolean isAllowedFor(Context context, int instance) {
		return Util.getPreferences(context)
				.getBoolean(Constants.PREFERENCES_KEY_SERVER_ALLOW_SELF_SIGNED + instance, false);
	}

	/**
	 * The socket factory that accepts anything, or null when this server is not marked -
	 * in which case the caller should leave the connection alone and let the platform
	 * check the certificate as usual.
	 */
	public static SSLSocketFactory socketFactoryFor(Context context, int instance) {
		return isAllowedFor(context, instance) ? acceptAnyFactory() : null;
	}

	/** The matching hostname verifier, or null on the same terms as {@link #socketFactoryFor}. */
	public static HostnameVerifier hostnameVerifierFor(Context context, int instance) {
		return isAllowedFor(context, instance) ? ACCEPT_ANY_HOSTNAME : null;
	}

	/**
	 * Applies both to a connection when its server is marked, and otherwise does nothing
	 * at all - which leaves the platform's own checking in place.
	 */
	public static void applyTo(Object connection, Context context, int instance) {
		if(!(connection instanceof HttpsURLConnection) || !isAllowedFor(context, instance)) {
			return;
		}

		SSLSocketFactory factory = acceptAnyFactory();
		if(factory == null) {
			return;
		}

		HttpsURLConnection ssl = (HttpsURLConnection) connection;
		ssl.setSSLSocketFactory(factory);
		ssl.setHostnameVerifier(ACCEPT_ANY_HOSTNAME);
	}

	private static synchronized SSLSocketFactory acceptAnyFactory() {
		if(socketFactory != null) {
			return socketFactory;
		}

		try {
			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[]{ new X509TrustManager() {
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}

				public void checkClientTrusted(X509Certificate[] certs, String authType) {
				}

				public void checkServerTrusted(X509Certificate[] certs, String authType) {
				}
			}}, new SecureRandom());
			socketFactory = sslContext.getSocketFactory();
		} catch(Exception e) {
			Log.w(TAG, "Could not build a socket factory that skips certificate checking", e);
		}

		return socketFactory;
	}
}
