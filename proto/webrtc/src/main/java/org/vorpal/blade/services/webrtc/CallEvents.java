package org.vorpal.blade.services.webrtc;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;

/// The call facts this gateway puts on the BLADE event bus.
///
/// A WebRTC call used to be invisible to analytics. `WebrtcServlet` extends `AsyncSipServlet` rather
/// than `B2buaServlet`, so none of the publishers that live in `InitialInvite` and `Terminate` ever
/// ran for it, and a browser call left no trace anywhere a report could find it. Everything here is
/// about closing that hole, and nothing here is new vocabulary.
///
/// ## The names are the framework's, deliberately
///
/// These are the same six names `InitialInvite` and `Terminate` publish — `callStarted` through
/// `callDeclined` — which [BladeEventTypes#forEventName] maps onto the canonical
/// `org.vorpal.blade.call.*` types. Reusing them is the whole point: a consumer filtering on
/// `eventType` gets browser calls and phone calls in one subscription, with no webrtc-specific
/// clause, and the payload shape is `AnalyticsEventMapper`'s rather than a second dialect that
/// happens to look similar. Inventing `webrtc.*` types would have meant a new catalog entry, a new
/// schema, and a consumer that has to know this application exists.
///
/// The two naming conventions that meet here are both correct and are not drifting apart: camelCase
/// is the operator-facing name in an application's `analytics.events` configuration, dotted
/// reverse-DNS is the type on the wire, and `BladeEventTypes.forEventName` is the one place the
/// mapping lives.
///
/// ## Two rules the emit sites depend on
///
/// - **A lost fact must never cost a call.** Every publish is wrapped. `Analytics.sendEvent` already
///   swallows its own failures, but `SettingsManager.sendEvent` also calls `addDestinationAttributes`
///   and `sipLogger.logEvent` unguarded, and `SignalEndpoint` documents that the WebSocket container
///   can start before the SIP servlet — so there is a window at deployment where the logger is null.
///   Analytics is not worth a dropped call.
/// - **A call ends exactly once.** Several paths can race to end the same call: `cancelOrBye` can
///   publish an abandon, have its CANCEL throw, and fall through to `byeAndRelease`; inbound, a
///   browser decline does not cancel the BYE expectation armed while it was ringing.
///   [#terminal] claims the call before publishing, so the loser of that race is silent.
///   `Analytics.sessionStop` has no idempotence of its own.
public final class CallEvents {

	/// The framework's own analytics names. Anything else would land on
	/// [BladeEventTypes#CALL_EVENT], the fallback for operator-invented names.
	static final String STARTED = "callStarted";
	static final String ANSWERED = "callAnswered";
	static final String CONNECTED = "callConnected";
	static final String COMPLETED = "callCompleted";
	static final String ABANDONED = "callAbandoned";
	static final String DECLINED = "callDeclined";

	/// Which side of the gateway a call came in on. Not decoration: a browser-to-browser call is one
	/// call that traverses this application twice — out through [OutboundFromBrowser], back in
	/// through [InboundToBrowser] via the location service — and the second leg inherits the first's
	/// `X-Vorpal-ID`. Both legs therefore publish under the same correlator, the same source and the
	/// same application name, and without this attribute nothing in the payload tells them apart.
	static final String LEG = "leg";
	static final String INBOUND = "inbound";
	static final String OUTBOUND = "outbound";

	/// [SipApplicationSession] attribute (Boolean): the terminal fact has been published.
	private static final String ENDED = "org.vorpal.blade.webrtc.events.ended";

	/// [SipApplicationSession] attribute (Boolean): this call reached a `200 OK`, which is what
	/// separates a completed call from an abandoned one.
	private static final String ANSWERED_FLAG = "org.vorpal.blade.webrtc.events.answered";

	private CallEvents() {
	}

