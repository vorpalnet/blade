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

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.MediaConfigs;

/// A browser calls out to the SIP network.
///
/// Started from a WebSocket thread rather than an inbound SIP request, so [#process] is never
/// called — [#start] is the entry point, the way `services/transfer` originates from a REST thread.
/// Everything after the first `sendRequest` is ordinary BLADE: continuations run on SIP threads
/// under the application-session lock.
///
/// **Always anchored**, because the far end is a phone. Two independent negotiations that never see
/// each other's SDP:
///
/// ```
/// browser  --offer-->  [ webrtc leg | rtp leg ]  --offer-->  network
///          <-answer--                           <-answer--
/// ```
///
/// Joining the two legs is what connects them. Neither side's ICE, DTLS or codec choices leak into
/// the other's SDP, which is the entire reason a media server sits in the middle.
///
/// ## The browser is answered before the far end picks up
///
/// Our SDP answer goes out on `call.progress`, not `call.established`. Waiting for the `200 OK`
/// would leave the browser with no media path during alerting — no ringback, no carrier early
/// media, silence until the moment of connect.
public class OutboundFromBrowser extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	private static final String SDP_TYPE = "application/sdp";

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
			});
		});

		return callId;
	}

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
			BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.CALL_ENDED, callId,
					status + " " + response.getReasonPhrase()));
			InboundToBrowser.releaseMedia(app);
			return;
		}

		// 200 OK: apply the answer, acknowledge, and the call is up.
		byte[] answer = rawContent(response);
		if (answer != null) {
			processAnswer(networkLeg, answer, done -> acknowledge(response, app, aor, callId));
		} else {
			acknowledge(response, app, aor, callId);
		}
	}

	private void acknowledge(SipServletResponse response, SipApplicationSession app, String aor, String callId) {
		try {
			response.createAck().send();
		} catch (Exception e) {
			sipLogger.warning("webrtc: ACK failed for " + callId + ": " + e);
		}
		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP,
				hangup -> byeAndRelease(response.getSession(), app));
		expectRequest(response.getSession(), "BYE", bye -> onFarEndHungUp(bye, app, aor, callId));

		BrowserRegistry.deliver(aor,
				SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, callId, SignalProtocol.data()));
	}

	/// The browser hung up before the far end answered — CANCEL the INVITE.
	private void cancelOrBye(SipServletRequest invite, SipApplicationSession app) {
		try {
			invite.createCancel().send();
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
			sendRequest(session.createRequest("BYE"), response -> InboundToBrowser.releaseMedia(app));
		} catch (Exception alreadyGone) {
			InboundToBrowser.releaseMedia(app);
		}
	}

	/// The far end hung up.
	private void onFarEndHungUp(SipServletRequest bye, SipApplicationSession app, String aor, String callId)
			throws Exception {
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
