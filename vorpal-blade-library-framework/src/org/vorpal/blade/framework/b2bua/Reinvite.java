package org.vorpal.blade.framework.b2bua;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;

public class Reinvite extends Callflow {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
//	private Callback<SipServletRequest> loopOnPrack;
//	private Callback<SipServletResponse> bobCallback = null;
	private B2buaListener sipServlet;

	public Reinvite(B2buaListener sipServlet) {
		this.sipServlet = sipServlet;
	}

	@Override
	public void process(SipServletRequest request) throws Exception {

		aliceRequest = request;
		SipSession sipSession = getLinkedSession(aliceRequest.getSession());
		SipServletRequest bobRequest = sipSession.createRequest(INVITE);
		copyContentAndHeaders(aliceRequest, bobRequest);

		callEvents(aliceRequest, bobRequest);
//		sendRequest(bobRequest, bobCallback = (bobResponse) -> {
		sendRequest(bobRequest, (bobResponse) -> {
			SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
			copyContentAndHeaders(bobResponse, aliceResponse);
			callEvents(aliceResponse, bobResponse);
			sendResponse(aliceResponse, (aliceAckOrPrack) -> {
				
//				if (aliceAckOrPrack.getMethod().equals(PRACK)) {
//					SipServletRequest bobPrack = bobResponse.createPrack();
//					copyContentAndHeaders(aliceAckOrPrack, bobPrack);
//					callEvents(aliceAckOrPrack, bobPrack);
//					sendRequest(bobPrack, this.bobCallback);
//				} else {
				
					SipServletRequest bobAck = bobResponse.createAck();
					copyContentAndHeaders(aliceAckOrPrack, bobAck);
					callEvents(aliceAckOrPrack, bobAck);
					sendRequest(bobAck);
					
//				}
			});
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
