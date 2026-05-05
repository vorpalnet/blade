package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/// Answer a (re-)INVITE with `200 OK` carrying a fixed "blackhole" SDP
/// (`c=IN IP4 0.0.0.0` + `a=inactive`), putting the leg on hold without
/// echoing the caller's offer. Always replies `application/sdp`; any
/// multipart parts (e.g. SIPREC `application/rs-metadata+xml`) in the
/// request are dropped from the response.
public class CallflowHold extends Callflow {

	private static final long serialVersionUID = 1L;

	private static final String BLACKHOLE_SDP = ""
			+ "v=0\r\n"
			+ "o=- 0 0 IN IP4 127.0.0.1\r\n"
			+ "s=-\r\n"
			+ "c=IN IP4 0.0.0.0\r\n"
			+ "t=0 0\r\n"
			+ "m=audio 23348 RTP/AVP 0\r\n"
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

		response.setContent(BLACKHOLE_SDP.getBytes(), "application/sdp");

		sendResponse(response, (ack) -> {
			// do nothing
		});
	}

}
