package org.vorpal.blade.services.webrtc;

import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The browser signaling vocabulary: fourteen event types in CloudEvents 1.0 envelopes, carried as
/// text frames over one WebSocket.
///
/// ## Two verbs that were declared and are deliberately gone
///
/// **`call.accept`** meant "yes, without SDP" — accepting a call before its media was negotiated.
/// There is no SIP message it can honestly produce. Both answer paths build the `200 OK` out of an
/// SDP only the browser has: pass-through puts the browser's own answer in it, and the anchored path
/// needs the browser leg negotiated before the legs can be joined. Answering the network early would
/// start a call whose browser has no media path yet — dead air on an answered call — and a `183`
/// instead tends to stop the caller's ringback, replacing a ringing tone with silence for as long as
/// ICE gathering takes. Neither beats waiting for [#CALL_ANSWER], which is a moment away.
///
/// **`call.record`** was a browser asking this gateway to record. Recording is not this
/// application's job: it belongs to a recording service, and routing a browser's button through a
/// gateway with no recording responsibility only re-creates the coupling that separation exists to
/// remove. What the gateway owes such a service is the one thing only it can decide — whether the
/// call's media is anchored at all — and that is [MediaMode], a configuration field, not an event.
/// A relayed call cannot be recorded by anyone, this gateway included, because its two endpoints
/// key DTLS to each other and the signaling path never sees the secret.
///
/// Both were removed rather than left declared-and-inert, because the whole point of a vocabulary
/// this small is that reading it tells you what the protocol does. Restoring either is one constant
/// and one `case`.
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

	/// Claim an address on this gateway. `data.aor` is the address and `data.token` is the signed
	/// JWT proving the browser may have it.
	///
	/// This is the only authenticated step in the protocol, and it is enough: every other event
	/// requires the socket to be registered already, so refusing here refuses all of them. The
	/// token names its own address, so `aor` is a statement the gateway checks rather than
	/// obeys — a mismatch is refused, not silently corrected.
	public static final String SESSION_CONNECT = "session.connect";
	/// Place a call. `data.target` is who to reach, `data.sdp` the browser's complete offer.
	public static final String CALL_OFFER = "call.offer";
	/// Answer a call we offered. `data.sdp` is the browser's complete answer.
	public static final String CALL_ANSWER = "call.answer";
	/// Hang up, or decline a call that is still ringing.
	public static final String CALL_HANGUP = "call.hangup";
	/// Send a DTMF digit on an established call. `data.digit`.
	public static final String CALL_DTMF = "call.dtmf";

	// ---- gateway -> browser -------------------------------------------------------------------

	/// The address is claimed and calls can now arrive. `data.aor` is the address that was actually
	/// bound — the token's, which may differ from the one asked for. `data.authenticated` is false
	/// when the deployment has browser authentication switched off, so a page can show that its
	/// identity was taken on trust rather than proved.
	public static final String SESSION_READY = "session.ready";
	/// A call is arriving. `data.from` identifies the caller; `data.sdp`, when present, is the
	/// media server's offer for the browser to answer.
	public static final String CALL_INCOMING = "call.incoming";
	/// The far end is ringing (a SIP 180/183).
	public static final String CALL_PROGRESS = "call.progress";
	/// The call was answered. `data.sdp` carries the answer when we owed one.
	///
	/// This is the `200 OK`, not the end of setup. The handshake is not finished until the `ACK`,
	/// which is [#CALL_CONNECTED].
	public static final String CALL_ESTABLISHED = "call.established";

	/// The `ACK` completed the handshake. **After [#CALL_ESTABLISHED], never before it.**
	///
	/// SIP answers a call in three messages, not two, and the third one carries information. This
	/// event is that third message, which the browser previously never saw: `call.established` was
	/// emitted *from* the ACK continuation and said nothing about it, so a browser could not tell an
	/// answered call from a completed one, and the SDP an ACK can carry was dropped on the floor.
	/// The names and the ordering rule are the framework's own — see
	/// `org.vorpal.blade.framework.v3.events.BladeEventTypes.CALL_CONNECTED`.
	///
	/// `data.negotiated` is whether the media path is actually negotiated at this instant. It is
	/// false when the far end answered without SDP and nothing was applied to the media leg — a call
	/// that is up for signaling and silent for media. Saying so lets a browser show that, instead of
	/// reporting a healthy call and leaving the user to wonder why nobody can hear them.
	///
	/// `data.sdp` is **reserved and unset on every path this gateway ships.** It exists because the
	/// ACK is a real SDP-carrying moment — in late media the caller's answer arrives there — and
	/// declaring it now means the type describes the ACK honestly and a future late-media path needs
	/// no protocol revision. Clients must tolerate its absence, which is the ordinary case.
	public static final String CALL_CONNECTED = "call.connected";
	/// The call is over. `data.reason` says why.
	public static final String CALL_ENDED = "call.ended";
	/// An error tied to a specific call or to the session. `data.reason`, optional `data.code`.
	///
	/// **`signal.` and not `session.` or `call.`, because it is both.** Every other type names the
	/// scope it belongs to, and the `subject` says which one an error is about: set for a call,
	/// absent for the socket itself — a refused token, a malformed frame, an event sent before
	/// `session.connect`. Filing it under either scope would be wrong half the time, and splitting
	/// it into two types would duplicate a distinction `subject` already draws. `signal` is this
	/// protocol's own name, which is what the odd one out should be filed under.
	public static final String ERROR = "signal.error";

	/// **A new SDP offer for a call that is already up.** `data.sdp`; the browser replies with
	/// [#CALL_ANSWER].
	///
	/// This is a re-INVITE by another name, and without it the media path of a call could never
	/// change after setup. It is what lets a relayed call be moved onto a media server.
	///
	/// The reason moving it needs a fresh offer at all — rather than quietly inserting a server
	/// alongside — is cryptographic. In a relayed call the two browsers complete a DTLS handshake
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

	/// An event carrying nothing but a reason — `call.ended`, `signal.error`.
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
