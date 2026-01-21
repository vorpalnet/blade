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

package org.vorpal.blade.framework.v2.b2bua;

import java.io.IOException;
import java.util.Iterator;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * Callflow for handling BYE and CANCEL requests in a B2BUA scenario. Terminates
 * both call legs by sending appropriate termination requests.
 */
public class Terminate extends Callflow {
	private static final long serialVersionUID = 1L;

	/** HTTP status code for successful response. */
	private static final int STATUS_OK = 200;

	private final B2buaListener b2buaListener;

	/**
	 * Constructs a Terminate callflow with the specified listener.
	 *
	 * @param b2buaListener the B2BUA listener to receive lifecycle callbacks, or
	 *                      null for no callbacks
	 */
	public Terminate(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// request can be either a BYE or CANCEL

		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();
		SipSession linkedSession;
		Exception exception = null;

		@SuppressWarnings("unchecked")
		Iterator<SipSession> itr = (Iterator<SipSession>) appSession.getSessions(SIP);
		while (itr.hasNext()) { // iterate through all possible linked sessions
			linkedSession = itr.next();

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(request, "Terminate.process - sipSession=" + getVorpalDialogId(sipSession)
						+ ", linkedSession=" + getVorpalDialogId(linkedSession));
			}

			if (linkedSession != sipSession) { // do not operate on self
				if (getLinkedSession(linkedSession) == sipSession) { // we found it!
					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request, "Terminate.process - sipSession.id=" + sipSession.getId() //
								+ ", sipSession.state=" + sipSession.getState() //
								+ ", sipSession.isValid=" + sipSession.isValid() //
								+ ", linkedSession.id=" + linkedSession.getId() //
								+ ", linkedSession.state=" + linkedSession.getState() //
								+ ", linkedSession.isValid=" + linkedSession.isValid() //
						);
					}

					SipServletRequest terminationRequest = null;

					switch (linkedSession.getState()) {

					case INITIAL: // has not been sent?
						linkedSession.invalidate();
						break;

					case EARLY: // 180 Ringing, sipSession will be in INITIAL state
						SipServletRequest activeInvite = linkedSession.getActiveInvite(UAMode.UAC);
						if (activeInvite != null) {
							terminationRequest = activeInvite.createCancel();
							copyContentAndHeaders(request, terminationRequest);

							if (b2buaListener != null) {
								SettingsManager.createEvent("callAbandoned", terminationRequest);
								b2buaListener.callAbandoned(terminationRequest);
								SettingsManager.sendEvent(terminationRequest);
							}
						}

						break;

					case CONFIRMED: // 200 OK
						terminationRequest = linkedSession.createRequest(BYE);
						copyContentAndHeaders(request, terminationRequest);

						if (b2buaListener != null) {
							SettingsManager.createEvent("callCompleted", terminationRequest);
							b2buaListener.callCompleted(terminationRequest);
							SettingsManager.sendEvent(terminationRequest);
						}

						break;

					case TERMINATED: // two simultaneous BYEs from both directions?
						// nothing you can do
						break;
					}

					try {
						if (terminationRequest != null) {
							sendRequest(terminationRequest);
						}
					} catch (Exception ex1) {
						sipLogger.warning(request,
								"Terminate.process - Failed to send termination request: " + ex1.getMessage());
						exception = ex1;
					}

				} else {

					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request,
								"Terminate.process - Unrelated sessions... How did they get there!? sipSession="
										+ getVorpalDialogId(sipSession) + ", linkedSession="
										+ getVorpalDialogId(linkedSession));
					}

				}

			}

		}

		// Send response immediately for fear of a downstream process eating the BYE
		if (request.getMethod().equals(BYE)) {
			sendResponse(request.createResponse(STATUS_OK));
		}

		if (exception != null) {
			throw new ServletException(exception);
		}

	}

}
