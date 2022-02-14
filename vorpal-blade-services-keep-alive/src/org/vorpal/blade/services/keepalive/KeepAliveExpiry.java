package org.vorpal.blade.services.keepalive;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

public class KeepAliveExpiry extends KeepAliveCallflow {

	@Override
	public void handle(SipSession sipSession) {

		Iterator<SipSession> itr = (Iterator<SipSession>) sipSession.getApplicationSession().getSessions();

		SipSession ss;
		SipServletRequest bye;

		while (itr.hasNext()) {
			ss = itr.next();
			if (ss.isValid()) {
				try {
					bye = ss.createRequest(BYE);
					sipLogger.warning(bye,
							"Session expired... Sending BYE to: " + bye.getTo() + ", from: " + bye.getFrom());
					sendRequest(ss.createRequest(BYE));
				} catch (ServletException | IOException e) {
					sipLogger.logStackTrace(ss, e);
				}
			}

		}

	}

}
