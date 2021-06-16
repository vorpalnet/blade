package org.vorpal.blade.framework.b2bua;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.callflow.Callflow;

public class Bye extends Callflow {
	private static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private B2buaListener sipServlet;

	public Bye(B2buaListener sipServlet) {
		this.sipServlet = sipServlet;
	}

	@Override
	public void process(SipServletRequest request) throws Exception {

		
		SipServletRequest bobRequest;

		aliceRequest = request;
		SipSession sipSession = getLinkedSession(aliceRequest.getSession());

		bobRequest = sipSession.createRequest(request.getMethod());
		copyContentAndHeaders(aliceRequest, bobRequest);

		callEvents(aliceRequest, bobRequest);
		this.sipServlet.callCompleted(bobRequest);

		sendRequest(bobRequest, (bobResponse) -> {
			SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
			copyContentAndHeaders(bobResponse, aliceResponse);
			callEvents(aliceResponse, bobResponse);
			sendResponse(aliceResponse);
		});

	}

	private void callEvents(SipServletMessage alice, SipServletMessage bob) throws Exception {
		if (((String) bob.getSession().getAttribute("USER_TYPE")).equals("CALLEE")) {
			this.sipServlet.calleeEvent(bob);
			this.sipServlet.callerEvent(alice);
		} else {
			this.sipServlet.calleeEvent(alice);
			this.sipServlet.callerEvent(bob);
		}
	}

}
