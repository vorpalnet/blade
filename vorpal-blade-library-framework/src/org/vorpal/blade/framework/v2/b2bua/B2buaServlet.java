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
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;

/**
 * This class implements a simple back-to-back user agent. It accepts an
 * incoming SipServletRequest object and creates an outgoing copy of it. By
 * extending this class and overriding the B2buaListener interface methods,
 * users can create simple routing applications by modifying the outgoing
 * request or response objects before they are sent.
 * 
 * @author Jeff McDonald
 */
public abstract class B2buaServlet extends AsyncSipServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		Callflow callflow;

		switch (inboundRequest.getMethod()) {
		case Callflow.INVITE:
			if (inboundRequest.isInitial()) {
				callflow = new InitialInvite(this);
			} else {
				callflow = new Reinvite(this);
			}
			break;

		case Callflow.BYE:
		case Callflow.CANCEL:
			callflow = new Terminate(this);
			break;
		default:
			callflow = new Passthru(this);
		}

		return callflow;
	}

	@Override
	public abstract void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException;

	@Override
	public abstract void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException;

	@Override
	public abstract void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException;

	@Override
	public abstract void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException;

	@Override
	public abstract void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException;

	@Override
	public abstract void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException;

	/**
	 * Tells the B2buaServlet not to send the message (request or response). You are
	 * now responsible for completing the callflow.
	 * 
	 * @param outboundRequest
	 */
	public void doNotProcess(SipServletMessage msg) {
		msg.setAttribute("doNotProcess", true);
	}

	/**
	 * Tells the B2buaServlet not to send the outbound request and send a reply back
	 * upstream with the supplied status code.
	 * 
	 * @param outboundRequest
	 * @param statusCode
	 * @throws ServletException
	 * @throws IOException
	 */
	public void doNotProcess(SipServletRequest outboundRequest, int statusCode) throws ServletException, IOException {
		outboundRequest.setAttribute("doNotProcess", true);
		SipSession linkedSession = Callflow.getLinkedSession(outboundRequest.getSession());
		SipServletRequest incomingRequest = linkedSession.getActiveInvite(UAMode.UAS);
		SipServletResponse errorResponse = incomingRequest.createResponse(statusCode);
		sendResponse(errorResponse);
	}

	/**
	 * Tells the B2buaServlet not to send the outbound request and send a reply back
	 * upstream with the supplied status code and custom reason phrase.
	 * 
	 * @param outboundRequest
	 * @param statusCode
	 * @param reasonPhrase
	 * @throws ServletException
	 * @throws IOException
	 */
	public void doNotProcess(SipServletRequest outboundRequest, int statusCode, String reasonPhrase)
			throws ServletException, IOException {
		outboundRequest.setAttribute("doNotProcess", true);
		SipSession linkedSession = Callflow.getLinkedSession(outboundRequest.getSession());
		SipServletRequest incomingRequest = linkedSession.getActiveInvite(UAMode.UAS);
		SipServletResponse errorResponse = incomingRequest.createResponse(statusCode, reasonPhrase);
		sendResponse(errorResponse);
	}

	/**
	 * Returns the original incoming request which initiated the callStarted method.
	 * Useful when used in conjunction with 'doNotProcess'.
	 * 
	 * @param outboundRequest
	 * @return incoming request
	 */
	public static SipServletRequest getIncomingRequest(SipServletRequest outboundRequest) {
		SipSession linkedSession = Callflow.getLinkedSession(outboundRequest.getSession());
		SipServletRequest incomingRequest = linkedSession.getActiveInvite(UAMode.UAS);
		return incomingRequest;
	}

}
