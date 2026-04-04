package org.vorpal.blade.services.crud;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;

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

		case "remoteIP":
		case "RemoteIP":
		case "Remote-IP":
			return msg.getRemoteAddr();

		default:
			return msg.getHeader(attribute);
		}
	}

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
			if (contentType != null && msg.getContentType() != null && msg.getContentType().startsWith("multipart/")) {
				MimeHelper.removePart(msg, contentType);
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
		if (msg.getContent() == null) {
			return null;
		}

		// If targeting a specific MIME part
		if (contentType != null && msg.getContentType() != null && msg.getContentType().startsWith("multipart/")) {
			return MimeHelper.getPartBody(msg, contentType);
		}

		// Simple body
		Object content = msg.getContent();
		if (content instanceof String) {
			return (String) content;
		} else if (content instanceof byte[]) {
			return new String((byte[]) content);
		}
		return null;
	}

	private static void setBodyContent(SipServletMessage msg, String value, String contentType) throws Exception {
		if (contentType != null && msg.getContentType() != null && msg.getContentType().startsWith("multipart/")) {
			MimeHelper.setPartBody(msg, contentType, value);
		} else {
			String ct = (contentType != null) ? contentType : msg.getContentType();
			msg.setContent(value, ct);
		}
	}
}
