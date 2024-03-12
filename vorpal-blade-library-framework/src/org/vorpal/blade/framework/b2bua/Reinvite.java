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
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.callflow.Callflow;

public class Reinvite extends Callflow {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private B2buaServlet b2buaListener;

	public Reinvite() {
	}

	public Reinvite(B2buaServlet b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			aliceRequest = request;
			SipSession sipSession = getLinkedSession(aliceRequest.getSession());
			SipServletRequest bobRequest = sipSession.createRequest(INVITE);
			copyContentAndHeaders(aliceRequest, bobRequest);

			if (b2buaListener != null) {
				b2buaListener.requestEvent(bobRequest);
			}

			sendRequest(bobRequest, (bobResponse) -> {

				if (false==aliceRequest.isCommitted()) { // It may have been CANCELed			
					SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
					copyContentAndHeaders(bobResponse, aliceResponse);
					if (b2buaListener != null) {
						b2buaListener.responseEvent(aliceResponse);
					}

					sendResponse(aliceResponse, (aliceAck) -> {
						if (aliceAck.getMethod().equals(ACK)) {
							SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
							if (b2buaListener != null) {
								b2buaListener.requestEvent(bobAck);
							}
							sendRequest(bobAck);
						}
					});
				}

			});

		}catch (java.lang.IllegalStateException e) {
			// eat the exception; do not log it
		}catch (Exception e) {
			sipLogger.logStackTrace(request, e);
			throw e;
		}

	}

}
