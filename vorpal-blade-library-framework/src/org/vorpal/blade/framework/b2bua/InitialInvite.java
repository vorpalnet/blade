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

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.callflow.Callflow;

public class InitialInvite extends Callflow {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private B2buaListener b2buaListener = null;

	public InitialInvite() {
	}

	public InitialInvite(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	/**
	 * This method looks for the "Session-Expires" header on either a request or
	 * response object. If it exists, it sets the SipApplicationSession to be the
	 * same (plus one minute for cleanup).
	 * 
	 * @param msg
	 * @throws ServletParseException
	 */
	public static void setSessionExpiration(SipServletMessage msg) throws ServletParseException {
		SipApplicationSession appSession = msg.getApplicationSession();

		String sessionExpires = null;
		Parameterable p = msg.getParameterableHeader("Session-Expires");
		if (p != null) {
			sessionExpires = p.getValue();
			if (sessionExpires != null) {
				appSession.setExpires((Integer.parseInt(sessionExpires) / 60) + 1);
			}
		}
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			aliceRequest = request;

			SipApplicationSession appSession = aliceRequest.getApplicationSession();

			Address to = aliceRequest.getTo();
			Address from = aliceRequest.getFrom();

			SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, from, to);
			bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
			copyContentAndHeaders(aliceRequest, bobRequest);
			bobRequest.setRequestURI(aliceRequest.getRequestURI());
			linkSessions(aliceRequest.getSession(), bobRequest.getSession());

			if (b2buaListener != null) {
				b2buaListener.callStarted(bobRequest);
			}

			sendRequest(bobRequest, (bobResponse) -> {

				if (false == aliceRequest.isCommitted()) {

					setSessionExpiration(bobResponse);

					SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
					copyContentAndHeaders(bobResponse, aliceResponse);

					if (successful(bobResponse)) {
						if (b2buaListener != null) {
							b2buaListener.callAnswered(aliceResponse);
						}
					} else if (failure(bobResponse)) {
						if (b2buaListener != null) {
							b2buaListener.callDeclined(aliceResponse);
						}
					}

					sendResponse(aliceResponse, (aliceAck) -> {
						if (aliceAck.getMethod().equals(PRACK)) {
							SipServletRequest bobPrack = copyContentAndHeaders(aliceAck, bobResponse.createPrack());
//							if (b2buaListener != null) {
//								b2buaListener.callEvent(bobPrack);
//							}
							sendRequest(bobPrack, (prackResponse) -> {
								sendResponse(aliceAck.createResponse(prackResponse.getStatus()));
							});
						} else if (aliceAck.getMethod().equals(ACK)) {
							SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
							if (b2buaListener != null) {
								b2buaListener.callConnected(bobAck);
							}
							sendRequest(bobAck);
						} else {
							// implement GLARE here?
						}

					});

				}
			});

		} catch (Exception e) {
			sipLogger.logStackTrace(request, e);
			throw e;
		}

	}

}
