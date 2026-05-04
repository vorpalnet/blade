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

	private MessageHelper() {
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

		switch (attribute) {
		case "body":
		case "Body":
		case "content":
		case "Content":
			return getBodyContent(msg, contentType);

		case "ruri":
		case "Ruri":
		case "RURI":
		case "requestURI":
		case "RequestURI":
		case "Request-URI":
			if (msg instanceof SipServletRequest) {
				return ((SipServletRequest) msg).getRequestURI().toString();
			}
			return null;

		case "status":
			if (msg instanceof SipServletResponse) {
				return Integer.toString(((SipServletResponse) msg).getStatus());
			}
			return null;

		case "reason":
		case "reasonPhrase":
			if (msg instanceof SipServletResponse) {
				return ((SipServletResponse) msg).getReasonPhrase();
			}
			return null;

		case "originIP":
		case "OriginIP":
		case "Origin-IP":
			// Original caller across proxy / B2BUA hops. Delegates to v3
			// Selector, which walks X-Vorpal-ID;origin → InitialRemoteAddr →
			// bottom Via received/sent-by → transport peer.
			return Selector.readSource(asRequest(msg), "Origin-IP");

		case "peerIP":
		case "PeerIP":
		case "Peer-IP":
			// Immediate transport peer — whoever sent THIS hop. Use this
			// when you specifically want the upstream neighbor; for
			// "who dialed?" use originIP.
			return msg.getRemoteAddr();

		case "transport":
		case "Transport":
			// UDP / TCP / TLS / WS / WSS, taken from the initial request
			// regardless of whether `msg` is the request or its response.
			SipServletRequest tReq = asRequest(msg);
			return tReq != null ? tReq.getInitialTransport() : null;

		case "isSecure":
		case "IsSecure":
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
		case "Body":
		case "content":
		case "Content":
			setBodyContent(msg, value, contentType);
			break;

		case "ruri":
		case "Ruri":
		case "RURI":
		case "requestURI":
		case "RequestURI":
		case "Request-URI":
			if (msg instanceof SipServletRequest) {
				((SipServletRequest) msg).setRequestURI(SettingsManager.sipFactory.createURI(value));
			}
			break;

		default:
			msg.setHeader(attribute, value);
			break;
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
		case "Body":
		case "content":
		case "Content":
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

	/**
	 * Builds a Map of all String-typed attributes from a SipApplicationSession.
	 * Used for ${variable} resolution in operations.
	 */
	public static Map<String, String> getSessionVariables(SipApplicationSession appSession) {
		Map<String, String> vars = new HashMap<>();
		if (appSession == null) {
			return vars;
		}
		for (String name : appSession.getAttributeNameSet()) {
			Object value = appSession.getAttribute(name);
			if (value instanceof String) {
				vars.put(name, (String) value);
			}
		}
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
