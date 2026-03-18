package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletResponse;

/**
 * Handles the race condition where a CANCEL was sent but a 200 OK arrived anyway.
 * This callflow properly terminates the dialog by sending ACK followed by BYE.
 */
public class CallflowAckBye extends ClientCallflow {
	private static final long serialVersionUID = 1L;

	/**
	 * Processes a 200 OK response by acknowledging it and immediately terminating the call.
	 * This handles the SIP glare condition where CANCEL and 200 OK cross on the wire.
	 *
	 * @param response the 200 OK response to process
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	public void process(SipServletResponse response) throws ServletException, IOException {
		if (response == null) {
			return;
		}
		sendRequest(response.createAck());
		sendRequest(response.getSession().createRequest(BYE));
	}
}
