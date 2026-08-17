package org.vorpal.blade.services.webrtc;

import java.nio.charset.StandardCharsets;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.media.MediaConfigs;

/// A browser calls out — and *every* browser call goes out this way, including one whose far end is
/// another browser. Signaling always rides SIP: the INVITE goes through the App Router, past the
/// location service, visible to FSMAR and analytics like any other call on the network. There is no
/// WebSocket-only shortcut.
///
/// Started from a WebSocket thread rather than an inbound SIP request, so [#process] is never
/// called — [#start] is the entry point, the way `services/transfer` originates from a REST thread.
/// Everything after the first `sendRequest` is ordinary BLADE: continuations run on SIP threads
/// under the application-session lock.
///
/// ## What varies is the media, and only the media
///
/// [MediaMode] decides what SDP the INVITE carries:
///
/// - **Anchored** — two independent negotiations that never see each other's SDP:
///
///   ```
///   browser  --offer-->  [ webrtc leg | rtp leg ]  --offer-->  network
///            <-answer--                           <-answer--
///   ```
///
///   Joining the legs is what connects them. Required when the far end is a phone, and the only
///   way any other service can reach this call's audio — nothing can tap media it never carries.
///
/// - **Pass-through** — the browser's own offer goes in the INVITE body untouched, and the far
///   answer comes back untouched. If a browser answers (through the location service and a second
///   gateway leg), DTLS-SRTP runs endpoint to endpoint and this gateway could not decrypt a packet
///   if it wanted to; that property is RFC 8827's, not ours. Complete SDP both ways — no trickle,
///   so the candidates ride inside the offer and answer.
///
/// ## The browser is answered before the far end picks up (anchored path)
///
/// Our SDP answer goes out on `call.progress`, not `call.established`. Waiting for the `200 OK`
/// would leave the browser with no media path during alerting — no ringback, no carrier early
/// media, silence until the moment of connect. On the pass-through path there is no local answer
/// to give: SDP is forwarded as the far end produces it, early or final.
public class OutboundFromBrowser extends WebrtcCallflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) {
		// Nothing originates this callflow from the network; see start().
	}

	/// Place a call for `aor` to the target named in `offerEvent`.
	///
	/// @return the call id the browser should quote in later events, or null if the request was
	///         rejected (the browser has already been told why)
	public String start(String aor, CloudEvent offerEvent) throws Exception {
		String target = SignalProtocol.field(offerEvent, "target");
		String browserOffer = SignalProtocol.field(offerEvent, "sdp");
		if (target == null || browserOffer == null) {
			BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.ERROR, offerEvent.getSubject(),
					"call.offer requires a target and an sdp"));
			return null;
		}

		SipFactory sipFactory = getSipFactory();
		SipApplicationSession app = sipFactory.createApplicationSession();

		SipServletRequest invite;
		try {
			invite = sipFactory.createRequest(app, "INVITE", "sip:" + aor, normalizeTarget(target, aor));
		} catch (IllegalArgumentException | javax.servlet.sip.ServletParseException badAddress) {
			app.invalidate();
			BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.ERROR, offerEvent.getSubject(),
					"unroutable target: " + target));
			return null;
		}

		// The browser's handle is the application-session id, not the SIP Call-ID — that is what
		// SignalEndpoint can resolve with getApplicationSessionById.
		String callId = app.getId();
		app.setAttribute(BrowserSignals.BROWSER_AOR, aor);

		MediaMode mode = WebrtcServlet.mediaMode()
				.resolve(BrowserRegistry.isLocal(targetAor(target, aor)), getMsControlFactory() != null);

		if (mode == MediaMode.RELAY) {
			passThrough(invite, app, aor, callId, browserOffer);
			return callId;
		}

		MediaSession media = createMediaSession(app);
		NetworkConnection browserLeg = media.createNetworkConnection(MediaConfigs.WEBRTC);
		NetworkConnection networkLeg = media.createNetworkConnection(MediaConfigs.RTP);

		// Answer the browser now so its audio reaches the media server during alerting.
		offer(browserLeg, browserOffer.getBytes(StandardCharsets.UTF_8), browserAnswer -> {
			join(browserLeg, Joinable.Direction.DUPLEX, networkLeg);
			BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_PROGRESS, callId,
					SignalProtocol.data()
							.put("sdp", new String(browserAnswer.getMediaServerSdp(), StandardCharsets.UTF_8))
							.put("status", "trying")));

			// Offer to the network from the RTP leg.
			generateOffer(networkLeg, networkOffer -> {
				invite.setContent(networkOffer.getMediaServerSdp(), SDP_TYPE);
				BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, hangup -> cancelOrBye(invite, app));
				sendRequest(invite, response -> onNetworkResponse(response, networkLeg, aor, callId));
				// After sendRequest, never before: a call originated from a WebSocket thread has no
				// inbound request to have stamped a Vorpal-ID, so the correlator is minted on the way
				// out. Publish first and the fact carries no subject to join it to anything.
				CallEvents.started(invite, CallEvents.OUTBOUND);
			});
		});

		return callId;
	}

	// ---- pass-through -------------------------------------------------------------------------

	/// [SipApplicationSession] attribute (Boolean): an SDP answer has already been forwarded to the
	/// browser. A far end that puts the same answer in a `183` and the `200` is normal SIP; a
	/// browser told to apply a second answer throws. First one wins.
	private static final String ANSWER_FORWARDED = "org.vorpal.blade.webrtc.answerForwarded";

	/// Send the browser's own offer and forward whatever comes back. No media objects exist on
	/// this path — the gateway is a signaling participant only.
	private void passThrough(SipServletRequest invite, SipApplicationSession app, String aor, String callId,
			String browserOffer) throws Exception {
		invite.setContent(browserOffer.getBytes(StandardCharsets.UTF_8), SDP_TYPE);
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, hangup -> cancelOrBye(invite, app));
		sendRequest(invite, response -> onPassThroughResponse(response, aor, callId));

		// The one emit point in this application that is not already on a SIP thread. Nothing on the
		// RELAY path defers, so we are still here on the WebSocket thread that carried `call.offer`
		// while the INVITE is already on the wire and a fast provisional response may be inside a
		// continuation touching this same session.
		BrowserSignals.underLock(app, () -> CallEvents.started(invite, CallEvents.OUTBOUND));
	}

	/// The far end responded to a pass-through offer: forward its SDP verbatim and keep the
	/// browser's picture of the call current.
	private void onPassThroughResponse(SipServletResponse response, String aor, String callId) {
		SipApplicationSession app = response.getApplicationSession();
		int status = response.getStatus();

		if (status < 200) {
			BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_PROGRESS, callId,
					progressData(response, app, status)));
			return;
		}

		if (status >= 300) {
			BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
			CallEvents.declined(response, CallEvents.OUTBOUND);
			BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId,
					status + " " + response.getReasonPhrase()));
			return;
		}

		// 200 OK. ACK first either way — the transaction must complete before anything else.
		CallEvents.answered(response, CallEvents.OUTBOUND);
		try {
			SipServletRequest ack = response.createAck();
			ack.send();
			CallEvents.fact(CallEvents.CONNECTED, ack, CallEvents.OUTBOUND);
		} catch (Exception e) {
			sipLogger.warning("webrtc: ACK failed for " + callId + ": " + e);
		}
		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);

		// The mismatch a pass-through call cannot survive: our offer demanded DTLS-SRTP and the
		// far end answered without a fingerprint — it accepted the call but holds no key, so the
		// browser can never complete media with it (it will refuse the SDP outright). Stripping
		// or forging SDP cannot fix an endpoint that did not do the handshake; only enabling
		// SRTP/ICE on it, or anchoring on a media server, can. Say that, hang up, and leave the
		// diagnosis in both the log and the browser instead of a silent dead call.
		byte[] content = rawContent(response);
		if (content != null && !InboundToBrowser.isWebrtcOffer(content)) {
			sipLogger.warning("webrtc: " + callId + " answered without DTLS (no a=fingerprint) — "
					+ "tearing down; enable SRTP/ICE on the far endpoint or install a media server to interwork");
			byeAndRelease(response.getSession(), app);
			BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId,
					"far end answered without DTLS; enable SRTP/ICE on it or anchor on a media server"));
			return;
		}

		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP,
				hangup -> byeAndRelease(response.getSession(), app));
		expectRequest(response.getSession(), "BYE", bye -> onFarEndHungUp(bye, app, aor, callId));
		expectDtmf(app, response.getSession());
		expectReoffer(app, response.getSession(), aor, callId, null);

		ObjectNode data = SignalProtocol.data();
		String answer = firstAnswer(response, app);
		if (answer != null) {
			data.put("sdp", answer);
		}
		BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, callId, data));

		// The ACK went out at the top of this method, so the handshake is already complete. Whether
		// media can flow is a separate question and worth answering separately: it can only if an
		// answer actually reached the browser — in this 200 or in an earlier 18x. A 200 with no body
		// and no preceding early answer leaves the browser holding an offer nobody replied to.
		BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_CONNECTED, callId,
				SignalProtocol.data().put("negotiated",
						Boolean.TRUE.equals(app.getAttribute(ANSWER_FORWARDED)))));
	}

	private ObjectNode progressData(SipServletResponse response, SipApplicationSession app, int status) {
		ObjectNode data = SignalProtocol.data().put("status", status == 180 ? "ringing" : "progress");
		String answer = firstAnswer(response, app);
		if (answer != null) {
			data.put("sdp", answer);
		}
		return data;
	}

	/// This response's SDP, or null when it has none, an answer was already forwarded, or the SDP
	/// is not a WebRTC answer — handing the browser a fingerprint-less SDP would make its
	/// `setRemoteDescription` throw, which is a worse failure report than the teardown the 200
	/// path produces.
	private String firstAnswer(SipServletResponse response, SipApplicationSession app) {
		byte[] content = rawContent(response);
		if (content == null || Boolean.TRUE.equals(app.getAttribute(ANSWER_FORWARDED))
				|| !InboundToBrowser.isWebrtcOffer(content)) {
			return null;
		}
		app.setAttribute(ANSWER_FORWARDED, Boolean.TRUE);
		return new String(content, StandardCharsets.UTF_8);
	}

	/// The dialed target as `user@host`, for asking [BrowserRegistry] whether it is a browser on
	/// this node. `tel:` targets are never browsers; URI parameters and schemes are shed.
	static String targetAor(String target, String callerAor) {
		String normalized = normalizeTarget(target, callerAor);
		if (!normalized.startsWith("sip:") && !normalized.startsWith("sips:")) {
			return normalized;
		}
		String bare = normalized.substring(normalized.indexOf(':') + 1);
		int semi = bare.indexOf(';');
		if (semi >= 0) {
			bare = bare.substring(0, semi);
		}
		return bare.toLowerCase();
	}

	// ---- anchored -----------------------------------------------------------------------------

	/// The far end responded: ring, answer, or refuse.
	private void onNetworkResponse(SipServletResponse response, NetworkConnection networkLeg, String aor,
			String callId) throws MsControlException {

		SipApplicationSession app = response.getApplicationSession();
		int status = response.getStatus();

		if (status < 200) {
			// 180/183. Any SDP here is early media and belongs to the network leg only; the browser
			// is already negotiated and hears whatever the media server relays.
			byte[] early = rawContent(response);
			if (early != null) {
				processAnswer(networkLeg, early, done -> {
					// nothing further; the media path is now open for ringback
				});
			}
			BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_PROGRESS, callId,
					SignalProtocol.data().put("status", status == 180 ? "ringing" : "progress")));
			return;
		}

		if (status >= 300) {
			BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
			CallEvents.declined(response, CallEvents.OUTBOUND);
			BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId,
					status + " " + response.getReasonPhrase()));
			InboundToBrowser.releaseMedia(app);
			return;
		}

		// 200 OK: the call is answered. Applying the answer and acknowledging it come next.
		byte[] answer = rawContent(response);
		CallEvents.answered(response, CallEvents.OUTBOUND);
		BrowserRegistry.deliver(aor,
				SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, callId, SignalProtocol.data()));

		if (answer != null) {
			processAnswer(networkLeg, answer, done -> acknowledge(response, app, aor, callId, networkLeg, true));
		} else {
			// Answered with no SDP, so processAnswer never runs and the network leg is never
			// negotiated: the media server has nowhere to send audio. The call is up for signaling
			// and silent. This branch used to reach the same "established" the negotiated one did,
			// which reported a healthy call to a browser that would never hear anything.
			acknowledge(response, app, aor, callId, networkLeg, false);
		}
	}

	private void acknowledge(SipServletResponse response, SipApplicationSession app, String aor, String callId,
			NetworkConnection networkLeg, boolean negotiated) {
		try {
			SipServletRequest ack = response.createAck();
			ack.send();
			CallEvents.fact(CallEvents.CONNECTED, ack, CallEvents.OUTBOUND);
		} catch (Exception e) {
			sipLogger.warning("webrtc: ACK failed for " + callId + ": " + e);
		}
		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP,
				hangup -> byeAndRelease(response.getSession(), app));
		expectRequest(response.getSession(), "BYE", bye -> onFarEndHungUp(bye, app, aor, callId));
		expectDtmf(app, response.getSession());
		expectReoffer(app, response.getSession(), aor, callId, networkLeg);

		BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.CALL_CONNECTED, callId,
				SignalProtocol.data().put("negotiated", negotiated)));
	}

	/// The browser hung up before the far end answered — CANCEL the INVITE.
	private void cancelOrBye(SipServletRequest invite, SipApplicationSession app) {
		try {
			SipServletRequest cancel = invite.createCancel();
			cancel.send();
			// Published after the send, so what is recorded is what actually happened. Whether this
			// is an abandon or a completion is not this method's call: it depends on whether a 200
			// already arrived, which CallEvents knows and this code path does not.
			CallEvents.hungUp(cancel, CallEvents.OUTBOUND);
		} catch (Exception alreadyFinal) {
			// The 200 OK beat the hangup; tear the dialog down instead.
			byeAndRelease(invite.getSession(), app);
			return;
		}
		InboundToBrowser.releaseMedia(app);
	}

	/// The browser hung up an established call.
	private void byeAndRelease(SipSession session, SipApplicationSession app) {
		try {
			SipServletRequest bye = session.createRequest("BYE");
			CallEvents.hungUp(bye, CallEvents.OUTBOUND);
			sendRequest(bye, response -> InboundToBrowser.releaseMedia(app));
		} catch (Exception alreadyGone) {
			InboundToBrowser.releaseMedia(app);
		}
	}

	/// The far end hung up.
	private void onFarEndHungUp(SipServletRequest bye, SipApplicationSession app, String aor, String callId)
			throws Exception {
		CallEvents.hungUp(bye, CallEvents.OUTBOUND);
		sendResponse(bye.createResponse(200));
		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId, "far end hung up"));
		InboundToBrowser.releaseMedia(app);
	}

	/// Accept `+13125551212`, `user@host`, or a full `sip:`/`sips:`/`tel:` URI and produce something
	/// routable. Browsers dial phone numbers, not URIs, so requiring the scheme and domain would
	/// push SIP addressing into every client — which is the thing this gateway exists to avoid.
	///
	/// A bare number takes the caller's own domain, so `alice@example.com` dialling `13125551212`
	/// gets `sip:13125551212@example.com` and the routing decision stays where it belongs, in the
	/// application router.
	static String normalizeTarget(String target, String callerAor) {
		String trimmed = target.trim();
		if (trimmed.startsWith("sip:") || trimmed.startsWith("sips:") || trimmed.startsWith("tel:")) {
			return trimmed;
		}
		if (trimmed.indexOf('@') >= 0) {
			return "sip:" + trimmed;
		}
		int at = callerAor == null ? -1 : callerAor.indexOf('@');
		if (at < 0) {
			return "sip:" + trimmed;
		}
		return "sip:" + trimmed + callerAor.substring(at);
	}
}
