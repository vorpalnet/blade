package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public class CallflowResponseCode extends Callflow {
	private static final long serialVersionUID = 1L;
	int responseCode;
	String reasonPhrase;

	public CallflowResponseCode(int responseCode, String reasonPhrase) {
		this.responseCode = responseCode;
		this.reasonPhrase = reasonPhrase;
	}

	public CallflowResponseCode(int responseCode) {
		this.responseCode = responseCode;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response;

		if (reasonPhrase != null) {
			response = request.createResponse(responseCode, reasonPhrase);
		} else {
			response = request.createResponse(responseCode);
		}

		sendResponse(response);
	}

}
