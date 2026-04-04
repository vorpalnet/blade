package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

/**
 * A callflow that responds with 481 (Call/Transaction Does Not Exist).
 * Use this callflow when a request arrives for a dialog or transaction
 * that is no longer valid or has been terminated.
 */
public class Callflow481 extends Callflow {
	private static final long serialVersionUID = 1L;
	private static final int RESPONSE_CODE_481 = 481;

	/**
	 * Processes the request by sending a 481 response.
	 *
	 * @param request the incoming SIP request
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		if (request == null) {
			return;
		}
		sendResponse(request.createResponse(RESPONSE_CODE_481));
	}
}
