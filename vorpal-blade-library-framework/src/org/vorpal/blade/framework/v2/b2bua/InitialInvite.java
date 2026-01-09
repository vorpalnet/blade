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

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/**
 * Callflow for handling initial INVITE requests in a B2BUA scenario.
 * Creates the outbound leg, links sessions, and orchestrates the call setup process.
 */
public class InitialInvite extends Callflow {
	private static final long serialVersionUID = 1L;

	// Attribute keys for session and request attributes
	private static final String ATTR_DO_NOT_PROCESS = "doNotProcess";
	private static final String ATTR_USER_AGENT = "userAgent";
	private static final String ATTR_X_ORIGINAL_DN = "X-Original-DN";
	private static final String ATTR_X_PREVIOUS_DN = "X-Previous-DN";
	private static final String ATTR_INITIAL_INVITE = "initial_invite";
	private static final String ATTR_SIP_ADDRESS = "sipAddress";
	private static final String ATTR_CALLFLOW = "callflow";
	private static final String HEADER_SESSION_EXPIRES = "Session-Expires";

	// User agent role identifiers
	private static final String ROLE_CALLER = "caller";
	private static final String ROLE_CALLEE = "callee";

	// Time conversion constant
	private static final int SECONDS_PER_MINUTE = 60;

	private SipServletRequest aliceRequest;
	private SipServletRequest bobRequest;
	private B2buaListener b2buaListener;
	private boolean doNotProcess;

	public InitialInvite() {
	}

