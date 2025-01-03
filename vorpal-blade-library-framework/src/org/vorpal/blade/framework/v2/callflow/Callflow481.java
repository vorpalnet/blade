package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

public class Callflow481 extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sendResponse(request.createResponse(481));
	}

}
