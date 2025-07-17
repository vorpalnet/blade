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

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/blind_transfer_success.png
title Blind Transfer Success: Bob transfers Alice to Carol
hide footbox
participant Alice as alice
participant BlindTransfer as blade
participant Bob as bob
participant Carol as carol

alice     <-->      bob         : RTP
          blade <-  bob         : REFER
                                  note right: Refer-To: <sip:carol@vorpal.net>\nMore Stuff                                   
          blade --> bob         : 202 Accepted
          blade ->  bob         : NOTIFY
                                  note right: Event: refer \nSubscription-State: pending;expires=3600 \nSIP/2.0 100 Trying                                
          blade <-- bob         : 200 OK            
          blade ->        carol : INVITE                   
          blade <--       carol : 180 Ringing                                  
          blade <--       carol : 200 OK (SDP)                                   
alice <-  blade                 : INVITE (SDP)                                                 
alice --> blade                 : 200 OK (SDP)
alice <-  blade                 : ACK
          blade ->        carol : ACK (SDP)
          blade ->  bob         : NOTIFY
                                  note right: Event: refer \nSubscription-State: terminated;reason=noresource \nSIP/2.0 200 OK
          blade <-- bob         : 200 OK
          blade <-  bob         : BYE
          blade --> bob         : 200 OK

@enduml
*/

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw. 
@startuml doc-files/blind_transfer_486.png
title Failure: Carol rejects the call
hide footbox
participant Alice as alice
participant BlindTransfer as blade
participant Bob as bob
participant Carol as carol

alice     <-->      bob         : RTP
          blade <-  bob         : REFER
                                  note right: Refer-To: <sip:carol@vorpal.net>\nMore Stuff                                   
          blade --> bob         : 202 Accepted
          blade ->  bob         : NOTIFY
                                  note right: Event: refer \nSubscription-State: pending;expires=3600 \nSIP/2.0 100 Trying                                
          blade <-- bob         : 200 OK            
          blade ->        carol : INVITE                   
          blade <--       carol : 180 Ringing                                  
          blade <--       carol : 486 Busy Here                                  
          blade ->  bob         : NOTIFY (SDP)
                                  note right: Event: refer \nSubscription-State: terminated;reason=rejected \nSIP/2.0 486 Busy Here
          blade <-- bob         : 200 OK            

@enduml
*/

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/blind_transfer_bye.png
title Failure: Alice hangs up
hide footbox
participant Alice as alice
participant BlindTransfer as blade
participant Bob as bob
participant Carol as carol

alice     <-->      bob         : RTP
          blade <-  bob         : REFER
                                  note right: Refer-To: <sip:carol@vorpal.net>\nMore Stuff                                   
          blade --> bob         : 202 Accepted
          blade ->  bob         : NOTIFY
                                  note right: Event: refer \nSubscription-State: pending;expires=3600 \nSIP/2.0 100 Trying                                
          blade <-- bob         : 200 OK            
          blade ->        carol : INVITE                   
          blade <--       carol : 180 Ringing
alice ->  blade                 : BYE
alice <-- blade                 : 200 OK
          blade ->        carol : CANCEL
          blade <--       carol : 487 Request Terminated
          blade ->  bob         : NOTIFY
                                  note right: Event: refer \nSubscription-State: terminated;reason=giveup \nSIP/2.0 200 OK
          blade <-- bob         : 200 OK
          blade <-  bob         : BYE
          blade --> bob         : 200 OK                                  

@enduml
*/

package org.vorpal.blade.framework.v2.transfer;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.Expectation;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.transfer.api.Header;
import org.vorpal.blade.framework.v2.transfer.api.TransferAPI;
import org.vorpal.blade.framework.v2.transfer.api.TransferResponse;

/**
 * The BlindTransfer callflow performs an 'unattended' transfer operation.
 * <p>
 * <img src="doc-files/blind_transfer_success.png">
 * <p>
 * <img src="doc-files/blind_transfer_486.png">
 * <p>
 * <img src="doc-files/blind_transfer_bye.png">
 */
public class BlindTransfer extends Transfer {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private boolean sendNotify = true;

