package org.vorpal.blade.test.client;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class TestClientBye extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sendResponse(request.createResponse(200));
	}

}
