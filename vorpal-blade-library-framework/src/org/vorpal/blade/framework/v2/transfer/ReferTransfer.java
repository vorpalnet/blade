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
@startuml doc-files/refer_transfer.png
title Success: Alice transfers to Carol
hide footbox
participant Alice as alice
participant ReferTransfer as blade
participant Bob as bob
participant Carol as carol

alice     <-->      bob         : RTP

alice  <- blade                 : REFER
                                  note right: Refer-To: <sip:carol@vorpal.net>\nReferred-By: <sip:bob@vorpal.net>                                   
alice --> blade                 : 202 Accepted

alice ->  blade                 : INVITE                   
                                  note right: places call on hold
          blade ->  bob         : INVITE                   
          blade <-- bob         : 200 OK
alice <-- blade                 : 200 OK
alice ->  blade                 : ACK                                  
          blade ->  bob         : ACK                                  

alice ->  blade                 : INVITE
          blade ->        carol : INVITE                   
alice ->  blade                 : NOTIFY                   
                                  note right: 100 Trying
alice <-- blade                 : 200 OK (NOTIFY)

          blade <--       carol : 200 OK (INVITE)
alice  <- blade                 : 200 OK (INVITE)
alice ->  blade                 : ACK
          blade ->        carol : ACK
alice ->  blade                 : NOTIFY          
                                  note right: 200 OK
alice <-- blade                 : 200 OK (NOTIFY)          
alice     <-->            carol : RTP
          blade ->  bob         : BYE                                  
          blade <-- bob         : 200 OK
          
@enduml
*/

package org.vorpal.blade.framework.v2.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.transfer.api.Header;
import org.vorpal.blade.framework.v2.transfer.api.Header;

/**
 * The ReferTransfer callflow sends a REFER to Alice on behalf of Bob. Alice
 * then transfers to Carol.
 * <p>
 * <img src="doc-files/refer_transfer.png">
 */
public class ReferTransfer extends Transfer {
	static final long serialVersionUID = 1L;
	static private Logger sipLogger = Callflow.sipLogger;

	public SipServletResponse referResponse;

	public ReferTransfer(TransferListener referListener, TransferSettings transferSettings) {
		super(referListener, transferSettings);
	}

	@Override
	public void process(SipServletRequest transferorRequest) throws ServletException, IOException {
		try {

			// jwm - there should be a 202 response, but right now this is only invoked by
			// - REST APIs. should add a dummy 202 response in the future.

			SipSession transferorSession = transferorRequest.getSession();
			SipSession transfereeSession = getLinkedSession(transferorSession);

			transfereeRequest = transfereeSession.createRequest(REFER);
			copyContentAndHeaders(transferorRequest, transfereeRequest);

			// If this was method was invoked by the REST API, it might have set some extra
			// headers.
			if (this.inviteHeaders != null) {
				for (Header header : inviteHeaders) {
					targetRequest.setHeader(header.getName(), header.getValue());
				}
			}

			// save X-Previous-DN-Tmp for use later
			URI referTo = transferorRequest.getAddressHeader("Refer-To").getURI();
			transferorRequest.setAttribute("Refer-To", referTo);

			// User is notified a transfer is requested
			transferListener.transferRequested(transferorRequest);

			expectRequest(transfereeSession, CANCEL, (cancel) -> {
				sipLogger.finer(transfereeSession, "ReferTransfer.process - expectRequest CANCEL, Alice hangs up.");

				// Transferee (Alice) hangs up
				transferListener.transferAbandoned(transfereeRequest);

				sendRequest(continueRequest(transferorSession, cancel));
			});

			// Expect to receive NOTIFY messages from transferee (alice)
			expectRequest(transfereeSession, NOTIFY, (notify) -> {

				sipLogger.finer(transfereeSession, "ReferTransfer.process - expectRequest NOTIFY");

				// Place TransferListener logic here

				// Respond back to NOTIFY
				sendResponse(notify.createResponse(200));

				String sipfrag = null;

				if (notify.getContent() != null) {
					if (notify.getContent() instanceof String) {
						sipfrag = (String) notify.getContent();
					} else {
						sipfrag = new String(notify.getRawContent()).trim();
					}

					if (sipfrag != null) {
						// Can be one of these possibilities:
						// SIP/2.0 100 Trying -- Call Initiated
						// SIP/2.0 200 OK -- Call Answered
						// SIP/2.0 486 Busy Here -- Call Refused

						if (sipfrag.contains("100")) {
							// User is notified that transfer is initiated

// Bogus!
//							// Set Header X-Original-DN
//							URI xOriginalDN = (URI) targetRequest.getApplicationSession().getAttribute("X-Original-DN");
//							targetRequest.setHeader("X-Original-DN", xOriginalDN.toString());
//							// Set Header X-Previous-DN
//							URI xPreviousDN = (URI) targetRequest.getApplicationSession().getAttribute("X-Previous-DN");
//							targetRequest.setHeader("X-Previous-DN", xPreviousDN.toString());

							transferListener.transferInitiated(transfereeRequest);
						} else if (sipfrag.contains("200")) {
							// User is notified of a successful transfer
							// What response to use?

							SipSession callee = referResponse.getSession();
							callee.setAttribute("userAgent", "callee");
							SipSession caller = Callflow.getLinkedSession(callee);
							caller.setAttribute("userAgent", "caller");
							URI referTo2 = (URI) referResponse.getApplicationSession().getAttribute("Refer-To");

							if (referTo2 != null) {
								referResponse.getApplicationSession().setAttribute("X-Previous-DN", referTo2);
							}

							transferListener.transferCompleted(referResponse);
							sendRequest(transferorSession.createRequest(BYE));
						} else if (sipfrag.contains("486")) {

							// User is notified that the transfer target did not answer
							// What response to use?
							transferListener.transferDeclined(referResponse);

						}

					}

				}

			});

//			sendRequest(referRequest, (referResponse) -> {
			sendRequest(transfereeRequest, (referResponse) -> {
				this.referResponse = referResponse;

//				if (successful(referResponse)) {
//					// hang up. debugging. use the NOTIFY instead.
//					sendRequest(transferorSession.createRequest(BYE));
//				}

				if (failure(referResponse)) {
					// If refer fails, complete REST invocation as a failure
					transferListener.transferDeclined(referResponse);
				}

			});

		} catch (

		Exception e) {
			sipLogger.logStackTrace(transfereeRequest, e);
		}

	}

}
