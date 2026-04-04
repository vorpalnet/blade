package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

/**
 * Base class for client-initiated callflows where the application originates calls.
 * Provides a no-op implementation of the process method since client callflows
 * typically handle responses rather than incoming requests.
 */
public class ClientCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	/**
	 * No-op implementation for client callflows.
	 * Override this method if you need to handle incoming requests.
	 *
	 * @param request the incoming SIP request (ignored)
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// no-op for client callflows
	}
}
