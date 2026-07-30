package org.vorpal.blade.services.webrtc;

import java.nio.charset.StandardCharsets;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.MediaConfigs;

/// A browser calls another browser.
///
/// By default no media server is involved at all: browser A's SDP is handed to browser B verbatim,
/// B's answer comes back to A verbatim, and candidates are forwarded both ways. The two browsers
/// negotiate ICE and DTLS with each other and the media never touches OCCAS. This is the mode that
/// works with nothing deployed but BLADE.
///
/// ## Escalation — and why it is a re-key, not a tap
///
/// When someone hits record, the media server has to be pulled into a call that is already up. It
/// cannot simply start listening: A and B derived their SRTP keys from a DTLS handshake with each
/// other, and the signaling path never saw the master secret (RFC 8827 is designed so it cannot).
/// The gateway forwarded fingerprints and nothing else.
///
/// So both legs are re-offered from the media server:
///
/// ```
/// generateOffer(legA) -> call.update to A -> A answers -> processAnswer(legA)
/// generateOffer(legB) -> call.update to B -> B answers -> processAnswer(legB)
/// join both into the mix; record the mix
/// ```
///
/// Each leg does an ICE restart and a fresh DTLS handshake, so there is a brief audible gap. A
/// deployment that would rather not have one sets `mediaMode: anchor` and pays for a media server
/// on every call instead. That trade is the whole reason [MediaMode] exists.
///
/// Trickle ICE is free here in a way it is not when anchored: both ends are browsers, both trickle
/// natively, and the gateway is only forwarding.
public class BrowserToBrowser extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	/// [SipApplicationSession] attribute (String): the address of the browser that placed the call.
	static final String CALLER_AOR = "org.vorpal.blade.webrtc.b2b.caller";
	/// [SipApplicationSession] attribute (String): the address of the browser being called.
	static final String CALLEE_AOR = "org.vorpal.blade.webrtc.b2b.callee";
	/// [SipApplicationSession] attribute (Boolean): true once the media server is in the path.
	static final String ANCHORED = "org.vorpal.blade.webrtc.b2b.anchored";

	@Override
	public void process(SipServletRequest request) {
		// Browser-to-browser calls never begin with a SIP request; see start().
	}

	/// Ring `callee` on behalf of `caller`, relaying the caller's offer untouched.
	///
	/// @return the call id both browsers quote in later events, or null if the call was refused
	///         (the caller has already been told why)
	public String start(String caller, String callee, CloudEvent offerEvent) {
		String offer = SignalProtocol.field(offerEvent, "sdp");
		if (offer == null) {
			BrowserRegistry.deliver(caller, SignalProtocol.reason(SignalProtocol.ERROR,
					offerEvent.getSubject(), "call.offer requires an sdp"));
			return null;
		}
		if (!BrowserRegistry.isLocal(callee)) {
			BrowserRegistry.deliver(caller, SignalProtocol.reason(SignalProtocol.CALL_ENDED,
					offerEvent.getSubject(), callee + " is not available"));
			return null;
		}

		SipApplicationSession app = getSipFactory().createApplicationSession();
		// The session id IS the handle the browser quotes back; SignalEndpoint resolves it with
		// getApplicationSessionById. No index key needed.
		String callId = app.getId();
		app.setAttribute(CALLER_AOR, caller);
		app.setAttribute(CALLEE_AOR, callee);

		// The callee answers; until then either side may give up.
		BrowserSignals.expect(app, SignalProtocol.CALL_ANSWER, answer -> onCalleeAnswered(app, answer));
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, hangup -> onEarlyHangup(app));

		// Candidates flow both ways for the whole call: peer-to-peer is exactly the case where
		// trickle costs nothing, since there is no server in the middle to wait on.
		BrowserSignals.expectRepeating(app, SignalProtocol.ICE_CANDIDATE, c -> relayCandidate(app, c));

		// Both ends are browsers and nothing sits between them, so tell each to trickle: there is no
		// server whose gathering they would be waiting on.
		BrowserRegistry.deliver(callee, SignalProtocol.event(SignalProtocol.CALL_INCOMING, callId,
				SignalProtocol.data().put("from", caller).put("sdp", offer).put("trickle", true)));
		BrowserRegistry.deliver(caller, SignalProtocol.event(SignalProtocol.CALL_PROGRESS, callId,
				SignalProtocol.data().put("status", "ringing").put("trickle", true)));
		return callId;
	}

	/// The callee answered: hand its SDP straight back to the caller. No media server has been
	/// touched, and from here the browsers talk directly.
	private void onCalleeAnswered(SipApplicationSession app, CloudEvent answerEvent) {
		String caller = (String) app.getAttribute(CALLER_AOR);
		String answer = SignalProtocol.field(answerEvent, "sdp");
		if (answer == null) {
			BrowserRegistry.deliver(caller, SignalProtocol.reason(SignalProtocol.ERROR, app.getId(),
					"call.answer requires an sdp"));
			return;
		}

		BrowserSignals.cancel(app, SignalProtocol.CALL_HANGUP);
		BrowserSignals.expect(app, SignalProtocol.CALL_HANGUP, hangup -> onHangup(app));
		BrowserSignals.expectRepeating(app, SignalProtocol.CALL_RECORD, r -> onRecord(app, r));

		BrowserRegistry.deliver(caller, SignalProtocol.sdp(SignalProtocol.CALL_ESTABLISHED, app.getId(), answer));
		BrowserRegistry.deliver((String) app.getAttribute(CALLEE_AOR),
				SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, app.getId(), SignalProtocol.data()));
	}

	/// Forward one candidate to the other browser, unmodified.
	private void relayCandidate(SipApplicationSession app, CloudEvent candidate) {
		if (Boolean.TRUE.equals(app.getAttribute(ANCHORED))) {
			// Once anchored each browser negotiates with the media server, not with its peer, so a
			// relayed candidate would point at the wrong place entirely.
			return;
		}
		String from = SignalProtocol.field(candidate, "from");
		String caller = (String) app.getAttribute(CALLER_AOR);
		String callee = (String) app.getAttribute(CALLEE_AOR);
		String target = caller.equals(from) ? callee : caller;
		BrowserRegistry.deliver(target, SignalProtocol.event(SignalProtocol.ICE_CANDIDATE, app.getId(),
				SignalProtocol.data()
						.put("candidate", SignalProtocol.field(candidate, "candidate"))
						.put("sdpMid", SignalProtocol.field(candidate, "sdpMid"))
						.put("sdpMLineIndex", candidate.getData().path("sdpMLineIndex").asInt())));
	}

	// ---- escalation ---------------------------------------------------------------------------

	/// Record on or off. Turning it on for a relayed call pulls the media server in.
	private void onRecord(SipApplicationSession app, CloudEvent event) throws MsControlException {
		boolean on = event.getData() != null && event.getData().path("on").asBoolean(true);
		if (!on || Boolean.TRUE.equals(app.getAttribute(ANCHORED))) {
			return; // stopping, or already anchored — nothing to renegotiate
		}
		anchor(app);
	}

	/// Build the mix and re-offer both browsers onto it.
	///
	/// The two `call.update` exchanges are independent; each browser answers on its own schedule and
	/// the join happens once both are back.
	private void anchor(SipApplicationSession app) throws MsControlException {
		app.setAttribute(ANCHORED, Boolean.TRUE);

		MediaSession media = createMediaSession(app);
		NetworkConnection legA = media.createNetworkConnection(MediaConfigs.WEBRTC);
		NetworkConnection legB = media.createNetworkConnection(MediaConfigs.WEBRTC);
		MediaGroup recorder = media.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR);

		reoffer(app, (String) app.getAttribute(CALLER_AOR), legA,
				() -> reoffer(app, (String) app.getAttribute(CALLEE_AOR), legB,
						() -> joinAndRecord(app, legA, legB, recorder)));
	}

	/// A step in the escalation chain. Not a `Runnable` because every media verb is checked.
	@FunctionalInterface
	private interface MediaStep {
		void run() throws MsControlException;
	}

	/// Offer this leg's SDP to one browser as a `call.update` and apply the answer it sends back.
	private void reoffer(SipApplicationSession app, String aor, NetworkConnection leg, MediaStep then)
			throws MsControlException {

		generateOffer(leg, offerEvent -> {
			BrowserSignals.expect(app, SignalProtocol.CALL_ANSWER, answerEvent -> {
				String answer = SignalProtocol.field(answerEvent, "sdp");
				if (answer == null) {
					BrowserRegistry.deliver(aor, SignalProtocol.reason(SignalProtocol.ERROR, app.getId(),
							"call.update requires an sdp in the answer"));
					return;
				}
				processAnswer(leg, answer.getBytes(StandardCharsets.UTF_8), processed -> then.run());
			});
			BrowserRegistry.deliver(aor, SignalProtocol.sdp(SignalProtocol.CALL_UPDATE, app.getId(),
					new String(offerEvent.getMediaServerSdp(), StandardCharsets.UTF_8)));
		});
	}

	/// Both browsers are on the media server now: mix them and record the mix.
	private void joinAndRecord(SipApplicationSession app, NetworkConnection legA, NetworkConnection legB,
			MediaGroup recorder) {
		try {
			join(legA, Joinable.Direction.DUPLEX, legB);
			join(legA, Joinable.Direction.SEND, recorder);
			join(legB, Joinable.Direction.SEND, recorder);
			record(recorder, java.net.URI.create("file:///tmp/blade-webrtc-" + app.getId() + ".webm"),
					done -> sipLogger.info("webrtc: recording finished for " + app.getId()));

			notifyBoth(app, SignalProtocol.event(SignalProtocol.CALL_ESTABLISHED, app.getId(),
					SignalProtocol.data().put("recording", true)));
		} catch (Exception e) {
			sipLogger.severe("webrtc: could not anchor " + app.getId() + ": " + e);
			notifyBoth(app, SignalProtocol.reason(SignalProtocol.ERROR, app.getId(), "recording unavailable"));
		}
	}

	// ---- teardown -----------------------------------------------------------------------------

	private void onEarlyHangup(SipApplicationSession app) {
		BrowserSignals.cancel(app, SignalProtocol.CALL_ANSWER);
		notifyBoth(app, SignalProtocol.reason(SignalProtocol.CALL_ENDED, app.getId(), "cancelled"));
		app.invalidate();
	}

	private void onHangup(SipApplicationSession app) {
		notifyBoth(app, SignalProtocol.reason(SignalProtocol.CALL_ENDED, app.getId(), "hung up"));
		releaseMedia(app);
		app.invalidate();
	}

	private void notifyBoth(SipApplicationSession app, CloudEvent event) {
		BrowserRegistry.deliver((String) app.getAttribute(CALLER_AOR), event);
		BrowserRegistry.deliver((String) app.getAttribute(CALLEE_AOR), event);
	}

	/// Free the media session if one was ever created. A relayed call never had one.
	static void releaseMedia(SipApplicationSession app) {
		try {
			MediaSession media = reattach(app);
			if (media != null) {
				media.release();
			}
		} catch (MsControlException e) {
			// The node may already be gone, which is the outcome we wanted anyway.
		}
	}
}
