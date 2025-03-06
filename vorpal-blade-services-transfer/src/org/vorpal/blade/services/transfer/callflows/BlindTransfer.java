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
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Expectation;

/**
 * This class implements a blind transfer.
 * 
 * <pre>{@code
 * 
 * Success: Bob transfers Alice to Carol
 * =======================================================================================                               
 *                                   [BlindTransfer]<----------REFER---[bob]             ; Refer-To: <sip:carol@vorpal.net>
 *                                   [BlindTransfer]-------------202-->[bob]             ; Accepted (REFER)
 *                                   [BlindTransfer]----NOTIFY-(sdp)-->[bob]             ; Event: refer, Subscription-State: pending;expires=3600, SIP/2.0 100 Trying
 *                                   [BlindTransfer]----------INVITE-->[carol]           ; sip:carol@vorpal.net            
 *                                   [Callflow]<-----------------200---[bob]             ; OK (NOTIFY)
 *                                   [BlindTransfer]<------------180---[carol]           ; Ringing (INVITE)
 *                                   [BlindTransfer]<------200-(sdp)---[carol]           ; OK (INVITE)
 * [alice]<-----------INVITE-(sdp)---[BlindTransfer]                                     ; From: <sip:bob@vorpal.net>;tag=35ea0865
 * [alice]---------------200-(sdp)-->[BlindTransfer]                                     ; OK (INVITE)
 * [alice]<--------------------ACK---[BlindTransfer]                                     ;                                 
 *                                   [BlindTransfer]-------ACK-(sdp)-->[carol]           ;                                 
 *                                   [BlindTransfer]----NOTIFY-(sdp)-->[bob]             ; Event: refer, Subscription-State: terminated;reason=noresource, SIP/2.0 200 OK
 *                                   [BlindTransfer]<------------200---[bob]             ; OK (NOTIFY)
 *                                   [BlindTransfer]<------------BYE---[bob]             ;                                 
 *                                   [BlindTransfer]-------------200-->[bob]             ; OK (BYE)
 * 
 * Failure: Carol rejects the call, Bob reinvites Alice
 * =======================================================================================
 *                                   [BlindTransfer]<----------REFER---[bob]             ; Refer-To: <sip:carol@vorpal.net>
 *                                   [BlindTransfer]-------------202-->[bob]             ; Accepted (REFER)
 *                                   [BlindTransfer]----NOTIFY-(sdp)-->[bob]             ; Event: refer, Subscription-State: pending;expires=3600, SIP/2.0 100 Trying
 *                                   [BlindTransfer]----------INVITE-->[carol]           ; sip:carol@vorpal.net            
 *                                   [Callflow]<-----------------200---[bob]             ; OK (NOTIFY)
 *                                   [BlindTransfer]<------------486---[carol]           ; Busy Here (INVITE)
 *                                   [BlindTransfer]----NOTIFY-(sdp)-->[bob]             ; Event: refer, Subscription-State: terminated;reason=rejected, SIP/2.0 486 Busy Here
 *                                   [BlindTransfer]<------------200---[bob]             ; OK (NOTIFY)
 *                                   [Reinvite]<--------INVITE-(sdp)---[bob]             ; To: "Alice" <sip:alice@vorpal.net>;tag=2bbbce8c
 * [alice]<-----------INVITE-(sdp)---[Reinvite]                                          ; From: <sip:bob@vorpal.net>;tag=e78dc7cf
 * [alice]---------------200-(sdp)-->[Reinvite]                                          ; OK (INVITE)
 *                                   [Reinvite]------------200-(sdp)-->[bob]             ; OK (INVITE)
 *                                   [Reinvite]<-----------------ACK---[bob]             ;                                 
 * [alice]<--------------------ACK---[Reinvite]                                          ;                                 
 * 
 * 
 * Failure: Alice gives up. Bob and Carol hangup.
 * =======================================================================================
 *                                   [BlindTransfer]<----------REFER---[bob]             ; Refer-To: <sip:carol@vorpal.net>
 *                                   [BlindTransfer]-------------202-->[bob]             ; Accepted (REFER)
 *                                   [BlindTransfer]----NOTIFY-(sdp)-->[bob]             ; Event: refer, Subscription-State: pending;expires=3600, SIP/2.0 100 Trying
 *                                   [BlindTransfer]----------INVITE-->[carol]           ; sip:carol@vorpal.net            
 *                                   [Callflow]<-----------------200---[bob]             ; OK (NOTIFY)
 *                                   [BlindTransfer]<------------180---[carol]           ; Ringing (INVITE)
 * [alice]---------------------BYE-->[BlindTransfer]                                     ;                                 
 * [alice]<--------------------200---[BlindTransfer]                                     ; OK (BYE)
 *                                   [BlindTransfer]----------CANCEL-->[carol]           ;                                 
 *                                   [BlindTransfer]<------------487---[carol]           ; Request Terminated (INVITE)
 *                                   [BlindTransfer]----NOTIFY-(sdp)-->[bob]             ; Event: refer, Subscription-State: terminated;reason=giveup, SIP/2.0 200 OK
 *                                   [BlindTransfer]<------------200---[bob]             ; OK (NOTIFY)
 *                                   [BlindTransfer]<------------BYE---[bob]             ;                                 
 *                                   [BlindTransfer]-------------200-->[bob]             ; OK (BYE)
 *
 * }</pre>
 */

