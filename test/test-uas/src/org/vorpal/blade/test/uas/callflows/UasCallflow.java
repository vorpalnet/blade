package org.vorpal.blade.test.uas.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class UasCallflow extends Callflow {

	private static final long serialVersionUID = 1L;
	private Integer status;

	public UasCallflow(Integer status) {
		this.status = status;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		SipServletResponse response = request.createResponse(status);

		sendResponse(response, (ack) -> {
			sipLogger.fine(request, "Got my ACK!");
		});

		sipLogger.fine(REQUEST_CALLBACK_ + ACK + ": " + response.getAttribute(REQUEST_CALLBACK_ + ACK));
	}

}
