package org.vorpal.blade.applications.dashboard;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Whether an address belongs to a site ([NetworkSite#getAddresses]).
///
/// An entry is a host name, an IPv4 address, or an IPv4 CIDR block. A host
/// name matches itself, ignoring case, and also any address it resolves to:
/// a carrier's INVITEs arrive from IP addresses while its configuration names
/// a host. Lookups are cached for [#RESOLVE_TTL_MS], so a slow resolver costs
/// one wait per host per few minutes, not one per poll.
final class SiteAddresses {

	static final long RESOLVE_TTL_MS = 5 * 60_000L;

	private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

	/// The host of a SIP URI (`sip:user@host:port;params`), of a bare
	/// `host:port`, or the address itself.
	private static final Pattern URI_HOST = Pattern.compile("^(?:<)?(?:sips?:|tel:)?(?:[^@]*@)?\\[?([^\\]:;>?]+)");

	private static final Map<String, Resolved> RESOLVED = new ConcurrentHashMap<>();

	private static final class Resolved {
		final Set<String> addresses;
		final long at;

		Resolved(Set<String> addresses, long at) {
			this.addresses = addresses;
			this.at = at;
		}
	}

	private SiteAddresses() {
	}

	/// The host part of a URI or address, lower case; null for none.
	static String host(String uriOrAddress) {
		if (uriOrAddress == null) {
			return null;
		}
		Matcher m = URI_HOST.matcher(uriOrAddress.trim());
		return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
	}

	private static final Pattern JDBC_DESCRIPTOR_HOST = Pattern.compile("\\(\\s*HOST\\s*=\\s*([^)\\s]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern JDBC_URL_HOST = Pattern.compile("^jdbc:[a-z0-9]+(?::[a-z]+)?:(?://|@//|@)\\[?([A-Za-z0-9._-]+)\\]?(?:[:/;?]|$)",
			Pattern.CASE_INSENSITIVE);

	/// The database host a JDBC URL names: `@//host:port/service`,
	/// `@host:port:sid`, a `(DESCRIPTION=...(HOST=host)...)` descriptor, or
	/// `//host:port` for MySQL and SQL Server. Null for a TNS alias
	/// (`@name_tp?TNS_ADMIN=...`), whose host is in a tnsnames file, not the URL.
	static String jdbcHost(String url) {
		if (url == null) {
			return null;
		}
		Matcher d = JDBC_DESCRIPTOR_HOST.matcher(url);
		if (d.find()) {
			return d.group(1).toLowerCase(Locale.ROOT);
		}
		Matcher m = JDBC_URL_HOST.matcher(url.trim());
		if (!m.find()) {
			return null;
		}
		String host = m.group(1).toLowerCase(Locale.ROOT);
		boolean alias = url.contains("@") && !url.contains("@//") && !url.substring(url.indexOf('@')).contains(":");
		return alias ? null : host;
	}

	/// Whether `uriOrAddress` belongs to a site with these entries.
	static boolean matches(List<String> entries, String uriOrAddress) {
		String host = host(uriOrAddress);
		if (host == null || entries == null) {
			return false;
		}
		for (String raw : entries) {
			if (raw == null || raw.trim().isEmpty()) {
				continue;
			}
			String entry = raw.trim().toLowerCase(Locale.ROOT);
			if (entry.equals(host)) {
				return true;
			}
			if (entry.contains("/")) {
				if (inCidr(host, entry)) {
					return true;
				}
			} else if (!IPV4.matcher(entry).matches() && IPV4.matcher(host).matches()
					&& resolve(entry).contains(host)) {
				return true;
			}
		}
		return false;
	}

	/// Whether an IPv4 address lies in a CIDR block such as `192.0.2.0/28`.
	static boolean inCidr(String address, String cidr) {
		int slash = cidr.indexOf('/');
		long ip = ipv4(address), net = ipv4(cidr.substring(0, slash));
		if (ip < 0 || net < 0) {
			return false;
		}
		int bits;
		try {
			bits = Integer.parseInt(cidr.substring(slash + 1).trim());
		} catch (NumberFormatException e) {
			return false;
		}
		if (bits < 0 || bits > 32) {
			return false;
		}
		long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
		return (ip & mask) == (net & mask);
	}

	private static long ipv4(String s) {
		Matcher m = IPV4.matcher(s.trim());
		if (!m.matches()) {
			return -1;
		}
		long v = 0;
		for (int i = 1; i <= 4; i++) {
			int octet = Integer.parseInt(m.group(i));
			if (octet > 255) {
				return -1;
			}
			v = (v << 8) | octet;
		}
		return v;
	}

	private static Set<String> resolve(String hostName) {
		long now = System.currentTimeMillis();
		Resolved r = RESOLVED.get(hostName);
		if (r != null && now - r.at < RESOLVE_TTL_MS) {
			return r.addresses;
		}
		Set<String> addresses = new HashSet<>();
		try {
			for (InetAddress a : InetAddress.getAllByName(hostName)) {
				addresses.add(a.getHostAddress());
			}
		} catch (Exception e) {
			// an unknown name matches nothing until it resolves
		}
		RESOLVED.put(hostName, new Resolved(Collections.unmodifiableSet(addresses), now));
		return addresses;
	}
}