public class BlindTransfer extends Transfer {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private boolean sendNotify = true;

	public BlindTransfer(TransferListener referListener) {
		super(referListener);
	}

	public BlindTransfer(TransferListener referListener, boolean sendNotify) {
		super(referListener);
		this.sendNotify = sendNotify;
	}

	@Override
	public void process(SipServletRequest referRequest) throws ServletException, IOException {
		try {
			// request is REFER from transferor (bob)
			SipApplicationSession appSession = referRequest.getApplicationSession();

			// save X-Previous-DN-Tmp for use later
			URI referTo = referRequest.getAddressHeader("Refer-To").getURI();
			appSession.setAttribute("Refer-To", referTo);

			// User is notified a transfer is requested
			transferListener.transferRequested(referRequest);

			createRequests(referRequest);

			// First copy any specified INVITE headers
			SipServletRequest intialInvite = (SipServletRequest) referRequest.getApplicationSession()
					.getAttribute("INITIAL_INVITE");
			if (intialInvite != null) {
				preserveInviteHeaders(intialInvite, this.targetRequest);
			}

			// Second, copy any specified REFER headers (for this request)
			preserveReferHeaders(referRequest, this.targetRequest);

			// Third, copy any specified REFER headers (for the original REFER which may or
			// may not be the same thing as #2)
			SipServletRequest intialRefer = (SipServletRequest) referRequest.getApplicationSession()
					.getAttribute("INITIAL_REFER");
			if (intialRefer != null) {
				preserveReferHeaders(intialRefer, this.targetRequest);
			}

			sendResponse(referRequest.createResponse(202));

			if (sendNotify) {
				SipServletRequest notify100 = referRequest.getSession().createRequest(NOTIFY);
				notify100.setHeader(EVENT, "refer");
				notify100.setHeader(SUBSCRIPTION_STATE, "pending;expires=3600");
				notify100.setContent(TRYING_100.getBytes(), SIPFRAG);
				sendRequest(notify100);
			}

			// in the event the transferee hangs up before the transfer completes
			Expectation aliceExpectation = expectRequest(transfereeRequest.getSession(), BYE, (bye) -> {
				sipLogger.finer(bye, "transferee (alice) disconnected before transfer completed");
				sendResponse(bye.createResponse(200));
				sendRequest(targetRequest.createCancel());
			});

			Expectation bobExpectation = expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
				sipLogger.finer(transferorRequest, "transferor (bob) hangs up");
				sendResponse(bye.createResponse(200));
			});

			// Set Header X-Original-DN
			URI xOriginalDN = (URI) appSession.getAttribute("X-Original-DN");
			targetRequest.setHeader("X-Original-DN", xOriginalDN.toString());
			// Set Header X-Previous-DN
			URI xPreviousDN = (URI) appSession.getAttribute("X-Previous-DN");
			targetRequest.setHeader("X-Previous-DN", xPreviousDN.toString());
			// now update X-Previous-DN for future use
			referTo = (URI) appSession.getAttribute("Refer-To");
			appSession.setAttribute("X-Previous-DN", referTo);

			// User is notified that transfer is initiated
			transferListener.transferInitiated(targetRequest);

