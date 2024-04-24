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

package org.vorpal.blade.services.crud2.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.b2bua.B2buaListener;
import org.vorpal.blade.framework.callflow.Callflow;

public class InitialInvite extends Callflow implements CallflowListener {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private SipServletRequest bobRequest;
	private B2buaListener b2buaListener = null;
	private boolean doNotProcess = false;

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
	 * @param msg
	 * @throws ServletParseException
	 */
	public static void setSessionExpiration(SipServletMessage msg) throws ServletParseException {
		SipApplicationSession appSession = msg.getApplicationSession();

		String sessionExpires = null;
		Parameterable p = msg.getParameterableHeader("Session-Expires");
		if (p != null) {
			sessionExpires = p.getValue();
			if (sessionExpires != null) {
				appSession.setExpires((Integer.parseInt(sessionExpires) / 60) + 1);
			}
		} else {
			// Use configuration file instead
			// moving this to the AsyncSipServlet
//			if (Callflow.getSessionParameters() != null) {
//				if (Callflow.getSessionParameters().getExpiration() != null) {
//					appSession.setExpires(Callflow.getSessionParameters().getExpiration());
//				}
//			}

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

			if (false == aliceRequest.isCommitted()) {

				setSessionExpiration(bobResponse);

				SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
				copyContentAndHeaders(bobResponse, aliceResponse);

				if (successful(bobResponse)) {
					if (b2buaListener != null) {
						b2buaListener.callAnswered(aliceResponse);
					}
				} else if (failure(bobResponse)) {
					if (b2buaListener != null) {
						b2buaListener.callDeclined(aliceResponse);
					}
				}

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
		});

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			aliceRequest = request;

//			SipApplicationSession appSession = aliceRequest.getApplicationSession();

			// CallflowListener API
			bobRequest = this.zzCreateInitialRequest(aliceRequest);
			linkSessions(aliceRequest.getSession(), bobRequest.getSession());

			// Set the outgoing X-Vorpal-Session header
			String indexKey = getVorpalSessionId(bobRequest.getApplicationSession());
			String dialog = createVorpalDialogId(bobRequest.getSession());
			bobRequest.setHeader("X-Vorpal-Session", indexKey + ":" + dialog);

			// This is an API kludge to let the user know what callflow was used
			bobRequest.setAttribute("callflow", this);

			if (b2buaListener != null) {
				b2buaListener.callStarted(bobRequest);
			}

			// Remove the callflow so it's not serialized
			bobRequest.removeAttribute("callflow");

			// Sometimes you want to arrest the processing of the transaction.
			// If either the callflow or the request are marked as 'doNotProcess', we won't
			boolean _doNotProcess = (null == bobRequest.getAttribute("doNotProcess")) ? false
					: (Boolean) bobRequest.getAttribute("doNotProcess");
			this.doNotProcess = (this.doNotProcess || _doNotProcess);
			if (false == this.doNotProcess) {
				// This gives the developer a chance to halt processing and 'continue' later.
				this.processContinue();
			}

		} catch (Exception e) {
			sipLogger.logStackTrace(request, e);
			throw e;
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

	// CallflowListener API
	@Override
	public SipServletRequest zzCreateInitialRequest(SipServletRequest origin)
			throws IOException, ServletParseException {
		return createContinueRequest(origin);
	}

}
