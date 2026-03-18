package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/**
 * Handles errors that occur after a call has been connected (after 200 OK/ACK exchange).
 * Terminates both legs of a B2BUA call by sending BYE requests with error information
 * in the Reason header and stack trace in the body.
 */
public class CallflowCallConnectedError extends Callflow {

	private static final long serialVersionUID = 1L;
	private static final String REASON_HEADER = "Reason";
	private static final String TEXT_PLAIN = "text/plain";
	private static final String REASON_FORMAT = "SIP;cause=500;text=\"%s\"";

	private final Exception ex;

	/**
	 * Creates a new error handler for the given exception.
	 *
	 * @param ex the exception that caused the call to fail
	 */
	public CallflowCallConnectedError(Exception ex) {
		this.ex = ex;
	}

	/**
	 * Processes an ACK by terminating both call legs with BYE requests containing error details.
	 *
	 * @param ack the ACK request that triggered this callflow
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void process(SipServletRequest ack) throws ServletException, IOException {
		if (ack == null) {
			return;
		}
		SipSession sipSession = ack.getSession();
		if (sipSession == null) {
			return;
		}
		SipSession linkedSession = getLinkedSession(sipSession);

		Throwable cause = ex.getCause();
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause == null) {
			cause = ex;
		}

		String reasonPhrase = AsyncSipServlet.convertCamelCaseToRegularWords(
				SettingsManager.getApplicationName() + " " + cause.getClass().getSimpleName());
		String reason = String.format(REASON_FORMAT, reasonPhrase);

		// Send BYE to upstream (caller)
		SipServletRequest bye = sipSession.createRequest(BYE);
		bye.setContent(Logger.stackTraceToString(ex), TEXT_PLAIN);
		bye.setHeader(REASON_HEADER, reason);
		sendRequest(bye);

		// Send ACK and BYE to downstream (callee)
		if (linkedSession != null) {
			SipServletRequest activeInvite = linkedSession.getActiveInvite(UAMode.UAC);
			if (activeInvite != null && activeInvite.getFinalResponse() != null) {
				SipServletRequest downstreamAck = activeInvite.getFinalResponse().createAck();
				sendRequest(downstreamAck);
			}

			SipServletRequest downstreamBye = linkedSession.createRequest(BYE);
			downstreamBye.setContent(Logger.stackTraceToString(ex), TEXT_PLAIN);
			downstreamBye.setHeader(REASON_HEADER, reason);
			sendRequest(downstreamBye);
		}
	}
}
