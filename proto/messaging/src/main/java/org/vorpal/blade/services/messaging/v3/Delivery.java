package org.vorpal.blade.services.messaging.v3;

import java.nio.charset.StandardCharsets;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.messaging.MessageFormats;
import org.vorpal.blade.framework.v3.messaging.RoomHeaders;
import org.vorpal.blade.framework.v3.messaging.StoredMessage;

/// Sends one stored message to one member as a SIP MESSAGE.
///
/// The MESSAGE is addressed to the member's address of record and routed like any request this
/// application originates, through the App Router to the registrar, which forks it to every
/// device registered for the address. A browser's device is the WebRTC gateway, which hands the
/// message to the page.
///
/// ## The envelope is headers, the body is untouched
///
/// The body and Content-Type are the sender's, copied as they arrived. What the room vouches for
/// rides in the [RoomHeaders] the room alone sets, so a receiver never has to trust anything
/// inside the body.
///
/// The From header is the room itself, in the recipient's domain: the MESSAGE comes from the room,
/// and a reply to it is a post to the room.
///
/// ## In a format the member takes
///
/// The body is the one posted when the member takes its format or has not said what it takes;
/// otherwise the room writes the message in the first format the member takes that it can write
/// ([MessageFormats]). A member who takes none of them is not sent the message.
final class Delivery extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) {
		// Nothing arrives here from the network; see send().
	}

	/// Send `message` to `address` from the room's session `app`, in a format `room` says they take.
	/// Call under that session's lock.
	void send(SipApplicationSession app, Room room, StoredMessage message, String address) {
		send(app, room, message, address, false);
	}

	/// ## A 415 is how a member says what it takes
	///
	/// A member that cannot take the body answers `415 Unsupported Media Type` with `Accept` (RFC 3428
	/// asks exactly that of it). [Room#refused] records the list for the address, so every later
	/// message goes out right the first time, and names the format to send this one again in. Once: a
	/// member that refuses the second as well is not asked a third time, and a format it just refused
	/// is never tried again.
	private void send(SipApplicationSession app, Room room, StoredMessage message, String address,
			boolean retried) {
		String original = (message.getContentType() == null) ? MessageFormats.PLAIN : message.getContentType();
		String type = room.formatFor(address, original, message.getText() != null);
		if (type == null) {
			sipLogger.fine("messaging: " + address + " takes " + room.acceptOf(address) + ", nothing " + message.getRoom()
					+ " #" + message.getSequence() + " (" + original + ") can be written as; not sent");
			return;
		}
		try {
			String domain = address.substring(address.indexOf('@') + 1);
			SipServletRequest request = getSipFactory().createRequest(app, "MESSAGE",
					"<sip:" + message.getRoom() + "@" + domain + ">", "sip:" + address);
			request.setHeader(RoomHeaders.ROOM, message.getRoom());
			request.setHeader(RoomHeaders.FROM, message.getFrom());
			if (message.getDisplayName() != null && !message.getDisplayName().isEmpty()) {
				request.setHeader(RoomHeaders.NAME, clean(message.getDisplayName()));
			}
			request.setHeader(RoomHeaders.SEQ, Integer.toString(message.getSequence()));
			request.setHeader(RoomHeaders.TIME, Long.toString(message.getAtMillis()));
			if (message.getTo() != null) {
				request.setHeader(RoomHeaders.TO, message.getTo());
			}
			if (type.equals(original)) {
				String body = (message.getBody() == null) ? "" : message.getBody();
				request.setContent(body.getBytes(StandardCharsets.UTF_8), original);
			} else {
				MessageFormats.Format format = MessageFormats.of(type);
				request.setContent(format.write(message, address).getBytes(StandardCharsets.UTF_8),
						format.contentType());
			}
			String roomName = message.getRoom();
			sendRequest(request, response -> {
				int status = response.getStatus();
				if (status == 415 && !retried) {
					SipApplicationSession session = response.getApplicationSession();
					Room current = Rooms.of(session, roomName);
					String next = current.refused(address, MessageFormats.parseAccept(response.getHeaders("Accept")),
							type, original, message.getText() != null);
					Rooms.save(session, current);
					if (next != null) {
						new Delivery().send(session, current, message, address, true);
						return;
					}
				}
				if (status >= 300) {
					sipLogger.fine("messaging: " + roomName + " #" + message.getSequence() + " to " + address
							+ " answered " + status);
				}
			});
		} catch (Exception e) {
			sipLogger.warning("messaging: could not send " + message.getRoom() + " #" + message.getSequence()
					+ " to " + address + ": " + e);
		}
	}

	/// A header value from client input: no CR or LF.
	static String clean(String value) {
		return value.replaceAll("[\\r\\n]", " ").trim();
	}
}
