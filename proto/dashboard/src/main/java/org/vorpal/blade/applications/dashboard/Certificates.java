package org.vorpal.blade.applications.dashboard;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.vorpal.blade.framework.v3.events.Problems;

/// The certificate every TLS listener in the domain presents, read the way a
/// client reads it: the dashboard connects to each secure channel's address
/// (HTTPS, SIP-TLS) and takes the certificate from the handshake. No keystore
/// is opened and no password is needed, and what is checked is what callers
/// get, whatever the keystore holds.
///
/// Read once an hour from the minute sampling ([AppSampling]), so a
/// certificate is watched whether or not anyone has the board open. Inside
/// `warningDays` of expiry it is raised as a problem under Operations ›
/// Issues, and cleared once a renewed one is seen.
///
/// **The handshake trusts anything.** It only reads the certificate, sends
/// nothing, and closes: an expired or self-signed certificate is exactly what
/// it must be able to see.
final class Certificates {

	private static final Logger logger = Logger.getLogger(Certificates.class.getName());

	static final long EVERY_MS = 3_600_000L;
	static final int TIMEOUT_MS = 5_000;

	/// The latest reading, one entry per listener.
	static volatile List<Map<String, Object>> latest = Collections.emptyList();
	static volatile long readAt;
	/// Problem keys this dashboard raised and has not cleared.
	private static final Set<String> RAISED = ConcurrentHashMap.newKeySet();

	private Certificates() {
	}

	/// Read every listener again if the last reading is an hour old.
	static void refresh(MBeanServer dr, int warningDays, long now) {
		if (now - readAt < EVERY_MS) {
			return;
		}
		readAt = now;
		List<Map<String, Object>> found = new ArrayList<>();
		for (Map.Entry<String, String[]> l : listeners(dr).entrySet()) {
			found.add(read(l.getValue()[0], l.getValue()[1], l.getKey(), warningDays, now));
		}
		latest = found;
	}

	/// Secure listeners by `host:port`: {server, protocol}. HTTPS, T3S and the
	/// rest share a port, and one handshake reads it once.
	static Map<String, String[]> listeners(MBeanServer dr) {
		Map<String, String[]> out = new LinkedHashMap<>();
		try {
			for (ObjectName on : dr.queryNames(new ObjectName("com.bea:Type=ServerChannelRuntime,*"), null)) {
				Object url = HealthServlet.get(dr, on, "PublicURL");
				if (url == null) {
					continue;
				}
				URI u = URI.create(String.valueOf(url));
				String scheme = String.valueOf(u.getScheme()).toLowerCase(java.util.Locale.ROOT);
				if (!(scheme.equals("https") || scheme.equals("sips") || scheme.equals("t3s"))) {
					continue;
				}
				String key = u.getHost() + ":" + u.getPort();
				String[] seen = out.get(key);
				// One name per port, the one a person knows it by.
				if (seen == null || (!seen[1].equals("https") && !seen[1].equals("sips"))) {
					out.put(key, new String[] { on.getKeyProperty("Location"), scheme });
				}
			}
		} catch (Exception e) {
			logger.log(Level.FINE, "channels unreadable", e);
		}
		return out;
	}

	private static Map<String, Object> read(String server, String protocol, String address, int warningDays, long now) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("server", server);
		r.put("protocol", protocol);
		r.put("address", address);
		String key = "cert:" + server + ":" + address;
		try {
			X509Certificate cert = presented(address);
			long left = (cert.getNotAfter().getTime() - now) / 86_400_000L;
			r.put("subject", cert.getSubjectX500Principal().getName());
			r.put("issuer", cert.getIssuerX500Principal().getName());
			r.put("notAfter", cert.getNotAfter().getTime());
			r.put("daysLeft", left);
			if (left <= warningDays) {
				if (RAISED.add(key)) {
					Problems.raise(key, "The " + protocol.toUpperCase(java.util.Locale.ROOT) + " certificate on "
							+ server + " (" + address + ") " + (left < 0 ? "expired " + -left + " days ago"
									: "expires in " + left + (left == 1 ? " day" : " days")), null);
				}
			} else if (RAISED.remove(key)) {
				Problems.clear(key);
			}
		} catch (Exception e) {
			r.put("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
		}
		return r;
	}

	/// The leaf certificate a TLS listener presents.
	static X509Certificate presented(String address) throws Exception {
		int colon = address.lastIndexOf(':');
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, new TrustManager[] { new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String auth) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String auth) {
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		} }, null);
		try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
			s.connect(new InetSocketAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1))),
					TIMEOUT_MS);
			s.setSoTimeout(TIMEOUT_MS);
			s.startHandshake();
			Certificate[] chain = s.getSession().getPeerCertificates();
			return (X509Certificate) chain[0];
		}
	}
}
