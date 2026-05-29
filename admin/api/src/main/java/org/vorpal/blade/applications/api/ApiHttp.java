package org.vorpal.blade.applications.api;

import java.net.http.HttpClient;
import java.time.Duration;

/// Shared, stateless HTTP plumbing for talking to the engine tier, plus the
/// pure URL-building / sanitizing helpers (kept here so they are unit-testable
/// without a servlet container).
///
/// The [HttpClient] is a thread-safe connection pool, reused across requests —
/// this is the idiomatic way to use the JDK client, not shared mutable
/// application state (cf. memory `[[no-singletons]]`, which is about per-node
/// SIP state, not stateless resource pools).
final class ApiHttp {

	private ApiHttp() {
	}

	static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/// Per-request read timeout for engine-tier calls.
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

	/// Normalizes and validates the `?app=` value so a spec URL built from it
	/// can ONLY ever resolve to a path *under* the configured engine base URL.
	///
	/// Strips leading slashes (so it cannot become a protocol-relative
	/// `//host` authority) and rejects anything containing `..`, a scheme
	/// (`://`), a backslash, or characters outside the context-root alphabet.
	/// Returns `null` if the value is unusable.
	static String sanitizeApp(String app) {
		if (app == null) {
			return null;
		}
		String s = app.trim();
		while (s.startsWith("/")) {
			s = s.substring(1);
		}
		if (s.isEmpty()) {
			return null;
		}
		if (s.contains("..") || s.contains("://") || s.contains("\\")) {
			return null;
		}
		if (!s.matches("[A-Za-z0-9._/-]+")) {
			return null;
		}
		return s;
	}

	/// Builds `<base>/<app>/resources/openapi.<ext>`. `base` is expected to be
	/// already trimmed of a trailing slash; `app` already sanitized.
	static String specUrl(String base, String app, String ext) {
		return base + "/" + app + "/resources/openapi." + ext;
	}

	/// `"yaml"` (any case) maps to `yaml`; everything else to `json`.
	static String normalizeFormat(String format) {
		return (format != null && format.equalsIgnoreCase("yaml")) ? "yaml" : "json";
	}
}
