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
import java.util.HashSet;
import java.util.Iterator;

import javax.servlet.ServletException;
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
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

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
	protected static final String REFER = "REFER";
	protected static final String SIP = "SIP";
	protected static final String Contact = "Contact";
	protected static final String RELIABLE = "100rel";

	private static final String REQUEST_CALLBACK_ = "REQUEST_CALLBACK_";
	private static final String RESPONSE_CALLBACK_ = "RESPONSE_CALLBACK_";
	private static final String LINKED_SESSION = "LINKED_SESSION";
	private static final String DELAYED_REQUEST = "DELAYED_REQUEST";
	private static final String WITHHOLD_RESPONSE = "WITHHOLD_RESPONSE";

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

	@SuppressWarnings("unchecked")
	public static Callback<SipServletRequest> pullCallback(SipServletRequest request) {
		Callback<SipServletRequest> callback = null;
		SipSession sipSession = request.getSession();
		String attribute = REQUEST_CALLBACK_ + request.getMethod();
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
		String attribute = RESPONSE_CALLBACK_ + response.getMethod();
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

	public abstract void process(SipServletRequest request) throws ServletException, IOException;

	public void processWrapper(SipServletRequest request) throws ServletException, IOException {

		try {
			Callflow.sipLogger.superArrow(Direction.RECEIVE, request, null, this.getClass().getSimpleName());
			process(request);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public String schedulePeriodicTimer(SipApplicationSession appSession, int seconds,
			Callback<ServletTimer> lambdaFunction) throws ServletException, IOException {
		ServletTimer timer = null;
		long delay = seconds * 1000;
		long period = seconds * 1000;
		boolean fixedDelay = false;
		boolean isPersistent = false;
		timer = timerService.createTimer(appSession, delay, period, fixedDelay, isPersistent, lambdaFunction);
		return timer.getId();
	}

	public String scheduleTimer(SipApplicationSession appSession, int seconds, Callback<ServletTimer> lambdaFunction)
			throws ServletException, IOException {
		ServletTimer timer = null;
		long delay = seconds * 1000;
		boolean isPersistent = false;
		String timerId = null;

		timer = timerService.createTimer(appSession, delay, isPersistent, lambdaFunction);
		return timer.getId();
	}

	public Expectation expectRequest(SipSession sipSession, String method, Callback<SipServletRequest> callback) {
		sipSession.setAttribute(REQUEST_CALLBACK_ + method, callback);
		return new Expectation(sipSession, method);
	}

	public Expectation expectRequest(SipApplicationSession appSession, String method,
			Callback<SipServletRequest> callback) {
		appSession.setAttribute(REQUEST_CALLBACK_ + method, callback);
		return new Expectation(appSession, method);
	}

	public void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambdaFunction)
			throws ServletException, IOException {
		Callflow.sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());

		try {
			request.getSession().setAttribute(RESPONSE_CALLBACK_ + request.getMethod(), lambdaFunction);
			request.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public void sendRequest(SipServletRequest request) throws ServletException, IOException {
		Callflow.sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());

		try {
// jwm--this seems unnecessary.
//			request.getSession().removeAttribute(RESPONSE_CALLBACK_ + request.getMethod());

			request.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	/**
	 * Marks the response to be sent 'reliably', meaning you should expect at PRACK
	 * request.
	 * 
	 * @param response
	 * @return
	 */
	public SipServletResponse makeReliable(SipServletResponse response) {
		response.setAttribute(RELIABLE, true);
		return response;
	}

	/**
	 * Mark the response as 'withheld' to prevent the method sendResponse() from
	 * actually sending the message. This gives the user the ability to prevent an
	 * automatically created response in the B2BUA or Proxy APIs from being sent
	 * back upstream.
	 * 
	 * @param response
	 */
	public void withholdResponse(SipServletResponse response) {
		response.setAttribute(WITHHOLD_RESPONSE, true);
	}

	// Send a response, expect an 'ACK'
	/**
	 * Send a response back upstream and expect an ACK/PRACK.
	 * 
	 * @param response
	 * @param lambdaFunction
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendResponse(SipServletResponse response, Callback<SipServletRequest> lambdaFunction)
			throws ServletException, IOException {

		if (response.getAttribute(WITHHOLD_RESPONSE) == null) {

			Callflow.sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());

			try {
				response.getSession().setAttribute(this.REQUEST_CALLBACK_ + ACK, lambdaFunction);

				if (provisional(response)) {

					if (response.isReliableProvisional() || null != response.getAttribute(RELIABLE)) {
						response.getSession().setAttribute(this.REQUEST_CALLBACK_ + PRACK, lambdaFunction);
						response.sendReliably();
					} else {
						response.send();
					}

				} else {
					response.send();
				}

			} catch (Exception e) {
				sipLogger.logStackTrace(e);
				throw e;
			}
		}

	}

	/**
	 * Send a response back upstream and do not expect and ACK/PRACK. If one is
	 * received, it will be absorbed by the framework without exception.
	 * 
	 * @param response
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		if (response.getAttribute(WITHHOLD_RESPONSE) == null) {

			Callflow.sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());

			try {
				response.send();
			} catch (Exception e) {
				sipLogger.logStackTrace(e);
				throw e;
			}
		}
	}

	public static void setSipFactory(SipFactory sipFactory) {
		Callflow.sipFactory = sipFactory;
	}

	public static void setSipUtil(SipSessionsUtil sipUtil) {
		Callflow.sipUtil = sipUtil;
	}

	public static SipServletRequest createContinueRequest(SipServletRequest origin)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				origin.getTo()); //

		destination.setRequestURI(origin.getRequestURI());
		destination.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, origin);
		copyContentAndHeaders(origin, destination);
		return destination;
	}

	public static SipServletRequest createNewRequest(SipServletRequest origin)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				origin.getTo()); //

		destination.setRequestURI(origin.getRequestURI());
		copyContentAndHeaders(origin, destination);
		return destination;
	}

	@Deprecated
	public static SipServletRequest createInitialRequest(SipServletRequest origin, boolean copyContent)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				origin.getTo()); //

		destination.setRequestURI(origin.getRequestURI());
		destination.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, origin);
		if (copyContent) {
			copyContentAndHeaders(origin, destination);
		} else {
			copyHeaders(origin, destination);
		}

		return destination;
	}

	public static SipServletRequest createRequest(SipServletResponse response, String method)
			throws IOException, ServletParseException {
		SipServletRequest request = response.getSession().createRequest(method);
		return request;
	}

	public static SipServletResponse createResponse(SipServletRequest request, SipServletResponse responseToCopy,
			boolean copyContent) throws UnsupportedEncodingException, IOException, ServletParseException {
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

	private static void copyHeadersMsg(SipServletMessage copyFrom, SipServletMessage copyTo)
			throws ServletParseException {

		for (String header : copyFrom.getHeaderNameList()) {

			// Walking the high-wire without a net;
//			try {

			switch (header.hashCode()) {
			case 2715: // ------- To
			case 2198474: // ---- From
			case 85998: // ------ Via
			case -2081731894: // Call-ID
			case -1678787584: // Contact
			case 1244061434: // - Content-Length
			case 2079004: // --- CSeq
			case 1848913111: // - Max-Forwards
			case 949037134: // -- Content-Type
			case 887838157: // -- Record-Route
			case 79151657: // --- Route
			case 2525869: // ---- RSeq
			case 2508503: // ---- RAck
				// do not copy these headers
				break;
			default:
				copyHeader(header, copyFrom, copyTo);
			}

//			} catch (Exception e) {
//				sipLogger.log(Level.WARNING, "Cannot copy header: " + header + " hash: " + header.hashCode());
//			}

		}
	}

	private static void copyHeader(String header, SipServletMessage copyFrom, SipServletMessage copyTo) {

		String v;
		HashSet<String> hashSet = new HashSet<>();
		StringBuilder sb = new StringBuilder();
		for (String value : copyFrom.getHeaderList(header)) {
			hashSet.add(value);
		}
		Iterator<String> i = hashSet.iterator();
		while (i.hasNext()) {
			v = i.next();
			if (sb.length() == 0) {
				sb.append(v);
			} else {
				sb.append(",");
				sb.append(v);
			}
		}
		copyTo.setHeader(header, sb.toString());

	}

	private static void copyContentMsg(SipServletMessage copyFrom, SipServletMessage copyTo)
			throws UnsupportedEncodingException, IOException {
		copyTo.setContent(copyFrom.getContent(), copyFrom.getContentType());
	}

	public static SipServletRequest copyContent(SipServletMessage copyFrom, SipServletRequest copyTo)
			throws UnsupportedEncodingException, IOException {
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public SipServletResponse copyContent(SipServletMessage copyFrom, SipServletResponse copyTo)
			throws UnsupportedEncodingException, IOException {
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	/**
	 * Copy non-system headers with the exception of Contact for REGISTER requests.
	 * 
	 * @param copyFrom
	 * @param copyTo
	 * @return copyTo
	 * @throws ServletParseException
	 */
	public static SipServletRequest copyHeaders(SipServletRequest copyFrom, SipServletRequest copyTo)
			throws ServletParseException {

		// Special case, copy Contact headers for REGISTER message
		if (copyFrom.getMethod().equals(REGISTER)) {
			for (String value : copyFrom.getHeaderList(Contact)) {
				copyTo.setHeader(Contact, value);
			}
		}

		copyHeadersMsg(copyFrom, copyTo);
		return copyTo;
	}

	/**
	 * Copy non-system headers with the exception of Contact for REGISTER responses,
	 * 3xx and 485 responses, and 200/OPTIONS responses.
	 * 
	 * @param copyFrom
	 * @param copyTo
	 * @return copyTo
	 * @throws ServletParseException
	 */
	public static SipServletResponse copyHeaders(SipServletResponse copyFrom, SipServletResponse copyTo)
			throws ServletParseException {
		boolean copyContact = false;

		if (copyFrom.getMethod().equals(REGISTER)) {
			copyContact = true;
		} else if (copyFrom.getStatus() >= 300 && copyFrom.getStatus() < 400) {
			copyContact = true;
		} else if (copyFrom.getStatus() == 485) {
			copyContact = true;
		} else if (copyFrom.getMethod().equals(OPTIONS) && copyFrom.getStatus() == 200) {
			copyContact = true;
		}

		if (copyContact) {
			for (String value : copyFrom.getHeaderList(Contact)) {
				copyTo.setHeader(Contact, value);
			}
		}

		copyHeadersMsg(copyFrom, copyTo);
		return copyTo;
	}

	public static SipServletRequest copyContentAndHeaders(SipServletRequest copyFrom, SipServletRequest copyTo)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		copyHeadersMsg(copyFrom, copyTo);
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public static SipServletResponse copyContentAndHeaders(SipServletResponse copyFrom, SipServletResponse copyTo)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		copyHeadersMsg(copyFrom, copyTo);
		copyContentMsg(copyFrom, copyTo);
		return copyTo;
	}

	public static void linkSessions(SipSession ss1, SipSession ss2) {
		ss1.setAttribute(LINKED_SESSION, ss2);
		ss2.setAttribute(LINKED_SESSION, ss1);
	}

	public static void unlinkSessions(SipSession ss1, SipSession ss2) {
		ss1.removeAttribute(LINKED_SESSION);
		ss2.removeAttribute(LINKED_SESSION);
	}

	public static SipSession getLinkedSession(SipSession ss) {
		return (SipSession) ss.getAttribute(LINKED_SESSION);
	}

	public void processLater(SipServletRequest request, long delay_in_milliseconds) {
		request.getApplicationSession().setAttribute(DELAYED_REQUEST, request);
		timerService.createTimer(request.getApplicationSession(), delay_in_milliseconds, false, this);
	}

	/**
	 * Used for testing, this method prints the hash-codes system headers.
	 * 
	 * @param args none required
	 */
	public static void main(String[] args) {
		System.out.println("Hashcodes of system headers include: ");
		System.out.println("\tTo: " + "To".hashCode());
		System.out.println("\tFrom: " + "From".hashCode());
		System.out.println("\tVia: " + "Via".hashCode());
		System.out.println("\tCall-ID: " + "Call-ID".hashCode());
		System.out.println("\tContact: " + "Contact".hashCode());
		System.out.println("\tContent-Length: " + "Content-Length".hashCode());
		System.out.println("\tCSeq: " + "CSeq".hashCode());
		System.out.println("\tMax-Forwards: " + "Max-Forwards".hashCode());
		System.out.println("\tContent-Type: " + "Content-Type".hashCode());
		System.out.println("\tRecord-Route: " + "Record-Route".hashCode());
		System.out.println("\tRoute: " + "Route".hashCode());
		System.out.println("\tRSeq: " + "RSeq".hashCode());
		System.out.println("\tRAck: " + "RAck".hashCode());
	}

}
