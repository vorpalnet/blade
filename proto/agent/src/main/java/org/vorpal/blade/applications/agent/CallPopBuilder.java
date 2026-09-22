package org.vorpal.blade.applications.agent;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.configuration.selectors.IdentitySelector;

/// Builds a [CallPop] from an inbound INVITE, reusing the framework's `identity`
/// PASSporT decoder for the STIR/SHAKEN attestation. Everything here is a read
/// off the message the caller and the upstream BLADE apps produced; enrichment
/// that needs a database ([CallerHistory]) is added by the caller.
public final class CallPopBuilder {

	/// Ten-digit NANP number from a sip:/sips:/tel: URI, with or without +1.
	private static final Pattern NANP = Pattern.compile(".*?(?:sips?|tel):\\+?1?(\\d{10}).*", Pattern.DOTALL);
	private static final Pattern USER = Pattern.compile("sips?:([^@:;>]+)@");
	private static final Pattern DISPLAY = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<");
	private static final Pattern VERSTAT = Pattern.compile(".*;verstat=([A-Za-z-]+).*", Pattern.DOTALL);

	private CallPopBuilder() {
	}

	/// Build the pop from the INVITE and the caller's catalog history.
	public static CallPop of(SipServletRequest invite, String callId, CallerHistory history) {
		CallPop pop = new CallPop();
		pop.callId = callId;
		pop.receivedUtc = Instant.now().toString();
		pop.history = (history != null) ? history : CallerHistory.EMPTY;

		String from = header(invite, "From");
		String pai = header(invite, "P-Asserted-Identity");
		// Network-asserted identity beats From for the number the caller presents.
		pop.ani = firstNanp(pai, from);
		pop.displayName = display(from);
		// The dialled number when there is one; otherwise the To user as it stands
		// (a queue name, an extension), which still tells the agent what was called.
		String to = header(invite, "To");
		pop.dialed = firstNanp(to, requestUri(invite));
		if (pop.dialed == null) {
			pop.dialed = userPart(to, requestUri(invite));
		}
		pop.anonymous = from != null && from.toLowerCase().contains("anonymous");

		pop.verstat = firstMatch(VERSTAT, pai, from);

		String identity = header(invite, "Identity");
		if (identity != null) {
			Map<String, String> claims = IdentitySelector.parse(identity);
			if (claims != null) {
				pop.attestation = claims.get("attest");
				pop.stirAgeSeconds = parseLong(claims.get("age"));
			}
		}

		String screen = header(invite, "X-Call-Screen");
		if (screen != null) {
			int semi = screen.indexOf(';');
			pop.screenVerdict = (semi >= 0 ? screen.substring(0, semi) : screen).trim();
			int r = screen.indexOf("reason=");
			if (r >= 0) {
				pop.screenReason = screen.substring(r + "reason=".length()).trim();
			}
		}
		pop.callRate = parseInt(header(invite, "X-Call-Rate"));

		// The fused risk band, when the closed risk engine has stamped one on the
		// call (X-Call-Risk: clear|watch|suspect). Absent means not yet scored; we
		// do not invent a band from the screening verdict, which is a different
		// signal shown on its own line.
		String risk = header(invite, "X-Call-Risk");
		if (risk != null) {
			int semi = risk.indexOf(';');
			pop.riskBand = (semi >= 0 ? risk.substring(0, semi) : risk).trim();
			int sc = risk.indexOf("score=");
			if (sc >= 0) {
				String rest = risk.substring(sc + "score=".length());
				int end = rest.indexOf(';');
				pop.riskScore = (end >= 0 ? rest.substring(0, end) : rest).trim();
			}
		}
		return pop;
	}

	/// The user part of the first address that has one: `sip:agent@host` → `agent`.
	private static String userPart(String... addresses) {
		for (String a : addresses) {
			if (a == null) {
				continue;
			}
			Matcher m = USER.matcher(a);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
	}

	private static String firstNanp(String... headers) {
		for (String h : headers) {
			if (h == null) {
				continue;
			}
			Matcher m = NANP.matcher(h);
			if (m.matches()) {
				return m.group(1);
			}
		}
		return null;
	}

	private static String display(String fromHeader) {
		if (fromHeader == null) {
			return null;
		}
		Matcher m = DISPLAY.matcher(fromHeader);
		if (m.find()) {
			String name = m.group(1).trim();
			return name.isEmpty() ? null : name;
		}
		return null;
	}

	private static String firstMatch(Pattern p, String... headers) {
		for (String h : headers) {
			if (h == null) {
				continue;
			}
			Matcher m = p.matcher(h);
			if (m.matches()) {
				return m.group(1);
			}
		}
		return null;
	}

	private static String header(SipServletRequest r, String name) {
		try {
			String v = r.getHeader(name);
			return (v == null || v.trim().isEmpty()) ? null : v.trim();
		} catch (Throwable t) {
			return null;
		}
	}

	private static String requestUri(SipServletRequest r) {
		try {
			return r.getRequestURI() == null ? null : r.getRequestURI().toString();
		} catch (Throwable t) {
			return null;
		}
	}

	private static Long parseLong(String s) {
		try {
			return s == null ? null : Long.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Integer parseInt(String s) {
		try {
			return s == null ? null : Integer.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
