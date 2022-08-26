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

/**
 *  Notes: https://www.dialogic.com/webhelp/BorderNet2020/2.2.0/WebHelp/sip_rfr_calltrans.htm
 * 
 *   ALICE              Transfer              BOB                CAROL
 * transferee                              transferor           target
 * ----------           --------              ---               ------
 *     |                   |                   |                   |
 *     | RTP               |                   |                   |
 *     |<=====================================>|                   |
 *     |                   |                   |                   |
 *     |                   |             REFER |                   |   To:          transferee
 *     |                   |<------------------|                   |   Refer-To:    target
 *     |                   |                   |                   |   Referred-By: transferor
 *     |                   |                   |                   |   
 *     |                   | 202 Accepted      |                   |   
 *     |                   |------------------>|                   |
 *     |                   | NOTIFY            |                   |   Subscription-State: pending
 *     |                   |------------------>|                   |   Event: refer
 *     |                   |            200 OK |                   |   Content-Type: message/sipfrag
 *     |                   |<------------------|                   |   Content: SIP/2.0 100 Trying
 *     |                   |                   |                   |   
 *     |                   | INVITE            |                   |
 *     |                   |-------------------------------------->|   empty INVITE to get SDP
 *     |                   |                   |       180 Ringing |
 *     |                   |<--------------------------------------|
 *     |                   |                   |            200 OK |
 *     |                   |<-------------------------------(sdp)--|
 *     |                   | NOTIFY            |                   |   Subscription-State: active
 *     |                   |------------------>|                   |   Event:              refer
 *     |                   |            200 OK |                   |   Content-Type:       message/sipfrag
 *     |                   |<------------------|                   |   Content:            SIP/2.0 200 OK
 *     |                   |                   |                   |   
 *     |          INVITE   |                   |                   |   
 *     |<-----------(sdp)--|                   |                   |   
 *     | 200 OK            |                   |                   |
 *     |--(sdp)----------->|                   |                   |
 *     |               ACK |                   |                   |
 *     |<------------------|                   |                   |
 *     |                   | ACK               |                   |
 *     |                   |--(sdp)------------------------------->|
 *     | RTP               |                   |                   |
 *     |<=========================================================>|
 *     |                   | BYE               |                   |
 *     |                   |------------------>|                   |
 *     |                   |            200 OK |                   |
 *     |                   |<------------------|                   |
 *     |                   |                   |                   |
*/

package org.vorpal.blade.framework.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Expectation;

public class BlindTransfer extends Transfer {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private Callback<SipServletRequest> loopOnPrack;

	public BlindTransfer(TransferListener referListener) {
		super(referListener);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			createRequests(request);

			sendResponse(request.createResponse(202));

			SipServletRequest notify100 = request.getSession().createRequest(NOTIFY);
			notify100.setHeader(EVENT, "refer");
			notify100.setHeader(SUBSCRIPTION_STATE, "pending;expires=3600");
			notify100.setContent(TRYING_100.getBytes(), SIPFRAG);
			sendRequest(notify100);

			// in the event the transferee hangs up before the transfer completes
			Expectation expectation = expectRequest(transfereeRequest.getSession(), BYE, (bye) -> {
				sendResponse(bye.createResponse(200));

				sipLogger.severe(bye,
						"isValid: " + bye.getSession().isValid() + ", state: " + bye.getSession().getState());

				if (false == bye.getSession().getState().equals(State.TERMINATED)) {
					sendRequest(targetRequest.createCancel());
					sendRequest(transferorRequest.getSession().createRequest(BYE));
				}

			});

//			// Expect the transferor to hang up after the NOTIFY that the transfer succeeded
//			expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
//				sendResponse(bye.createResponse(200));
//			});

			copyHeaders(request, targetRequest);
			targetRequest.removeHeader(REFER_TO);
			targetRequest.removeHeader(REFERRED_BY);

			copyHeaders(request, transfereeRequest);
			String user = ((SipURI) request.getAddressHeader(REFER_TO)).getUser();
			((SipURI) transfereeRequest.getRequestURI()).setUser(user);

			// User is notified that transfer is initiated
			transferListener.transferInitiated(targetRequest);

			sendRequest(targetRequest, (targetResponse) -> {

				if (successful(targetResponse)) {

					SipServletRequest notify200 = request.getSession().createRequest(NOTIFY);
					notify200.setHeader(EVENT, "refer");
					notify200.setHeader(SUBSCRIPTION_STATE, "active;expires=3600");
					notify200.setContent(OK_200.getBytes(), SIPFRAG);
					sendRequest(notify200);

					// User is notified of a successful transfer
					transferListener.transferCompleted(targetResponse);

					copyContent(targetResponse, transfereeRequest);
					sendRequest(transfereeRequest, (transfereeResponse) -> {
						linkSessions(transfereeRequest.getSession(), targetResponse.getSession());

						// Expect a BYE from the transferor
						expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
							sendResponse(bye.createResponse(200));
						});

						sendRequest(transfereeResponse.createAck());
						sendRequest(copyContent(transfereeResponse, targetResponse.createAck()));
					});

				} else if (failure(targetResponse)) {

					// Clear the BYE expectation
					expectation.clear();

					// User is notified that the transfer target did not answer
					transferListener.transferDeclined(targetResponse);

					SipServletRequest notifyFailure = request.getSession().createRequest(NOTIFY);
					String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " " + targetResponse.getReasonPhrase();
					notifyFailure.setHeader(EVENT, "refer");
//					notifyFailure.setHeader(SUBSCRIPTION_STATE, "active;expires=3600");
					notifyFailure.setHeader(SUBSCRIPTION_STATE, "terminated");
					notifyFailure.setContent(sipFrag.getBytes(), SIPFRAG);
					sendRequest(notifyFailure);

				}

			});

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}

	}

}
