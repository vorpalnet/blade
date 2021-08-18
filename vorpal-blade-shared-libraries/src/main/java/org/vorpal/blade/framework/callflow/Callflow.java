/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 ted, free of charge, to any p this software and associated documentation fdeal
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

package org.vorpal.blade.framework.callflow;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.TimerService;

import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.framework.logging.Logger.Direction;

public abstract class Callflow implements Serializable {
	private static final long serialVersionUID = 1L;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;
	protected static Logger sipLogger;

	// Useful strings
	protected static final String INVITE = "INVITE";
	protected static final String ACK = "ACK";
	protected static final String BYE = "BYE";
	protected static final String CANCEL = "CANCEL";
	protected static final String REGISTER = "REGISTER";
	protected static final String OPTIONS = "OPTIONS";
	protected static final String PRACK = "PRACK";
	protected static final String SUBSCRIBE = "SUBSCRIBE";
	protected static final String NOTIFY = "NOTIFY";
	protected static final String PUBLISH = "PUBLISH";
	protected static final String INFO = "INFO";
	protected static final String UPDATE = "UPDATE";
	protected static final String SIP = "SIP";

	private static final String REQUEST_CALLBACK_ = "REQUEST_CALLBACK_";
	private static final String RESPONSE_CALLBACK_ = "RESPONSE_CALLBACK_";

	public static boolean provisional(SipServletResponse response) {
		return (response.getStatus() >= 100 && response.getStatus() < 200);
	}

	public static boolean successful(SipServletResponse response) {
		return (response.getStatus() >= 200 && response.getStatus() < 300);
	}

	public static boolean redirection(SipServletResponse response) {
		return (response.getStatus() >= 300 && response.getStatus() < 400);
	}

	public static boolean failure(SipServletResponse response) {
		return (response.getStatus() >= 400);
	}

//	public static void putCallback(SipServletRequest request, Callback<SipServletResponse> callback) {
//		SipSession sipSession = request.getSession();
//		String attribute = "RESPONSE_CALLBACK_" + request.getMethod();
//		sipSession.setAttribute(attribute, callback);
//	}	
//	
//	public static void putCallback(SipSession sipSession, String method, Callback<SipServletRequest> callback) {
//		String attribute = "REQUEST_CALLBACK_" + method;
//		sipSession.setAttribute(attribute, callback);
//	}	

	// if request comes in
	@SuppressWarnings("unchecked")
	public static Callback<SipServletRequest> pullCallback(SipServletRequest request) {
		Callback<SipServletRequest> callback = null;
		SipSession sipSession = request.getSession();
		String attribute = "REQUEST_CALLBACK_" + request.getMethod();
		callback = (Callback<SipServletRequest>) sipSession.getAttribute(attribute);
		if (callback != null) {
			sipSession.removeAttribute(attribute);
		} else {
			SipApplicationSession appSession = request.getApplicationSession();
			callback = (Callback<SipServletRequest>) appSession.getAttribute(attribute);
			if (callback != null) {
				appSession.removeAttribute(attribute);
			}
		}
		return callback;
	}

	@SuppressWarnings("unchecked")
	public static Callback<SipServletResponse> pullCallback(SipServletResponse response) {
		Callback<SipServletResponse> callback = null;
		SipSession sipSession = response.getSession();
		String attribute = "RESPONSE_CALLBACK_" + response.getMethod();
		callback = (Callback<SipServletResponse>) sipSession.getAttribute(attribute);
		if (callback != null) {
			if (response.getStatus() >= 200) {
				sipSession.removeAttribute(attribute);
			}
		}
		return callback;
	}

	@SuppressWarnings("unchecked")
	public static Callback<ServletTimer> pullCallback(ServletTimer timer) {
		Callback<ServletTimer> callback = null;
		callback = (Callback<ServletTimer>) timer.getInfo();
		return callback;
	}

	public static Logger getLogger() {
		return sipLogger;
	}

	public static void setLogger(Logger sipLogger) {
		Callflow.sipLogger = sipLogger;
	}

	public static TimerService getTimerService() {
		return timerService;
	}

	public static void setTimerService(TimerService timerService) {
		Callflow.timerService = timerService;
	}

