/*
 * MIT License
 *
 * Copyright (c) 2021 Vorpal Networks, LLC (https://vorpal.net)
 *  
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.DummyResponse;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;
import org.vorpal.blade.framework.v2.proxy.ProxyTier.Mode;

public abstract class Callflow implements Serializable {
	private static final long serialVersionUID = 1L;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;
	protected static Logger sipLogger;
	protected static SessionParameters sessionParameters;

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

	protected static final String REQUEST_CALLBACK_ = "REQUEST_CALLBACK_";
	protected static final String RESPONSE_CALLBACK_ = "RESPONSE_CALLBACK_";
	protected static final String LINKED_SESSION = "LINKED_SESSION";
	protected static final String DELAYED_REQUEST = "DELAYED_REQUEST";
	protected static final String WITHHOLD_RESPONSE = "WITHHOLD_RESPONSE";

//	public static boolean isProxy(SipServletRequest request) throws TooManyHopsException {
//		return AsyncSipServlet.isProxy(request);
//	}

//	public static boolean isProxy(SipServletResponse response) {
//		return AsyncSipServlet.isProxy(response);
//	}

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

//	public static String superclass(Class _class) {
//		Class _superclass = _class;
//		return _superclass.getSimpleName();
//	}

//	public static String superclass(Class _class) {
//		Class _superclass = _class;
//
//		while (_superclass.getSuperclass() != null) {
//			_superclass = _superclass.getSuperclass();
//		}
//
//		return _superclass.getSimpleName();
//	}

	/**
	 * Not to be called by the end user. This method finds the callback method
	 * associated with either the SipSession or SipApplicationSession. Special logic
	 * is in place to not clear the callback if the request is a NOTIFY with a
	 * sipfrag of less that 200.
	 * 
	 * @param request
	 * @return the callback method
	 */
	@SuppressWarnings("unchecked")
	public static Callback<SipServletRequest> pullCallback(SipServletRequest request) {
		Callback<SipServletRequest> callback = null;
		SipSession sipSession = request.getSession();
		String attribute = REQUEST_CALLBACK_ + request.getMethod();
		callback = (Callback<SipServletRequest>) sipSession.getAttribute(attribute);
		if (callback != null) {
			boolean removeAttribute = true;

			try {
				if (request.getMethod().equals(NOTIFY) && request.getContentType().equals("message/sipfrag")) {
					String sipfrag;
					if (request.getContent() instanceof String) {
						sipfrag = (String) request.getContent();
					} else {
						sipfrag = new String(request.getRawContent()).trim();
					}

					if (false == sipfrag.matches(".*[2-5][0-9][0-9].*")) {
						removeAttribute = false;
					}

				}
			} catch (Exception e) {
				sipLogger.logStackTrace(request, e);
			}

			if (removeAttribute) {
				sipSession.removeAttribute(attribute);
			}

		} else {
			SipApplicationSession appSession = request.getApplicationSession();
			callback = (Callback<SipServletRequest>) appSession.getAttribute(attribute);
			if (callback != null) {
				appSession.removeAttribute(attribute);
			}
		}
		return callback;
	}

