package org.vorpal.blade.framework.v3.crud;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

/**
 * Static utility methods for reading and writing SIP message attributes.
 * Follows the same attribute naming conventions as AttributeSelector.
 */
public class MessageHelper implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Prefix on the application-session attribute that holds a CRUD variable.
	/// `${callerUser}` is stored as `crud.callerUser`, so a variable can never
	/// overwrite an attribute the hosting application keeps for itself: CRUD
	/// runs inside any v3 application now, not only the CRUD service.
	public static final String VARIABLE_PREFIX = "crud.";

	private MessageHelper() {
	}

	/// Stores CRUD variable `name` on the application session, under
	/// [#VARIABLE_PREFIX]. A null value removes it.
	public static void putVariable(SipApplicationSession appSession, String name, String value) {
		if (appSession == null || name == null) {
			return;
		}
		if (value == null) {
			appSession.removeAttribute(VARIABLE_PREFIX + name);
		} else {
			appSession.setAttribute(VARIABLE_PREFIX + name, value);
		}
	}

	/// CRUD variable `name`, or null.
	public static String getVariable(SipApplicationSession appSession, String name) {
		if (appSession == null || name == null) {
			return null;
		}
		Object value = appSession.getAttribute(VARIABLE_PREFIX + name);
		return (value instanceof String) ? (String) value : null;
	}

	/// Removes CRUD variable `name` from the application session.
	public static void removeVariable(SipApplicationSession appSession, String name) {
		putVariable(appSession, name, null);
	}

	/**
	 * Reads a value from a SIP message attribute (header, body, Request-URI, etc.).
	 * When contentType is specified and attribute is body/Content, delegates to
	 * MimeHelper for multipart part extraction.
	 */
	public static String getAttributeValue(SipServletMessage msg, String attribute, String contentType)
			throws IOException {
		if (msg == null || attribute == null) {
			return null;
		}

		// One canonical spelling per pseudo-attribute (lowerCamelCase,
		// acronyms uppercase) — matches v3 Selector.readSource. Any other
		// name is a real SIP header.
		switch (attribute) {
		case "body":
			return getBodyContent(msg, contentType);

		case "requestURI":
			if (msg instanceof SipServletRequest) {
				return ((SipServletRequest) msg).getRequestURI().toString();
			}
			return null;

		case "status":
			if (msg instanceof SipServletResponse) {
				return Integer.toString(((SipServletResponse) msg).getStatus());
			}
			return null;

		case "reasonPhrase":
			if (msg instanceof SipServletResponse) {
				return ((SipServletResponse) msg).getReasonPhrase();
			}
			return null;

		case "originIP":
			// Original caller across proxy / B2BUA hops. Delegates to v3
			// Selector, which walks X-Vorpal-ID;origin → InitialRemoteAddr →
			// bottom Via received/sent-by → transport peer.
			return Selector.readSource(asRequest(msg), "originIP");

		case "peerIP":
			// Immediate transport peer — whoever sent THIS hop. Use this
			// when you specifically want the upstream neighbor; for
			// "who dialed?" use originIP.
			return msg.getRemoteAddr();

		case "transport":
			// UDP / TCP / TLS / WS / WSS, taken from the initial request
			// regardless of whether `msg` is the request or its response.
			SipServletRequest tReq = asRequest(msg);
			return tReq != null ? tReq.getInitialTransport() : null;

		case "isSecure":
			SipServletRequest sReq = asRequest(msg);
			String t = (sReq != null) ? sReq.getInitialTransport() : null;
			return Boolean.toString("TLS".equalsIgnoreCase(t) || "WSS".equalsIgnoreCase(t));

		default:
			return msg.getHeader(attribute);
		}
	}

	private static SipServletRequest asRequest(SipServletMessage msg) {
		if (msg instanceof SipServletRequest) return (SipServletRequest) msg;
		if (msg instanceof SipServletResponse) return ((SipServletResponse) msg).getRequest();
		return null;
	}

	/// Adds a body part. If the message already has a body, it is wrapped
	/// (or extended) into multipart/mixed and the new part is appended;
	/// otherwise the new part becomes the entire body.
	public static void addBodyPart(SipServletMessage msg, String contentType, String body) throws Exception {
		if (msg == null || contentType == null) return;
		MimeHelper.addPart(msg, contentType, body);
	}

	/// If `value` is a JSON-shaped string (starts with `{` or `[`), parse it
	/// to a JsonNode so JsonPath / SDP create and update can splice in
	/// objects and arrays — not the literal string. Otherwise returns the
	/// original string. This makes the save/restore pattern work: read a
	/// media block on the INVITE, save it as a session variable, then write
	/// `${videoMedia}` back into `$.media` on the 200 OK.
	public static Object jsonOrString(String value) {
		if (value == null) return null;
		int i = 0;
		while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
		if (i >= value.length()) return value;
		char c = value.charAt(i);
		if (c == '{' || c == '[') {
			try {
				return JSON.readTree(value);
			} catch (Exception ignore) {
			}
		}
		return value;
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	/**
	 * Writes a value to a SIP message attribute (header, body, Request-URI).
	 * When contentType is specified and attribute is body/Content, delegates to
	 * MimeHelper for multipart part replacement.
	 */
	public static void setAttributeValue(SipServletMessage msg, String attribute, String value, String contentType)
			throws Exception {
		if (msg == null || attribute == null) {
			return;
		}

		switch (attribute) {
		case "body":
			setBodyContent(msg, value, contentType);
			break;

		case "requestURI":
			if (msg instanceof SipServletRequest) {
				((SipServletRequest) msg).setRequestURI(SettingsManager.sipFactory.createURI(value));
			}
			break;

		case "To":
		case "to":
		case "From":
		case "from":
			setAddress(msg, attribute, value);
			break;

		case "contact":
		case "Contact":
			// `Contact` is a SIP system header — `setHeader` throws. The
			// container manages the Contact URI; what a rule can usefully
			// contribute is header parameters (notably `+sip.src` for SIPREC
			// role advertisement). Parse the supplied value, copy its
			// parameters onto the container-managed Contact.
			mergeContactParameters(msg, value);
			break;

		default:
			msg.setHeader(attribute, value);
			break;
		}
	}

	/// Rewrites the `To` or `From` address in place: its URI and display name
	/// become those of `value`. The change goes through the message's own
	/// address object, as the gateway's `From` rewrite does, not through
	/// `setHeader`, which the container may refuse for these headers. The tag and other header parameters stay as the
	/// dialog has them; parameters written in `value` are ignored.
	///
	/// Only an initial request is changed. Inside a dialog the URIs are the
	/// dialog's (RFC 3261 section 12.2.1.1), and a response's `To` is its
	/// request's, so a rule broad enough to reach a re-INVITE or a 200 is
	/// skipped there rather than breaking the dialog.
	private static void setAddress(SipServletMessage msg, String attribute, String value) throws Exception {
		if (!(msg instanceof SipServletRequest) || !((SipServletRequest) msg).isInitial()) {
			SettingsManager.getSipLogger().finer(msg,
					"MessageHelper - " + attribute + " is fixed inside a dialog; not changed");
			return;
		}
		if (msg instanceof org.vorpal.blade.framework.sip.DetachedMessage) {
			// BLADE's own message (the editor's preview, tests) keeps its headers as
			// text and may have no SipFactory behind it, as on the AdminServer.
			String current = msg.getHeader(attribute);
			if (current == null) {
				return;
			}
			javax.servlet.sip.Address address = new org.vorpal.blade.framework.sip.DetachedAddress(current);
			javax.servlet.sip.Address supplied = new org.vorpal.blade.framework.sip.DetachedAddress(value);
			address.setURI(supplied.getURI());
			address.setDisplayName(supplied.getDisplayName());
			msg.setHeader(attribute, address.toString());
			return;
		}
		javax.servlet.sip.Address supplied = SettingsManager.sipFactory.createAddress(value);
		javax.servlet.sip.Address live = attribute.equalsIgnoreCase("To") ? msg.getTo() : msg.getFrom();
		if (live == null) {
			SettingsManager.getSipLogger().warning(msg, "MessageHelper - no " + attribute + " header on message");
			return;
		}
		live.setURI(supplied.getURI());
		live.setDisplayName(supplied.getDisplayName());
	}

	/// Copies the header parameters of a textual Contact value onto the
	/// message's container-managed Contact address. The supplied URI is
	/// discarded — only the parameters survive, since the container owns the
	/// host:port and contact-URI shape.
	private static void mergeContactParameters(SipServletMessage msg, String value) {
		try {
			javax.servlet.sip.Parameterable supplied = SettingsManager.sipFactory.createParameterable(value);
			javax.servlet.sip.Address contact = msg.getAddressHeader("Contact");
			if (contact == null) {
				SettingsManager.getSipLogger().warning(msg,
						"MessageHelper - no Contact header on message; cannot apply parameters");
				return;
			}
			java.util.Iterator<String> names = supplied.getParameterNames();
			while (names.hasNext()) {
				String pname = names.next();
				String pvalue = supplied.getParameter(pname);
				contact.setParameter(pname, pvalue == null ? "" : pvalue);
			}
		} catch (Exception e) {
			SettingsManager.getSipLogger().warning(msg,
					"MessageHelper - failed to apply Contact parameters: " + e.getMessage());
		}
	}

	/**
	 * Removes a header or clears body content from a SIP message.
	 * When contentType is specified and attribute is body/Content, removes
	 * only the matching MIME part.
	 */
	public static void removeAttribute(SipServletMessage msg, String attribute, String contentType) throws Exception {
		if (msg == null || attribute == null) {
			return;
		}

		switch (attribute) {
		case "body":
			String wantedCt = (contentType != null && !contentType.trim().isEmpty()) ? contentType : null;
			boolean multipart = msg.getContentType() != null
					&& msg.getContentType().toLowerCase().startsWith("multipart/");
			if (wantedCt != null && multipart) {
				MimeHelper.removePart(msg, wantedCt);
			} else if (wantedCt == null && multipart) {
				// Refuse to nuke the entire multipart body when the operator
				// didn't specify which part they meant.
				SettingsManager.getSipLogger().warning(msg,
						"MessageHelper - body delete on a multipart message has no `contentType` filter; skipping.");
			} else {
				msg.setContent(null, null);
			}
			break;

		default:
			msg.removeHeader(attribute);
			break;
		}
	}

	/// The variables `${name}` resolves against: every String attribute on the
	/// application session, with CRUD's own variables (stored under
	/// [#VARIABLE_PREFIX]) laid over them by their bare name. Unprefixed
	/// attributes stay visible on purpose: the tester writes `${scenario}` and
	/// `${index}` itself, and a rule may read an attribute its application set.
	public static Map<String, String> getSessionVariables(SipApplicationSession appSession) {
		Map<String, String> vars = new HashMap<>();
		if (appSession == null) {
			return vars;
		}
		Map<String, String> own = new HashMap<>();
		for (String name : appSession.getAttributeNameSet()) {
			Object value = appSession.getAttribute(name);
			if (value instanceof String) {
				if (name.startsWith(VARIABLE_PREFIX)) {
					own.put(name.substring(VARIABLE_PREFIX.length()), (String) value);
				} else {
					vars.put(name, (String) value);
				}
			}
		}
		vars.putAll(own);
		return vars;
	}

	private static String getBodyContent(SipServletMessage msg, String contentType) throws IOException {
		if (msg.getContent() == null) return null;

		String wanted = blank(contentType) ? null : contentType;
		boolean isMultipart = isMultipart(msg);

		// Multipart body: route through MimeHelper.
		if (isMultipart) {
			if (wanted == null) {
				SettingsManager.getSipLogger().warning(msg,
						"MessageHelper - body operation on a multipart message has no `contentType` filter; skipping. "
								+ "Set contentType to the part you want to read (e.g. application/sdp).");
				return null;
			}
			return MimeHelper.getPartBody(msg, wanted);
		}

		// Single-part body. If the operator specified a contentType filter
		// that doesn't match the body's actual content type, refuse to
		// return the body — feeding SDP to an XML parser is what produced
		// the operator-reported `Content is not allowed in prolog` errors.
		// `wanted == null` (no filter) keeps the legacy behaviour.
		if (wanted != null) {
			String actual = msg.getContentType();
			if (actual == null
					|| !actual.toLowerCase().startsWith(wanted.toLowerCase())) {
				return null;
			}
		}

		Object content = msg.getContent();
		if (content instanceof String) return (String) content;
		if (content instanceof byte[]) return new String((byte[]) content);
		return null;
	}

	private static void setBodyContent(SipServletMessage msg, String value, String contentType) throws Exception {
		String wanted = blank(contentType) ? null : contentType;
		boolean isMultipart = isMultipart(msg);

		if (wanted != null && isMultipart) {
			MimeHelper.setPartBody(msg, wanted, value);
			return;
		}
		if (wanted == null && isMultipart) {
			SettingsManager.getSipLogger().warning(msg,
					"MessageHelper - body write on a multipart message has no `contentType` filter; skipping. "
							+ "Set contentType to the part you want to update.");
			return;
		}
		String ct = (wanted != null) ? wanted : msg.getContentType();
		msg.setContent(value, ct);
	}

	private static boolean isMultipart(SipServletMessage msg) {
		String mt = msg.getContentType();
		return mt != null && mt.toLowerCase().startsWith("multipart/");
	}

	private static boolean blank(String s) {
		return s == null || s.trim().isEmpty();
	}
}