	public InitialInvite(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	/**
	 * This method looks for the "Session-Expires" header on either a request or
	 * response object. If it exists, it sets the SipApplicationSession to be the
	 * same (plus one minute for cleanup). If no header is found, use the expiration
	 * value in the configuration file.
	 * 
	 * @param msg the SIP servlet message
	 */
	public static void setSessionExpiration(SipServletMessage msg) {
		if (msg == null) {
			return;
		}
		SipApplicationSession appSession = msg.getApplicationSession();
		if (appSession == null) {
			return;
		}

		try {
			String sessionExpires = null;
			Parameterable p = msg.getParameterableHeader(HEADER_SESSION_EXPIRES);
			if (p != null) {
				sessionExpires = p.getValue();
				if (sessionExpires != null) {
					appSession.setExpires((Integer.parseInt(sessionExpires) / SECONDS_PER_MINUTE) + 1);
				}
			}
			// If no header, use configuration file instead (handled in AsyncSipServlet)
		} catch (ServletParseException e) {
			// Invalid Session-Expires header format; ignore and use default expiration
		} catch (NumberFormatException e) {
			// Invalid numeric value in Session-Expires header; ignore and use default expiration
		}
	}

	/**
	 * This method allows the continuation of processing the transaction at a later
	 * time. Used in the development of the Queue service.
	 * 
	 * @throws ServletException
	 * @throws IOException
	 */
	@Override
	public void processContinue() throws ServletException, IOException {

		sendRequest(bobRequest, (bobResponse) -> {

			if (!aliceRequest.isCommitted()) {

				setSessionExpiration(bobResponse);

				SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
				copyContentAndHeaders(bobResponse, aliceResponse);

				if (successful(bobResponse)) {
					if (b2buaListener != null) {

						SipSession caller = aliceResponse.getSession();
						caller.setAttribute(ATTR_USER_AGENT, ROLE_CALLER);
						SipSession callee = Callflow.getLinkedSession(caller);
						if (callee != null) {
							callee.setAttribute(ATTR_USER_AGENT, ROLE_CALLEE);
						}

						try {
							b2buaListener.callAnswered(aliceResponse);
						} catch (Exception ex) {
							sipLogger.warning(aliceResponse, "InitialInvite.processContinue - catch #1");
							throw new ServletException(ex);
						}

					}
				} else if (failure(bobResponse)) {
					if (b2buaListener != null) {
						b2buaListener.callDeclined(aliceResponse);
					}
				}

				// Sometimes you want to arrest the processing of the transaction.
				// If either the callflow or the request are marked as 'doNotProcess', we won't
				boolean _doNotProcess = Boolean.TRUE.equals(bobRequest.getAttribute(ATTR_DO_NOT_PROCESS));
				this.doNotProcess = (this.doNotProcess || _doNotProcess);
				if (!this.doNotProcess) {

					sendResponse(aliceResponse, (aliceAck) -> {
						if (aliceAck.getMethod().equals(PRACK)) {
							SipServletRequest bobPrack = copyContentAndHeaders(aliceAck, bobResponse.createPrack());

//					if (b2buaListener != null) {
//						b2buaListener.callEvent(bobPrack);
//					}
							sendRequest(bobPrack, (prackResponse) -> {
								sendResponse(aliceAck.createResponse(prackResponse.getStatus()));
							});
						} else if (aliceAck.getMethod().equals(ACK)) {
							SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
							if (b2buaListener != null) {
								b2buaListener.callConnected(bobAck);
							}
							sendRequest(bobAck);
						} else {
							// implement GLARE here?
						}

					});

				}

			}
		});

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		aliceRequest = request;

		SipApplicationSession appSession = aliceRequest.getApplicationSession();

		Address to = aliceRequest.getTo();
		Address from = aliceRequest.getFrom();

		bobRequest = sipFactory.createRequest(appSession, INVITE, from, to);
		bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
		copyContentAndHeaders(aliceRequest, bobRequest);
		bobRequest.setRequestURI(aliceRequest.getRequestURI());
		linkSessions(aliceRequest.getSession(), bobRequest.getSession());

		// This is an API kludge to let the user know what callflow was used
		bobRequest.setAttribute(ATTR_CALLFLOW, this);

		if (b2buaListener != null) {
			
			Address xOriginalDnAddress = aliceRequest.getAddressHeader(ATTR_X_ORIGINAL_DN);
			URI xOriginalDN;
			if(xOriginalDnAddress!=null) {
				xOriginalDN = xOriginalDnAddress.getURI();
			}else {
				xOriginalDN  = bobRequest.getTo().getURI();
			}
			appSession.setAttribute(ATTR_X_ORIGINAL_DN, xOriginalDN);				
			
			
			URI xPreviousDN = bobRequest.getRequestURI();
			appSession.setAttribute(ATTR_X_PREVIOUS_DN, xPreviousDN);
			bobRequest.getSession().setAttribute(ATTR_INITIAL_INVITE, bobRequest);
			SipServletRequest incomingAliceRequest = getIncomingRequest(bobRequest);
			if (incomingAliceRequest != null) {
				incomingAliceRequest.getSession().setAttribute(ATTR_SIP_ADDRESS, incomingAliceRequest.getFrom());
			}
			bobRequest.getSession().setAttribute(ATTR_SIP_ADDRESS, bobRequest.getTo());

			b2buaListener.callStarted(bobRequest);
		}

		// Remove the callflow so it's not serialized
		bobRequest.removeAttribute(ATTR_CALLFLOW);

		// Sometimes you want to arrest the processing of the transaction.
		// If either the callflow or the request are marked as 'doNotProcess', we won't
		boolean _doNotProcess = Boolean.TRUE.equals(bobRequest.getAttribute(ATTR_DO_NOT_PROCESS));
		this.doNotProcess = (this.doNotProcess || _doNotProcess);
		if (!this.doNotProcess) {
			// This gives the developer a chance to halt processing and 'continue' later.

			try {
				this.processContinue();
			} catch (Exception ex) {
				sipLogger.warning(bobRequest, "InitialInvite.process - catch #1");
				throw new ServletException(ex);
			}

		}

	}

	public SipServletRequest getInboundRequest() {
		return aliceRequest;
	}

	public InitialInvite setInboundRequest(SipServletRequest aliceRequest) {
		this.aliceRequest = aliceRequest;
		return this;
	}

	public SipServletRequest getOutboundRequest() {
		return bobRequest;
	}

	public InitialInvite setOutboundRequest(SipServletRequest bobRequest) {
		this.bobRequest = bobRequest;
		return this;
	}

	public B2buaListener getB2buaListener() {
		return b2buaListener;
	}

	public void setB2buaListener(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	public boolean isDoNotProcess() {
		return doNotProcess;
	}

	public void setDoNotProcess(boolean doNotProcess) {
		this.doNotProcess = doNotProcess;
	}

}
