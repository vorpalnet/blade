package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

/// Answer a (re-)INVITE with `200 OK` carrying a "blackhole" SDP that
/// puts the leg on hold. When the request carries an offer, [Callflow#hold]
/// derives the answer from it: every `m=` port → 0, every `c=` →
/// `0.0.0.0` (or `::`), every direction attribute → `inactive`. Multipart
/// SIPREC (`application/sdp` + `application/rs-metadata+xml`) is accepted
/// on input; non-SDP parts are dropped and the response is always plain
/// `application/sdp`.
///
/// **Offerless re-INVITEs** (RFC 6337 §5, e.g. RFC 4028 keep-alive
/// refreshes) carry no SDP body and require us to replay the same answer
/// we last gave on this dialog — sending a different SDP would advertise
/// a different media negotiation and strict SBCs reject it. We cache the
/// last sent SDP on the per-dialog [SipSession] and replay it when the
/// request body is empty. If no cached SDP exists yet (very first INVITE
/// happened to be offerless) we fall back to a fixed minimal blackhole.
public class CallflowHold extends Callflow {

	private static final long serialVersionUID = 1L;

	/// Per-dialog cache of the SDP body we last sent on this [SipSession],
	/// used to satisfy offerless re-INVITEs without renegotiating media.
	private static final String LAST_SDP_ATTR = "org.vorpal.blade.callflowHold.lastSdp";

	private static final String BLACKHOLE_SDP = ""
			+ "v=0\r\n"
			+ "o=- 0 0 IN IP4 127.0.0.1\r\n"
			+ "s=-\r\n"
			+ "c=IN IP4 0.0.0.0\r\n"
			+ "t=0 0\r\n"
			+ "m=audio 9 RTP/AVP 0\r\n"
			+ "a=rtpmap:0 PCMU/8000\r\n"
			+ "a=inactive\r\n";

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(200);
		response.setHeader("Allow", "INVITE, ACK, BYE, CANCEL");

		String sessionExpires = request.getHeader("Session-Expires");
		if (sessionExpires != null) {
			if (!sessionExpires.toLowerCase().contains("refresher=")) {
				sessionExpires = sessionExpires + ";refresher=uac";
			}
			response.setHeader("Session-Expires", sessionExpires);
		}

		try {
			hold(request, response);
		} catch (MessagingException e) {
			throw new IOException("CallflowHold: failed to extract SDP from multipart body", e);
		}

		SipSession dialog = request.getSession();

		// Offerless / unparseable: replay the SDP we last sent on this dialog
		// so the keep-alive sees the same media negotiation as before.
		if (response.getContent() == null) {
			String cached = (String) dialog.getAttribute(LAST_SDP_ATTR);
			if (cached != null) {
				response.setContent(cached.getBytes(StandardCharsets.UTF_8), "application/sdp");
			}
		}

		// Nothing to replay (first INVITE on this dialog had no body) —
		// emit the static blackhole so the caller still gets an answer.
		if (response.getContent() == null) {
			response.setContent(BLACKHOLE_SDP.getBytes(), "application/sdp");
		}

		// Cache whatever we end up sending so the next offerless re-INVITE
		// on this dialog can replay it.
		Object body = response.getContent();
		if (body != null) {
			String sdp = (body instanceof String)
					? (String) body
					: new String((byte[]) body, StandardCharsets.UTF_8);
			dialog.setAttribute(LAST_SDP_ATTR, sdp);
		}

		sendResponse(response, (ack) -> {
			// do nothing
		});
	}

}
