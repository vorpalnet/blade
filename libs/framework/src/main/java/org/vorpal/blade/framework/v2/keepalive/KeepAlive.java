package org.vorpal.blade.framework.v2.keepalive;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/keepalive_reinvite.png
title Keep-Alive: one offerless re-INVITE, chained through the call
hide footbox
participant Bob as bob
participant KeepAlive as blade
participant Alice as alice

bob   <-  blade          : INVITE (no SDP)
bob   --> blade          : 200 OK (Bob's SDP, offer)
          blade ->  alice : INVITE (Bob's SDP)
          blade <-- alice : 200 OK (Alice's SDP, answer)
bob   <-  blade          : ACK (Alice's SDP)
          blade ->  alice : ACK

@enduml
*/

/// Session keep-alive: refreshes both dialogs of a call with one offerless
/// re-INVITE, chained through the call.
///
/// The re-INVITE goes to one leg with no SDP. That endpoint offers its current
/// session description in the 2xx; the offer is relayed to the other leg as a
/// re-INVITE; the answer comes back and is carried to the first leg in its ACK.
/// Each endpoint supplies its own SDP, and nothing changed, so each sees an
/// unchanged session (RFC 3264 section 8: an unchanged version "is effectively
/// a no-op"). BLADE keeps no copy of anyone's SDP: the endpoints hold the only
/// authoritative one. Downstream BLADE applications relay the re-INVITE like any
/// other, so one chain refreshes every dialog in a chain of applications, and an
/// application that anchors media re-anchors it on the way through.
///
/// **The first leg's 2xx must be acknowledged within 64*T1 (32 s)**, when the
/// endpoint stops retransmitting it and ends the dialog. So the relay leg is
/// retried after a 491 only while there is time, and any other failure there is
/// answered the way RFC 3261 section 13.2.2.4 prescribes for an offer the UAC
/// cannot use: "the UAC core MUST generate a valid answer in the ACK and then send
/// a BYE immediately". The answer rejects every stream (port 0, RFC 3264 section
/// 6), which is built from the offer alone.
///
/// Outcomes are read by the RFC 5057 table: only responses that destroy the
/// dialog or its invite usage, and a timeout, mean an endpoint has lost the call.
/// A 491 means a transaction was in progress and is retried; any other refusal
/// means the endpoint is alive and would not refresh this way, and the chain is
/// tried once from the other leg.
///
/// The same chain is the expiration probe ([#probeAndConfirm]): a call that
/// completes it is alive and keeps its lifetime; one whose endpoint has lost the
/// call is hung up.
///
/// @see SessionKeepAlive.Callback
public class KeepAlive extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	/// Retries after a 491, per leg. Three fit inside the 32 s the first leg's 2xx
	/// waits for its ACK.
	static final int MAX_RETRIES = 3;

	/// How long after the first leg's 2xx its ACK may still be sent: 64*T1 (32 s)
	/// less a margin, since the endpoint ends the dialog once it stops
	/// retransmitting.
	static final long ACK_WINDOW_MS = 30_000;

	/// The retry delay after a 491 (RFC 3261 section 14.1): a random value between
	/// 2.1 and 4 seconds, in units of 10 ms. The RFC gives this range to the owner of
	/// the Call-ID and 0 to 2 seconds to the other side; BLADE cannot tell which it
	/// is on every leg, and the longer range never collides with a peer using the
	/// shorter one.
	static final long RETRY_MIN_MS = 2_100;
	static final long RETRY_MAX_MS = 4_000;

	/// For the expiration probe, the appSession lifetime to restore when the chain
	/// completes; null for a periodic refresh.
	private final Integer confirmMinutes;

	/// A periodic refresh, as the session keep-alive callback.
	public KeepAlive() {
		this.confirmMinutes = null;
	}

	private KeepAlive(int confirmMinutes) {
		this.confirmMinutes = confirmMinutes;
	}

	/// The container's refresh callback: refresh this dialog and the one linked
	/// to it.
	///
	/// @param sipSession the dialog whose keep-alive timer fired
	@Override
	public void handle(SipSession sipSession) {
		if (sipSession == null || !sipSession.isValid()) {
			return;
		}
		SipSession linkedSession = getLinkedSession(sipSession);
		if (linkedSession == null || !linkedSession.isValid()) {
			return;
		}
		offer(sipSession, linkedSession, 0, false, null);
	}

	/// Last-chance liveness probe, run when the SipApplicationSession expires
	/// (`AsyncSipServlet.sessionExpired`): the refresh chain, once. A call that
	/// completes it, or whose endpoints are alive but decline to refresh, gets
	/// `fullMinutes` back; a call an endpoint has lost is hung up. The caller has
	/// already extended the appSession by a grace window so it survives the round
	/// trip; a leg that never answers lets that window lapse.
	///
	/// @param first       one dialog of the call; its peer is found via getLinkedSession
	/// @param fullMinutes the expiration to restore if the call is alive
	public void probeAndConfirm(SipSession first, int fullMinutes) {
		if (first == null || !first.isValid()) {
			return;
		}
		SipSession second = getLinkedSession(first);
		if (second == null || !second.isValid()) {
			return;
		}
		new KeepAlive(fullMinutes).offer(first, second, 0, false, null);
	}

	/// Send `from` an offerless re-INVITE; its 2xx carries the offer relayed to `to`.
	///
	/// @param reversed true when this is the second attempt, from the other leg
	/// @param minSE    the Session-Expires and Min-SE to force after a 422, or null
	void offer(SipSession from, SipSession to, int retries, boolean reversed, String minSE) {
		try {
			if (from == null || to == null || !from.isValid() || !to.isValid()) {
				return; // the call ended while a retry waited
			}
			SipServletRequest invite = from.createRequest(INVITE);
			forceInterval(invite, minSE);

			String fromId = from.getId();
			String toId = to.getId();
			sendRequest(invite, (fromAnswer) -> {
				if (provisional(fromAnswer)) {
					return;
				}
				int status = fromAnswer.getStatus();
				if (successful(fromAnswer)) {
					relay(fromAnswer, to, System.currentTimeMillis(), 0, reversed, null);
				} else if (status == 491 && retries < MAX_RETRIES) {
					later(from, (timer) -> {
						SipApplicationSession app = timer.getApplicationSession();
						offer(app.getSipSession(fromId), app.getSipSession(toId), retries + 1, reversed, minSE);
					});
				} else if (status == 422 && minSE == null && fromAnswer.getHeader(MIN_SE) != null) {
					offer(from, to, retries, reversed, fromAnswer.getHeader(MIN_SE));
				} else if (callLost(status)) {
					lost(from, status);
				} else if (!reversed) {
					offer(to, from, 0, true, null);
				} else {
					declined(from, status);
				}
			});
		} catch (Exception ex) {
			sipLogger.logStackTrace(from, ex);
		}
	}

	/// Relay the first leg's offer to `to`, and carry the answer back in the first
	/// leg's ACK.
	///
	/// @param answeredAt when the first leg's 2xx arrived; its ACK is due within
	///        [#ACK_WINDOW_MS]
	void relay(SipServletResponse fromAnswer, SipSession to, long answeredAt, int retries, boolean reversed,
			String minSE) {
		SipSession from = fromAnswer.getSession();
		try {
			if (to == null || !to.isValid()) {
				answerAndEnd(fromAnswer, 481); // the other leg ended while a retry waited
				return;
			}
			if (fromAnswer.getContentType() == null) {
				// No offer in the 2xx, against RFC 3261 section 13.2.1; nothing to relay and
				// nothing to answer. The first leg is refreshed; refresh the other on its own.
				sendRequest(fromAnswer.createAck());
				if (!reversed) {
					offer(to, from, 0, true, null);
				} else {
					succeeded(from);
				}
				return;
			}

			SipServletRequest invite = to.createRequest(INVITE);
			copyContent(fromAnswer, invite);
			forceInterval(invite, minSE);

			sendRequest(invite, (toAnswer) -> {
				if (provisional(toAnswer)) {
					return;
				}
				int status = toAnswer.getStatus();
				if (successful(toAnswer)) {
					sendRequest(copyContent(toAnswer, fromAnswer.createAck()));
					sendRequest(toAnswer.createAck());
					succeeded(from);
				} else if (status == 491 && retries < MAX_RETRIES
						&& System.currentTimeMillis() - answeredAt + RETRY_MAX_MS < ACK_WINDOW_MS) {
					String toId = to.getId();
					later(to, (timer) -> relay(fromAnswer, timer.getApplicationSession().getSipSession(toId),
							answeredAt, retries + 1, reversed, minSE));
				} else if (status == 422 && minSE == null && toAnswer.getHeader(MIN_SE) != null) {
					relay(fromAnswer, to, answeredAt, retries, reversed, toAnswer.getHeader(MIN_SE));
				} else {
					answerAndEnd(fromAnswer, status);
				}
			});
		} catch (Exception ex) {
			sipLogger.logStackTrace(to, ex);
			answerAndEnd(fromAnswer, 500);
		}
	}

	/// The first leg's offer cannot be answered from the other leg: acknowledge it
	/// with a valid answer that rejects every stream, then hang up (RFC 3261
	/// section 13.2.2.4).
	private void answerAndEnd(SipServletResponse fromAnswer, int status) {
		sipLogger.warning(fromAnswer, "KeepAlive - the other leg answered " + status
				+ " to the relayed offer; acknowledging with every stream rejected and ending the call");
		try {
			SipServletRequest ack = fromAnswer.createAck();
			String contentType = fromAnswer.getContentType();
			if (contentType != null && contentType.toLowerCase().startsWith(APPLICATION_SDP)) {
				ack.setContent(rejectAll(fromAnswer.getContent()), APPLICATION_SDP);
			}
			sendRequest(ack);
		} catch (Exception ex) {
			sipLogger.logStackTrace(fromAnswer, ex);
		}
		new KeepAliveExpiry().handle(fromAnswer.getSession());
	}

	/// An endpoint answered with a response that ends its dialog or invite usage
	/// (RFC 5057), or the request timed out: it has lost the call. Hang up both legs.
	private void lost(SipSession from, int status) {
		sipLogger.warning(from, "KeepAlive - re-INVITE answered " + status + "; the endpoint has lost the call, ending it");
		new KeepAliveExpiry().handle(from);
	}

	/// Both legs answered, but neither would take an offerless re-INVITE. The
	/// endpoints are alive; a probe keeps the call, and a refresh leaves it to the
	/// session timer.
	private void declined(SipSession from, int status) {
		sipLogger.warning(from, "KeepAlive - both legs declined an offerless re-INVITE (last " + status + ")");
		succeeded(from);
	}

	/// The chain completed. A probe restores the appSession lifetime.
	private void succeeded(SipSession session) {
		if (confirmMinutes == null) {
			return;
		}
		SipApplicationSession appSession = session.getApplicationSession();
		if (appSession != null && appSession.isValid()) {
			appSession.setExpires(confirmMinutes);
			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(appSession, "KeepAlive - probe confirmed the call; expires in " + confirmMinutes + " minutes");
			}
		}
	}

	/// After a 422 (RFC 4028), retry once with the Session-Expires raised to the
	/// peer's Min-SE.
	private static void forceInterval(SipServletRequest invite, String minSE) {
		if (minSE != null) {
			invite.setHeader(SESSION_EXPIRES, minSE);
			invite.setHeader(MIN_SE, minSE);
		}
	}

	/// Run `callback` after the RFC 3261 section 14.1 retry delay.
	private static void later(SipSession session, Callback<ServletTimer> callback) {
		long delay = RETRY_MIN_MS + 10 * ThreadLocalRandom.current().nextLong((RETRY_MAX_MS - RETRY_MIN_MS) / 10 + 1);
		startTimer(session.getApplicationSession(), delay, false, callback);
	}

	/// Whether a final response to a re-INVITE means the endpoint no longer has the
	/// call: a response that destroys the dialog or its usage (RFC 5057 section 5.1:
	/// 404, 405, 410, 416, 480, 481, 482, 483, 484, 485, 489, 501, 502, 604), or a
	/// timeout (408; RFC 3261 section 14.1 ends the dialog on a 481, a 408 or no
	/// response).
	static boolean callLost(int status) {
		switch (status) {
		case 404:
		case 405:
		case 408:
		case 410:
		case 416:
		case 480:
		case 481:
		case 482:
		case 483:
		case 484:
		case 485:
		case 489:
		case 501:
		case 502:
		case 604:
			return true;
		default:
			return false;
		}
	}

	/// An answer to `offer` that rejects every stream: the offer with each media
	/// line's port set to zero (RFC 3264 section 6).
	static String rejectAll(Object offer) {
		String sdp = (offer instanceof byte[]) ? new String((byte[]) offer, StandardCharsets.UTF_8) : String.valueOf(offer);
		StringBuilder answer = new StringBuilder();
		for (String line : sdp.split("\r?\n")) {
			if (line.startsWith("m=")) {
				String[] media = line.split(" ", 3);
				if (media.length == 3) {
					line = media[0] + " 0 " + media[2];
				}
			}
			answer.append(line).append("\r\n");
		}
		return answer.toString();
	}

}
