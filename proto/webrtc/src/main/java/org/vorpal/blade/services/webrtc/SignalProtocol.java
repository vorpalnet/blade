package org.vorpal.blade.services.webrtc;

import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The browser signaling vocabulary: thirteen event types in CloudEvents 1.0 envelopes, carried as
/// text frames over one WebSocket.
///
/// ## Why not SIP over WebSocket
///
/// RFC 7118 exists and works, but OCCAS 8.1 does not implement it — the JSR-359 API jar ships
/// `SipWebSocketContext` while the container has no WebSocket transport behind it — so a browser
/// cannot register as a SIP UA against this server no matter how the application is written. That
/// settles the question for us, and it also happens to be the design we would choose: a browser
/// that speaks "a call is arriving, do you want it?" does not need to own SIP dialogs, Via headers,
/// registration refreshes or transaction state, and every one of those is a thing that can be got
/// wrong in JavaScript.
///
/// ## Why the shape is a declared envelope
///
/// The obvious failure mode for a protocol like this is the one Oracle's WebRTC Session Controller
/// hit: a bespoke JSON dialect that the deployment had to hand-map onto SIP, with the payload shape
/// written down nowhere. Using [CloudEvent] means the envelope is a published specification rather
/// than a local invention, and the types below are the whole contract — small enough to read in one
/// sitting, which is the actual design goal.
public final class SignalProtocol {

	private static final ObjectMapper M = new ObjectMapper();

	/// The negotiated WebSocket subprotocol. Versioned in the token so an incompatible future
	/// revision fails the handshake instead of failing a call halfway through.
	public static final String SUBPROTOCOL = "blade.webrtc.v1";

	/// CloudEvents `source` for everything this gateway emits.
	public static final String SOURCE = "/webrtc";

	// ---- browser -> gateway -------------------------------------------------------------------

	/// Claim an address on this gateway. `data.aor` is the address; credentials, when the
	/// deployment requires them, ride alongside.
	public static final String SESSION_CONNECT = "session.connect";
	/// Place a call. `data.target` is who to reach, `data.sdp` the browser's complete offer.
	public static final String CALL_OFFER = "call.offer";
	/// Answer a call we offered. `data.sdp` is the browser's complete answer.
	public static final String CALL_ANSWER = "call.answer";
	/// Accept an incoming call before media is negotiated (ringing -> answered).
	public static final String CALL_ACCEPT = "call.accept";
	/// Hang up, or decline a call that is still ringing.
	public static final String CALL_HANGUP = "call.hangup";
	/// Send a DTMF digit on an established call. `data.digit`.
	public static final String CALL_DTMF = "call.dtmf";
	/// Start or stop recording this call. `data.on` is a boolean.
	///
	/// In a relayed (peer-to-peer) call this is what pulls the media server in, which is not a
	/// tap — see [#CALL_UPDATE].
	public static final String CALL_RECORD = "call.record";

	// ---- gateway -> browser -------------------------------------------------------------------

	/// The address is claimed and calls can now arrive.
	public static final String SESSION_READY = "session.ready";
	/// A call is arriving. `data.from` identifies the caller; `data.sdp`, when present, is the
	/// media server's offer for the browser to answer.
	public static final String CALL_INCOMING = "call.incoming";
	/// The far end is ringing (a SIP 180/183).
	public static final String CALL_PROGRESS = "call.progress";
	/// Media is negotiated and the call is up. `data.sdp` carries the answer when we owed one.
	public static final String CALL_ESTABLISHED = "call.established";
	/// The call is over. `data.reason` says why.
	public static final String CALL_ENDED = "call.ended";
	/// An error tied to a specific call or to the session. `data.reason`, optional `data.code`.
	public static final String ERROR = "error";

	/// **A new SDP offer for a call that is already up.** `data.sdp`; the browser replies with
	/// [#CALL_ANSWER].
	///
	/// This is a re-INVITE by another name, and without it the media path of a call could never
	/// change after setup. It is what lets a peer-to-peer call become a recorded conference.
	///
	/// The reason that transition needs a fresh offer at all — rather than quietly adding a
	/// recorder — is cryptographic. In a relayed call the two browsers complete a DTLS handshake
	/// directly with each other and derive their SRTP keys from a master secret the signaling path
	/// never sees (RFC 8827 is built that way). The gateway forwarded fingerprints and nothing more,
	/// so it cannot decrypt a single packet. The only way in is to re-offer **both** legs from the
	/// media server so it becomes a real DTLS endpoint on each.
	///
	/// The visible cost is an ICE restart and a new DTLS handshake per leg — a short audible gap.
	/// A deployment that would rather not have that gap can anchor every call from the start; see
	/// `WebrtcSettings.mediaMode`.
	public static final String CALL_UPDATE = "call.update";

	// ---- both directions ----------------------------------------------------------------------

	/// One ICE candidate, shaped like the browser's `RTCIceCandidate`.
	///
	/// In practice this flows gateway -> browser. Browsers always accept trickled candidates, so
	/// sending ours as they are discovered costs nothing and shortens call setup; browsers only
	/// send theirs incrementally when told we can take them, and we do not say so, which is why a
	/// browser's offer and answer arrive complete. Nothing in the protocol prevents the reverse
	/// direction later — the type is already here.
	public static final String ICE_CANDIDATE = "ice.candidate";

	private SignalProtocol() {
	}

	/// Build an event about a particular call. `callId` becomes the CloudEvents `subject`, which is
	/// how a browser with several calls up tells them apart.
	public static CloudEvent event(String type, String callId, ObjectNode data) {
		return CloudEvent.create(type, SOURCE, callId, data);
	}

	/// A mutable, empty data object to fill in.
	public static ObjectNode data() {
		return M.createObjectNode();
	}

	/// An event carrying nothing but a reason — `call.ended`, `error`.
	public static CloudEvent reason(String type, String callId, String reason) {
		return event(type, callId, data().put("reason", reason));
	}

	/// An event carrying one SDP body — `call.incoming`, `call.established`.
	public static CloudEvent sdp(String type, String callId, String sdp) {
		return event(type, callId, data().put("sdp", sdp));
	}

	/// Read a string out of an inbound event's `data`, or null when absent or blank.
	public static String field(CloudEvent event, String name) {
		JsonNode data = event.getData();
		if (data == null) {
			return null;
		}
		JsonNode value = data.get(name);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text.trim().isEmpty() ? null : text;
	}
}
