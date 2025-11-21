package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletResponse;

/**
 * This class is for the special case where the call was canceled, yet a 200 OK
 * came back anyway.
 */
public class CallflowAckBye extends ClientCallflow {
	private static final long serialVersionUID = 1L;

	/**
	 * When receiving a 200 OK, send an ACK, followed by a BYE.
	 * 
	 * @param response
	 * @throws ServletException
	 * @throws IOException
	 */
	public void process(SipServletResponse response) throws ServletException, IOException {
		sendRequest(response.createAck());
		sendRequest(response.getSession().createRequest(BYE));
	}

}
