package org.vorpal.blade.framework.v2.keepalive;

import java.io.IOException;
import java.io.Serializable;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/keepalive_reinvite.png
title Keep-Alive: each dialog is refreshed on its own transaction
hide footbox
participant Alice as alice
participant KeepAlive as blade
participant Bob as bob

alice <-> bob : RTP

== refresh Alice (offer = Bob's cached SDP) ==
alice <-  blade : INVITE (Bob SDP)
alice --> blade : 200 OK (Alice SDP)
alice <-  blade : ACK

== refresh Bob (offer = Alice's cached SDP), independently ==
          blade ->  bob : INVITE (Alice SDP)
          blade <-- bob : 200 OK (Bob SDP)
          blade ->  bob : ACK

@enduml
*/

/// Implements SIP session keep-alive by refreshing both call dialogs.
///
/// Each dialog is refreshed on its own re-INVITE transaction, independently of
/// the other. The offer is the media the peer leg already advertised, cached
/// per session under
/// [Callflow#LAST_SDP][org.vorpal.blade.framework.v2.callflow.Callflow#LAST_SDP]
/// by AsyncSipServlet as messages flow. Re-offering the already-negotiated SDP
/// is a no-op at the endpoint (nothing changed), so it refreshes the RFC 4028
/// session timer and any intermediate NAT/firewall state without disturbing
/// media.
///
/// Independence is the point. The earlier design chained one offerless
/// re-INVITE through both dialogs (offerless INVITE to Alice, forward her fresh
/// offer to Bob, ACK Alice only once Bob answered). A non-2xx from Bob then left
/// Alice's `200 OK` unacknowledged, and per RFC 3261 Alice tears the dialog down
/// after retransmitting the 2xx, killing the very call keep-alive exists to
/// preserve. Refreshing each leg with its peer's cached SDP removes the cross-leg
/// dependency: a failure on one leg cannot orphan the other.
///
/// **Signaling-relay assumption.** The cached SDP is what each endpoint
/// advertised as its own media, so re-offering the peer's copy is a no-op only
/// when BLADE relays media end to end (each endpoint's remote *is* the peer). A
/// media-anchoring deployment (a media server between the legs) points each
/// endpoint at the anchor, not the peer, and must drive keep-alive from its own
/// media state instead of this generic relay refresh.
///
/// @see SessionKeepAlive.Callback
public class KeepAlive extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	/// Handles the keep-alive callback by refreshing both call dialogs, each on
	/// its own transaction.
	///
	/// @param sipSession the SIP session that triggered the keep-alive
	@Override
	public void handle(SipSession sipSession) {
		if (sipSession == null) {
			return;
		}

		SipSession linkedSession = getLinkedSession(sipSession);
		if (linkedSession == null) {
			return;
		}

		refreshLeg(sipSession, linkedSession);
		refreshLeg(linkedSession, sipSession);
	}

	/// Refresh one dialog by re-offering the media its peer already advertised.
	/// The re-INVITE carries the peer's cached SDP (from
	/// [Callflow#LAST_SDP][org.vorpal.blade.framework.v2.callflow.Callflow#LAST_SDP]);
	/// the endpoint answers in its 2xx and BLADE ACKs. Nothing changed, so the
	/// endpoint keeps its media as-is. A non-2xx is auto-ACKed by the container
	/// and leaves the dialog untouched (media was never renegotiated), so there
	/// is nothing to unwind and the peer leg is unaffected.
	///
	/// When no cached peer SDP is available, the leg is skipped for this cycle
	/// rather than fall back to an offerless re-INVITE, which would solicit an
	/// offer BLADE has no answer for and reintroduce the orphaned-ACK failure.
	///
	/// @param target the dialog to refresh
	/// @param peer   the linked dialog whose advertised SDP is re-offered
	private void refreshLeg(SipSession target, SipSession peer) {
		try {
			if (target == null || !target.isValid()) {
				return;
			}

			Object offer = (peer != null && peer.isValid()) ? peer.getAttribute(LAST_SDP) : null;
			if (offer == null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(target, "KeepAlive.refreshLeg - no cached peer SDP; skipping this leg this cycle");
				}
				return;
			}

			sendRefresh(target, offer, null);
		} catch (Exception ex) {
			sipLogger.logStackTrace(target, ex);
		}
	}

	/// Send one refresh re-INVITE on `target` offering `offer`, and ACK on 2xx.
	///
	/// On 422 Session Interval Too Small (RFC 4028) the refresh retries once with
	/// Session-Expires raised to the Min-SE the 422 carried. A 422 is only
	/// reachable if the far end's Min-SE now exceeds the interval it already
	/// accepted at call setup; the single-retry guard (`forceMinSE == null` marks
	/// the first attempt) stops a misbehaving peer from looping. Any other non-2xx
	/// is auto-ACKed by the container and leaves the dialog untouched.
	///
	/// @param target     the dialog to refresh
	/// @param offer       the SDP body to re-offer (application/sdp)
	/// @param forceMinSE the Min-SE / Session-Expires to force on this attempt,
	///        or null on the first attempt (let the negotiated interval stand)
	private void sendRefresh(SipSession target, Object offer, String forceMinSE)
			throws ServletException, IOException {

		SipServletRequest invite = target.createRequest(INVITE);
		invite.setContent(offer, APPLICATION_SDP);
		if (forceMinSE != null) {
			invite.setHeader(SESSION_EXPIRES, forceMinSE);
			invite.setHeader(MIN_SE, forceMinSE);
		}

		boolean firstAttempt = (forceMinSE == null);

		sendRequest(invite, (response) -> {
			if (provisional(response)) {
				return;
			}
			if (successful(response)) {
				// Answer arrived in the 2xx; the ACK carries no body.
				sendRequest(response.createAck());
				return;
			}
			if (firstAttempt && response.getStatus() == 422) {
				String peerMinSE = response.getHeader(MIN_SE);
				if (peerMinSE != null) {
					sendRefresh(target, offer, peerMinSE);
				}
			}
		});
	}

	/// Last-chance liveness probe, driven by the SipApplicationSession expiry
	/// listener (`AsyncSipServlet.sessionExpired`). Re-INVITEs both dialogs and
	/// keeps the session alive — extends it to `fullMinutes` — only if BOTH
	/// endpoints answer 2xx. If either leg is dead (non-2xx, or no answer before
	/// the grace window lapses) the call is torn down and allowed to expire.
	///
	/// This lets a call that is actually still up survive a BLADE bookkeeping
	/// timeout — the case where an external element keeps the media alive and the
	/// operator has turned BLADE keep-alive off — while a genuinely dead call
	/// still dies. The caller must already have extended the appSession by a grace
	/// window so it survives the probe round-trip.
	///
	/// @param first       one dialog of the call; its peer is found via getLinkedSession
	/// @param fullMinutes the expiration to set if the probe confirms both legs alive
	public void probeAndConfirm(SipSession first, int fullMinutes) {
		if (first == null || !first.isValid()) {
			return;
		}
		SipSession second = getLinkedSession(first);
		if (second == null) {
			return;
		}

		// Offer each endpoint the media its peer already advertised (see refreshLeg).
		Object offerToFirst = validSdp(second);
		Object offerToSecond = validSdp(first);
		if (offerToFirst == null || offerToSecond == null) {
			// No negotiated media to re-offer: cannot confirm the call safely, so
			// reap and let it expire rather than guess it is alive.
			new KeepAliveExpiry().handle(first);
			return;
		}

		Tally tally = new Tally();
		try {
			probeLeg(first, offerToFirst, tally, fullMinutes);
			probeLeg(second, offerToSecond, tally, fullMinutes);
		} catch (Exception ex) {
			sipLogger.logStackTrace(first, ex);
		}
	}

	private static Object validSdp(SipSession session) {
		return (session != null && session.isValid()) ? session.getAttribute(LAST_SDP) : null;
	}

	/// Probe one leg. ACK a 2xx immediately (never orphan it), then record the
	/// outcome. When both legs have reported: both alive extends the appSession to
	/// `fullMinutes`; any dead leg reaps the call (BYE both) and leaves the grace
	/// window to collect it. A leg that never answers leaves the tally short of
	/// both, so the grace window lapses and the session expires on its own.
	private void probeLeg(SipSession target, Object offer, Tally tally, int fullMinutes)
			throws ServletException, IOException {

		SipServletRequest invite = target.createRequest(INVITE);
		invite.setContent(offer, APPLICATION_SDP);

		sendRequest(invite, (response) -> {
			if (provisional(response)) {
				return;
			}

			boolean alive = successful(response);
			if (alive) {
				sendRequest(response.createAck());
			} else {
				tally.failed = true;
			}

			// Container serializes response callbacks per appSession, so this
			// countdown needs no synchronization.
			if (--tally.pending == 0) {
				if (tally.failed) {
					new KeepAliveExpiry().handle(response.getSession());
				} else {
					SipApplicationSession appSession = response.getApplicationSession();
					if (appSession != null && appSession.isValid()) {
						appSession.setExpires(fullMinutes);
					}
				}
			}
		});
	}

	/// Two-leg join state for [#probeAndConfirm]. Serializable so it may be
	/// captured by the serializable response callbacks that ride the appSession.
	private static final class Tally implements Serializable {
		private static final long serialVersionUID = 1L;
		int pending = 2;
		boolean failed = false;
	}
}
