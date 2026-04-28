package org.vorpal.blade.framework.v3.configuration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

/// Per-call state wrapper threaded through the iRouter pipeline.
///
/// Hides the [SipServletRequest] from connectors, selectors, and
/// routing tables — only the [org.vorpal.blade.framework.v3.configuration.connectors.SipConnector]
/// reaches through to the request itself (via [#getRequest]). Everything
/// else operates on the session-state API and `${var}` substitution
/// exposed here.
///
/// ## Session precedence
///
/// [#get] and [#resolve] look up names in this order:
///
/// 1. SipSession attribute
/// 2. SipApplicationSession attribute
///
/// SIP headers are **not** a fallback source for `${var}` resolution.
/// A `${To}` placeholder only resolves if an upstream SipConnector
/// selector explicitly copied the To header into session state.
/// This keeps data flow explicit.
///
/// ## `${var}` on writes
///
/// [#put] and [#putAppSession] both run the value through
/// [#resolve] before storing — so a selector can produce values like
/// `"sip:fraud@${customerDomain}"` and the stored attribute is the
/// fully-resolved URI.
public class Context {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

	private final SipServletRequest request;

	public Context(SipServletRequest request) {
		this.request = request;
	}

	// ---- session state ----

	/// Look up a name in the SIP session first, then the SIP
	/// application session. Returns null if not found.
	public String get(String name) {
		if (name == null || request == null) return null;

		SipSession session = request.getSession();
		if (session != null) {
			Object v = session.getAttribute(name);
			if (v instanceof String) return (String) v;
			if (v != null) return v.toString();
		}

		SipApplicationSession appSession = request.getApplicationSession();
		if (appSession != null) {
			Object v = appSession.getAttribute(name);
			if (v instanceof String) return (String) v;
			if (v != null) return v.toString();
		}

		return null;
	}

	/// Write to the SIP session. `${var}` placeholders in the value
	/// are resolved before storage.
	public void put(String name, String value) {
		if (name == null || value == null || request == null) return;
		SipSession session = request.getSession();
		if (session != null) session.setAttribute(name, resolve(value));
	}

	/// Write to the SIP application session. `${var}` resolved.
	public void putAppSession(String name, String value) {
		if (name == null || value == null || request == null) return;
		SipApplicationSession appSession = request.getApplicationSession();
		if (appSession != null) appSession.setAttribute(name, resolve(value));
	}

	/// Snapshot of all currently-resolvable string attributes from
	/// both sessions. SipSession wins over SipApplicationSession on
	/// name collision (matches [#get] precedence).
	public Map<String, String> snapshot() {
		Map<String, String> out = new HashMap<>();
		if (request == null) return out;

		SipApplicationSession appSession = request.getApplicationSession();
		if (appSession != null) {
			for (String name : appSession.getAttributeNameSet()) {
				Object v = appSession.getAttribute(name);
				if (v instanceof String) out.put(name, (String) v);
			}
		}

		SipSession session = request.getSession();
		if (session != null) {
			for (String name : session.getAttributeNameSet()) {
				Object v = session.getAttribute(name);
				if (v instanceof String) out.put(name, (String) v);
			}
		}

		return out;
	}

	// ---- ${var} substitution ----

	/// Reserved meta-variables that resolve without consulting the
	/// session, map, environment, or system properties. Always win
	/// over same-named values from those sources. Syntax: `${name}`
	/// or `${name:args}`.
	///
	/// - `${now}` — current Unix time in milliseconds
	///   (`System.currentTimeMillis()`).
	/// - `${now:FORMAT}` — the current time rendered by a
	///   [DateTimeFormatter] pattern in UTC, e.g.
	///   `${now:yyyy-MM-dd'T'HH:mm:ssX}` for ISO-8601 or
	///   `${now:EEE, dd MMM yyyy HH:mm:ss 'GMT'}` for RFC 2822.
	///   Invalid patterns leave the placeholder literal.
	/// - `${uuid}` — a random UUID (RFC 4122 version-4).
	///
	/// Plain `${NAME}` (no colon, not reserved) resolves via the
	/// fallback chain: session attribute (or caller's `vars` map for
	/// the static `substitute`), then `System.getenv(NAME)`, then
	/// `System.getProperty(NAME)`, then left literal. This matches
	/// the v2 `AttributeSelector` precedence and lets operators
	/// write `${HOME}`, `${user.dir}`, `${SECURELOGIX_API_KEY}`,
	/// etc., without wrapping them in `env:` or `sys:` prefixes.
	///
	/// Per-call caching: within a single substitution pass, all
	/// `${now}` / `${now:…}` references resolve against the same
	/// instant, and every `${uuid}` returns the same value. Across
	/// separate `resolve` / `substitute` calls they regenerate.
	///
	/// Returns null when `expr` is not a recognized reserved form;
	/// the outer loop then falls back to the session/map lookup.
	private static String reserved(String expr, Reserved cache) {
		int colon = expr.indexOf(':');
		String name = (colon < 0) ? expr : expr.substring(0, colon);
		String args = (colon < 0) ? null : expr.substring(colon + 1);
		switch (name) {
			case "now":
				if (cache.nowMillis == 0L) cache.nowMillis = System.currentTimeMillis();
				if (args == null) return String.valueOf(cache.nowMillis);
				return formatInstant(cache.nowMillis, args);
			case "uuid":
				if (args != null) return null; // unknown args form — fall through
				if (cache.uuid == null) cache.uuid = UUID.randomUUID().toString();
				return cache.uuid;
			default:
				return null;
		}
	}

