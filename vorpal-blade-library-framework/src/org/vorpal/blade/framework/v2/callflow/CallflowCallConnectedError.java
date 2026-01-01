package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

public class CallflowCallConnectedError extends Callflow {

	private static final long serialVersionUID = 1L;

	private Exception ex;

	public CallflowCallConnectedError(Exception ex) {
		this.ex = ex;
	}

	@Override
	public void process(SipServletRequest ack) throws ServletException, IOException {

		SipSession sipSession = ack.getSession();
		SipSession linkedSession = getLinkedSession(sipSession);

		Throwable cause = ex.getCause();
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}

		String reasonPhrase = AsyncSipServlet.convertCamelCaseToRegularWords(
				SettingsManager.getApplicationName() + " " + cause.getClass().getSimpleName());

		String reason = "SIP;cause=500;text=\"" + reasonPhrase + "\"";

		SipServletRequest bye = ack.getSession().createRequest(BYE);
		bye.setContent(Logger.stackTraceToString(ex), "text/plain");
		bye.setHeader("Reason", reason);

		sendRequest(bye);

		SipServletRequest downstreamAck = getLinkedSession(ack.getSession()).getActiveInvite(UAMode.UAC)
				.getFinalResponse().createAck();
		sendRequest(downstreamAck);

		SipServletRequest downstreamBye = linkedSession.createRequest(BYE);
		downstreamBye.setContent(Logger.stackTraceToString(ex), "text/plain");
		downstreamBye.setHeader("Reason", reason);
		sendRequest(downstreamBye);
	}

}
