package org.vorpal.blade.services.webrtc;

import java.nio.charset.StandardCharsets;

import org.vorpal.blade.framework.v3.events.CloudEvent;

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

	// ---- events from the far side ---------------------------------------------------------------

	/// The content type of an event the far side sends the browser: one CloudEvent, structured mode.
	protected static final String EVENT_TYPE = "application/cloudevents+json";

	/// The RFC 6086 Info Package these events travel as. The gateway advertises it with `Recv-Info`
	/// on each call it establishes, and a sender names it in `Info-Package` on each `INFO`.
	public static final String INFO_PACKAGE = "blade-event";

	/// Say this dialog accepts [#INFO_PACKAGE] events (RFC 6086 `Recv-Info`), on the INVITE or the
	/// 200 that establishes it.
	protected static void advertiseEvents(javax.servlet.sip.SipServletMessage message) {
		try {
			message.setHeader("Recv-Info", INFO_PACKAGE);
		} catch (Exception e) {
			// A container that refuses the header still relays the event; the package is a courtesy.
		}
	}

	/// The event types the far side may send through. Only an application's own namespace, never
	/// this protocol's: a far side that could send `call.ended` or `call.update` could hang up or
	/// renegotiate the browser's call from outside it.
	protected static final String FAR_SIDE_PREFIX = "meeting.";

	/// Relay events the far side sends in the dialog, as a SIP `INFO` whose body is one CloudEvent,
	/// to the browser for the rest of the call.
	///
	/// This is how an application that is not a browser speaks to one mid-call: a meeting's captions,
	/// its roster, which track carries whom. The event arrives with the application's `subject`
	/// replaced by this call's id, so the browser files it under the call it belongs to. Re-armed
	/// after each, like [#expectReoffer]. An `INFO` that is not an event, or names a type outside
	/// [#FAR_SIDE_PREFIX], is refused with `415` and goes no further.
	protected void expectFarSideEvents(SipSession dialog, String aor, String callId) {
		expectRequest(dialog, "INFO", info -> onFarSideEvent(info, aor, callId));
	}

	private void onFarSideEvent(SipServletRequest info, String aor, String callId) throws Exception {
		expectFarSideEvents(info.getSession(), aor, callId);
		CloudEvent event = farSideEvent(info.getHeader("Info-Package"), info.getContentType(), rawContent(info), callId);
		if (event == null) {
			sendResponse(info.createResponse(415, "Unsupported Media Type"));
			return;
		}
		BrowserRegistry.deliver(aor, event);
		sendResponse(info.createResponse(200));
	}

	/// The event an `INFO` from the far side carries, filed under `callId`, or null when it is not an
	/// event this relay passes on: another Info Package, not [#EVENT_TYPE], not a CloudEvent, or a
	/// type outside [#FAR_SIDE_PREFIX]. An `INFO` with no `Info-Package` is taken on its content type,
	/// as RFC 6086 allows for legacy use.
	static CloudEvent farSideEvent(String infoPackage, String contentType, byte[] body, String callId) {
		if (infoPackage != null && !INFO_PACKAGE.equalsIgnoreCase(infoPackage.trim())) {
			return null; // another package's INFO: not ours to relay
		}
		if (contentType == null || body == null || !contentType.toLowerCase().startsWith(EVENT_TYPE)) {
			return null;
		}
		CloudEvent event;
		try {
			event = CloudEvent.fromJson(new String(body, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return null;
		}
		if (event == null || event.getType() == null || !event.getType().startsWith(FAR_SIDE_PREFIX)) {
			return null;
		}
		return CloudEvent.create(event.getType(), event.getSource(), callId, event.getData());
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
