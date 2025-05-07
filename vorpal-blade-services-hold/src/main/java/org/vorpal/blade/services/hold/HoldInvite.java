package org.vorpal.blade.services.hold;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class HoldInvite extends Callflow {

	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		String body;
		SipServletResponse response = request.createResponse(200);

		Object obj = request.getContent();
		if (obj instanceof String) {
			body = new String((String) obj);
		} else {
			body = new String((byte[]) obj);
		}

		body = body.replace("a=sendrecv", "a=inactive");

		response.setHeader("Allow", request.getHeader("Allow"));

		response.setContent(body, request.getContentType());

		sendResponse(response, (ack) -> {
			// do nothing;
		});

	}

//	static final String blackhole = "" //
//			+ "v=0\r\n" //
//			+ "o=- 15474517 1 IN IP4 127.0.0.1\r\n" //
//			+ "s=cpc_med\r\n" //
//			+ "c=IN IP4 0.0.0.0\r\n" //
//			+ "t=0 0\r\n" //
//			+ "m=audio 23348 RTP/AVP 0\r\n" //
//			+ "a=rtpmap:0 pcmu/8000\r\n" //
//			+ "a=inactive\r\n";

}
