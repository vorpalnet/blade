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
/// and another dialog of this gateway — and is passed to the browser untouched, so the two endpoints
/// key to each other and media never comes near this server. An offer without one came from a phone
/// or a trunk, which a browser cannot talk to directly, so the media server terminates WebRTC on
/// the browser side and plain RTP on the network side, and bridging the dialogs makes the call.
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
///
///   Having the browser offer instead, so that a deployment with no media server could take these
///   calls, does not work and is not worth attempting again. Late media is a third-party-call-control
///   pattern that browsers never originate, so the caller is a phone or a trunk; the answer coming
///   back in the `ACK` is plain RTP with no `a=fingerprint`, and a browser will not complete media
///   against it — the same dead end [OutboundFromBrowser] fails fast on when a pass-through far end
///   answers without DTLS. The media server is not a convenience on this path; it is the only party
///   present that can speak to both ends.
public class InboundToBrowser extends WebrtcCallflow {
	private static final long serialVersionUID = 1L;

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
			// No media plane, or it refused. A browser cannot be reached from a phone without one —
			// and a late-media INVITE cannot be taken without one at all, since there is no offer to
			// pass through. Name which of the two it was; "media unavailable" sent an operator
			// looking for a driver problem when the answer was that this call shape requires one.
			sipLogger.severe(invite, "webrtc: " + (content == null
					? "late media requires a media server and none is installed"
					: "media unavailable for inbound call") + ": " + e.getMessage());
			refuse(invite);
		}
	}

	private void refuse(SipServletRequest invite) {
		try {
			SipServletResponse unavailable = invite.createResponse(503, "Service Unavailable");
			CallEvents.declined(unavailable, CallEvents.INBOUND);
			sendResponse(unavailable);
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

		// The call begins here rather than in either media path: this method is the shared prologue,
		// the one point both traverse exactly once. Published before the reachability check on
		// purpose, so a call refused for an absent browser still appears as started-then-declined
		// rather than never having happened.
		CallEvents.started(invite, CallEvents.INBOUND);

		if (!BrowserRegistry.isLocal(aor)) {
			// A stale binding. The registrar sent this call here because the contact named this
			// engine, so the browser was here — the socket has since gone and it has not yet
			// re-registered from wherever it reconnected. Until it does it is unreachable from
			// every engine, so 480 is the honest answer rather than a stopgap.
			sipLogger.warning(invite, "webrtc: " + aor + " is not connected to this node; rejecting");
			SipServletResponse unavailable = invite.createResponse(480, "Temporarily Unavailable");
			CallEvents.declined(unavailable, CallEvents.INBOUND);
			sendResponse(unavailable);
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
			SipServletResponse unavailable = invite.createResponse(480, "Temporarily Unavailable");
			CallEvents.declined(unavailable, CallEvents.INBOUND);
			sendResponse(unavailable);
			return;
		}
		sendResponse(invite.createResponse(180, "Ringing"));
		expectRequest(invite.getSession(), "BYE", bye -> onCallerHungUp(bye, app, aor, callId));
		expectRequest(invite.getSession(), "CANCEL", cancel -> onCallerCancelled(cancel, app, aor, callId));
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
		expectDtmf(app, invite.getSession());
		expectReoffer(app, invite.getSession(), aor, callId, null);

		SipServletResponse ok = invite.createResponse(200);
		ok.setContent(browserAnswer.getBytes(StandardCharsets.UTF_8), SDP_TYPE);
		established(ok, aor, callId);
		// Nothing is owed to the ACK here: the browser's answer went out in the 200 OK and the two
		// endpoints key DTLS to each other without this server.
		sendResponse(ok, ack -> connected(ack, aor, callId, true));
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
		expectRequest(invite.getSession(), "CANCEL", cancel -> onCallerCancelled(cancel, app, aor, callId));
	}

	/// Offer the browser dialog's SDP to the browser and wait for it to accept.
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
				SipServletResponse unavailable = invite.createResponse(480, "Temporarily Unavailable");
				CallEvents.declined(unavailable, CallEvents.INBOUND);
				sendResponse(unavailable);
				return;
			}
			sendResponse(invite.createResponse(180, "Ringing"));
		});
	}

	/// The browser accepted: apply its answer, bridge the dialogs, and complete the SIP side.
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
			expectDtmf(app, invite.getSession());
			expectReoffer(app, invite.getSession(), aor, callId, networkLeg);

			if (networkAnswer != null) {
				SipServletResponse ok = invite.createResponse(200);
				ok.setContent(networkAnswer, SDP_TYPE);
				established(ok, aor, callId);
				sendResponse(ok, ack -> connected(ack, aor, callId, true));
			} else {
				// Late media: the media server offers in the 200 OK, the caller answers in the ACK.
				// This is the one path where answered and connected are genuinely different moments,
				// and the only one where the ACK can arrive owing an answer it does not carry.
				answerWithLateMedia(invite, networkLeg,
						ok -> established(ok, aor, callId),
						ack -> connected(ack, aor, callId, rawContent(ack) != null));
			}
		});
	}

	/// Forward every digit the browser presses to the far end for the rest of the call.
	///
	/// **Repeating, not one-shot.** Digits recur — a caller keying an account number sends a dozen —
	/// and re-arming after each would leave a window in which one is dropped. [BrowserSignals] names
	/// this exact case.
	///
	/// Armed at answer rather than at ring, because an `INFO` needs a confirmed dialog. The same call
	/// works on both media paths: the digit rides the SIP dialog, so a relayed call whose media this
	/// server never sees carries DTMF just as well as an anchored one.
	/// The `200 OK` is on its way to the caller. Told to the browser and to the event bus, from the
	/// one place, so the two can never disagree about when a call was answered.
	private void established(SipServletResponse ok, String aor, String callId) {
		CallEvents.answered(ok, CallEvents.INBOUND);
		BrowserRegistry.deliver(aor,
				SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, callId, SignalProtocol.data()));
	}

	/// The `ACK` arrived and the handshake is complete.
	///
	/// `negotiated` is false only on the late-media path, and only when the ACK owed an answer and
	/// carried none — the media dialog then stays as the media server set it, which is a call that is
	/// up for signaling and may be silent for media. Every other path negotiated before the 200 OK
	/// went out, so there is nothing left for the ACK to settle.
	private void connected(SipServletRequest ack, String aor, String callId, boolean negotiated) {
		CallEvents.fact(CallEvents.CONNECTED, ack, CallEvents.INBOUND);
		BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_CONNECTED, callId,
				SignalProtocol.data().put("negotiated", negotiated)));
	}

	private void onBrowserDeclined(SipServletRequest invite, SipApplicationSession app) throws Exception {
		BrowserSignals.cancel(app, SignalProtocol.CALL_ANSWER);
		releaseMedia(app);
		SipServletResponse decline = invite.createResponse(603, "Decline");
		CallEvents.declined(decline, CallEvents.INBOUND);
		sendResponse(decline);
	}

	private void onBrowserHungUp(SipServletRequest invite, SipApplicationSession app) throws Exception {
		try {
			SipServletRequest bye = invite.getSession().createRequest("BYE");
			CallEvents.hungUp(bye, CallEvents.INBOUND);
			sendRequest(bye, response -> releaseMedia(app));
		} catch (IllegalStateException alreadyGone) {
			// The dialog was torn down from the network side first; nothing left to BYE.
			releaseMedia(app);
		}
	}

	/// The caller gave up while the browser was still ringing.
	///
	/// **Nothing is sent in reply.** The container answers a CANCEL with its own `200 OK` and kills
	/// the INVITE transaction with a `487`; that is why `Terminate` guards its one `sendResponse`
	/// down to BYE only. Everything left here is local cleanup: stop waiting on a browser that is
	/// about to stop ringing, tell it the call is gone, and let the media go.
	///
	/// Reached through an `expectRequest` expectation rather than a callflow, because
	/// [WebrtcServlet#chooseCallflow] answers only initial INVITEs. Without the expectation the
	/// CANCEL found no callback and no callflow, and `AsyncSipServlet` replied `501` — which left
	/// the browser ringing at a caller who had already hung up, the media session allocated, and no
	/// `callAbandoned` on the bus.
	private void onCallerCancelled(SipServletRequest cancel, SipApplicationSession app, String aor, String callId)
			throws Exception {
		CallEvents.hungUp(cancel, CallEvents.INBOUND);
		BrowserSignals.cancel(app, SignalProtocol.CALL_ANSWER);
		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId, "caller cancelled"));
		releaseMedia(app);
	}

	private void onCallerHungUp(SipServletRequest bye, SipApplicationSession app, String aor, String callId)
			throws Exception {
		// Completed or abandoned depending on whether this call was ever answered — the BYE
		// expectation is armed while the browser is still ringing, so a caller who gives up first
		// lands here too. CallEvents knows which, because established() recorded it.
		CallEvents.hungUp(bye, CallEvents.INBOUND);
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
