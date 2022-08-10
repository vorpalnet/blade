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

package org.vorpal.blade.framework.deprecated.proxy.callflows;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.callflow.Callflow;

/*
 * 
 * ALICE                       BLADE BOB ;
 *   |                         |                         | ;
 *   | INVITE                  |                         | ; InitialInvite callflow starts here
 *   |------------------------>|                         | ;
 *   |                         | INVITE                  | ;
 *   |                         |------------------------>| ;
 *   |                         |             180 Ringing | ;
 *   |                         |<------------------------| ;
 *   | 180 Ringing             |                         | ;
 *   |<------------------------|                         | ;
 *   | CANCEL                  |                         | ; Cancel callflow starts here
 *   |------------------------>|                         | ;
 *   | 200 OK                  |                         | ;
 *   |<------------------------|                         | ;
 *   |                         |                  CANCEL | ;
 *   |                         |------------------------>| ;
 *   |                         |                  200 OK | ;
 *   |                         |<------------------------| ;
 *   |                         |  487 Request Terminated | ;
 *   |                         |<------------------------| ;
 *   |                         |                     ACK | ;
 *   |                         |------------------------>| ; 
 *   |  487 Request Terminated |                         | ;
 *   |<------------------------|                         | ;
 *   |                     ACK |                         | ;
 *   |------------------------>|                         | ;
 *
 */

public class ProxyCancel extends Callflow {
	private static final long serialVersionUID = 1L;
	private SipServletRequest aliceCancel;
//	private B2buaServlet b2buaListener;
	private SipServletRequest aliceInvite;
	private SipServletRequest bobInvite;

//	public ProxyCancel(B2buaServlet b2buaListener) {
//		this.b2buaListener = b2buaListener;
//	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		aliceCancel = request;

		for (SipServletRequest aliceRequest : request.getSession().getActiveRequests(UAMode.UAS)) {
			if (aliceRequest.getSession().getState().equals(State.EARLY) && aliceRequest.getMethod().equals("INVITE")) {
				aliceInvite = aliceRequest;
				break;
			}
		}

		SipSession linkedSession = getLinkedSession(aliceCancel.getSession());
		if (linkedSession.isValid()) {
			for (SipServletRequest bobRequest : linkedSession.getActiveRequests(UAMode.UAC)) {
				if (bobRequest.getSession().getState().equals(State.EARLY) && bobRequest.getMethod().equals("INVITE")) {
					bobInvite = bobRequest;
					break;
				}
			}

			sendResponse(aliceCancel.createResponse(200));

			SipServletRequest bobCancel = bobInvite.createCancel();
			copyContentAndHeaders(aliceCancel, bobCancel);
//			b2buaListener.callAbandoned(bobCancel);

			sendRequest(bobCancel, (bobCancelResponse) -> {
				if (bobCancelResponse.getStatus() == 487) {
					sendRequest(bobCancelResponse.createAck());
					sendResponse(copyContentAndHeaders(bobCancelResponse, aliceInvite.createResponse(487)),
							(bobAck) -> {
								// do nothing
							});
				}
			});

		}

	}

}
