package org.vorpal.blade.services.messaging.v3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.TrustedPeers;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.messaging.MessageFormats;
import org.vorpal.blade.framework.v3.messaging.RoomHeaders;
import org.vorpal.blade.framework.v3.messaging.StoredMessage;

/// A member posts to a room (RFC 3428 MESSAGE to `sip:<room>@<domain>`).
///
/// Runs under the room's lock ([Rooms]), so posts take their numbers in the order the container
/// takes them. The post is answered as soon as it has a number: storing it and sending it on do not
/// hold the sender up.
///
/// ## Who sent it is the network's word
///
/// The sender is the `P-Asserted-Identity` from a trusted hop (the WebRTC gateway sets it from the
/// signed-in browser), else the From. Nothing in the body is read for identity. The body is read
/// only for its text, when the room knows the format, so it can be written in another format for a
/// member who does not take this one ([MessageFormats]). Only a member may post, so a guest still in a meeting's waiting room, who is not a
/// member yet, cannot.
///
/// ## Answers
///
/// - `200` with `X-Chat-Seq` and `X-Chat-Time`: accepted, numbered, on its way.
/// - `403`: the sender is not in the room.
/// - `404`: a private message (`X-Chat-To`) to someone not in the room.
/// - `503`: the room could not open, because the archive could not say where its numbering stands.
public class PostMessage extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest message) throws ServletException, IOException {
		String name = Rooms.nameOf(message);
		if (name == null) {
			sendResponse(message.createResponse(404, "No Such Room"));
			return;
		}
		SipApplicationSession app = message.getApplicationSession();
		Room room;
		try {
			room = Rooms.of(app, name);
		} catch (IOException e) {
			sipLogger.warning(message, "messaging: room " + name + " cannot open: " + e.getMessage());
			sendResponse(message.createResponse(503, "Room Unavailable"));
			return;
		}

		String from = sender(message);
		if (!room.isMember(from)) {
			sipLogger.info(message, "messaging: " + from + " is not in " + name + "; refused");
			sendResponse(message.createResponse(403, "Not A Member"));
			return;
		}
		String to = message.getHeader(RoomHeaders.TO);
		to = (to == null || to.trim().isEmpty()) ? null : to.trim().toLowerCase();
		List<String> recipients = room.recipients(from, to);
		if (to != null && recipients.isEmpty()) {
			sendResponse(message.createResponse(404, "Not In Room"));
			return;
		}

		byte[] raw = message.getRawContent();
		String body = (raw == null) ? "" : new String(raw, StandardCharsets.UTF_8);
		StoredMessage stored = new StoredMessage(room.next(), name, from, room.nameOf(from), to,
				System.currentTimeMillis(), message.getContentType(), body);
		stored.setText(MessageFormats.read(message.getContentType(), body));
		Rooms.save(app, room);

		SipServletResponse ok = message.createResponse(200);
		ok.setHeader(RoomHeaders.SEQ, Integer.toString(stored.getSequence()));
		ok.setHeader(RoomHeaders.TIME, Long.toString(stored.getAtMillis()));
		sendResponse(ok);

		Rooms.store(stored);
		Delivery delivery = new Delivery();
		for (String address : recipients) {
			delivery.send(app, room, stored, address);
		}
	}

	/// The poster's address, `user@host`, lowercased: the trusted `P-Asserted-Identity`, else the
	/// From.
	static String sender(SipServletRequest message) {
		Address pai = null;
		try {
			if (TrustedPeers.isTrusted(message)) {
				pai = message.getAddressHeader("P-Asserted-Identity");
			}
		} catch (Exception e) {
			pai = null;
		}
		URI uri = (pai != null) ? pai.getURI() : message.getFrom().getURI();
		if (uri instanceof SipURI) {
			SipURI sip = (SipURI) uri;
			return ((sip.getUser() == null ? "" : sip.getUser() + "@") + sip.getHost()).toLowerCase();
		}
		return String.valueOf(uri).toLowerCase();
	}
}
