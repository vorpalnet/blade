package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.IOException;
import java.io.Serializable;
import java.util.ListIterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.Parameterable;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// Pulls a value out of whatever payload its parent
/// [org.vorpal.blade.framework.v3.configuration.connectors.Connector]
/// produced and writes it to the SIP session via the [Context].
///
/// Concrete selectors choose their own extraction technique:
///
/// - [AttributeSelector] — named SIP header / Map field
/// - [JsonSelector] — JsonPath
/// - [XmlSelector] — XPath
/// - [SdpSelector] — SDP field code
/// - [RegexSelector] — regex with named groups + expression template
///
/// They are peers — none extends another. If you need regex parsing
/// on top of a JSON-extracted value, chain a [JsonSelector] (writes
/// its result into the session) followed by a [RegexSelector]
/// (reads from the session attribute by name and applies its pattern).
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = AttributeSelector.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = AttributeSelector.class, name = "attribute"),
		@JsonSubTypes.Type(value = JsonSelector.class, name = "json"),
		@JsonSubTypes.Type(value = XmlSelector.class, name = "xml"),
		@JsonSubTypes.Type(value = SdpSelector.class, name = "sdp"),
		@JsonSubTypes.Type(value = RegexSelector.class, name = "regex")
})
@JsonPropertyOrder({ "type", "id", "description", "attribute" })
// Tolerate the legacy `index` and `applicationSession` fields in older
// configs: they moved out of the per-selector schema (they're now a
// session-level concern; see SessionParameters). This annotation lets
// existing irouter.json files keep loading without an edit pass.
@JsonIgnoreProperties(value = { "index", "applicationSession" }, ignoreUnknown = false)
public abstract class Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String id;
	protected String description;
	protected String attribute;

	public Selector() {
	}

	@JsonPropertyDescription("Unique identifier; also the default session attribute name for the extracted value")
	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	@JsonPropertyDescription("Human-readable description")
	@FormLayout(wide = true)
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	@JsonPropertyDescription("What to extract: header name / JsonPath / XPath / SDP field / column / source attribute")
	public String getAttribute() { return attribute; }
	public void setAttribute(String attribute) { this.attribute = attribute; }

	/// Subclasses implement this to read from `payload` (whatever the
	/// parent connector provided) and call [#store] with the resulting
	/// value. Errors are swallowed by the parent connector.
	public abstract void extract(Context ctx, Object payload);

	/// Write a value to the SIP session. `${var}` placeholders in the
	/// value are resolved against the current session state (via
	/// [Context#resolve]) before storage.
	///
	/// Application-session storage and session-index keying are now a
	/// session-level concern — they're configured on `SessionParameters`
	/// rather than per-selector. Future work.
	protected void store(Context ctx, String name, String value) {
		if (name == null || value == null || ctx == null) return;
		ctx.put(name, value);
	}

	/// Read a raw string value identified by `name` from the connector
	/// payload:
	///
	/// - `Map<String,String>` payload (REST/JDBC/LDAP/Map connectors):
	///   name is a map key.
	/// - [SipServletRequest] payload (SipConnector): name is a SIP
	///   header name, with special pseudo-headers handled directly:
	///   - `Request-URI`, `requestURI`, `RequestURI`, `ruri` — request URI
	///   - `Remote-IP`, `remoteIP` — original caller (fallback chain)
	///   - `Peer-IP`, `peerIP` — immediate transport peer
	///   - `content`, `body` — message body
	///   - `Transport`, `transport` — `UDP` / `TCP` / `TLS` / `WS` / `WSS`
	///   - `IsSecure`, `isSecure` — `true` if transport is `TLS` or `WSS`
	///   - `ClientCertSubject`, `clientCertSubject` — subject DN of the
	///     client's X.509 cert (TLS/WSS with mutual auth)
	///   - `ClientCertIssuer`, `clientCertIssuer` — issuer DN of that cert
	///   - `TlsCipher`, `tlsCipher` — negotiated TLS cipher suite
	///
	/// Returns null if `payload` isn't recognized or the name isn't
	/// present.
	@SuppressWarnings("unchecked")
	public static String readSource(Object payload, String name) {
		if (name == null) return null;

		if (payload instanceof Map) {
			Object v = ((Map<String, Object>) payload).get(name);
			return (v != null) ? v.toString() : null;
		}

		if (payload instanceof SipServletRequest) {
			SipServletRequest request = (SipServletRequest) payload;
			try {
				switch (name) {
				case "Request-URI":
				case "requestURI":
				case "RequestURI":
				case "ruri":
					return request.getRequestURI() != null ? request.getRequestURI().toString() : null;

				case "Remote-IP":
				case "remoteIP":
					return resolveOriginalSourceIp(request);
				case "Peer-IP":
				case "peerIP": {
					// Raw transport-level peer of the immediate socket —
					// whatever sent THIS hop. Only useful when you
					// specifically want to know your upstream neighbor
					// (rarely). For "who dialed the call?", use remoteIP.
					String peer = request.getRemoteAddr();
					return (peer != null) ? peer : "127.0.0.1";
				}

				case "content":
				case "Content":
				case "body":
				case "Body":
					if (request.getContent() == null) return null;
					if (request.getContent() instanceof String) return (String) request.getContent();
					return new String((byte[]) request.getContent());

				case "Transport":
				case "transport":
					return request.getInitialTransport();

				case "IsSecure":
				case "isSecure": {
					String t = request.getInitialTransport();
					boolean secure = "TLS".equalsIgnoreCase(t) || "WSS".equalsIgnoreCase(t);
					return Boolean.toString(secure);
				}

				case "ClientCertSubject":
				case "clientCertSubject":
					return firstClientCert(request, /*subject=*/true);

				case "ClientCertIssuer":
				case "clientCertIssuer":
					return firstClientCert(request, /*subject=*/false);

				case "TlsCipher":
				case "tlsCipher": {
					Object cipher = request.getAttribute("javax.servlet.request.cipher_suite");
					return (cipher != null) ? cipher.toString() : null;
				}

				default:
					return request.getHeader(name);
				}
			} catch (IOException e) {
				SettingsManager.getSipLogger().logStackTrace(e);
				return null;
			}
		}

		return null;
	}

	/// Resolves the peer-presented client certificate (TLS/WSS mutual
	/// auth) and returns either its subject DN or its issuer DN.
	/// Returns null if the connection wasn't TLS, if no client cert was
	/// presented, or if the container didn't populate the standard
	/// `javax.servlet.request.X509Certificate` attribute.
	private static String firstClientCert(SipServletRequest request, boolean subject) {
		Object attr = request.getAttribute("javax.servlet.request.X509Certificate");
		if (!(attr instanceof java.security.cert.X509Certificate[])) return null;
		java.security.cert.X509Certificate[] chain = (java.security.cert.X509Certificate[]) attr;
		if (chain.length == 0 || chain[0] == null) return null;
		return subject
				? chain[0].getSubjectX500Principal().getName()
				: chain[0].getIssuerX500Principal().getName();
	}

	// Kept for convenience — some subclasses need to know the
	// SipApplicationSession directly (to null-check before writing
	// an "_index_*" key when the session machinery is rebuilding).
	// Most callers should use the Context API.
	protected static SipApplicationSession appSessionOf(Context ctx) {
		if (ctx == null || ctx.getRequest() == null) return null;
		return ctx.getRequest().getApplicationSession();
	}

	// ---- "remoteIP" resolution ----
	//
	// Goal: identify the UA that sent the ORIGINAL request even when the
	// consumer lives several proxy / B2BUA hops downstream.
	//
	// Fallback chain (first non-null wins):
	//   1. X-Vorpal-ID `origin` parameter — stamped by Callflow on the
	//      first BLADE service to see the request and propagated through
	//      every forward. This is the reliable, position-independent
	//      answer; the remaining steps are fallbacks for flows that
	//      didn't go through Callflow (e.g. iRouter uses the proxy API,
	//      not Callflow.sendRequest, so it has no X-Vorpal-ID to read
	//      when it's first-in-chain).
	//   2. SipServletRequest#getInitialRemoteAddr() — when the container
	//      can derive it. OCCAS sometimes returns null for
	//      internally-synthesized requests, so we can't rely on it.
	//   3. Bottom-most Via header's `received` parameter — per RFC 3261
	//      §18.2.1, the first proxy that received the request stamps
	//      the real source IP here when `sent-by` doesn't match. Most
	//      reliable for NAT'd external callers.
	//   4. Bottom-most Via header's `sent-by` host — what the UA
	//      claimed about itself. Works when the UA isn't NAT'd.
	//   5. SipServletRequest#getRemoteAddr() — immediate transport peer;
	//      last-ditch fallback. When iRouter is first-in-chain (reached
	//      via the proxy API without an upstream Callflow-using BLADE
	//      app), this IS the external sender, so the chain resolves
	//      correctly without any special-case code.

	// sent-by host is either a bracketed IPv6 literal `[2001:db8::1]` or
	// a plain host/IPv4 that stops at whitespace / `:` / `;` / `,`.
	private static final Pattern VIA_SENT_BY = Pattern.compile(
			"^\\s*SIP/2\\.0/[A-Za-z0-9._-]+\\s+(\\[[^\\]]+\\]|[^\\s:;,]+)"
	);
	private static final Pattern VIA_RECEIVED = Pattern.compile(
			"(?:^|;)\\s*received\\s*=\\s*([^;,\\s]+)"
	);

	static String resolveOriginalSourceIp(SipServletRequest request) {
		if (request == null) return "127.0.0.1";

		// 1. X-Vorpal-ID;origin=<ip> — the preferred answer when present.
		try {
			Parameterable xVorpalId = request.getParameterableHeader(Callflow.X_VORPAL_ID);
			if (xVorpalId != null) {
				String origin = xVorpalId.getParameter(Callflow.ORIGIN_PARAM);
				if (origin != null && !origin.isEmpty()) return origin;
			}
		} catch (Exception ignore) {
			// Malformed header must not break the pipeline — fall through.
		}

		// 2. Container-derived initial remote addr
		String initial = request.getInitialRemoteAddr();
		if (initial != null && !initial.isEmpty()) return initial;

		// 3/4. Walk Via stack to the bottom; prefer received, else sent-by
		String bottomVia = bottomOfViaStack(request);
		if (bottomVia != null) {
			Matcher rcv = VIA_RECEIVED.matcher(bottomVia);
			if (rcv.find()) return rcv.group(1);
			Matcher sb = VIA_SENT_BY.matcher(bottomVia);
			if (sb.find()) return sb.group(1);
		}

		// 5. Hop-by-hop peer
		String peer = request.getRemoteAddr();
		return (peer != null && !peer.isEmpty()) ? peer : "127.0.0.1";
	}

	/// Returns the last (bottom-most) Via header value, or null if none.
	/// The bottom Via is the one added by the originating UA; every
	/// proxy prepends its own on top.
	private static String bottomOfViaStack(SipServletRequest request) {
		@SuppressWarnings("unchecked")
		ListIterator<String> vias = request.getHeaders("Via");
		if (vias == null) return null;
		String last = null;
		while (vias.hasNext()) last = vias.next();
		return last;
	}
}
