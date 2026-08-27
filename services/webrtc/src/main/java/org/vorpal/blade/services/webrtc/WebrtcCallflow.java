package org.vorpal.blade.services.webrtc;

import java.nio.charset.StandardCharsets;

import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v3.media.MediaCallflow;

/// What both directions of a browser call do the same way.
///
/// A call is either [InboundToBrowser] or [OutboundFromBrowser] depending on who dialled, and up to
/// the moment it is answered the two have almost nothing in common. After that they are the same
/// call: one browser on a WebSocket, one dialog on the network, and the same two things can happen
/// to it — the browser presses a key, or the far end re-offers. Handling those twice, once per
/// direction, is how the two copies drift.
public abstract class WebrtcCallflow extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	protected static final String SDP_TYPE = "application/sdp";

	/// Advertised tone length on an out-of-band digit. Nothing plays it locally — the far end decides
	/// what to do with the duration — so this only has to be a plausible keypress.
	protected static final int DTMF_DURATION_MS = 250;

	// ---- DTMF ---------------------------------------------------------------------------------

	/// Forward every digit the browser presses to the far end for the rest of the call, as a SIP
	/// `INFO`.
	///
	/// **Repeating, not one-shot.** Digits recur — a caller keying an account number sends a dozen —
	/// and re-arming after each would leave a window in which one is dropped. [BrowserSignals] names
	/// this exact case.
	///
	/// Signaling-plane by necessity: no JSR-309 driver behind this framework implements tone
	/// generation, so there is no media-plane alternative. That is also why it works identically on
	/// both media paths — a relayed call has no media session to inject into and never will, its
	/// audio being encrypted end to end, and the INFO rides the dialog either way.
	protected void expectDtmf(SipApplicationSession app, SipSession dialog) {
		BrowserSignals.expectRepeating(app, SignalProtocol.CALL_DTMF, event -> {
			String digit = SignalProtocol.field(event, "digit");
			if (digit != null) {
				sendInfoDtmf(dialog, digit, DTMF_DURATION_MS);
			}
		});
	}

	// ---- re-INVITE ----------------------------------------------------------------------------

	/// Handle a re-INVITE for the rest of the call.
	///
	/// Without this a re-offer found no callback and no callflow — `WebrtcServlet.chooseCallflow`
	/// answers only *initial* INVITEs — and `AsyncSipServlet` replied `501`. A phone putting a
	/// browser on hold got an error, and so would the re-INVITE that moves an established relayed
	/// call onto a media server.
	///
	/// Re-armed after each one, because `expectRequest` is one-shot and a call is re-offered
	/// repeatedly in normal use: hold, unhold, a session refresh, a codec change.
	///
	/// `networkLeg` is null on the pass-through path, where there is no media server and the browser
	/// owns the SDP; non-null on the anchored path, where the media server answers and the browser
	/// need never hear about it.
	protected void expectReoffer(SipApplicationSession app, SipSession dialog, String aor, String callId,
			NetworkConnection networkLeg) {
		expectRequest(dialog, "INVITE", reinvite -> onReoffer(reinvite, app, aor, callId, networkLeg));
	}

	private void onReoffer(SipServletRequest reinvite, SipApplicationSession app, String aor, String callId,
			NetworkConnection networkLeg) throws Exception {

		expectReoffer(app, reinvite.getSession(), aor, callId, networkLeg);
		byte[] reoffer = rawContent(reinvite);

		if (networkLeg != null) {
			// Anchored: this belongs to the network dialog alone. The browser's dialog is untouched, so it
			// is not told and does not re-key — the point of anchoring is that the two negotiations
			// are independent of each other.
			if (reoffer == null) {
				answerWithLateMedia(reinvite, networkLeg, negotiated -> {
					// Nothing further; the dialog is as the media server just set it.
				});
			} else {
				offer(networkLeg, reoffer, answerEvent -> {
					SipServletResponse ok = reinvite.createResponse(200);
					ok.setContent(answerEvent.getMediaServerSdp(), SDP_TYPE);
					sendResponse(ok);
				});
			}
			return;
		}

		// Pass-through: the browser owns both halves of this negotiation, so the offer goes to it and
		// its answer is the answer. This is the path a media-server escalation from the far side
		// arrives on, and it needs no escalation-specific code — a re-INVITE carrying new SDP is
		// handled exactly the way the original INVITE was.
		if (reoffer == null) {
			// A re-INVITE with no SDP asks *us* to offer, and on this path there is nothing to offer
			// with: no media server, and a browser cannot be made to produce an offer on demand.
			sipLogger.warning(reinvite, "webrtc: re-INVITE with no SDP on a pass-through call; "
					+ "nothing here can generate an offer without a media server");
			sendResponse(reinvite.createResponse(488, "Not Acceptable Here"));
			return;
		}

		BrowserSignals.expect(app, SignalProtocol.CALL_ANSWER, answer -> {
			String browserAnswer = SignalProtocol.field(answer, "sdp");
			if (browserAnswer == null) {
				sendResponse(reinvite.createResponse(488, "Not Acceptable Here"));
				return;
			}
			SipServletResponse ok = reinvite.createResponse(200);
			ok.setContent(browserAnswer.getBytes(StandardCharsets.UTF_8), SDP_TYPE);
			sendResponse(ok);
		});
		BrowserRegistry.deliver(aor,
				SignalProtocol.sdp(SignalProtocol.CALL_UPDATE, callId, new String(reoffer, StandardCharsets.UTF_8)));
	}
}
