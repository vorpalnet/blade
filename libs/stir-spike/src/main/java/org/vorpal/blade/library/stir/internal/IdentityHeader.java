package org.vorpal.blade.library.stir.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/// Parser for the SIP `Identity` header per RFC 8224 §4.1:
///
/// ```
/// <compact-JWS>;info=<x5u-URI>;alg=ES256;ppt=shaken
/// ```
///
/// Lenient about parameter quoting — accepts `info=<uri>`, `info="uri"`,
/// and bare `info=uri`. The spike does not handle line-folding or
/// multi-header concatenation; one Identity header value per call.
public final class IdentityHeader {

	private final String jws;
	private final URI x5u;
	private final String alg;
	private final String ppt;

	private IdentityHeader(String jws, URI x5u, String alg, String ppt) {
		this.jws = jws;
		this.x5u = x5u;
		this.alg = alg;
		this.ppt = ppt;
	}

	public static IdentityHeader parse(String headerValue) {
		if (headerValue == null || headerValue.isEmpty()) {
			throw new IllegalArgumentException("empty Identity header");
		}
		String[] parts = headerValue.trim().split(";");
		String jws = parts[0].trim();
		if (jws.isEmpty() || jws.chars().filter(c -> c == '.').count() != 2) {
			throw new IllegalArgumentException("Identity value is not a compact JWS");
		}

		Map<String, String> params = new HashMap<>();
		for (int i = 1; i < parts.length; i++) {
			String p = parts[i].trim();
			int eq = p.indexOf('=');
			if (eq <= 0) continue;
			String k = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
			String v = p.substring(eq + 1).trim();
			if (v.length() >= 2 && v.startsWith("<") && v.endsWith(">")) {
				v = v.substring(1, v.length() - 1);
			} else if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
				v = v.substring(1, v.length() - 1);
			}
			params.put(k, v);
		}

		URI x5u = null;
		String infoRaw = params.get("info");
		if (infoRaw != null && !infoRaw.isEmpty()) {
			try {
				x5u = new URI(infoRaw);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException("info= is not a valid URI: " + infoRaw, e);
			}
		}

		return new IdentityHeader(jws, x5u, params.get("alg"), params.get("ppt"));
	}

	public String getJws()  { return jws; }
	public URI getX5u()     { return x5u; }
	public String getAlg()  { return alg; }
	public String getPpt()  { return ppt; }
}