	/// Plain-name fallback: env var, then system property.
	/// Returns null if neither is set so the caller leaves the
	/// placeholder literal.
	private static String fallback(String name) {
		if (name == null || name.isEmpty()) return null;
		String v = System.getenv(name);
		if (v != null) return v;
		return System.getProperty(name);
	}

	/// Per-call cache shared by every reserved-variable lookup inside
	/// one [#resolve] or [#substitute] invocation. Kept in a small
	/// mutable struct so two `${now}` (or `${uuid}`) references in the
	/// same template see the same value.
	private static final class Reserved {
		long nowMillis;
		String uuid;
	}

	/// Renders `millis` as a UTC instant using a
	/// [DateTimeFormatter] pattern. Returns null on any format error
	/// so the substitution loop falls back to leaving the placeholder
	/// literal.
	private static String formatInstant(long millis, String pattern) {
		if (pattern == null || pattern.isEmpty()) return null;
		try {
			return DateTimeFormatter.ofPattern(pattern)
					.withZone(ZoneId.of("UTC"))
					.format(Instant.ofEpochMilli(millis));
		} catch (IllegalArgumentException | DateTimeException e) {
			return null;
		}
	}

	/// Resolve `${name}` placeholders in `template` against session
	/// state. Reserved meta-variables (see [#reserved]) take
	/// precedence. Unresolved placeholders are left as-is.
	public String resolve(String template) {
		if (template == null) return null;
		if (request == null) return substituteReservedOnly(template);
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		Reserved cache = new Reserved();
		while (m.find()) {
			String expr = m.group(1);
			String value = reserved(expr, cache);
			// Session + env + system-property lookup uses only the bare
			// name, ignoring any `:args` tail (which only reserved forms
			// know how to parse).
			if (value == null) {
				int colon = expr.indexOf(':');
				String bare = (colon < 0) ? expr : expr.substring(0, colon);
				value = get(bare);
				if (value == null) value = fallback(bare);
			}
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/// Substitute `${name}` placeholders in `template` against an
	/// arbitrary `vars` map (does not consult the SIP session).
	/// Reserved meta-variables (see [#reserved]) take precedence.
	/// Unresolved placeholders are left as-is. Static utility, useful
	/// for selectors that work against per-call data like regex
	/// capture groups.
	public static String substitute(String template, Map<String, String> vars) {
		if (template == null) return null;
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		Reserved cache = new Reserved();
		while (m.find()) {
			String expr = m.group(1);
			String value = reserved(expr, cache);
			if (value == null) {
				int colon = expr.indexOf(':');
				String bare = (colon < 0) ? expr : expr.substring(0, colon);
				if (vars != null) value = vars.get(bare);
				if (value == null) value = fallback(bare);
			}
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/// Fast path for `resolve()` when `request == null` (e.g. in tests
	/// that construct a Context with no SIP request). Honors reserved
	/// meta-variables and the env/system-property fallback so `${now}`,
	/// `${HOME}`, `${user.dir}` work without a session.
	private static String substituteReservedOnly(String template) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		Reserved cache = new Reserved();
		while (m.find()) {
			String expr = m.group(1);
			String value = reserved(expr, cache);
			if (value == null) {
				int colon = expr.indexOf(':');
				value = fallback(colon < 0 ? expr : expr.substring(0, colon));
			}
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(out);
		return out.toString();
	}

	// ---- SipConnector-only access ----

	/// Intended for [org.vorpal.blade.framework.v3.configuration.connectors.SipConnector]
	/// only. Other connectors and selectors should stick to the
	/// session-state API above.
	public SipServletRequest getRequest() {
		return request;
	}
}
