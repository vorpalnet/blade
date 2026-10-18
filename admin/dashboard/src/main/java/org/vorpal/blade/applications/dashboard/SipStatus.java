package org.vorpal.blade.applications.dashboard;

import java.util.HashMap;
import java.util.Map;

/// What a final response to an INVITE says about the server that sent it, for
/// the network map.
///
/// Four kinds, by who has to act:
/// - [Kind#FAILING]: the server or the path to it is not working. 408 (nothing
///   came back), 500, 502, 503, 504. Someone has to fix equipment.
/// - [Kind#MISCONFIGURED]: the server works and is refusing what it was sent.
///   404 and 484 (a number or route not provisioned), 403 (not allowed), 488
///   and 606 (no codec in common), 482 and 483 (a routing loop), the identity
///   and STIR/SHAKEN refusals, 607 and 608 (calls rejected as unwanted).
///   Someone has to fix configuration, here or there.
/// - [Kind#CALLEE]: a person or their phone said no: 480, 486, 600, 603.
///   Normal for calls; shown, never coloured.
/// - [Kind#NORMAL]: part of a call working as designed: 401 and 407 (a
///   challenge the sender answers; wrong credentials come back as 403), 422 (a
///   session timer renegotiated), 487 (the call was cancelled), 491 (glare,
///   retried). Not shown.
///
/// A code not listed takes its class: an unknown 4xx is misconfigured, 5xx
/// failing, 6xx the callee.
final class SipStatus {

	enum Kind {
		FAILING, MISCONFIGURED, CALLEE, NORMAL
	}

	private static final Map<Integer, Kind> KIND = new HashMap<>();
	private static final Map<Integer, String> MEANING = new HashMap<>();

	private static void put(Kind kind, int status, String meaning) {
		KIND.put(status, kind);
		MEANING.put(status, meaning);
	}

	static {
		put(Kind.FAILING, 408, "no answer from the server");
		put(Kind.FAILING, 500, "server error");
		put(Kind.FAILING, 502, "bad answer from the next hop");
		put(Kind.FAILING, 503, "server unavailable");
		put(Kind.FAILING, 504, "next hop did not answer");

		put(Kind.MISCONFIGURED, 400, "malformed request");
		put(Kind.MISCONFIGURED, 403, "not allowed");
		put(Kind.MISCONFIGURED, 404, "number or route not provisioned");
		put(Kind.MISCONFIGURED, 405, "method not allowed");
		put(Kind.MISCONFIGURED, 410, "number no longer in service");
		put(Kind.MISCONFIGURED, 413, "request too large");
		put(Kind.MISCONFIGURED, 414, "address too long");
		put(Kind.MISCONFIGURED, 415, "body type not supported");
		put(Kind.MISCONFIGURED, 416, "address scheme not supported");
		put(Kind.MISCONFIGURED, 420, "extension not supported");
		put(Kind.MISCONFIGURED, 421, "extension required");
		put(Kind.MISCONFIGURED, 423, "interval too brief");
		put(Kind.MISCONFIGURED, 428, "identity header required");
		put(Kind.MISCONFIGURED, 429, "referrer identity required");
		put(Kind.MISCONFIGURED, 433, "anonymous calls refused");
		put(Kind.MISCONFIGURED, 436, "bad identity information");
		put(Kind.MISCONFIGURED, 437, "identity credential not supported");
		put(Kind.MISCONFIGURED, 438, "identity header invalid");
		put(Kind.MISCONFIGURED, 440, "too many forks");
		put(Kind.MISCONFIGURED, 469, "info package not supported");
		put(Kind.MISCONFIGURED, 470, "consent needed");
		put(Kind.MISCONFIGURED, 482, "routing loop");
		put(Kind.MISCONFIGURED, 483, "too many hops: routing loop");
		put(Kind.MISCONFIGURED, 484, "number incomplete: dial plan");
		put(Kind.MISCONFIGURED, 485, "number ambiguous");
		put(Kind.MISCONFIGURED, 488, "no codec in common");
		put(Kind.MISCONFIGURED, 489, "event package not supported");
		put(Kind.MISCONFIGURED, 493, "could not decrypt");
		put(Kind.MISCONFIGURED, 494, "security agreement required");
		put(Kind.MISCONFIGURED, 501, "not implemented");
		put(Kind.MISCONFIGURED, 505, "SIP version not supported");
		put(Kind.MISCONFIGURED, 513, "message too large: MTU or UDP");
		put(Kind.MISCONFIGURED, 580, "precondition failed");
		put(Kind.MISCONFIGURED, 604, "number does not exist anywhere");
		put(Kind.MISCONFIGURED, 606, "no codec in common");
		put(Kind.MISCONFIGURED, 607, "rejected as unwanted");
		put(Kind.MISCONFIGURED, 608, "rejected by call analytics");

		put(Kind.CALLEE, 480, "callee unavailable");
		put(Kind.CALLEE, 486, "busy");
		put(Kind.CALLEE, 600, "busy everywhere");
		put(Kind.CALLEE, 603, "declined");

		put(Kind.NORMAL, 401, "authentication challenge");
		put(Kind.NORMAL, 407, "proxy authentication challenge");
		put(Kind.NORMAL, 422, "session timer renegotiated");
		put(Kind.NORMAL, 487, "cancelled");
		put(Kind.NORMAL, 491, "request pending: glare");
	}

	private SipStatus() {
	}

	static Kind kind(int status) {
		Kind k = KIND.get(status);
		if (k != null) {
			return k;
		}
		if (status >= 600) {
			return Kind.CALLEE;
		}
		if (status >= 500) {
			return Kind.FAILING;
		}
		return status >= 400 ? Kind.MISCONFIGURED : Kind.NORMAL;
	}

	/// What the code means, in words; the class's words for one not listed.
	static String meaning(int status) {
		String m = MEANING.get(status);
		if (m != null) {
			return m;
		}
		switch (kind(status)) {
		case FAILING:
			return "server error";
		case MISCONFIGURED:
			return "refused";
		case CALLEE:
			return "refused everywhere";
		default:
			return "";
		}
	}
}
