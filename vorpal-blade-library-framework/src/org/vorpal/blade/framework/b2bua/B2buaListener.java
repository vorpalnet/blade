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
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface B2buaListener extends Serializable {
	/**
	 * This method is called upon startup of the B2BUA application.
	 * 
	 * @param event information about the application
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void b2buaCreated(SipServletContextEvent event) throws ServletException, IOException;

	/**
	 * This method is called upon shutdown of the B2BUA application.
	 * 
	 * @param event the same event called in b2buaCreated()
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void b2buaDestroyed(SipServletContextEvent event) throws ServletException, IOException;

	/**
	 * Called upon an initial INVITE. This method contain a copy of the initial
	 * request from the caller to be sent to the caller after this method has been
	 * invoked. You may modify this SipServletRequest object. (Do not call the
	 * .send() method.)
	 * 
	 * @param request copy of the initial INVITE to be modified before sending
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callStarted(SipServletRequest request) throws ServletException, IOException;

	/**
	 * Called after a 200 OK is received. This response object may be modified
	 * before it is sent back. (Do not call the .send() method.)
	 * 
	 * @param response modifiable successful response object
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callAnswered(SipServletResponse response) throws ServletException, IOException;

	/**
	 * This method is called after the ACK for the initial INVITE is received.
	 * 
	 * @param request a modifiable ACK request
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callConnected(SipServletRequest request) throws ServletException, IOException;

	/**
	 * This method is called after receiving a BYE request.
	 * 
	 * @param request a modifiable BYE request
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callCompleted(SipServletRequest request) throws ServletException, IOException;

	/**
	 * This method is called after receiving an error status (like 404).
	 * 
	 * @param response a modifiable error response.
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callDeclined(SipServletResponse response) throws ServletException, IOException;

	/**
	 * This method is called in response to receiving a CANCEL request.
	 * 
	 * @param request a modifiable CANCEL request
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException;

	/**
	 * This method is called for every other type of message event. Useful for
	 * intercepting INFO or other types of messages. It is invoked by the Passthru
	 * and Reinvite Callflow classes.
	 * 
	 * @param message a modifiable message object, either a request or response
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void callEvent(SipServletMessage message) throws ServletException, IOException;

}
