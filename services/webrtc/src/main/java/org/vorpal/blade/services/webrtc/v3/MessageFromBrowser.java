package org.vorpal.blade.services.webrtc.v3;

import java.nio.charset.StandardCharsets;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.messaging.RoomHeaders;
import org.vorpal.blade.services.webrtc.BrowserRegistry;
import org.vorpal.blade.services.webrtc.SignalProtocol;
import org.vorpal.blade.services.webrtc.OutboundFromBrowser;

/// A browser posts to a messaging room: [SignalProtocol#MESSAGE_SEND] becomes a SIP MESSAGE
/// (RFC 3428) to the room's address, through the App Router like every request this gateway
/// sends.
///
/// The gateway does not read the body. It carries it with the page's Content-Type, and vouches
/// only for who sent it: `P-Asserted-Identity` is the socket's signed-in address, set here the way
/// [OutboundFromBrowser] sets it on a call. A private message names its one recipient in
/// `X-Chat-To` ([RoomHeaders#TO]).
///
/// Started from a WebSocket thread on a fresh application session, like [OutboundFromBrowser];
/// the answer arrives as an ordinary continuation and is written back to the address's sockets.
public class MessageFromBrowser extends Callflow {
	private static final long serialVersionUID = 1L;

	/// The largest body a page may post, in bytes. Chat text, not files: a file goes to storage
	/// and the message carries its reference.
	static final int MAX_BODY = 16 * 1024;

	/// A media type, `type/subtype` with optional parameters, and nothing that could end the header.
	private static final String CONTENT_TYPE = "^[\\w.+-]+/[\\w.+-]+(\\s*;[^\\r\\n]*)?$";

	@Override
	public void process(SipServletRequest request) {
		// Nothing originates this callflow from the network; see send().
	}

	/// Post `event` for `aor`. A refusal is written to `socket`; the room's answer, to every socket
	/// of `aor`, carrying the event's `id` as `ref`.
	public void send(javax.websocket.Session socket, String aor, CloudEvent event) throws Exception {
		String ref = event.getId();
		String room = SignalProtocol.field(event, "room");
		String body = (event.getData() == null) ? null : event.getData().path("body").asText(null);
		String contentType = SignalProtocol.field(event, "contentType");
		contentType = (contentType == null) ? "text/plain" : contentType.trim();
		if (room == null || body == null || body.isEmpty()) {
			BrowserRegistry.send(socket, refused(ref, room, "message.send requires a room and a body"));
			return;
		}
		if (!contentType.matches(CONTENT_TYPE)) {
			BrowserRegistry.send(socket, refused(ref, room, "not a content type: " + contentType));
			return;
		}
		byte[] content = body.getBytes(StandardCharsets.UTF_8);
		if (content.length > MAX_BODY) {
			BrowserRegistry.send(socket, refused(ref, room, "message larger than " + MAX_BODY + " bytes"));
			return;
		}

		SipApplicationSession app = getSipFactory().createApplicationSession();
		SipServletRequest message;
		try {
			message = getSipFactory().createRequest(app, "MESSAGE", "sip:" + aor,
					OutboundFromBrowser.normalizeTarget(room, aor));
		} catch (IllegalArgumentException | javax.servlet.sip.ServletParseException badAddress) {
			app.invalidate();
			BrowserRegistry.send(socket, refused(ref, room, "not a room address: " + room));
			return;
		}
		OutboundFromBrowser.assertIdentity(message, aor, SignalProtocol.field(event, "displayName"));
		String to = SignalProtocol.field(event, "to");
		if (to != null) {
			message.setHeader(RoomHeaders.TO, to.replaceAll("[\\r\\n]", "").trim());
		}
		message.setContent(content, contentType);

		sendRequest(message, response -> {
			if (provisional(response)) {
				return;
			}
			if (successful(response)) {
				BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.MESSAGE_SENT, null,
						SignalProtocol.data()
								.put("ref", ref)
								.put("room", room)
								.put("seq", number(response.getHeader(RoomHeaders.SEQ)))
								.put("atMs", number(response.getHeader(RoomHeaders.TIME)))));
				return;
			}
			CloudEvent error = refused(ref, room, response.getStatus() + " " + response.getReasonPhrase());
			((com.fasterxml.jackson.databind.node.ObjectNode) error.getData()).put("code", response.getStatus());
			BrowserRegistry.deliver(aor, error);
		});
	}

	private static CloudEvent refused(String ref, String room, String reason) {
		return SignalProtocol.event(SignalProtocol.ERROR, null,
				SignalProtocol.data().put("reason", reason).put("ref", ref).put("room", room));
	}

	static long number(String header) {
		try {
			return (header == null) ? 0 : Long.parseLong(header.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
