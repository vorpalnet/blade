package org.vorpal.blade.test.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class CancelGlare extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// For testing purposes, do not send CANCEL
		sipLogger.severe(request, "For testing purposes, do *NOT* send CANCEL...");
		sendResponse(request.createResponse(200));
	}

}
