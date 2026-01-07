package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/**
 * A configurable callflow that responds with a specific SIP response code.
 * Useful for quickly rejecting requests with appropriate status codes.
 */
public class CallflowResponseCode extends Callflow {
	private static final long serialVersionUID = 1L;

	private final int responseCode;
	private final String reasonPhrase;

	/**
	 * Creates a callflow that responds with the specified code and custom reason phrase.
	 *
	 * @param responseCode the SIP response code (e.g., 404, 486, 503)
	 * @param reasonPhrase the custom reason phrase for the response
	 */
	public CallflowResponseCode(int responseCode, String reasonPhrase) {
		this.responseCode = responseCode;
		this.reasonPhrase = reasonPhrase;
	}

	/**
	 * Creates a callflow that responds with the specified code and default reason phrase.
	 *
	 * @param responseCode the SIP response code (e.g., 404, 486, 503)
	 */
	public CallflowResponseCode(int responseCode) {
		this.responseCode = responseCode;
		this.reasonPhrase = null;
	}

	/**
	 * Processes the request by sending the configured response code.
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
		SipServletResponse response = (reasonPhrase != null)
				? request.createResponse(responseCode, reasonPhrase)
				: request.createResponse(responseCode);
		sendResponse(response);
	}
}
