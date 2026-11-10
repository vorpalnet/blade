package org.vorpal.blade.framework.v3.configuration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
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
/// ## Values are data; only configuration is a template
///
/// [#put] and [#putAppSession] store the value exactly as given, and
/// [#resolve] replaces each placeholder once without scanning the text it
/// inserted. Most stored values come from the call: a header, a REST
/// response, a database row. A caller controls that text, and a From
/// display name may legally contain `${DB_PASSWORD}`. If stored values were
/// templates, that name would reach the environment-variable fallback and
/// the secret would travel on in an outbound header, a REST body, or a
/// redirect's `Contact`.
///
/// Configuration text is still a template. A caller writing a value it took
/// from the configuration resolves it first, `ctx.put(name, ctx.resolve(v))`,
/// as the table connector and table selector do for their extras.
public class Context {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

	/// Delimiter joining the multiple values of a repeating SIP header into a
	/// single context value (see the `allInstances` mode on
	/// [org.vorpal.blade.framework.v3.configuration.selectors.AttributeSelector]).
	/// `NUL` never occurs in a real SIP header or body, so a value carrying it
	/// is unambiguously a multi-instance set — which lets the `matches` operator
	/// in [org.vorpal.blade.framework.v3.configuration.expressions.Expression]
	/// apply "any instance" semantics while leaving every ordinary single value
	/// (no delimiter) untouched.
	public static final String MULTI_VALUE_DELIMITER = "\u0000";

	private final SipServletRequest request;
	private final Map<String, String> defaults;

	public Context(SipServletRequest request) {
		this(request, null);
	}

	/// With `defaults` beneath both sessions: a name neither session holds
	/// resolves to its default. Nothing is written for them, so a default
	/// never replaces a session attribute of the same name.
	public Context(SipServletRequest request, Map<String, String> defaults) {
		this(request, defaults, false);
	}