	/// A call began. Publishes the session start beside it, the way `InitialInvite` does.
	///
	/// **Outbound callers must call this after `sendRequest`, not before.** A call originated from a
	/// WebSocket thread has no inbound request to have stamped a Vorpal-ID, so the correlator is
	/// minted lazily on the way out; publish first and `Analytics.sessionStart` returns early on a
	/// null correlator and the event carries no subject.
	static void started(SipServletMessage carrier, String leg) {
		try {
			Analytics.sessionStart(carrier);
			publish(STARTED, carrier, leg);
		} catch (Throwable t) {
			dropped(STARTED, t);
		}
	}

	/// The call was answered — a `200 OK`. Also records that it got that far, so the eventual
	/// teardown knows whether it is completing a call or abandoning one.
	static void answered(SipServletMessage carrier, String leg) {
		markAnswered(sessionOf(carrier));
		fact(ANSWERED, carrier, leg);
	}

	/// Record that this call reached a `200 OK`, which is what [#terminalName] reads later to tell a
	/// completed call from an abandoned one.
	static void markAnswered(SipApplicationSession app) {
		if (app != null) {
			app.setAttribute(ANSWERED_FLAG, Boolean.TRUE);
		}
	}

	/// One fact, no lifecycle bookkeeping.
	static void fact(String name, SipServletMessage carrier, String leg) {
		try {
			publish(name, carrier, leg);
		} catch (Throwable t) {
			dropped(name, t);
		}
	}

	/// The call is over: publish `callCompleted` if it was ever answered and `callAbandoned` if it
	/// was not, then stop the session. At most once per call.
	static void hungUp(SipServletMessage carrier, String leg) {
		terminal(terminalName(sessionOf(carrier)), carrier, leg);
	}

	/// Whether a call that is ending now completed or was abandoned.
	///
	/// The distinction is whether it was ever answered, not which message is tearing it down. A
	/// browser that hangs up while the far end is still ringing sends the same `call.hangup` as one
	/// that hangs up an hour in, and outbound a CANCEL can lose the race with a `200 OK` and become
	/// a BYE — so the message in hand is not evidence. What was answered is.
	static String terminalName(SipApplicationSession app) {
		return (app != null && Boolean.TRUE.equals(app.getAttribute(ANSWERED_FLAG))) ? COMPLETED : ABANDONED;
	}

	/// Take ownership of ending this call, once. False means somebody else already did.
	static boolean claimTerminal(SipApplicationSession app) {
		if (app == null || Boolean.TRUE.equals(app.getAttribute(ENDED))) {
			return false;
		}
		app.setAttribute(ENDED, Boolean.TRUE);
		return true;
	}

	/// The call failed or was refused before it could be answered.
	static void declined(SipServletMessage carrier, String leg) {
		terminal(DECLINED, carrier, leg);
	}

	/// Publish a terminal fact and its session stop, once. Later callers are silent.
	static void terminal(String name, SipServletMessage carrier, String leg) {
		if (!claimTerminal(sessionOf(carrier))) {
			return;
		}
		try {
			publish(name, carrier, leg);
			Analytics.sessionStop(carrier);
		} catch (Throwable t) {
			dropped(name, t);
		}
	}

	// ---- plumbing ---------------------------------------------------------------------------------

	/// `createEvent` hands back the event *and* stashes it on the message, which is what `sendEvent`
	/// reads back — so both calls must see the same message object. A null return means this
	/// application is not collecting, and there is nothing to send.
	private static void publish(String name, SipServletMessage carrier, String leg) {
		if (carrier == null) {
			return;
		}
		AnalyticsEvent event = SettingsManager.createEvent(name, carrier);
		if (event == null) {
			return;
		}
		if (leg != null) {
			event.addAttribute(LEG, leg);
		}
		SettingsManager.sendEvent(carrier);
	}

	private static SipApplicationSession sessionOf(SipServletMessage carrier) {
		try {
			return carrier == null ? null : carrier.getApplicationSession();
		} catch (Throwable t) {
			// The session was invalidated out from under a teardown path.
			return null;
		}
	}

	private static void dropped(String name, Throwable t) {
		org.vorpal.blade.framework.v2.logging.Logger logger = Callflow.getSipLogger();
		if (logger != null) {
			logger.warning("webrtc: analytics event " + name + " DROPPED: " + t);
		}
	}
}
