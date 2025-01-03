package org.vorpal.blade.services.presence;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class PublishCallflow extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		SipServletResponse response = request.createResponse(200);
		response.setExpires(request.getExpires());
		sendResponse(response);

	}

}
