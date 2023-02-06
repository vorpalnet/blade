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
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface B2buaListener extends Serializable {

	/**
	 * Called upon an initial INVITE. This method contain a copy of the initial
	 * request from the caller to be sent to the caller after this method has been
	 * invoked. You may modify this SipServletRequest object. (Do not call the
	 * .send() method.)
	 * 
	 * @param outboundRequest copy of the initial INVITE to be modified before sending
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException;

	/**
	 * Called after a 200 OK is received. This response object may be modified
	 * before it is sent back. (Do not call the .send() method.)
	 * 
	 * @param outboundResponse modifiable successful response object
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException;

	/**
	 * This method is called after the ACK for the initial INVITE is received.
	 * 
	 * @param outboundRequest a modifiable ACK request
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException;

	/**
	 * This method is called after receiving a BYE request.
	 * 
	 * @param outboundRequest a modifiable BYE request
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException;

	/**
	 * This method is called after receiving an error status (like 404).
	 * 
	 * @param outboundResponse a modifiable error response.
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException;

	/**
	 * This method is called in response to receiving a CANCEL request.
	 * 
	 * @param outboundRequest a modifiable CANCEL request
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException;

}
