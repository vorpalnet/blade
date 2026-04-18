package org.vorpal.blade.framework.v3.configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

/// Per-call state wrapper threaded through the iRouter pipeline.
///
/// Hides the [SipServletRequest] from adapters, selectors, and
/// routing tables — only the [org.vorpal.blade.framework.v3.configuration.adapters.SipAdapter]
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
/// A `${To}` placeholder only resolves if an upstream SipAdapter
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

	/// Reserved attachment key: the last matched
	/// [org.vorpal.blade.framework.v3.configuration.translations.Translation]
	/// produced by any [org.vorpal.blade.framework.v3.configuration.adapters.TableAdapter]
	/// that ran during this pipeline invocation. The outer
	/// [org.vorpal.blade.framework.v3.configuration.RouterConfiguration]
	/// uses this to return the final routing decision.
	public static final String LAST_MATCH = "__lastMatch";

	private final SipServletRequest request;

	/// Per-call scratch space for non-serializable, non-String values
	/// (notably the last matched Translation). Deliberately kept off
	/// the SipSession to avoid cluster-serialization cost — attachments
	/// live only for the duration of one pipeline invocation.
	private final Map<String, Object> attachments = new HashMap<>();

	public Context(SipServletRequest request) {
		this.request = request;
	}

	// ---- transient per-call attachments ----

	public void setAttachment(String name, Object value) {
		if (name != null) attachments.put(name, value);
	}

	public Object getAttachment(String name) {
		return (name == null) ? null : attachments.get(name);
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

	/// Resolve `${name}` placeholders in `template` against session
	/// state. Unresolved placeholders are left as-is.
	public String resolve(String template) {
		if (template == null) return null;
		if (request == null) return template;
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String name = m.group(1);
			String value = get(name);
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/// Substitute `${name}` placeholders in `template` against an
	/// arbitrary `vars` map (does not consult the SIP session).
	/// Unresolved placeholders are left as-is. Static utility,
	/// useful for selectors that work against per-call data like
	/// regex capture groups.
	public static String substitute(String template, Map<String, String> vars) {
		if (template == null) return null;
		if (vars == null || vars.isEmpty()) return template;
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String name = m.group(1);
			String value = vars.get(name);
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(out);
		return out.toString();
	}

	// ---- SipAdapter-only access ----

	/// Intended for [org.vorpal.blade.framework.v3.configuration.adapters.SipAdapter]
	/// only. Other adapters and selectors should stick to the
	/// session-state API above.
	public SipServletRequest getRequest() {
		return request;
	}
}