	public BlindTransfer(TransferListener referListener, TransferSettings transferSettings) {
		super(referListener, transferSettings);
	}

	public BlindTransfer(TransferListener referListener, TransferSettings transferSettings, boolean sendNotify) {
		super(referListener, transferSettings);
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

			// If this was method was invoked by the REST API, it might have set some INVITE
			// headers.
			if (this.inviteHeaders != null) {
				for (Header header : inviteHeaders) {
					targetRequest.setHeader(header.getName(), header.getValue());
				}
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

				if (!sendNotify) {
					// jwm - 2025-05-14, we have to manually hang up on Bob.
					sipLogger.finest(transferorRequest, "Manually sending BYE to Bob");
					sendRequest(transferorRequest.getSession().createRequest(BYE), (bobByeResponse) -> {
						sipLogger.finest(bobByeResponse, "Received response from Bob for manual BYE request");
					});
				}

				transferListener.transferAbandoned(bye);

			});

			// In case transferor (bob) hangs up.
			Expectation bobExpectation = expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
				sipLogger.finer(transferorRequest, "transferor (bob) hangs up");
				sendResponse(bye.createResponse(200));
//				transferListener.transferInitiated(bye);
			});

			// User is notified that transfer is initiated
			// Set Header X-Original-DN
			URI xOriginalDN = (URI) targetRequest.getApplicationSession().getAttribute("X-Original-DN");
			targetRequest.setHeader("X-Original-DN", xOriginalDN.toString());
			// Set Header X-Previous-DN
			URI xPreviousDN = (URI) targetRequest.getApplicationSession().getAttribute("X-Previous-DN");
			targetRequest.setHeader("X-Previous-DN", xPreviousDN.toString());

			transferListener.transferInitiated(targetRequest);

			// Force Referred-By, ignore preserveReferHeaders
			this.targetRequest.removeHeader("Referred-By");
			String referredBy = referRequest.getHeader("Referred-By");

			if (referredBy != null) {
				this.targetRequest.setHeader("Referred-By", referredBy);
			}

			sendRequest(targetRequest, (targetResponse) -> {

				sipLogger.finer(targetResponse, "targetResponse status=" + targetResponse.getStatus());

				if (provisional(targetResponse)) {
					sipLogger.finer(targetResponse, "target (carol) sends provisional response "
							+ targetResponse.getStatus() + " " + targetResponse.getReasonPhrase());

				} else if (successful(targetResponse)) {
					
					sipLogger.finer(targetResponse, "target (carol) sends successful response "
							+ targetResponse.getStatus() + " " + targetResponse.getReasonPhrase());

					transferListener.transferCompleted(targetResponse);

					
					// Alice will no longer hangup, expect a BYE from Bob
					aliceExpectation.clear();

					copyContent(targetResponse, transfereeRequest);
					sendRequest(transfereeRequest, (transfereeResponse) -> {
						linkSessions(transfereeRequest.getSession(), targetResponse.getSession());
						sendRequest(transfereeResponse.createAck());
						sendRequest(copyContent(transfereeResponse, targetResponse.createAck()));

						// Send the SIP/2.0 200 OK to the transferor (bob)
						if (sendNotify) {
							SipServletRequest notify200 = referRequest.getSession().createRequest(NOTIFY);
							notify200.setHeader(EVENT, "refer");
							notify200.setHeader(SUBSCRIPTION_STATE, "terminated;reason=noresource");
							String sipFrag = "SIP/2.0 " + targetResponse.getStatus() + " "
									+ targetResponse.getReasonPhrase();
							notify200.setContent(sipFrag.getBytes(), SIPFRAG);

							sendRequest(notify200);
						} else {
							// Send a BYE to transferor (bob)
							sendRequest(referRequest.getSession().createRequest(BYE));
						}

						// User is notified of a successful transfer

						SipSession callee = targetResponse.getSession();
						callee.setAttribute("userAgent", "callee");
						SipSession caller = Callflow.getLinkedSession(callee);
						caller.setAttribute("userAgent", "caller");
						URI referTo2 = (URI) appSession.getAttribute("Refer-To");
						appSession.setAttribute("X-Previous-DN", referTo2);
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
