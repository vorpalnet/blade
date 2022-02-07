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

package org.vorpal.blade.framework.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;

public class Transfer extends Callflow {
	static final long serialVersionUID = 1L;

	protected final static String REFER_TO = "Refer-To";
	protected final static String REFERRED_BY = "Referred-By";
	protected final static String SUBSCRIPTION_STATE = "Subscription-State";
	protected final static String EVENT = "Event";
	protected final static String ACTIVE = "active";
	protected final static String PENDING = "pending";
	protected final static String SIPFRAG = "message/sipfrag";
	protected final static String TRYING_100 = "SIP/2.0 100 Trying";
	protected final static String OK_200 = "SIP/2.0 200 OK";

	protected TransferListener transferListener;

	protected SipServletRequest transfereeRequest;
	protected SipServletRequest targetRequest;
	protected SipServletRequest transferorRequest;

	/**
	 * This is a generic base class to be extended by other classes to create a
	 * complete transfer callflow.
	 * 
	 * @param transferListener
	 */
	public Transfer(TransferListener transferListener) {
		this.transferListener = transferListener;
	}

	
	/**
	 * Call this method to construct the various request objects.
	 * 
	 * @param request
	 */
	protected void createRequests(SipServletRequest request) throws ServletException, IOException {
		transferorRequest = request;

		SipApplicationSession appSession = request.getApplicationSession();

		Address transferee = request.getTo();
		Address transferor = request.getAddressHeader(REFERRED_BY);
		Address target = request.getAddressHeader(REFER_TO);

		targetRequest = sipFactory.createRequest(appSession, INVITE, transferee, target);
		transfereeRequest = this.getLinkedSession(request.getSession()).createRequest(INVITE);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

	}

}