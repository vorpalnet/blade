package org.vorpal.blade.framework.v3.net;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TelURL;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.analytics.Analytics;

/// Where a call came from, recorded once per call as two call keys: the
/// address its first INVITE came from ([#ORIGIN_KEY]), the SBC or carrier
/// edge that handed it to BLADE, and where its caller is ([#AREA_KEY]), the
/// area code and never the number.
///
/// **Recorded by the first BLADE application the call reaches.** That
/// application notes both ([#note]) when it mints the Vorpal-ID, and
/// publishes them ([#publish]) after the session they belong to opens. An
/// application further down the chain adopts the Vorpal-ID from a trusted peer
/// instead, so it notes nothing: two keys per call, not two per hop.
public final class CallOrigin {

	/// The call key that says where the caller is, as far as a map needs: the
	/// area code of a North American number, or `+` and the first three digits
	/// of any other ([#area]).
	public static final String AREA_KEY = "area";

	/// The call key naming the address the call's first INVITE came from. A
	/// dashboard tells from it which inbound peers are sending calls, and which
	/// have gone quiet.
	public static final String ORIGIN_KEY = "origin";

	/// The application-session attributes holding the two between [#note] and
	/// [#publish].
	static final String CALLER_AREA = "blade.analytics.callerArea";
	static final String CALLER_ORIGIN = "blade.analytics.callerOrigin";

	private CallOrigin() {
	}

	/// Note, for a call entering BLADE, the address its INVITE came from and
	/// where its caller is. The caller is the `P-Asserted-Identity` where it
	/// holds a number, which a carrier vouches for, and the From otherwise: a
	/// carrier's asserted identity is not always a telephone number. Never
	/// throws: this runs on the SIP container thread.
	public static void note(SipServletRequest request) {
		try {
			String remote = request.getRemoteAddr();
			if (remote != null && !remote.isEmpty()) {
				request.getApplicationSession().setAttribute(CALLER_ORIGIN, remote);
			}
			String area = null;
			if (request.getHeader("P-Asserted-Identity") != null) {
				try {
					Address asserted = request.getAddressHeader("P-Asserted-Identity");
					area = area(number(asserted == null ? null : asserted.getURI()));
				} catch (ServletParseException unreadable) {
					// fall back to the From
				}
			}
			if (area == null && request.getFrom() != null) {
				area = area(number(request.getFrom().getURI()));
			}
			if (area != null) {
				request.getApplicationSession().setAttribute(CALLER_AREA, area);
			}
		} catch (RuntimeException e) {
			// A map without this call is better than a call without its INVITE.
		}
	}

	/// Publish what [#note] noted, as call keys, once: call it after the
	/// call's session has opened, so the keys have a session to belong to.
	/// Does nothing for a call this application did not note. Never throws.
	public static void publish(SipApplicationSession appSession) {
		try {
			if (appSession == null) {
				return;
			}
			Object area = appSession.getAttribute(CALLER_AREA);
			if (area != null) {
				appSession.removeAttribute(CALLER_AREA);
				Analytics.sessionKey(appSession, AREA_KEY, (String) area);
			}
			Object origin = appSession.getAttribute(CALLER_ORIGIN);
			if (origin != null) {
				appSession.removeAttribute(CALLER_ORIGIN);
				Analytics.sessionKey(appSession, ORIGIN_KEY, (String) origin);
			}
		} catch (IllegalStateException invalidated) {
			// The session went away under us; there is nothing left to publish.
		}
	}

	/// The telephone number in a `tel:` URI, or the user part of a `sip:` URI
	/// without its parameters: a carrier writes
	/// `sip:+16465550100;verstat=TN-Validation-Passed@host` for a call STIR/SHAKEN
	/// verified, and `getUser` returns the parameter with the number.
	static String number(URI uri) {
		if (uri instanceof TelURL) {
			TelURL tel = (TelURL) uri;
			return (tel.isGlobal() ? "+" : "") + tel.getPhoneNumber();
		}
		String user = uri instanceof SipURI ? ((SipURI) uri).getUser() : null;
		int params = user == null ? -1 : user.indexOf(';');
		return params < 0 ? user : user.substring(0, params);
	}

	/// Where a number is, as far as a map needs: the area code of a North
	/// American number (`+1` and ten digits, `1` and ten, or ten digits
	/// starting 2 to 9), `+` and the first three digits of any other
	/// international number (the reader finds the country by longest prefix),
	/// or null for anything else: an extension, an anonymous caller, a name.
	/// Visual separators (`-`, `.`, spaces, parentheses) are ignored.
	public static String area(String number) {
		if (number == null) {
			return null;
		}
		String n = number.trim().replaceAll("[-.() ]", "");
		boolean plus = n.startsWith("+");
		String digits = plus ? n.substring(1) : n;
		if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
			return null;
		}
		if (digits.length() == 11 && digits.charAt(0) == '1') {
			digits = digits.substring(1);
		} else if (plus) {
			return digits.length() < 7 ? null : "+" + digits.substring(0, 3);
		}
		return digits.length() == 10 && digits.charAt(0) >= '2' ? digits.substring(0, 3) : null;
	}
}
