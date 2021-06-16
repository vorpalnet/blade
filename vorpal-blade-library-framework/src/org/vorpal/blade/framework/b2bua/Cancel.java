package org.vorpal.blade.framework.b2bua;

import java.util.Collection;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.callflow.Callflow;

public class Cancel extends Callflow {
	private static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private B2buaListener sipServlet;

	public Cancel(B2buaListener sipServlet) {
		this.sipServlet = sipServlet;
	}

	@Override
	public void process(SipServletRequest request) throws Exception {
		aliceRequest = request;
		SipSession linkedSession = getLinkedSession(aliceRequest.getSession());

		Collection<SipServletRequest> requests = linkedSession.getActiveRequests(UAMode.UAC);
		for (SipServletRequest rq : requests) {
			if (rq.getSession().getState().equals(State.EARLY)) {
				sendRequest(copyContentAndHeaders(request, rq.createCancel()));
			}
		}
	}

//	private void callEvents(SipServletMessage alice, SipServletMessage bob) throws Exception {
//		if (((String) bob.getSession().getAttribute("USER_TYPE")).equals("CALLEE")) {
//			this.sipServlet.calleeEvent(bob);
//			this.sipServlet.callerEvent(alice);
//		} else {
//			this.sipServlet.calleeEvent(alice);
//			this.sipServlet.callerEvent(bob);
//		}
//	}

}