			sendRequest(targetRequest, (targetResponse) -> {

				sipLogger.finer(targetResponse, "targetResponse status=" + targetResponse.getStatus());

				if (provisional(targetResponse)) {
					sipLogger.finer(targetResponse, "target (carol) sends provisional response "
							+ targetResponse.getStatus() + " " + targetResponse.getReasonPhrase());

				} else if (successful(targetResponse)) {
					sipLogger.finer(targetResponse, "target (carol) sends successful response "
							+ targetResponse.getStatus() + " " + targetResponse.getReasonPhrase());

					// Alice will no longer hangup, expect a BYE from Bob
					aliceExpectation.clear();

					copyContent(targetResponse, transfereeRequest);
					sendRequest(transfereeRequest, (transfereeResponse) -> {

						sipLogger.finer(transfereeResponse,
								"transfereeResponse status=" + transfereeResponse.getStatus());

						sipLogger.finer(transfereeResponse,
								"linkSessions(transfereeRequest.getSession(), targetResponse.getSession());");
						linkSessions(transfereeRequest.getSession(), targetResponse.getSession());

						sipLogger.finer(transfereeResponse, "sendRequest(transfereeResponse.createAck());");
						sendRequest(transfereeResponse.createAck());

						sipLogger.finer(transfereeResponse,
								"sendRequest(copyContent(transfereeResponse, targetResponse.createAck()));");
						sendRequest(copyContent(transfereeResponse, targetResponse.createAck()));

						sipLogger.finer(transfereeResponse, "sendNotify=" + sendNotify);

						// Send the SIP/2.0 200 OK to the transferor (bob)
						if (sendNotify) {
							SipServletRequest notify200 = referRequest.getSession().createRequest(NOTIFY);
							notify200.setHeader(EVENT, "refer");
							notify200.setHeader(SUBSCRIPTION_STATE, "terminated;reason=noresource");
							String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " "
									+ targetResponse.getReasonPhrase();
							notify200.setContent(sipFrag.getBytes(), SIPFRAG);

							sipLogger.finer(notify200, "sending notify... " + sipFrag);

							sendRequest(notify200);
						} else {
							sipLogger.finer(referRequest, "sending BYE... referRequest.getSession() is null? "
									+ (referRequest.getSession() == null));
							sipLogger.finer(referRequest,
									"sending BYE... Using session id=" + referRequest.getSession().getId());

							sipLogger.finer(referRequest, "sending BYE...");
							// Send a BYE to transferor (bob)
							sendRequest(referRequest.getSession().createRequest(BYE));
							sipLogger.finer(referRequest, "BYE Sent.");
						}

						// User is notified of a successful transfer
						transferListener.transferCompleted(targetResponse);

					});

				} else if (failure(targetResponse)) {
					sipLogger.finer(targetResponse, "target (carol) sends failure response "
							+ targetResponse.getStatus() + " " + targetResponse.getReasonPhrase());

					if (targetResponse.getStatus() == 487) {
						sipLogger.finer(targetResponse, "transferee (alice) has decided to 'giveup'");
						transferListener.transferAbandoned(referRequest);

						// Instead of sending the failure notice, we pretend everything is successful so
						// Bob will hang up
						if (sendNotify) {
							SipServletRequest notifyFailure = referRequest.getSession().createRequest(NOTIFY);
							String sipFrag = "SIP/2.0 200 OK";
							notifyFailure.setHeader(EVENT, "refer");
							notifyFailure.setHeader(SUBSCRIPTION_STATE, "terminated;reason=giveup");
							notifyFailure.setContent(sipFrag.getBytes(), SIPFRAG);
							sendRequest(notifyFailure);
						}

					} else {
						sipLogger.finer(targetResponse, "target (carol) has 'rejected' the call");

						// User is notified that the transfer target did not answer
						transferListener.transferDeclined(targetResponse);

						// Bob won't send a BYE, but instead reINVITE.
						bobExpectation.clear();

						// If Alice hangs up, let some other callflow handle it
						aliceExpectation.clear();

						if (sendNotify) {
							SipServletRequest notifyFailure = referRequest.getSession().createRequest(NOTIFY);
							String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " "
									+ targetResponse.getReasonPhrase();
							notifyFailure.setHeader(EVENT, "refer");
							notifyFailure.setHeader(SUBSCRIPTION_STATE, "terminated;reason=rejected");
							notifyFailure.setContent(sipFrag.getBytes(), SIPFRAG);
							sendRequest(notifyFailure);
						}
					}

				}

			});

		} catch (Exception e) {
			sipLogger.logStackTrace(referRequest, e);
		}

	}

}