//	@SuppressWarnings("unchecked")
//	public static Callback<SipServletResponse> pullCallback(SipServletResponse response) {
//		Callback<SipServletResponse> callback = null;
//		SipSession sipSession = response.getSession();
//		String attribute = RESPONSE_CALLBACK_ + response.getMethod();
//		callback = (Callback<SipServletResponse>) sipSession.getAttribute(attribute);
//		if (callback != null) {
//			if (response.getStatus() >= 200) {
//				sipSession.removeAttribute(attribute);
//			}
//		}
//		return callback;
//	}

	@SuppressWarnings("unchecked")
	public static Callback<SipServletResponse> pullCallback(SipServletResponse response) {

		Callback<SipServletResponse> callback = null;
		SipSession sipSession = response.getSession();
		String attribute = RESPONSE_CALLBACK_ + response.getMethod();
		callback = (Callback<SipServletResponse>) sipSession.getAttribute(attribute);
		if (callback != null) {

			Proxy proxy = response.getProxy();
			if (proxy != null) {
				// If this is due to a 'proxy' event, the incoming request object
				// has the real callback. The response object just has a copy of it, which
				// can't be relied upon for session locking to ensure consistent variable data.
				callback = (Callback<SipServletResponse>) response.getRequest().getSession().getAttribute(attribute);
			}

			// If this is the final response, remove the callback attribute to prevent weird
			// echos. It is unnecessary to delete it for proxy requests since this will
			// never be called again.
			if (response.getProxyBranch() == null && response.getStatus() >= 200) {
				sipSession.removeAttribute(attribute);
			}

		}
		return callback;
	}

	@SuppressWarnings("unchecked")
	public static Callback<ServletTimer> pullCallback(ServletTimer timer) {
		return (Callback<ServletTimer>) timer.getInfo();
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

//	public void processWrapper(SipServletRequest request) throws ServletException, IOException {
//		Callflow.sipLogger.superArrow(Direction.RECEIVE, request, null, this.getClass().getSimpleName());
//		process(request);
//	}

	@Deprecated
	public String schedulePeriodicTimer(SipApplicationSession appSession, int seconds,
			Callback<ServletTimer> lambdaFunction) throws ServletException, IOException {
		ServletTimer timer = null;
		long delay = seconds * 1000;
		long period = seconds * 1000;
		boolean fixedDelay = false;
		boolean isPersistent = false;
		return timerService.createTimer(appSession, delay, period, fixedDelay, isPersistent, lambdaFunction).getId();
	}

	@Deprecated
	public String schedulePeriodicTimerInMilliseconds(SipApplicationSession appSession, long milliseconds,
			Callback<ServletTimer> lambdaFunction) throws ServletException, IOException {
		ServletTimer timer = null;
		long delay = milliseconds;
		long period = milliseconds;
		boolean fixedDelay = false;
		boolean isPersistent = false;
		return timerService.createTimer(appSession, delay, period, fixedDelay, isPersistent, lambdaFunction).getId();
	}

	@Deprecated
	public String scheduleTimer(SipApplicationSession appSession, int seconds, Callback<ServletTimer> lambdaFunction)
			throws ServletException, IOException {
		long delay = seconds * 1000;
		boolean isPersistent = false;
		return timerService.createTimer(appSession, delay, isPersistent, lambdaFunction).getId();
	}

	@Deprecated
	public String scheduleTimerInMilliseconds(SipApplicationSession appSession, long milliseconds,
			Callback<ServletTimer> lambdaFunction) throws ServletException, IOException {
		long delay = milliseconds;
		boolean isPersistent = false;
		return timerService.createTimer(appSession, delay, isPersistent, lambdaFunction).getId();
	}

	@Deprecated
	public void cancelTimer(SipApplicationSession appSession, String timerId) {

		if (appSession != null && timerId != null) {
			ServletTimer timer = appSession.getTimer(timerId);
			if (timer != null) {
				timer.cancel();
			}
		}

	}

	/**
	 * Creates a one-time ServletTimer and schedules it to expire after the
	 * specified delay.
	 * 
	 * @param appSession     the application session with which the new ServletTimer
	 *                       is to be associated
	 * @param delay          delay in milliseconds before timer is to expire
	 * @param isPersistent   if true, the ServletTimer will be reinstated after a
	 *                       shutdown be it due to complete failure or operator
	 *                       shutdown
	 * @param lambdaFunction
	 * @return the newly created ServletTimer's id
	 */
	public static String startTimer(SipApplicationSession appSession, //
			long delay, //
			boolean isPersistent, //
			Callback<ServletTimer> lambdaFunction) {
		return timerService.createTimer(appSession, delay, isPersistent, lambdaFunction).getId();
	}

	/**
	 * @param appSession     the application session with which the new ServletTimer
	 *                       is to be associated
	 * @param delay          delay in milliseconds before timer is to expire
	 * @param period         time in milliseconds between successive timer
	 *                       expirations
	 * @param fixedDelay     if true, the repeating timer is scheduled in a
	 *                       fixed-delay mode, otherwise in a fixed-rate mode
	 * @param isPersistent   if true, the ServletTimer will be reinstated after a
	 *                       shutdown be it due to complete failure or operator
	 *                       shutdown
	 * @param lambdaFunction
	 * @return the newly created ServletTimer's id
	 */
	public static String startTimer(SipApplicationSession appSession, //
			long delay, //
			long period, //
			boolean fixedDelay, //
			boolean isPersistent, //
			Callback<ServletTimer> lambdaFunction) {

		return timerService.createTimer(appSession, delay, period, fixedDelay, isPersistent, lambdaFunction).getId();
	}

	/**
	 * Cancels the timer.
	 * 
	 * @param appSession
	 * @param timerId
	 */
	public static void stopTimer(SipApplicationSession appSession, String timerId) {
		if (appSession != null && timerId != null) {
			ServletTimer timer = appSession.getTimer(timerId);
			if (timer != null) {
				timer.cancel();

			}
		}
	}

	/**
	 * Stops all timers for the application session.
	 * 
	 * @param appSession
	 */
	public static void stopTimers(SipApplicationSession appSession) {
		if (appSession != null) {
			for (ServletTimer timer : appSession.getTimers()) {
				timer.cancel();
			}
		}
	}

	/**
	 * Use this method to create a lambda expression which is called when a
	 * particular SIP method like CANCEL is expected. Using this technique means you
	 * don't have to write a complete CANCEL Callflow class. How convenient!
	 * 
	 * @param sipSession
	 * @param method
	 * @param callback
	 * @return an expectation object
	 */
	public Expectation expectRequest(SipSession sipSession, String method, Callback<SipServletRequest> callback) {

		if (callback != null && sipSession.isValid()) {
			sipSession.setAttribute(REQUEST_CALLBACK_ + method, callback);
		}

		return new Expectation(sipSession, method, callback);
	}

	/**
	 * Use this method to create a lambda expression which is called when a
	 * particular SIP method like CANCEL is expected. Using this technique means you
	 * don't have to write a complete CANCEL Callflow class. How convenient!
	 * 
	 * @param appSession
	 * @param method
	 * @param callback
	 * @return an expectation object
	 */
	public Expectation expectRequest(SipApplicationSession appSession, String method,
			Callback<SipServletRequest> callback) {

		if (appSession.isValid()) {
			appSession.setAttribute(REQUEST_CALLBACK_ + method, callback);
		}

		return new Expectation(appSession, method, callback);
	}

	public static String createVorpalSessionId(SipApplicationSession appSession) {
		String indexKey = null;

		do {
			indexKey = String.format("%08X", //
					Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)) //
			).toUpperCase();
		} while (null != getSipUtil().getApplicationSessionByKey(indexKey, false));

		appSession.setAttribute("X-Vorpal-Session", indexKey);

		// jwm - instead, use the Configuration file to define index keys
		appSession.addIndexKey(indexKey);

		// X-Vorpal-Session + X-Vorpal-Timestamp will be unique.
		// Use this for a database primary key in future designs.
		appSession.setAttribute("X-Vorpal-Timestamp", Long.toHexString(System.currentTimeMillis()).toUpperCase());

		return indexKey;
	}

	public static String getVorpalSessionId(SipApplicationSession appSession) {
		String indexKey = (String) appSession.getAttribute("X-Vorpal-Session");
		return indexKey;
	}

	public static String createVorpalDialogId(SipSession sipSession) {
		String dialog = null;

		try {
			dialog = String.format("%04X", Math.abs(sipSession.getId().hashCode()) % 0xFFFF);
			sipSession.setAttribute("X-Vorpal-Dialog", dialog);
		} catch (Exception e) {
			// OU812;
		}

		return dialog;
	}

	public static String getVorpalDialogId(SipSession sipSession) {
		String dialog = (String) sipSession.getAttribute("X-Vorpal-Dialog");

		// jwm - When not using the full framework
		if (dialog == null) {
			dialog = createVorpalDialogId(sipSession);
		}

		return dialog;
	}

	/**
	 * Sends a SipServletRequest object. Supplies a SipServletResponse object to the
	 * user as part of the lambda function.
	 * 
	 * @param request
	 * @param lambdaFunction
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambdaFunction)
			throws ServletException, IOException {
		request.getSession().setAttribute(RESPONSE_CALLBACK_ + request.getMethod(), lambdaFunction);
		sendRequest(request);
	}

	/**
	 * Sends a SipServletRequest object. This method does not supply a
	 * SipServletResponse object to the user. (Useful for messages like ACK or
	 * INFO.)
	 * 
	 * @param request
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendRequest(SipServletRequest request) throws ServletException, IOException {

		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();

		if (request.isInitial() && null == request.getHeader("X-Vorpal-Session")) {
			String indexKey = getVorpalSessionId(appSession);
			if (indexKey == null) {
				indexKey = AsyncSipServlet.generateIndexKey(request);
			}
			String dialog = getVorpalDialogId(sipSession);
			if (dialog == null) {
				dialog = createVorpalDialogId(sipSession);
			}

			String xvs = indexKey + ":" + dialog;
			request.setHeader("X-Vorpal-Session", xvs);
			String xvt = (String) appSession.getAttribute("X-Vorpal-Timestamp");
			if (xvt == null) {
				xvt = Long.toHexString(System.currentTimeMillis()).toUpperCase();
			}
			request.setHeader("X-Vorpal-Timestamp", xvt);

		}

		sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());

		try {
			// useful for identifying sessions
			sipSession.setAttribute("sipAddress", request.getTo());
			request.send();
		} catch (Exception e) {

			sipLogger.severe(request, e);

			if (false == (request.getMethod().equals(ACK) || request.getMethod().equals(PRACK))) {

				// It's too maddening to write callflows where you have to worry about both
				// error responses and exceptions. Let's create a dummy error response.
				SipServletResponse errorResponse = new DummyResponse(request, 502);

				Callback<SipServletResponse> callback = Callflow.pullCallback(errorResponse);

				if (callback != null) {
					Callflow.getLogger().superArrow(Direction.RECEIVE, null, errorResponse,
							callback.getClass().getSimpleName());
					callback.accept(errorResponse);
				} else {
					Callflow.getLogger().superArrow(Direction.RECEIVE, null, errorResponse,
							this.getClass().getSimpleName());
				}

			}

		}

	}

	/**
	 * Sends multiple requests (INVITE) in serial.
	 * 
	 * @param milliseconds   timer duration in milliseconds for each request
	 * @param requests       a list of SipServletRequest objects
	 * @param lambdaFunction supplies a SipServletResponse object
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendRequestsInSerial(long milliseconds, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction) throws ServletException, IOException {

		if (requests.size() > 0) {
			SipServletRequest request = requests.remove(0);
			String timerId = startTimer(request.getApplicationSession(), milliseconds, false, (timeout) -> {
				sendRequest(request.createCancel());
				if (requests.size() > 0) {
					sendRequestsInSerial(milliseconds, requests, lambdaFunction);
				} else {
					// No valid responses, create a dummy one
					lambdaFunction.accept(request.createResponse(408));
				}
			});

			try {
				sendRequest(request, (response) -> {
					stopTimer(response.getApplicationSession(), timerId);
					if (!failure(response) || requests.size() == 0) {
						lambdaFunction.accept(response);
					} else {
						if (requests.size() > 0) {
							sendRequestsInSerial(milliseconds, requests, lambdaFunction);
						} else {
							// No valid responses, create a dummy one
							lambdaFunction.accept(request.createResponse(408));
						}
					}

				});
			} catch (Exception e) {
				sipLogger.severe(request, e.getMessage());
				stopTimer(request.getApplicationSession(), timerId);

				if (requests.size() > 0) {
					sendRequestsInSerial(milliseconds, requests, lambdaFunction);
				} else {
					// No valid responses, create a dummy one
					lambdaFunction.accept(request.createResponse(408));
				}

			}

		} else {
			sipLogger.warning("Callflow.sendRequestsInSerial... Empty request list.");
		}

	}

	/**
	 * Sends multiple requests (INVITE) in parallel.
	 * 
	 * @param requests       a list of SipServletRequest objects
	 * @param lambdaFunction supplies a SipServletResponse object
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendRequestsInParallel(List<SipServletRequest> requests, Callback<SipServletResponse> lambdaFunction)
			throws ServletException, IOException {
		sendRequestsInParallel(0, requests, lambdaFunction);
	}

	/**
	 * Sends multiple requests (INVITE) in parallel, with a timeout.
	 * 
	 * @param timeout        timer duration in milliseconds for all requests
	 * @param requests       a list of SipServletRequest objects
	 * @param lambdaFunction supplies a SipServletResponse object
	 * @throws ServletException
	 * @throws IOException
	 */
	public void sendRequestsInParallel(long timeout, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction) throws ServletException, IOException {

		// Save requests in appSession memory.
		// Use a unique identifier to prevent simultaneous invocations of
		// 'sendRequests' from clobbering each other.

		SipServletRequest firstRequest = requests.iterator().next();
		SipApplicationSession appSession = firstRequest.getApplicationSession();

		String id = "SEND_REQUESTS_" + Math.abs(ThreadLocalRandom.current().nextInt());

		// create a hash map for outstanding requests, use the SipSession id as the key
		Map<String, SipServletRequest> requestMap = new HashMap<>();
		for (SipServletRequest request : requests) {
			requestMap.put(request.getSession().getId(), request);
		}

		appSession.setAttribute(id, requestMap);

		// create a timer for canceling requests, if needed
		// using a little syntactical magic for lambda expressions
		final String timerId = (timeout > 0) ? startTimer(appSession, timeout, false, (timer) -> {
			// get the requests from appSession memory
			@SuppressWarnings("unchecked")
			Map<String, SipServletRequest> savedRequests = (Map<String, SipServletRequest>) appSession.getAttribute(id);

			if (savedRequests != null && false == savedRequests.isEmpty()) {

				appSession.removeAttribute(id);

				// cancel outstanding messages
				for (SipServletRequest rqst : savedRequests.values()) {
					try {
						sendRequest(rqst.createCancel());
					} catch (Exception e) {
						// do nothing;
					}
				}

				// create a dummy response and give it to the user
				lambdaFunction.accept(firstRequest.createResponse(408));
			}
		}) : null;

		// Now send all the requests
		for (SipServletRequest request : requests) {

			sendRequest(request, (response) -> {

				if (!provisional(response)) {

					// get the requests saved in the appSession. It is the only reliable source.
					@SuppressWarnings("unchecked")
					Map<String, SipServletRequest> savedRequests = (Map<String, SipServletRequest>) appSession
							.getAttribute(id);

					if (successful(response)) {
						stopTimer(appSession, timerId);
						appSession.removeAttribute(id);

						// cancel outstanding messages
						for (SipServletRequest rqst : savedRequests.values()) {
							try {
								sendRequest(rqst.createCancel());
							} catch (Exception e) {
								// do nothing;
							}
						}

						// give the successful response to the user
						lambdaFunction.accept(response);
					} else {

						if (savedRequests != null) {

							savedRequests.remove(response.getSession().getId());

							if (savedRequests.isEmpty()) {
								stopTimer(appSession, timerId);

								// give the error response to the user
								lambdaFunction.accept(response);
							} else {
								// save the outstanding requests and await for future responses
								appSession.setAttribute(id, savedRequests);
							}

						}

					}

				}

			});
		}

	}

	/**
	 * Marks the response to be sent 'reliably', meaning you should expect at PRACK
	 * request.
	 * 
	 * @param response a SipServletResponse object
	 * @return the same response as the input parameter
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

		if (response != null && response.isCommitted() == false) {

			SipSession sipSession = response.getSession();

			if (sipSession != null && sipSession.isValid()) {

				if (response.getAttribute(WITHHOLD_RESPONSE) == null) {

					sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());

					switch (response.getMethod()) {
					case INVITE:
						// glare handling;
						sipSession.setAttribute("EXPECT_ACK", Boolean.TRUE);
						// do not break; flow through
					case REGISTER:
					case SUBSCRIBE:
					case NOTIFY:
					case PUBLISH:
					case REFER:
						String indexKey = getVorpalSessionId(response.getApplicationSession());
						String dialog = getVorpalDialogId(response.getSession());
						response.setHeader("X-Vorpal-Session", indexKey + ":" + dialog);

						String xvt = (String) response.getApplicationSession().getAttribute("X-Vorpal-Timestamp");
						if (xvt == null) {
							xvt = Long.toHexString(System.currentTimeMillis()).toUpperCase();
						}
						response.setHeader("X-Vorpal-Timestamp", xvt);

					}

					response.getSession().setAttribute(REQUEST_CALLBACK_ + ACK, lambdaFunction);

					if (provisional(response)) {

						if (response.isReliableProvisional() || null != response.getAttribute(RELIABLE)) {

							if (response.getSession().isValid()) {
								response.getSession().setAttribute(REQUEST_CALLBACK_ + PRACK, lambdaFunction);
								response.sendReliably();
							}

						} else {
							response.send();
						}

					} else {
						response.send();
					}

				}
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
		sendResponse(response, (ackOrPrack) -> {
			// do nothing;
		});
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

	@Deprecated
	public static SipServletRequest createNewRequest(SipServletRequest origin)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				origin.getTo()); //

//		destination.setRequestURI(origin.getRequestURI());
		copyContentAndHeaders(origin, destination);
		return destination;
	}

	public static SipServletRequest createNewRequest(SipServletRequest origin, Address to)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				to); //

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

	// Why have this function? Of course you want to copy the content.
	@Deprecated
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

	public void sendAckOrPrack(SipServletRequest origin, SipServletResponse dest) throws IOException, ServletException {
		if (origin.getMethod().equals(PRACK)) {
			SipServletRequest destPrack = copyContentAndHeaders(origin, dest.createPrack());
			sendRequest(destPrack, (prackResponse) -> {
				sendResponse(origin.createResponse(prackResponse.getStatus()));
			});
		} else if (origin.getMethod().equals(ACK)) {
			SipServletRequest destAck = copyContentAndHeaders(origin, dest.createAck());
			sendRequest(destAck);
		} else {
			// Glare, send 486 busy
			sendResponse(origin.createResponse(486));
		}
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

		}
	}

	public static void copyHeader(String header, SipServletMessage copyFrom, SipServletMessage copyTo) {

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

	public static SipServletResponse copyContent(SipServletMessage copyFrom, SipServletResponse copyTo)
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

		if (ss1.isValid()) {
			ss1.removeAttribute(LINKED_SESSION);
		}
		if (ss2.isValid()) {
			ss2.removeAttribute(LINKED_SESSION);
		}
	}

	public static SipSession getLinkedSession(SipSession ss) {
		return (SipSession) ss.getAttribute(LINKED_SESSION);
	}

	/**
	 * This method is designed to be overloaded by the developer. It is the natural
	 * continuation of calling 'doNotProcess';
	 * 
	 * @throws ServletException
	 * @throws IOException
	 */
	public void processContinue() throws ServletException, IOException {
		// Must be overloaded
	}

	public void processLater(SipServletRequest request, long delay_in_milliseconds) {
		if (request.getApplicationSession().isValid()) {
			request.getApplicationSession().setAttribute(DELAYED_REQUEST, request);
			timerService.createTimer(request.getApplicationSession(), delay_in_milliseconds, false, this);
		}
	}

	public static SipServletResponse createResponse(SipServletRequest aliceRequest, SipServletResponse bobResponse)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletResponse aliceResponse;
		aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
		copyContentAndHeaders(bobResponse, aliceResponse);

		// jwm-test; Why not link the sessions now? I think that should work
		if (successful(bobResponse)) {
			linkSessions(bobResponse.getSession(), aliceResponse.getSession());
		}

		return aliceResponse;
	}

	public static SipServletRequest createAcknowlegement(SipServletResponse bobResponse,
			SipServletRequest aliceAckOrPrack) throws ServletParseException {
		SipServletRequest bobAckOrPrack = null;

		try {
			if (aliceAckOrPrack.getMethod().equals(PRACK)) {
				bobAckOrPrack = copyContentAndHeaders(aliceAckOrPrack, bobResponse.createPrack());
			} else if (aliceAckOrPrack.getMethod().equals(ACK)) {
				bobAckOrPrack = copyContentAndHeaders(aliceAckOrPrack, bobResponse.createAck());
			}
		} catch (Exception e) {
			throw new ServletParseException(e);
		}

		if (bobAckOrPrack == null) {
			throw new ServletParseException("Acknowlegement for " + aliceAckOrPrack.getMethod() + " not allowed.");
		}

		return bobAckOrPrack;
	}

	/**
	 * Creates a new request by copying the content and headers from an initial
	 * request.
	 * 
	 * @param endpoint
	 * @param directive
	 * @param initialRequest
	 * @return copy of initial request
	 * @throws ServletParseException
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	public static SipServletRequest createInitialRequest(URI endpoint, SipApplicationRoutingDirective directive,
			SipServletRequest initialRequest) throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				initialRequest.getApplicationSession(), //
				initialRequest.getMethod(), //
				initialRequest.getFrom(), //
				initialRequest.getTo());

		copyContentAndHeaders(initialRequest, bobRequest);

		bobRequest.setRequestURI(copyParameters(initialRequest.getRequestURI(), endpoint));
		bobRequest.setRoutingDirective(directive, initialRequest);

		return bobRequest;
	}

	/**
	 * Creates a new request with the NEW routing directive by copying the content
	 * and headers from an initial request.
	 * 
	 * @param endpoint
	 * @param initialRequest
	 * @return copy of initial request with the NEW routing directive
	 * @throws ServletParseException
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	public static SipServletRequest createNewInitialRequest(URI endpoint, SipServletRequest initialRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		return createInitialRequest(endpoint, SipApplicationRoutingDirective.NEW, initialRequest);
	}

	/**
	 * Creates a new request with the CONTINUE routing directive by copying the
	 * content and headers from an initial request.
	 * 
	 * @param endpoint
	 * @param initialRequest
	 * @return copy of initial request with the CONTINUE routing directive
	 * @throws ServletParseException
	 * @throws IOException
	 * @throws UnsupportedEncodingException
	 */
	public static SipServletRequest createContinueInitialRequest(URI endpoint, SipServletRequest initialRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		return createInitialRequest(endpoint, SipApplicationRoutingDirective.CONTINUE, initialRequest);
	}

	public static SipServletRequest createContinueInitialRequest(URI endpoint, SipServletRequest template,
			SipServletRequest aliceRequest) throws ServletParseException, UnsupportedEncodingException, IOException {

		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				template.getApplicationSession(), //
				template.getMethod(), //
				template.getFrom(), //
				template.getTo());

		copyContentAndHeaders(template, bobRequest);
		bobRequest.setRequestURI(copyParameters(template.getRequestURI(), endpoint));
		bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
		return bobRequest;
	}

	public static SipServletRequest createRequest(SipServletRequest aliceRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				aliceRequest.getApplicationSession(), //
				aliceRequest.getMethod(), //
				aliceRequest.getFrom(), //
				aliceRequest.getTo());

		copyContentAndHeaders(aliceRequest, bobRequest);
		bobRequest.setRequestURI(aliceRequest.getRequestURI());

		return bobRequest;
	}

	/**
	 * Copies user and parameters (that do not already exist) from one URI to
	 * another.
	 * 
	 * @param from
	 * @param to
	 * @return the update URI
	 * @throws ServletParseException
	 */
	public static URI copyParameters(URI from, URI to) throws ServletParseException {
		SipURI sipFrom = (SipURI) from;
		SipURI sipTo = (SipURI) to;

		// copy user (if it doesn't exist)
		if (sipFrom.getUser() != null && sipTo.getUser() == null) {
			sipTo.setUser(sipFrom.getUser());
		}

		// copy URI parameters (if they don't exist)
		String value;
		for (String name : from.getParameterNameSet()) {
			if (null == (value = to.getParameter(name))) {
				to.setParameter(name, value);
			}
		}

		return to;
	}

	/**
	 * Copies both Address and URI parameters that do not already exist. Will copy
	 * the user-part of the URI if it doesn't exist.
	 * 
	 * @param from
	 * @param to
	 * @return
	 * @throws ServletParseException
	 */
	public static Address copyParameters(Address from, Address to) throws ServletParseException {

		copyParameters(from.getURI(), to.getURI());

		String value;
		for (String name : from.getParameterNameSet()) {
			if (null == (value = to.getParameter(name))) {
				to.setParameter(name, value);
			}
		}

		return to;
	}

	public void proxyRequest(SipServletRequest inboundRequest, ProxyPlan proxyPlan,
			Callback<SipServletResponse> lambdaFunction) throws IOException, ServletException {
		
//		sipLogger.finer(inboundRequest, "Callflow.proxyRequest...");
//		sipLogger.logObjectAsJson(Level.FINER, proxyPlan);

		SipSession sipSession = inboundRequest.getSession();
		SipApplicationSession appSession = inboundRequest.getApplicationSession();
		Boolean isProxy = (Boolean) appSession.getAttribute("isProxy");
		isProxy = (isProxy==null) ? false : isProxy;
		
		if (isProxy) {
			// Should NOT explicitly proxy subsequent request INVITE
			sipLogger.warning(inboundRequest, "Callflow.proxyRequest should not be called twice.");
			return;
			// throw new IOException("Callflow.proxyRequest should not be called twice.");
		}

		if (proxyPlan.isEmpty()) {
			throw new ServletException("Invalid ProxyPlan. No ProxyTiers defined.");
		}

		Proxy proxy = inboundRequest.getProxy();

		ProxyTier proxyTier = proxyPlan.getTiers().remove(0);

		proxy.setParallel(proxyTier.getMode().equals(Mode.parallel));
		// proxy.setRecordRoute(false);
		// proxy.setSupervised(true);

		List<URI> endpoints = new LinkedList<URI>();
		for (URI endpoint : proxyTier.getEndpoints()) {
			endpoints.add(endpoint);
		}
		List<ProxyBranch> proxyBranches = proxy.createProxyBranches(endpoints);

		Integer timeout = proxyTier.getTimeout();
		if (timeout != null && timeout > 0) {
			proxy.setProxyTimeout(timeout);
		}

		inboundRequest.getSession().setAttribute("RESPONSE_CALLBACK_" + inboundRequest.getMethod(), lambdaFunction);
//		inboundRequest.getSession().setAttribute("isProxy", Boolean.TRUE);

		// jwm - test proxy arrow - works!
		inboundRequest.getApplicationSession().setAttribute("isProxy", Boolean.TRUE);

		proxy.startProxy();

		for (ProxyBranch proxyBranch : proxy.getProxyBranches()) {
			sipLogger.superArrow(Direction.SEND, false, proxyBranch.getRequest(), null, this.getClass().getSimpleName(),
					null);
		}

	}

	public static Logger getSipLogger() {
		return sipLogger;
	}

	public static void setSipLogger(Logger sipLogger) {
		Callflow.sipLogger = sipLogger;
	}

	public static SessionParameters getSessionParameters() {
		return sessionParameters;
	}

	public static void setSessionParameters(SessionParameters sessionParameters) {
		Callflow.sessionParameters = sessionParameters;
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
