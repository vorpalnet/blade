package org.vorpal.blade.services.webrtc.v3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.TrustedPeers;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.messaging.RoomHeaders;
import org.vorpal.blade.services.webrtc.BrowserRegistry;
import org.vorpal.blade.services.webrtc.BrowserSignals;
import org.vorpal.blade.services.webrtc.InboundToBrowser;
import org.vorpal.blade.services.webrtc.SignalProtocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

/// A messaging room sends this browser a message: a SIP MESSAGE to the browser's registered
/// contact becomes [SignalProtocol#MESSAGE_RECEIVED] on its sockets.
///
/// The MESSAGE arrives the way a call does, targeted into the browser's registration session
/// ([org.vorpal.blade.services.webrtc.BrowserRegistration]), which already knows the address. The room's envelope is read from the
/// [RoomHeaders] `proto/messaging` sets; the body is handed to the page untouched.
///
/// ## Believed only from a trusted hop
///
/// The headers are believed as `P-Asserted-Identity` is: from another BLADE hop
/// ([TrustedPeers]), on the understanding that the network's edge removes them from anything
/// arriving from outside, as it must remove `P-Asserted-Identity` (RFC 3325's trust domain). A
/// MESSAGE from an untrusted peer is refused with `403`.
///
/// ## Answers
///
/// `200` when a socket of the address took it; `480` when this node holds none, a stale binding,
/// as [InboundToBrowser] answers a call.
public class MessageToBrowser extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest message) throws ServletException, IOException {
		if (!TrustedPeers.isTrusted(message)) {
			sipLogger.warning(message, "webrtc: MESSAGE from an untrusted peer refused");
			sendResponse(message.createResponse(403));
			return;
		}
		SipApplicationSession app = message.getApplicationSession();
		String aor = (String) app.getAttribute(BrowserSignals.BROWSER_AOR);
		if (aor == null) {
			aor = InboundToBrowser.addressOf(message).toLowerCase();
		}
		boolean written = BrowserRegistry.deliver(aor, received(message));
		sendResponse(message.createResponse(written ? 200 : 480));
	}

	/// The page's event for `message`.
	static CloudEvent received(SipServletRequest message) throws IOException {
		byte[] raw = message.getRawContent();
		ObjectNode data = SignalProtocol.data();
		data.put("room", message.getHeader(RoomHeaders.ROOM));
		data.put("seq", MessageFromBrowser.number(message.getHeader(RoomHeaders.SEQ)));
		String from = message.getHeader(RoomHeaders.FROM);
		data.put("from", (from != null) ? from : InboundToBrowser.callerOf(message));
		data.put("displayName", message.getHeader(RoomHeaders.NAME));
		data.put("atMs", MessageFromBrowser.number(message.getHeader(RoomHeaders.TIME)));
		if (message.getHeader(RoomHeaders.TO) != null) {
			data.put("to", message.getHeader(RoomHeaders.TO));
		}
		data.put("contentType", message.getContentType());
		data.put("body", (raw == null) ? "" : new String(raw, StandardCharsets.UTF_8));
		return SignalProtocol.event(SignalProtocol.MESSAGE_RECEIVED, null, data);
	}
}
