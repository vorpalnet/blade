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

/**
 *   ALICE              Transfer              BOB                CAROL
 * transferee                              transferor           target
 * ----------           --------              ---               ------
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |<==RTP================================>|                   |
 *     |                   |                   |                   |
 *     |                   |           REFER   |                   |   To:          transferee
 *     |                   |<-----------------[ ]                  |   Referred-By: transferor
 *     |                   |                   |                   |   Refer-To:    target
 *     |                   |                   |                   | 
 *     |          INVITE   |                   |                   |
 *     |<-----------------[ ]                  |                   |   existing session
 *     |                   |                   |                   |
 *     |   200 OK          |                   |                   |
 *    [ ]--(sdp)---------->|                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |------------------>|------------------>|------------------>|
 *  
 *  
 *  
 *  
 *  
 */

package org.vorpal.blade.framework.v2.transfer;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.transfer.api.Header;
import org.vorpal.blade.framework.v2.transfer.api.Header;



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
	protected TransferSettings transferSettings;

	protected List<Header> inviteHeaders;

	public List<Header> getInviteHeaders() {
		return inviteHeaders;
	}

	public Transfer setInviteHeaders(List<Header> inviteHeaders) {
		this.inviteHeaders = inviteHeaders;
		return this;
	}

	/**
	 * This is a generic base class to be extended by other classes to create a
	 * complete transfer callflow.
	 * 
	 * @param transferListener
	 * @param transferSettings
	 */
	public Transfer(TransferListener transferListener, TransferSettings transferSettings) {
		this.transferListener = transferListener;
		this.transferSettings = transferSettings;
	}

	/**
	 * Call this method to construct the various request objects.
	 * 
	 * @param request
	 * @throws ServletException
	 * @throws IOException
	 */
	protected void createRequests(SipServletRequest request) throws ServletException, IOException {
		transferorRequest = request;

		SipApplicationSession appSession = request.getApplicationSession();

		Address transferee = request.getTo();
		Address target = request.getAddressHeader(REFER_TO);

		sipLogger.finer(request, "Transfer transferee=" + transferee + ", target=" + target);

		targetRequest = sipFactory.createRequest(appSession, INVITE, transferee, target);
		targetRequest.setHeader("Allow", this.transferSettings.getAllow());

		transfereeRequest = getLinkedSession(request.getSession()).createRequest(INVITE);
		transfereeRequest.setHeader("Allow", this.transferSettings.getAllow());
	}

	/**
	 * Copies INVITE headers as defined in the settings. If the header already
	 * exists, it will not copy over it.
	 * 
	 * @param copyFrom
	 * @param copyTo
	 */
	public void preserveInviteHeaders(SipServletRequest copyFrom, SipServletRequest copyTo) {
//		TransferSettings ts = TransferServlet.settingsManager.getCurrent();
		for (String header : this.transferSettings.getPreserveInviteHeaders()) {
			String value = copyFrom.getHeader(header);
			if (value != null && null == copyTo.getHeader(header)) {
				copyHeader(header, copyFrom, copyTo);
			}
		}
	}

	/**
	 * Copies REFER headers as defined in the settings. If the header already
	 * exists, it will not copy over it.
	 * 
	 * @param copyFrom
	 * @param copyTo
	 */
	public void preserveReferHeaders(SipServletRequest copyFrom, SipServletRequest copyTo) {
//		TransferSettings ts = TransferServlet.settingsManager.getCurrent();
		for (String header : this.transferSettings.getPreserveReferHeaders()) {
			String value = copyFrom.getHeader(header);
			if (value != null) {
				copyTo.setHeader(header, value);
			}
		}
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

	}

}
