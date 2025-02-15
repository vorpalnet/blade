/*
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

package org.vorpal.blade.services.transfer.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Expectation;

/**
 * This class implements a blind call transfer.
 * 
 * <pre>
 * {@code
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
 *     |                   | NOTIFY            |                   |   Subscription-State: pending;expires=3600
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
 *     |                   | NOTIFY            |                   |   Subscription-State: terminated;reason=noresource
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
 * }
 * </pre>
 */

public class BlindTransferOld extends Transfer {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;

	public BlindTransferOld(TransferListener referListener) {
		super(referListener);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			createRequests(request);

			// First copy any specified INVITE headers
			SipServletRequest intialInvite = (SipServletRequest) request.getApplicationSession()
					.getAttribute("INITIAL_INVITE");
			if (intialInvite != null) {
				preserveInviteHeaders(intialInvite, this.targetRequest);
			}

			// Second, copy any specified REFER headers (for this request)
			preserveReferHeaders(request, this.targetRequest);

			// Third, copy any specified REFER headers (for the original REFER which may or
			// may not be the same thing as #2)
			SipServletRequest intialRefer = (SipServletRequest) request.getApplicationSession()
					.getAttribute("INITIAL_REFER");
			if (intialRefer != null) {
				preserveReferHeaders(intialRefer, this.targetRequest);
			}

			sendResponse(request.createResponse(202));

			SipServletRequest notify100 = request.getSession().createRequest(NOTIFY);
			notify100.setHeader(EVENT, "refer");
			notify100.setHeader(SUBSCRIPTION_STATE, "pending;expires=3600");
			notify100.setContent(TRYING_100.getBytes(), SIPFRAG);
			sendRequest(notify100);

//			// in the event the transferee hangs up before the transfer completes
			Expectation expectation = expectRequest(transfereeRequest.getSession(), BYE, (bye) -> {
				sipLogger.finer(bye, "transferee (alice) disconnected before transfer completed");
				sendResponse(bye.createResponse(200));
				sendRequest(targetRequest.createCancel());
			});

			// Expect the transferor to hang up after the NOTIFY that the transfer succeeded
			expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
				sendResponse(bye.createResponse(200));
			});

			// User is notified that transfer is initiated
			transferListener.transferInitiated(targetRequest);

			sendRequest(targetRequest, (targetResponse) -> {

				if (successful(targetResponse)) {

					expectation.clear(); // no longer expect BYE from transferee

					SipServletRequest notify200 = request.getSession().createRequest(NOTIFY);
					notify200.setHeader(EVENT, "refer");
					//
					// https://www.dialogic.com/webhelp/csp1010/8.4.1_ipn3/sip_software_chap_-_sip_notify_subscription_state.htm

					notify200.setHeader(EVENT, "refer");
// bad					notify200.setHeader(SUBSCRIPTION_STATE, "active;expires=3600");
					notify200.setHeader(SUBSCRIPTION_STATE, "terminated;reason=noresource");
					String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " " + targetResponse.getReasonPhrase();
					notify200.setContent(sipFrag.getBytes(), SIPFRAG);
					sendRequest(notify200);

					// User is notified of a successful transfer
					transferListener.transferCompleted(targetResponse);

					copyContent(targetResponse, transfereeRequest);
					sendRequest(transfereeRequest, (transfereeResponse) -> {

						if (successful(transfereeResponse)) { // should always be the case
							linkSessions(transfereeRequest.getSession(), targetResponse.getSession());
							sendRequest(transfereeResponse.createAck());
							sendRequest(copyContent(transfereeResponse, targetResponse.createAck()));
						}

					});

				} else if (failure(targetResponse)) {
					sipLogger.finer(targetResponse, "target (carol) refused the call");

					// User is notified that the transfer target did not answer
					transferListener.transferDeclined(targetResponse);

					SipServletRequest notifyFailure = request.getSession().createRequest(NOTIFY);
					String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " " + targetResponse.getReasonPhrase();
					notifyFailure.setHeader(EVENT, "refer");
					notifyFailure.setHeader(SUBSCRIPTION_STATE, "terminated;reason=noresource");
					notifyFailure.setContent(sipFrag.getBytes(), SIPFRAG);

					sendRequest(notifyFailure, (notifyFailureResponse) -> {
					});

				}

			});

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}

	}

}
