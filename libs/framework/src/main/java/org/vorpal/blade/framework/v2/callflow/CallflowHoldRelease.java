package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public class CallflowHoldRelease extends Callflow {

	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(200);
		sendResponse(response);
	}

}
