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
import java.util.Collection;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * Legacy BYE callflow with complex glare handling.
 *
 * @deprecated Use {@link Terminate} instead.
 */
@Deprecated
public class ByeOld extends Callflow {
	private static final long serialVersionUID = 1L;

	/** HTTP status code for successful response. */
	private static final int STATUS_OK = 200;

	/** Session expiration time in minutes for cleanup. */
	private static final int CLEANUP_EXPIRATION_MINUTES = 5;

	/** Delay in milliseconds before sending BYE to avoid glare. */
	private static final int GLARE_DELAY_MS = 3000;

	private SipServletRequest aliceRequest;
	private B2buaServlet b2buaListener;

	public ByeOld() {
		this.b2buaListener = null;
	}

	public ByeOld(B2buaServlet b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// Assumptions:
		// * Any glare was resolved by AsyncSipServlet.

		SipApplicationSession appSession = request.getApplicationSession();

		SipServletRequest aliceRequest = request;
		SipServletResponse aliceResponse;
		SipSession aliceSession = aliceRequest.getSession();
		SipSession bobSession = getLinkedSession(aliceSession);
		StringBuilder warnings = new StringBuilder();
		boolean sendByeLater = false;
		boolean cancelSent = false;

		SipServletRequest bobRequest;

		// Regardless of what happens below, clean up memory in 1 minute;
		request.getApplicationSession().setInvalidateWhenReady(true); // Why would this be false?
		request.getApplicationSession().setExpires(CLEANUP_EXPIRATION_MINUTES);

		// Send a 200 OK first. This is too important to wait on downstream processes.
		try {

			if (aliceSession != null //
					&& aliceSession.isValid() //
					&& aliceSession.getState() != State.TERMINATED) {
				aliceResponse = request.createResponse(STATUS_OK);
				sendResponse(aliceResponse);
			} else {
				// probably CANCELed. Don't stress over it.
			}

		} catch (Exception e1) {
			warnings.append("Unable to send 200 OK to BYE request. ").append(e1.getClass().getName()).append(": ")
					.append(e1.getMessage()).append("; ");
		}

		try {

			if (bobSession != null) {

				Collection<SipServletRequest> requests;
				Iterator<SipServletRequest> itr;
				if (bobSession.isValid()) {

					// Check for any UAC INVITEs that need to be CANCELed
					requests = bobSession.getActiveRequests(UAMode.UAC);
					itr = requests.iterator();
					while (itr.hasNext()) {
						bobRequest = itr.next();
						if (bobRequest.getMethod().equals(INVITE)) {
							warnings.append("Outbound UAC INVITE awaiting final response. Sending CANCEL. ");

							try {
								cancelSent = true;
								sendRequest(bobRequest.createCancel());
							} catch (Exception cancelEx1) {
								warnings.append("Unable to send UAC INVITE. ").append(cancelEx1.getClass().getName())
										.append(": ").append(cancelEx1.getMessage()).append("; ");
							}

						} else {
							sendByeLater = true;
							warnings.append("Glare. Outbound ").append(bobRequest.getMethod())
									.append(" awaiting final response. Must send BYE later; ");
						}
					}

					// Check for any UAS INVITEs that need to be CANCELed
					requests = bobSession.getActiveRequests(UAMode.UAS);
					itr = requests.iterator();
					while (itr.hasNext()) {
						bobRequest = itr.next();
						if (bobRequest.getMethod().equals(INVITE)) {
							warnings.append("Outbound UAS INVITE awaiting final response. Sending CANCEL. ");

							try {
								cancelSent = true;
								sendRequest(bobRequest.createCancel());
							} catch (Exception cancelEx2) {
								warnings.append("Unable to send UAS INVITE. ").append(cancelEx2.getClass().getName())
										.append(": ").append(cancelEx2.getMessage()).append("; ");
							}

						} else {
							sendByeLater = true;
							warnings.append("Glare. Outbound ").append(bobRequest.getMethod())
									.append(" awaiting final response. Must send BYE later. ");
						}
					}

					if (!cancelSent) { // Don't send a BYE if a CANCEL was already sent

						if (!sendByeLater) {

							// Send outbound BYE.
							try {

								// Send BYE or CANCEL

								sipLogger.finer(bobSession, "bobSession.getState=" + bobSession.getState());
								switch (bobSession.getState()) {
								case CONFIRMED:
									bobRequest = bobSession.createRequest(BYE);
									copyContentAndHeaders(aliceRequest, bobRequest);

									if (this.b2buaListener != null) {
										b2buaListener.callCompleted(bobRequest);
									}

									sendRequest(bobRequest);
									break;

								case EARLY:
									bobRequest = bobSession.createRequest(CANCEL);
									copyContentAndHeaders(aliceRequest, bobRequest);

									if (this.b2buaListener != null) {
										b2buaListener.callCompleted(bobRequest);
									}

									sendRequest(bobRequest);
									break;

								case INITIAL:
									break;

								case TERMINATED:
									break;

								}

							} catch (Exception byeException) {
								warnings.append("Unable to send BYE to linked session. ")
										.append(byeException.getClass().getName()).append(": ")
										.append(byeException.getMessage()).append("; ");
							}
						} else {

							// Send outbound BYE 3 seconds later to avoid glare.
							startTimer(appSession, GLARE_DELAY_MS, false, (timer) -> {

								try {
									SipServletRequest laterBye = bobSession.createRequest(BYE);
									copyContentAndHeaders(aliceRequest, laterBye);

									if (this.b2buaListener != null) {
										b2buaListener.callCompleted(laterBye);
									}

									sendRequest(laterBye);
								} catch (Exception byeException) {
									String warning = SettingsManager.getApplicationName() + " : Bye.process - "
											+ "Due to potental glare, a 3 second timer was set to send a BYE, but it failed. "
											+ byeException.getClass().getName() + ": " + byeException.getMessage()
											+ "; ";
									sipLogger.warning(bobSession, warning);
									sipLogger.getParent().warning(warning);
								}

							});

						}
					}

				} else {
					sipLogger.finer(aliceRequest, "Bye.process - Outbound linked session exists, but is invalid.");
				}
			} else {
				sipLogger.finer(aliceRequest, "Bye.process - Linked session does not exist.");
			}

		} catch (Exception e1) {
			warnings.setLength(0);
			warnings.append("Unable to send 200 OK to BYE request. ").append(e1.getClass().getName()).append(": ")
					.append(e1.getMessage()).append("; ");
		}

		if (warnings.length() > 0) {
			String warning = "Bye.process - " + warnings.toString();
			sipLogger.warning(request, warning);
			sipLogger.getParent().warning(SettingsManager.getApplicationName() + " : " + warning);
		}

	}

}
