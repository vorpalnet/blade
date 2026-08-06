package org.vorpal.blade.services.webrtc;

import java.nio.charset.StandardCharsets;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.MediaConfigs;

/// A call arrives from the SIP network for a browser.
///
/// This is the class Oracle's WebRTC Session Controller made every deployment write for itself, as
/// a Groovy criterion keyed on `FROM_NET / INVITE / request` and maintained in an admin console.
/// Here it is compiled, shipped once, and reads top to bottom.
///
/// ## The offer itself says whether to anchor
///
/// The caller needed policy to pick a media path, because it could not know what would answer. This
/// side needs none: the INVITE that arrives *is* the evidence. An offer carrying a DTLS fingerprint
/// (`a=fingerprint:`) came from a WebRTC endpoint — the far browser, through the location service
/// and another leg of this gateway — and is passed to the browser untouched, so the two endpoints
/// key to each other and media never comes near this server. An offer without one came from a phone
/// or a trunk, which a browser cannot talk to directly, so the media server terminates WebRTC on
/// the browser side and plain RTP on the network side, and bridging the legs makes the call.
///
/// ## Both network-side offer directions (anchored path)
///
/// The browser is always offered to — it is the party being asked. The network side varies, and
/// handling both is the difference between working on a carrier trunk and working in a lab:
///
/// - **INVITE with SDP** — the media server answers it, and that answer goes in the `200 OK`.
/// - **INVITE with no SDP** — late media. The media server offers in the `200 OK` and the caller's
///   answer arrives in the `ACK`: [MediaCallflow#answerWithLateMedia]. This is the case the prior
///   art dropped; its published default script forwarded an empty message and engaged no media.
///   Late media cannot pass through — there is no offer to forward — so it always anchors.
public class InboundToBrowser extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	private static final String SDP_TYPE = "application/sdp";

	@Override
	public void process(SipServletRequest invite) {
		byte[] content = rawContent(invite);
		if (content != null && isWebrtcOffer(content)) {
			try {
				passThroughAndRing(invite, content);
			} catch (Exception e) {
				sipLogger.severe(invite, "webrtc: could not ring browser: " + e.getMessage());
				refuse(invite);
			}
			return;
		}
		try {
			anchorAndRing(invite);
		} catch (Exception e) {
			// No media plane, or it refused. A browser cannot be reached from the PSTN without one.
			sipLogger.severe(invite, "webrtc: media unavailable for inbound call: " + e.getMessage());
			refuse(invite);
		}
	}

	private void refuse(SipServletRequest invite) {
		try {
			sendResponse(invite.createResponse(503, "Service Unavailable"));
		} catch (Exception ignore) {
			// The caller is already gone; nothing left to tell.
		}
	}

	/// A WebRTC endpoint's offer: it carries a DTLS certificate fingerprint (RFC 8122), which no
	/// phone or trunk produces and every browser must.
	static boolean isWebrtcOffer(byte[] sdp) {
		return new String(sdp, StandardCharsets.UTF_8).contains("a=fingerprint:");
	}

	/// Bind this call to the browser it is for, or answer 480 and return null. Shared prologue of
	/// both media paths.
	///
	/// A registrar-forked INVITE arrives *targeted* — the container recognized the `encodeURI`
	/// parameters [BrowserRegistration] put in the registered contact and dispatched the request
	/// into the registration's own application session, which already knows its browser. That is
	/// the authoritative answer, and it has to be: the fork's Request-URI names this engine, not
	/// the browser's domain, so parsing it would yield `alice@172.16.32.129`. The Request-URI
	/// fallback remains for INVITEs addressed to the browser directly.
	private String claimBrowser(SipServletRequest invite, SipApplicationSession app) throws Exception {
		String aor = (String) app.getAttribute(BrowserSignals.BROWSER_AOR);
		if (aor == null) {
			aor = addressOf(invite);
		}

		// Index by the id the browser quotes back, so a WebSocket thread on any node can find this
		// call. Without it the browser's answer has nowhere to go.
		app.setAttribute(BrowserSignals.BROWSER_AOR, aor);

		if (!BrowserRegistry.isLocal(aor)) {
			// Not connected here. It may be on another engine, but cross-node delivery is not wired
			// up yet, so refuse honestly rather than ring a socket we do not hold.
			sipLogger.warning(invite, "webrtc: " + aor + " is not connected to this node; rejecting");
			sendResponse(invite.createResponse(480, "Temporarily Unavailable"));
			return null;
		}
		return aor;
	}

	// ---- pass-through -------------------------------------------------------------------------

	/// Ring the browser with the caller's own offer; its answer goes back in the `200 OK`
	/// untouched. No media objects exist on this path.
	private void passThroughAndRing(SipServletRequest invite, byte[] offer) throws Exception {
		SipApplicationSession app = invite.getApplicationSession();
		// The browser's handle is the application-session id, not the SIP Call-ID — that is what
		// SignalEndpoint can resolve with getApplicationSessionById.
		String callId = app.getId();
		String aor = claimBrowser(invite, app);
		if (aor == null) {
			return;
		}

		BrowserSignals.expect(app, SignalProtocol.CALL_ANSWER,
				answer -> onBrowserAnsweredPassThrough(invite, app, aor, callId, answer));
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, declined -> onBrowserDeclined(invite, app));

		boolean rang = BrowserRegistry.deliver(aor,
				SignalProtocol.event(SignalProtocol.CALL_INCOMING, callId,
						SignalProtocol.data().put("from", callerOf(invite))
								.put("sdp", new String(offer, StandardCharsets.UTF_8))));
		if (!rang) {
			// The socket died between routing and here.
			sendResponse(invite.createResponse(480, "Temporarily Unavailable"));
			return;
		}
		sendResponse(invite.createResponse(180, "Ringing"));
		expectRequest(invite.getSession(), "BYE", bye -> onCallerHungUp(bye, app, aor, callId));
	}

	/// The browser accepted a pass-through call: its answer is the SIP answer, verbatim.
	private void onBrowserAnsweredPassThrough(SipServletRequest invite, SipApplicationSession app, String aor,
			String callId, CloudEvent answerEvent) throws Exception {

		String browserAnswer = SignalProtocol.field(answerEvent, "sdp");
		if (browserAnswer == null) {
			BrowserRegistry.deliver(aor,
					SignalProtocol.reason(SignalProtocol.ERROR, callId, "call.answer requires an sdp"));
			return;
		}

		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, hangup -> onBrowserHungUp(invite, app));

		SipServletResponse ok = invite.createResponse(200);
		ok.setContent(browserAnswer.getBytes(StandardCharsets.UTF_8), SDP_TYPE);
		sendResponse(ok, ack -> established(aor, callId));
	}

	// ---- anchored -----------------------------------------------------------------------------

	private void anchorAndRing(SipServletRequest invite) throws Exception {
		SipApplicationSession app = invite.getApplicationSession();
		String callId = app.getId();
		String aor = claimBrowser(invite, app);
		if (aor == null) {
			return;
		}

		MediaSession media = createMediaSession(app);
		NetworkConnection browserLeg = media.createNetworkConnection(MediaConfigs.WEBRTC);
		NetworkConnection networkLeg = media.createNetworkConnection(MediaConfigs.RTP);

		if (isLateMedia(invite)) {
			// Ask the browser first; the caller is offered from the media server in the 200 OK.
			ringBrowser(invite, app, aor, callId, browserLeg, networkLeg, null);
		} else {
			offer(networkLeg, rawContent(invite), answerEvent ->
					ringBrowser(invite, app, aor, callId, browserLeg, networkLeg,
							answerEvent.getMediaServerSdp()));
		}

		expectRequest(invite.getSession(), "BYE", bye -> onCallerHungUp(bye, app, aor, callId));
	}

	/// Offer the browser leg's SDP to the browser and wait for it to accept.
	private void ringBrowser(SipServletRequest invite, SipApplicationSession app, String aor, String callId,
			NetworkConnection browserLeg, NetworkConnection networkLeg, byte[] networkAnswer)
			throws MsControlException {

		generateOffer(browserLeg, offerEvent -> {
			BrowserSignals.expect(app, SignalProtocol.CALL_ANSWER, answer ->
					onBrowserAnswered(invite, app, aor, callId, browserLeg, networkLeg, networkAnswer, answer));
			BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, declined -> onBrowserDeclined(invite, app));

			boolean rang = BrowserRegistry.deliver(aor,
					SignalProtocol.event(SignalProtocol.CALL_INCOMING, callId,
							SignalProtocol.data().put("from", callerOf(invite))
									.put("sdp", new String(offerEvent.getMediaServerSdp(), StandardCharsets.UTF_8))));
			if (!rang) {
				// The socket died between the check above and here.
				releaseMedia(app);
				sendResponse(invite.createResponse(480, "Temporarily Unavailable"));
				return;
			}
			sendResponse(invite.createResponse(180, "Ringing"));
		});
	}

	/// The browser accepted: apply its answer, bridge the legs, and complete the SIP side.
	private void onBrowserAnswered(SipServletRequest invite, SipApplicationSession app, String aor, String callId,
			NetworkConnection browserLeg, NetworkConnection networkLeg, byte[] networkAnswer,
			CloudEvent answerEvent) throws MsControlException {

		String browserAnswer = SignalProtocol.field(answerEvent, "sdp");
		if (browserAnswer == null) {
			BrowserRegistry.deliver(aor,
					SignalProtocol.reason(SignalProtocol.ERROR, callId, "call.answer requires an sdp"));
			return;
		}

		processAnswer(browserLeg, browserAnswer.getBytes(StandardCharsets.UTF_8), processed -> {
			join(browserLeg, Joinable.Direction.DUPLEX, networkLeg);
			BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
			BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, hangup -> onBrowserHungUp(invite, app));

			if (networkAnswer != null) {
				SipServletResponse ok = invite.createResponse(200);
				ok.setContent(networkAnswer, SDP_TYPE);
				sendResponse(ok, ack -> established(aor, callId));
			} else {
				// Late media: the media server offers in the 200 OK, the caller answers in the ACK.
				answerWithLateMedia(invite, networkLeg, ack -> established(aor, callId));
			}
		});
	}

	private void established(String aor, String callId) {
		BrowserRegistry.deliver(aor,
				SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, callId, SignalProtocol.data()));
	}

	private void onBrowserDeclined(SipServletRequest invite, SipApplicationSession app) throws Exception {
		BrowserSignals.cancel(app, SignalProtocol.CALL_ANSWER);
		releaseMedia(app);
		sendResponse(invite.createResponse(603, "Decline"));
	}

	private void onBrowserHungUp(SipServletRequest invite, SipApplicationSession app) throws Exception {
		try {
			sendRequest(invite.getSession().createRequest("BYE"), response -> releaseMedia(app));
		} catch (IllegalStateException alreadyGone) {
			// The dialog was torn down from the network side first; nothing left to BYE.
			releaseMedia(app);
		}
	}

	private void onCallerHungUp(SipServletRequest bye, SipApplicationSession app, String aor, String callId)
			throws Exception {
		sendResponse(bye.createResponse(200));
		BrowserSignals.cancel(app, SignalProtocol.CALL_ANSWER);
		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId, "caller hung up"));
		releaseMedia(app);
	}

	/// Free this call's media, if it had any. Hanging up must not fail because cleanup did.
	static void releaseMedia(SipApplicationSession app) {
		try {
			MediaSession media = reattach(app);
			if (media != null) {
				media.release();
			}
		} catch (MsControlException e) {
			// Already gone is the outcome we wanted.
		}
	}

	// ---- addressing ---------------------------------------------------------------------------

	/// The address this INVITE is for, as `user@host` — the form a browser claims with
	/// `session.connect`.
	static String addressOf(SipServletRequest invite) {
		URI uri = invite.getRequestURI();
		if (uri instanceof SipURI) {
			SipURI sip = (SipURI) uri;
			return sip.getUser() + "@" + sip.getHost();
		}
		return uri.toString();
	}

	/// Who is calling, for the browser to display.
	static String callerOf(SipServletRequest invite) {
		URI from = invite.getFrom().getURI();
		if (from instanceof SipURI) {
			SipURI sip = (SipURI) from;
			return sip.getUser() + "@" + sip.getHost();
		}
		return from.toString();
	}
}
