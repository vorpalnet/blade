package org.vorpal.blade.framework.v3.configuration;

/// Repairs SIP URI strings produced by `${variable}` template substitution
/// before they are handed to `sipFactory.createURI()` / `createAddress()`.
///
/// When a template variable resolves to empty, the template's literal delimiter
/// is left orphaned — e.g. `"${proto}:carol@${host}:5060;${uriparams}"` with no
/// params yields `"sip:carol@host:5060;"`, and the bracketed address form yields
/// `"<sip:carol@host:5060;>"`. A bare trailing `;` (or `;>`, or an empty `:`
/// port) is malformed per RFC 3261 and makes `createURI()` throw.
///
/// [#tidy] removes those orphaned delimiters and strips the SIP default port
/// (5060, or 5061 for `sips:`/`transport=tls`). Pure string logic, no SIP
/// dependencies — born in v3, to earn its way down to the base layer later.
public final class UriTidy {

	private UriTidy() {
	}

	/// Tidy a SIP URI (or angle-bracketed address) string. Returns the input
	/// unchanged if it is `null` or has no work to do.
	public static String tidy(String uri) {
		if (uri == null) {
			return null;
		}

		String s = uri;

		// Collapse runs of ';' left by adjacent empty params (e.g. ";;" -> ";").
		s = s.replaceAll(";{2,}", ";");

		// Drop a ';' immediately before '>' (bracketed address, empty trailing param).
		s = s.replace(";>", ">");

		// Drop a single trailing ';'.
		if (s.endsWith(";")) {
			s = s.substring(0, s.length() - 1);
		}

		// Empty port: a ':' with no port digits (followed by ';', '>', '?', or end).
		// The scheme colon ("sip:") is followed by content, so it is never matched.
		s = s.replaceAll(":(?=[;>?]|$)", "");

		// Empty user: scheme colon directly followed by '@' ("sip:@host" -> "sip:host").
		s = s.replaceAll("(?i)(sips?):@", "$1:");

		// Strip the SIP default port: 5061 for sips:/transport=tls, else 5060.
		// A non-default explicit port (e.g. :5070, or :5060 on a sips: URI) is kept.
		String lower = s.toLowerCase();
		boolean secure = lower.contains("sips:") || lower.contains("transport=tls");
		String defaultPort = secure ? "5061" : "5060";
		s = s.replaceAll(":" + defaultPort + "(?=[;>?]|$)", "");

		return s;
	}
}