	public static SipFactory getSipFactory() {
		return sipFactory;
	}

	public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	public abstract void process(SipServletRequest request) throws Exception;

	public void processWrapper(SipServletRequest request) throws Exception {

		try {
			// Callflow.sipLogger.log(Level.FINE, this, Direction.RECEIVE, request);
			Callflow.sipLogger.superArrow(Direction.RECEIVE, request, null, this.getClass().getSimpleName());
			process(request);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public void schedulePeriodicTimer(SipApplicationSession appSession, int seconds, Callback<ServletTimer> lambdaFunction) throws Exception {
		/*
		 * ServletTimer createTimer(SipApplicationSession appSession, long delay, long
		 * period, boolean fixedDelay, boolean isPersistent, Serializable info)
		 */

		long delay = seconds * 1000;
		timerService.createTimer(appSession, delay, delay, false, true, lambdaFunction);
	}

	public void scheduleTimer(SipApplicationSession appSession, int seconds, Callback<ServletTimer> lambdaFunction) throws Exception {
		/*
		 * ServletTimer createTimer(SipApplicationSession appSession, long delay,
		 * boolean isPersistent, Serializable info)
		 */
		long delay = seconds * 1000;
		timerService.createTimer(appSession, delay, true, lambdaFunction);
	}

	public void expectRequest(SipSession sipSession, String method, Callback<SipServletRequest> callback) {
		sipSession.setAttribute(REQUEST_CALLBACK_ + method, callback);
	}

	public void expectRequest(SipApplicationSession appSession, String method, Callback<SipServletRequest> callback) {
		appSession.setAttribute(REQUEST_CALLBACK_ + method, callback);
	}

	public void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambdaFunction) throws Exception {
		Callflow.sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());

		try {
			request.getSession().setAttribute(RESPONSE_CALLBACK_ + request.getMethod(), lambdaFunction);
			request.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public void sendRequest(SipServletRequest request) throws Exception {
		Callflow.sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());

		try {
			request.getSession().removeAttribute(RESPONSE_CALLBACK_ + request.getMethod());
			if (request.getMethod().equals(CANCEL)) {
				request.getSession().removeAttribute(RESPONSE_CALLBACK_ + INVITE);
			}

			request.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	// Send a response, expect an 'ACK'
	public void sendResponse(SipServletResponse response, Callback<SipServletRequest> lambdaFunction) throws Exception {
		Callflow.sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());

		try {
			response.getSession().setAttribute(this.REQUEST_CALLBACK_ + ACK, lambdaFunction);
			response.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public void sendResponse(SipServletResponse response) throws Exception {
		Callflow.sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());

		try {
			response.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public static void setSipFactory(SipFactory sipFactory) {
		Callflow.sipFactory = sipFactory;
	}

	public static void setSipUtil(SipSessionsUtil sipUtil) {
		Callflow.sipUtil = sipUtil;
	}

	public SipServletRequest createRequest(SipServletRequest origin, boolean copyContent) throws IOException, ServletParseException {

//		if (origin.isInitial()) {
		SipServletRequest destination = sipFactory.createRequest(origin.getApplicationSession(), origin.getMethod(), origin.getFrom(), origin.getTo());
//		} else {
//
//		}

//		destination.setRequestURI(origin.getRequestURI());
//		destination.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, origin);
		copyHeaders(origin, destination);

		if (copyContent == true) {
			destination.setContent(origin.getContent(), origin.getContentType());
		}
		return destination;
	}

	public SipServletRequest createRequest(SipServletRequest previous, String method) throws IOException, ServletParseException {
		SipServletRequest request = previous.getSession().createRequest(method);
//		request.setRequestURI(previous.getRequestURI());
//		request.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, previous);
		copyHeaders(previous, request);

		return request;
	}

	public SipServletRequest createRequest(SipServletResponse response, String method) throws IOException, ServletParseException {
		SipServletRequest request = response.getSession().createRequest(method);
		copyHeaders(response, request);
		return request;
	}

	public SipServletResponse createResponse(SipServletRequest request, SipServletResponse responseToCopy, boolean copyContent)
			throws UnsupportedEncodingException, IOException, ServletParseException {
		SipServletResponse response;
		response = request.createResponse(responseToCopy.getStatus(), responseToCopy.getReasonPhrase());
		copyHeaders(responseToCopy, response);
		if (copyContent == true) {
			response.setContent(responseToCopy.getContent(), responseToCopy.getContentType());
		}
		return response;
	}

	/*
	 * Applications must not add, delete, or modify so-called "system" headers.
	 * 
	 * These are header fields that the servlet container manages:
	 * 
	 * From, To, Call-ID, CSeq, Via, Route (except through pushRoute), Record-Route,
	 * Path.
	 * 
	 * Contact is a system header field in messages other than REGISTER requests and
	 * responses, 3xx and 485 responses, and 200/OPTIONS responses. Additionally,
	 * for containers implementing the reliable provisional responses extension,
	 * RAck and RSeq are considered system headers also. Note that From and To are
	 * system header fields only with respect to their tags (i.e., tag parameters on
	 * these headers are not allowed to be modified but modifications are allowed to
	 * the other parts).
	 */

	public void copyHeadersMsg(SipServletMessage copyFrom, SipServletMessage copyTo) throws ServletParseException {

		for (String header : copyFrom.getHeaderNameList()) {

			try {

				switch (header.hashCode()) {
				case 2715: // To
				case 2198474: // From
				case 85998: // Via
				case -2081731894: // Call-ID
				case -1678787584: // Contact
				case 1244061434: // Content-Length
				case 2079004: // CSeq
				case 1848913111: // Max-Forwards
				case 949037134: // Content-Type
				case 887838157: // Record-Route
				case 79151657: // Route
					// do not copy these headers
					break;
				default:

					StringBuilder sb = new StringBuilder();
					for (String value : copyFrom.getHeaderList(header)) {
						if (sb.length() == 0) {
							sb.append(value);
						} else {
							sb.append(",");
							sb.append(value);
						}
					}

					copyTo.setHeader(header, sb.toString());
				}

			} catch (Exception e) {
				sipLogger.log(Level.WARNING, "Cannot copy header: " + header + " hash: " + header.hashCode());
			}

		}
	}

	public void copyContentMsg(SipServletMessage copyFrom, SipServletMessage copyTo) throws UnsupportedEncodingException, IOException {
		copyTo.setContent(copyFrom.getContent(), copyFrom.getContentType());
	}

	public void copyContentAndHeadersMsg(SipServletMessage copyFrom, SipServletMessage copyTo)
			throws UnsupportedEncodingException, IOException, ServletParseException {
		copyHeadersMsg(copyFrom, copyTo);
		copyContentMsg(copyFrom, copyTo);
	}

	public SipServletRequest copyContent(SipServletMessage copyFrom, SipServletRequest copyTo) throws UnsupportedEncodingException, IOException {
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public SipServletResponse copyContent(SipServletMessage copyFrom, SipServletResponse copyTo) throws UnsupportedEncodingException, IOException {
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public SipServletRequest copyHeaders(SipServletMessage copyFrom, SipServletRequest copyTo) throws ServletParseException {
		copyHeadersMsg(copyFrom, copyTo);
		return copyTo;
	}

	public SipServletResponse copyHeaders(SipServletMessage copyFrom, SipServletResponse copyTo) throws ServletParseException {
		copyHeadersMsg(copyFrom, copyTo);
		return copyTo;
	}

	public SipServletRequest copyContentAndHeaders(SipServletMessage copyFrom, SipServletRequest copyTo)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		copyHeadersMsg(copyFrom, copyTo);
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public SipServletResponse copyContentAndHeaders(SipServletMessage copyFrom, SipServletResponse copyTo)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		copyHeadersMsg(copyFrom, copyTo);
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public void linkSessions(SipSession ss1, SipSession ss2) {
		ss1.setAttribute("LINKED_SESSION", ss2);
		ss2.setAttribute("LINKED_SESSION", ss1);
	}

	public void unlinkSessions(SipSession ss1, SipSession ss2) {
		ss1.removeAttribute("LINKED_SESSION");
		ss2.removeAttribute("LINKED_SESSION");
	}

	public SipSession getLinkedSession(SipSession ss) {
		return (SipSession) ss.getAttribute("LINKED_SESSION");
	}

}
