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
import javax.servlet.sip.SipSession;
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

public class Cancel extends Callflow {
	private static final long serialVersionUID = 1L;
	private SipServletRequest aliceCancel;
	private B2buaServlet b2buaListener = null;
	private SipServletRequest bobInvite;

	/**
	 * Implements the CANCEL callflow with no callback hooks.
	 */
	public Cancel() {
	}

	/**
	 * Implements the CANCEL callflow with callback hooks for objects implementing
	 * the B2buaListener interface.
	 * 
	 * @param b2buaListener
	 */
	public Cancel(B2buaServlet b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// The container will send a 200 OK for CANCEL
		// sendResponse(request.createResponse(200));

		try {
			aliceCancel = request;
			SipSession linkedSession = getLinkedSession(aliceCancel.getSession());
			if (linkedSession != null) {
				bobInvite = linkedSession.getActiveInvite(UAMode.UAC);
				if (bobInvite != null) {
					SipServletRequest bobCancel = bobInvite.createCancel();
					if (b2buaListener != null) {
						b2buaListener.callAbandoned(bobCancel);
					}
					sendRequest(bobCancel, (bobCancelResponse) -> {
						// do nothing;
					});
				}
			} else {
				// Ugh! complicated to find outstanding unlinked request.
				sipLogger.fine(request, "CANCEL received, but no linked session. Ignoring request.");
			}
		} catch (Exception e) {
			sipLogger.warning(request, "CANCEL received, but unable to process...");
			sipLogger.severe(e);
		}
	}

}
