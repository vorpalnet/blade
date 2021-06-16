/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.framework.b2bua;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;

public class InitialInvite extends Callflow {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private Callback<SipServletRequest> loopOnPrack;
	B2buaListener sipServlet;

	public InitialInvite(B2buaListener sipServlet) {
		this.sipServlet = sipServlet;
	}

	@Override
	public void process(SipServletRequest request) throws Exception {
		aliceRequest = request;
		this.sipServlet.callerEvent(aliceRequest);

		SipApplicationSession appSession = aliceRequest.getApplicationSession();

		Address to = aliceRequest.getTo();
		Address from = aliceRequest.getFrom();

		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, from, to);
		bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
		copyContentAndHeaders(aliceRequest, bobRequest);
		bobRequest.setRequestURI(aliceRequest.getRequestURI());
		aliceRequest.getSession().setAttribute("USER_TYPE", "CALLER");
		bobRequest.getSession().setAttribute("USER_TYPE", "CALLEE");
		linkSessions(aliceRequest.getSession(), bobRequest.getSession());

		// Change Request URI here
		this.sipServlet.callStarted(bobRequest);
		this.sipServlet.calleeEvent(bobRequest);
		loopOnPrack = s -> sendRequest(bobRequest, (bobResponse) -> {
			if (bobResponse.getStatus() >= 200) {
				this.sipServlet.callAnswered(bobResponse);
			}

			this.sipServlet.calleeEvent(bobResponse);

			SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
			copyContentAndHeaders(bobResponse, aliceResponse);
			this.sipServlet.callerEvent(aliceResponse);

			sendResponse(aliceResponse, (aliceAckOrPrack) -> {
				this.sipServlet.callerEvent(aliceAckOrPrack);

				if (aliceAckOrPrack.getMethod().equals(PRACK)) {
					SipServletRequest bobPrack = bobResponse.createPrack();
					copyContentAndHeaders(aliceAckOrPrack, bobPrack);
					loopOnPrack.accept(bobPrack);
				} else if (aliceAckOrPrack.getMethod().equals(ACK)) {
					SipServletRequest bobAck = bobResponse.createAck();
					copyContentAndHeaders(aliceAckOrPrack, bobAck);
					this.sipServlet.calleeEvent(bobAck);
					sendRequest(bobAck);
					linkSessions(aliceAckOrPrack.getSession(), bobAck.getSession());

				} else if (aliceAckOrPrack.getMethod().equals(CANCEL)) {
					SipServletRequest bobCancel = bobRequest.createCancel();
					copyContentAndHeaders(aliceAckOrPrack, bobCancel);
					sipServlet.calleeEvent(bobCancel);
					sendRequest(bobCancel, (bobCancelResponse) -> {
						this.sipServlet.calleeEvent(bobCancel);
						SipServletResponse aliceCancelResponse = createResponse(aliceAckOrPrack, bobCancelResponse, true);
						this.sipServlet.callerEvent(aliceCancelResponse);
						sendResponse(aliceCancelResponse);
					});
				}

				// implement GLARE here

			});
		});
		loopOnPrack.accept(bobRequest);
	}

}
