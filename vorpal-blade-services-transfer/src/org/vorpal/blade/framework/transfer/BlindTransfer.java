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
 *     |                   |             REFER |                   |   REFER 
 *     |                   |<------------------|                   |   To:          transferee
 *     |                   |                   |                   |   Refer-To:    target
 *     |                   |                   |                   |   Referred-By: transferor
 *     |                   |                   |                   |
 *     |          INVITE   |                   |                   |   INVITE
 *     |<------------------|                   |                   |   empty reINVITE to get SDP
 *     | 200 OK            |                   |                   |
 *     |--(sdp)----------->|                   |                   |
 *     |                   | 202 Accepted      |                   |   
 *     |                   |------------------>|                   |
 *     |                   | NOTIFY            |                   |   NOTIFY
 *     |                   |------------------>|                   |   Subscription-State: pending;expires=3600
 *     |                   |            200 OK |                   |   Event: refer
 *     |                   |<------------------|                   |   Content-Type: message/sipfrag
 *     |                   |                   |                   |   Content: SIP/2.0 100 Trying
 *     |                   | INVITE            |                   |
 *     |                   |--(sdp)------------------------------->|
 *     |                   |                   |       180 Ringing |
 *     |                   |<--------------------------------------|
 *     |                   |                   |            200 OK |
 *     |                   |<-------------------------------(sdp)--|
 *     |               ACK |                   |                   |
 *     |<-----------(sdp)--|                   |                   |
 *     |                   | ACK               |                   |
 *     |                   |-------------------------------------->|
 *     |                   | NOTIFY            |                   |   NOTIFY
 *     |                   |------------------>|                   |   Subscription-State: active;expires=3600
 *     |                   |            200 OK |                   |   Event:              refer
 *     |                   |<------------------|                   |   Content-Type:       message/sipfrag
 *     |                   |                   |                   |   Content:            SIP/2.0 200 OK
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

import org.vorpal.blade.framework.callflow.Callback;

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
			expectRequest(transfereeRequest.getSession(), BYE, (bye) -> {

				if (targetRequest.isCommitted() != false) {
					sendRequest(targetRequest.createCancel());
				}

				sendRequest(transferorRequest.getSession().createRequest(BYE), (byeResponse) -> {
					sendResponse(bye.createResponse(byeResponse.getStatus()));
				});
			});

			sendRequest(transfereeRequest, (transfereeResponse) -> {
				// Copy any headers that might be useful, but remove obvious REFER only headers
				copyHeaders(request, targetRequest);
				targetRequest.removeHeader(REFER_TO);
				targetRequest.removeHeader(REFERRED_BY);
				copyContent(transfereeResponse, targetRequest);

				// User can override the targetRequest parameters before sending
				transferListener.transferInitiated(targetRequest);

				sendRequest(targetRequest, (targetResponse) -> {

					if (successful(targetResponse)) {
						linkSessions(transfereeResponse.getSession(), targetResponse.getSession());

						// User is notified of a successful transfer
						transferListener.transferCompleted(targetResponse);

						SipServletRequest notify200 = request.getSession().createRequest(NOTIFY);
						notify200.setHeader(EVENT, "refer");
						notify200.setHeader(SUBSCRIPTION_STATE, "active;expires=3600");
						notify200.setContent(OK_200.getBytes(), SIPFRAG);
						sendRequest(notify200);

						sendRequest(copyContent(targetResponse, transfereeResponse.createAck()));
						sendRequest(targetResponse.createAck());

						// Is this needed?
						expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
							sendResponse(bye.createResponse(200));
						});

					} else if (this.failure(targetResponse)) {
						// User is notified that the transfer target did not answer
						transferListener.transferDeclined(targetResponse);

						if (targetResponse.getStatus() != 487) { // No point if canceled
							SipServletRequest notifyFailure = request.getSession().createRequest(NOTIFY);
							String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " "
									+ targetResponse.getReasonPhrase();
							notifyFailure.setHeader(EVENT, "refer");
							notifyFailure.setHeader(SUBSCRIPTION_STATE, "active;expires=3600");
							notifyFailure.setContent(sipFrag.getBytes(), SIPFRAG);
							sendRequest(notifyFailure);
						}

					}

				});

			});

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}

	}

}
