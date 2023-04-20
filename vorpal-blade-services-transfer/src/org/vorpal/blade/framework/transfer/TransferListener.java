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
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface TransferListener extends Serializable {

	/**
	 * This method is invoked by one of the Transfer callflows to indicate an INVITE
	 * is being sent to the transfer target. You may modify the intended destination
	 * of the INVITE by modifying the request URI or the 'To' header. To modify the
	 * 'To' header, use a syntax similar to:
	 * 
	 * request.getAddressHeader("To").setURI(sipFactory.createURI("sip:target2@vorpal.net"));
	 * 
	 * @param request
	 * @throws ServletException
	 * @throws IOException
	 */
	public void transferInitiated(SipServletRequest request) throws ServletException, IOException;

	/**
	 * This method is called after the transfer target has accepted the call.
	 * 
	 * @param response a modifiable successful response
	 * @throws ServletException
	 * @throws IOException
	 */
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException;

	/**
	 * This method is called to indicate that the transfer target did not accept the
	 * call.
	 * 
	 * @param response a non-modifiable error response
	 * @throws ServletException
	 * @throws IOException
	 */
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException;

	/**
	 * This method is called to indicate that the transferee has hung up during the
	 * transfer process. The request may be either a BYE or CANCEL depending upon
	 * the callflow.
	 * 
	 * @param request either a cancel or bye request
	 * @throws ServletException
	 * @throws IOException
	 */
	public void transferAbandoned(SipServletRequest request) throws ServletException, IOException;

}
