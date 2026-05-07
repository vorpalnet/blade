package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/// Answer a (re-)INVITE with `200 OK` carrying a "blackhole" SDP that
/// puts the leg on hold. When the request carries an offer, [Callflow#hold]
/// derives the answer from it: every `m=` port → 0, every `c=` →
/// `0.0.0.0` (or `::`), every direction attribute → `inactive`. Multipart
/// SIPREC (`application/sdp` + `application/rs-metadata+xml`) is accepted
/// on input; non-SDP parts are dropped and the response is always plain
/// `application/sdp`. If the request has no body (e.g. a re-INVITE asking
/// us to make the offer) we fall back to a fixed minimal blackhole SDP so
/// the caller still gets a well-formed answer.
public class CallflowHold extends Callflow {

	private static final long serialVersionUID = 1L;

	private static final String BLACKHOLE_SDP = ""
			+ "v=0\r\n"
			+ "o=- 0 0 IN IP4 127.0.0.1\r\n"
			+ "s=-\r\n"
			+ "c=IN IP4 0.0.0.0\r\n"
			+ "t=0 0\r\n"
			+ "m=audio 0 RTP/AVP 0\r\n"
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

		// No offer in the request — fall back to a static blackhole so the
		// caller still gets a well-formed answer and stays on hold.
		if (response.getContent() == null) {
			response.setContent(BLACKHOLE_SDP.getBytes(), "application/sdp");
		}

		sendResponse(response, (ack) -> {
			// do nothing
		});
	}

}
