package org.vorpal.blade.framework.v3.crud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

/// Everything a [Rule]'s operations can change on a message, taken before
/// the rule runs, so a rule that fails partway can be undone: the message
/// goes out as it was, never half-edited. A SIPREC rule that pruned the
/// metadata and then failed on the SDP would otherwise send a recorder XML
/// for two streams and SDP for four.
///
/// Covers the headers, the body, the Request-URI, the `To` and `From`
/// addresses, the `Contact` parameters, the CRUD session variables, and the
/// pending `status`, `reasonPhrase` and `reject` a rule may have set.
/// Restoring writes back only what changed, so the headers the container
/// guards are left alone unless a rule really did change them.
final class MessageSnapshot {

	private static final String[] ADDRESSES = { "To", "From" };

	private final Map<String, List<String>> headers = new LinkedHashMap<>();
	private final byte[] content;
	private final String contentType;
	private final String requestUri;
	private final Map<String, String> addresses = new LinkedHashMap<>();
	private final Map<String, String> contactParameters;
	/// The Contact values, so an undone delete puts the header back.
	private final java.util.List<String> contacts = new java.util.ArrayList<>();
	private final Map<String, String> variables;
	private final Map<String, Object> pending = new LinkedHashMap<>();

	private MessageSnapshot(SipServletMessage msg) throws Exception {
		for (Iterator<String> names = msg.getHeaderNames(); names != null && names.hasNext();) {
			String name = names.next();
			headers.put(name, values(msg, name));
		}
		content = msg.getRawContent();
		contentType = msg.getContentType();
		requestUri = (msg instanceof SipServletRequest && ((SipServletRequest) msg).getRequestURI() != null)
				? ((SipServletRequest) msg).getRequestURI().toString()
				: null;
		for (String name : ADDRESSES) {
			addresses.put(name, msg.getHeader(name));
		}
		contactParameters = contactParameters(msg);
		try {
			for (java.util.ListIterator<String> it = msg.getHeaders("Contact"); it.hasNext();) contacts.add(it.next());
		} catch (Exception ignore) {
			// no Contact to keep
		}
		variables = MessageHelper.ownVariables(msg.getApplicationSession());
		for (String name : MessageHelper.PENDING_ATTRIBUTES) {
			pending.put(name, msg.getAttribute(name));
		}
	}

	static MessageSnapshot of(SipServletMessage msg) throws Exception {
		return new MessageSnapshot(msg);
	}

	/// Puts back whatever differs from the snapshot.
	void restore(SipServletMessage msg) throws Exception {
		for (Map.Entry<String, Object> entry : pending.entrySet()) {
			if (entry.getValue() == null) {
				msg.removeAttribute(entry.getKey());
			} else {
				msg.setAttribute(entry.getKey(), entry.getValue());
			}
		}

		SipApplicationSession appSession = msg.getApplicationSession();
		if (appSession != null) {
			Map<String, String> now = MessageHelper.ownVariables(appSession);
			for (String name : now.keySet()) {
				if (!variables.containsKey(name)) MessageHelper.removeVariable(appSession, name);
			}
			for (Map.Entry<String, String> entry : variables.entrySet()) {
				if (!entry.getValue().equals(now.get(entry.getKey()))) {
					MessageHelper.putVariable(appSession, entry.getKey(), entry.getValue());
				}
			}
		}

		if (!java.util.Arrays.equals(content, msg.getRawContent())
				|| !Objects.equals(contentType, msg.getContentType())) {
			msg.setContent(content, contentType);
		}

		if (requestUri != null && msg instanceof SipServletRequest
				&& !requestUri.equals(String.valueOf(((SipServletRequest) msg).getRequestURI()))) {
			((SipServletRequest) msg).setRequestURI(
					org.vorpal.blade.framework.v2.config.SettingsManager.sipFactory.createURI(requestUri));
		}

		for (Map.Entry<String, String> entry : addresses.entrySet()) {
			if (entry.getValue() != null && !entry.getValue().equals(msg.getHeader(entry.getKey()))) {
				MessageHelper.setAttributeValue(msg, entry.getKey(), entry.getValue(), null);
			}
		}

		restoreContactParameters(msg);

		Set<String> names = new LinkedHashSet<>(headers.keySet());
		for (Iterator<String> it = msg.getHeaderNames(); it != null && it.hasNext();) {
			names.add(it.next());
		}
		for (String name : names) {
			if (isAddressOrContact(name) || isBodyHeader(name)) continue;
			List<String> before = headers.getOrDefault(name, new ArrayList<>());
			if (before.equals(values(msg, name))) continue;
			msg.removeHeader(name);
			for (String value : before) {
				msg.addHeader(name, value);
			}
		}
	}

	private static List<String> values(SipServletMessage msg, String name) {
		List<String> values = new ArrayList<>();
		for (java.util.ListIterator<String> it = msg.getHeaders(name); it != null && it.hasNext();) {
			values.add(it.next());
		}
		return values;
	}

	private static boolean isAddressOrContact(String name) {
		switch (name.toLowerCase()) {
		case "to":
		case "t":
		case "from":
		case "f":
		case "contact":
		case "m":
			return true;
		default:
			return false;
		}
	}

	/// Restored with the body, by `setContent`.
	private static boolean isBodyHeader(String name) {
		switch (name.toLowerCase()) {
		case "content-type":
		case "c":
		case "content-length":
		case "l":
			return true;
		default:
			return false;
		}
	}

	private static Map<String, String> contactParameters(SipServletMessage msg) {
		Map<String, String> parameters = new LinkedHashMap<>();
		Address contact = contact(msg);
		if (contact == null) return null;
		for (Iterator<String> it = contact.getParameterNames(); it.hasNext();) {
			String name = it.next();
			parameters.put(name, contact.getParameter(name));
		}
		return parameters;
	}

	private void restoreContactParameters(SipServletMessage msg) {
		// A Contact a rule deleted comes back, where the container lets the
		// application write one (a 3xx, a REGISTER).
		if (!contacts.isEmpty() && msg.getHeader("Contact") == null) {
			try {
				for (String value : contacts) msg.addHeader("Contact", value);
			} catch (Exception notAllowed) {
				// the container writes Contact on this message
			}
			return;
		}
		if (contactParameters == null) return;
		Map<String, String> now = contactParameters(msg);
		if (now == null || now.equals(contactParameters)) return;
		Address contact = contact(msg);
		for (String name : now.keySet()) {
			if (!contactParameters.containsKey(name)) contact.removeParameter(name);
		}
		for (Map.Entry<String, String> entry : contactParameters.entrySet()) {
			contact.setParameter(entry.getKey(), entry.getValue());
		}
	}

	private static Address contact(SipServletMessage msg) {
		try {
			return (msg.getHeader("Contact") != null) ? msg.getAddressHeader("Contact") : null;
		} catch (Exception e) {
			return null;
		}
	}
}