	/// With `rules` true, the context CRUD runs a pipeline and a `when` in
	/// ([#forRules]).
	protected Context(SipServletRequest request, Map<String, String> defaults, boolean rules) {
		this.request = request;
		this.defaults = defaults;
		this.rules = rules;
		// Synchronized: a connector the pipeline gave up on may still write
		// from its own thread while the rules copy what was written.
		this.written = rules ? java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>()) : null;
	}

	/// The context for CRUD: templates resolve strictly (no plain-name
	/// fallback to the environment, `${env:}`/`${sys:}` only for names meant
	/// for rules, [#exposed]), and what connectors write is kept in this
	/// context ([#written]) instead of the call's sessions, so a connector
	/// output never overwrites an attribute the hosting application keeps.
	public static Context forRules(SipServletRequest request, Map<String, String> defaults) {
		return new Context(request, defaults, true);
	}

	private final boolean rules;
	private final Map<String, String> written;

	/// True for a context made [#forRules]: its templates resolve strictly.
	public boolean forRules() {
		return rules;
	}

	/// Substitute `${name}` placeholders in `template` against `vars`, as
	/// strictly as this context [#resolve]s. In a context [#forRules] it is
	/// [#substituteStrict]: no plain-name fallback, `${env:}`/`${sys:}` reach
	/// only exposed names, and null when any placeholder is unresolved, so no
	/// literal `${name}` becomes a value a rule puts on the wire. Otherwise
	/// unresolved placeholders are left as-is.
	public String substituteLike(String template, Map<String, String> vars) {
		if (template == null) return null;
		if (rules) return substituteStrict(template, vars, v -> v);
		return singlePass(template, name -> (vars != null) ? vars.get(name) : null, null, true);
	}

	/// A copy of what connectors wrote, in a context [#forRules]; empty
	/// otherwise.
	public Map<String, String> written() {
		if (written == null) return java.util.Collections.emptyMap();
		synchronized (written) {
			return new java.util.LinkedHashMap<>(written); // a copy: safe to iterate
		}
	}

	// ---- session state ----

	/// Look up a name in the SIP session first, then the SIP
	/// application session, then the defaults. Returns null if not found.
	public String get(String name) {
		if (name == null) return null;
		if (written != null && written.containsKey(name)) return written.get(name);
		if (request == null) return (defaults != null) ? defaults.get(name) : null;

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

		return (defaults != null) ? defaults.get(name) : null;
	}

	/// Write to the SIP session. The value is stored as given; see the class
	/// comment for why placeholders in it are not resolved.
	public void put(String name, String value) {
		if (name == null || value == null) return;
		if (written != null) { written.put(name, value); return; }
		if (request == null) return;
		SipSession session = request.getSession();
		if (session != null) session.setAttribute(name, value);
	}

	/// Write to the SIP application session, stored as given.
	public void putAppSession(String name, String value) {
		if (name == null || value == null) return;
		if (written != null) { written.put(name, value); return; }
		if (request == null) return;
		SipApplicationSession appSession = request.getApplicationSession();
		if (appSession != null) appSession.setAttribute(name, value);
	}

	/// Snapshot of all currently-resolvable string attributes from
	/// both sessions. SipSession wins over SipApplicationSession on
	/// name collision (matches [#get] precedence).
	public Map<String, String> snapshot() {
		Map<String, String> out = new HashMap<>();
		if (defaults != null) defaults.forEach((k, v) -> { if (v != null) out.put(k, v); });
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

		if (written != null) out.putAll(written());
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
	/// - `${now:FORMAT@ZONE}` — the same in a time zone, e.g.
	///   `${now:H@America/New_York}` for the hour where the callers are.
	///   Business hours are local and move with daylight saving, which a
	///   UTC range cannot follow.
	/// - `${uuid}` — a random UUID (RFC 4122 version-4).
	/// - `${env:NAME}`: the environment variable `NAME`; `${sys:NAME}`: the
	///   system property. In strict substitution (CRUD, [#substituteStrict],
	///   a context [#forRules]) only names meant for rules ([#exposed]).
	///
	/// Plain `${NAME}` (no colon, not reserved) resolves via the
	/// fallback chain: session attribute (or caller's `vars` map for
	/// the static `substitute`), then `System.getenv(NAME)`, then
	/// `System.getProperty(NAME)`, then left literal. This matches
	/// the v2 `AttributeSelector` precedence and lets operators
	/// write `${HOME}`, `${user.dir}`, `${SCREENING_API_KEY}`,
	/// etc., without wrapping them in `env:` or `sys:` prefixes.
	/// Strict substitution has no such fallback: CRUD reads the
	/// environment as `${env:CRUD_NAME}`, and a plain name that is only an
	/// environment variable or system property stays unresolved, with one
	/// warning per name in the log ([#warnUnread]).
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
			case "env":
				if (cache.strict && !exposed(args)) return null;
				return (args != null && !args.isEmpty()) ? System.getenv(args) : null;
			case "sys":
				if (cache.strict && !exposed(args)) return null;
				return (args != null && !args.isEmpty()) ? System.getProperty(args) : null;
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
		/// Strict substitution ([#substituteStrict]): CRUD, whose output goes
		/// on the wire and whose rules an Operator writes.
		boolean strict;
	}

	/// Names [#warnUnread] has logged, so each is logged once per JVM.
	private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/// Logs, once per name, that strict substitution left `${expr}`
	/// unresolved although the server has an environment variable or
	/// system property by that name (`set`): a template written for the
	/// plain-name fallback, which CRUD does not have. The value is never
	/// logged.
	private static void warnUnread(String expr, boolean set) {
		if (!set || !WARNED.add(expr)) return;
		org.vorpal.blade.framework.v2.logging.Logger log = org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger();
		if (log != null) {
			log.warning("CRUD left ${" + expr + "} unresolved: the server has an environment variable or system "
					+ "property by that name, but rules read the environment only as ${env:CRUD_...} or ${sys:crud....}");
		}
	}

	/// In strict substitution an environment variable or system property is
	/// read only when its name says it is meant for rules (`CRUD_…` or
	/// `crud.…`): otherwise anyone who can write or preview a rule could put
	/// `${sys:javax.net.ssl.keyStorePassword}` in a header. Name a value so to
	/// expose it.
	static boolean exposed(String name) {
		return name != null && (name.startsWith("CRUD_") || name.startsWith("crud."));
	}

	/// Renders `millis` as a UTC instant using a
	/// [DateTimeFormatter] pattern. Returns null on any format error
	/// so the substitution loop falls back to leaving the placeholder
	/// literal.
	private static String formatInstant(long millis, String pattern) {
		if (pattern == null || pattern.isEmpty()) return null;
		ZoneId zone = ZoneId.of("UTC");
		int at = pattern.lastIndexOf('@');
		if (at > 0) {
			try {
				zone = ZoneId.of(pattern.substring(at + 1));
				pattern = pattern.substring(0, at);
			} catch (DateTimeException notAZone) {
				// the whole thing is the pattern
			}
		}
		try {
			return DateTimeFormatter.ofPattern(pattern)
					.withZone(zone)
					.format(Instant.ofEpochMilli(millis));
		} catch (IllegalArgumentException | DateTimeException e) {
			return null;
		}
	}

	/// Resolve `${name}` placeholders in `template` against session
	/// state. Reserved meta-variables (see [#reserved]) take precedence.
	/// Each placeholder is replaced once: text a replacement inserts is not
	/// scanned again, so a value holding `${...}` stays literal. Unresolved
	/// placeholders are left as-is.
	public String resolve(String template) {
		if (template == null) return null;
		return singlePass(template, this::get, null, !rules);
	}

	/// Resolve like [#resolve], passing each inserted value through `encode`
	/// before it lands in the output. `encode` receives the placeholder's
	/// offset in `template` and the value, so a caller that knows the
	/// template's syntax can escape by position, for example only inside a
	/// JSON string. Unresolved placeholders are left as-is and not encoded.
	public String resolve(String template, BiFunction<Integer, String, String> encode) {
		if (template == null) return null;
		return singlePass(template, this::get, encode, !rules);
	}

	/// Substitute `${name}` placeholders in `template` against an
	/// arbitrary `vars` map (does not consult the SIP session).
	/// Reserved meta-variables (see [#reserved]) take precedence.
	/// Each placeholder is replaced once, as in [#resolve]. Unresolved
	/// placeholders are left as-is.
	public static String substitute(String template, Map<String, String> vars) {
		if (template == null) return null;
		return singlePass(template, name -> (vars != null) ? vars.get(name) : null, null, true);
	}

	/// Substitute like [#substitute], passing each value through `encode`,
	/// but return null if any placeholder stays unresolved, and without the
	/// plain-name fallback to environment variables and system properties
	/// (`${env:NAME}` and `${sys:NAME}` still work). For CRUD, whose output
	/// goes on the wire: a misspelt `${HOME}` must not send the server's home
	/// directory to a carrier, and a literal `${name}` left in a regex or a
	/// JsonPath would mean something else entirely.
	public static String substituteStrict(String template, Map<String, String> vars,
			Function<String, String> encode) {
		return substituteStrictAt(template, vars, (at, value) -> encode.apply(value));
	}

	/// [#substituteStrict], with each placeholder's position in `template`
	/// passed to `encode`: for an encoding that depends on where the value
	/// lands (inside a JSON string or not).
	public static String substituteStrictAt(String template, Map<String, String> vars,
			BiFunction<Integer, String, String> encode) {
		if (template == null) return null;
		int placeholders = 0;
		for (Matcher m = PLACEHOLDER.matcher(template); m.find();) placeholders++;
		int[] resolved = { 0 };
		String out = singlePass(template, name -> (vars != null) ? vars.get(name) : null, (at, value) -> {
			resolved[0]++;
			return encode.apply(at, value);
		}, false);
		return (resolved[0] == placeholders) ? out : null;
	}

	/// Replaces every placeholder in one scan of `template`. Reserved
	/// meta-variables win first; otherwise the supplied `lookup` is consulted,
	/// then env-var and system-property fallback, and finally the placeholder is
	/// left literal. The matcher runs over the template only, never over the
	/// text it appends, which is what keeps a looked-up value from being read
	/// as a template.
	private static String singlePass(String template, Function<String, String> lookup,
			BiFunction<Integer, String, String> encode, boolean implicitFallback) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		Reserved cache = new Reserved();
		cache.strict = !implicitFallback;
		while (m.find()) {
			String expr = m.group(1);
			String value = reserved(expr, cache);
			// A colon only ever means a reserved form (`${env:NAME}`,
			// `${now:FORMAT}`). One that resolves to nothing stays unresolved:
			// it must not fall back to a variable named `env` or `now`.
			if (value == null && expr.indexOf(':') < 0) {
				value = lookup.apply(expr);
				if (value == null && implicitFallback) value = fallback(expr);
				else if (value == null) warnUnread(expr, fallback(expr) != null);
			} else if (value == null && cache.strict && (expr.startsWith("env:") || expr.startsWith("sys:"))) {
				String name = expr.substring(4);
				warnUnread(expr, !name.isEmpty()
						&& (expr.startsWith("env:") ? System.getenv(name) : System.getProperty(name)) != null);
			}
			if (value != null && encode != null) value = encode.apply(m.start(), value);
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
