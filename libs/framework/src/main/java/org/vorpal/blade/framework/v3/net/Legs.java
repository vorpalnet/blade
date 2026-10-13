package org.vorpal.blade.framework.v3.net;

import java.util.HashSet;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/// Which server refused a leg of a call, and how
/// ([BladeEventTypes#CALL_BRANCH_FAILED]).
///
/// **Everything that sends a call somewhere meets here**: the framework's
/// response handling reports every final failure on an initial INVITE leg
/// ([#failed]), so a proxy's branch, a balancer's endpoint and a B2BUA's callee
/// leg are recorded alike, and so is the 408 or 503 the container answers
/// itself for a server that never did (no `responder`). Its send path reports
/// an INVITE the transport refused outright ([#notSent]), which no response
/// will ever describe.
public final class Legs {

	/// The reason a leg carries when the INVITE could not be sent at all: no
	/// connection to the server, an unresolvable host. A dashboard reads it as
	/// the server unreachable.
	public static final String NOT_SENT = "not sent";

	/// The application-session attribute holding the legs already reported.
	static final String REPORTED = "blade.analytics.branchFailures";

	private Legs() {
	}

	/// A server answered one leg with a final failure: an initial INVITE's
	/// response of 400 or above. Never throws: this runs on the SIP container
	/// thread.
	public static void failed(SipServletResponse response) {
		try {
			if (response.getStatus() < 400 || !"INVITE".equals(response.getMethod())) {
				return;
			}
			Integer retryAfter = null;
			String header = response.getHeader("Retry-After");
			if (header != null) {
				try {
					retryAfter = Integer.valueOf(header.trim().split("[ ;(]", 2)[0]);
				} catch (NumberFormatException unreadable) {
					// left out
				}
			}
			publish(response.getRequest(), response.getStatus(), response.getReasonPhrase(), response.getRemoteAddr(),
					retryAfter);
		} catch (RuntimeException e) {
			// A missing fact is better than a failed response.
		}
	}

	/// An initial INVITE could not be sent: the transport refused it, so no
	/// response will ever come. Recorded as the 500 the framework hands the
	/// callflow in its place, with the reason [#NOT_SENT] and no `responder`.
	/// Never throws.
	public static void notSent(SipServletRequest request, Exception cause) {
		try {
			if ("INVITE".equals(request.getMethod())) {
				publish(request, 500,
						NOT_SENT + (cause == null || cause.getMessage() == null ? "" : ": " + cause.getMessage()), null,
						null);
			}
		} catch (RuntimeException e) {
			// A missing fact is better than a failed send.
		}
	}

	/// One `branchFailed` per leg and status, however often it is reported: the
	/// container may deliver a proxy's branch response, then the same response
	/// as the best.
	@SuppressWarnings("unchecked")
	private static void publish(SipServletRequest request, int status, String reason, String responder,
			Integer retryAfter) {
		if (request == null || !request.isInitial()) {
			return;
		}
		SipApplicationSession appSession = request.getApplicationSession();
		String destination = String.valueOf(request.getRequestURI());
		HashSet<String> seen = (HashSet<String>) appSession.getAttribute(REPORTED);
		if (seen == null) {
			seen = new HashSet<>();
		}
		if (!seen.add(destination + "|" + status)) {
			return;
		}
		appSession.setAttribute(REPORTED, seen);
		Events.publish(appSession, BladeEventTypes.CALL_BRANCH_FAILED, data -> data
				.put("status", status)
				.put("reason", reason)
				.put("destination", destination)
				.put("responder", responder)
				.put("retryAfter", retryAfter));
	}
}
