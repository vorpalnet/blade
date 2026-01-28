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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SessionKeepAlive.Refresher;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.TooManyHopsException;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.keepalive.KeepAlive;
import org.vorpal.blade.framework.v2.keepalive.KeepAliveExpiry;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;
import org.vorpal.blade.framework.v2.proxy.ProxyTier.Mode;
import org.vorpal.blade.framework.v2.testing.DummyResponse;

import com.sun.xml.ws.runtime.dev.SessionManager;

public abstract class Callflow implements Serializable {
	private static final long serialVersionUID = 1L;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;
	protected static Logger sipLogger;
	protected static SessionParameters sessionParameters;

	public static SessionParameters getSessionParameters() {
		return sessionParameters;
	}

	public static void setSessionParameters(SessionParameters sessionParameters) {
		Callflow.sessionParameters = sessionParameters;
	}

	// Useful strings
	public static final String INVITE = "INVITE";
	public static final String ACK = "ACK";
	public static final String BYE = "BYE";
	public static final String CANCEL = "CANCEL";
	public static final String REGISTER = "REGISTER";
	public static final String OPTIONS = "OPTIONS";
	public static final String PRACK = "PRACK";
	public static final String SUBSCRIBE = "SUBSCRIBE";
	public static final String NOTIFY = "NOTIFY";
	public static final String PUBLISH = "PUBLISH";
	public static final String INFO = "INFO";
	public static final String UPDATE = "UPDATE";
	public static final String REFER = "REFER";
	public static final String SIP = "SIP";
	public static final String Contact = "Contact";
	public static final String RELIABLE = "100rel";

	protected static final String REQUEST_CALLBACK_ = "REQUEST_CALLBACK_";
	protected static final String RESPONSE_CALLBACK_ = "RESPONSE_CALLBACK_";
	protected static final String PROXY_CALLBACK_ = "PROXY_CALLBACK_";
	protected static final String LINKED_SESSION = "LINKED_SESSION";
	protected static final String DELAYED_REQUEST = "DELAYED_REQUEST";
	protected static final String WITHHOLD_RESPONSE = "WITHHOLD_RESPONSE";

	// Header and attribute name constants
	private static final String X_VORPAL_SESSION = "X-Vorpal-Session";
	private static final String X_VORPAL_TIMESTAMP = "X-Vorpal-Timestamp";
	private static final String X_VORPAL_DIALOG = "X-Vorpal-Dialog";
	private static final String EXPECT_ACK = "EXPECT_ACK";
	private static final String USER_AGENT_ATTR = "userAgent";
	private static final String SIP_ADDRESS_ATTR = "sipAddress";
	private static final String IS_PROXY_ATTR = "isProxy";
	private static final String CALLER = "caller";
	private static final String CALLEE = "callee";
	private static final String MESSAGE_SIPFRAG = "message/sipfrag";
	private static final String SEND_REQUESTS_PREFIX = "SEND_REQUESTS_";

	// Response code constants
	private static final int RESPONSE_CODE_408 = 408;
	private static final int RESPONSE_CODE_486 = 486;
	private static final int RESPONSE_CODE_500 = 500;

	// Session expiration defaults
	private static final int DEFAULT_SESSION_EXPIRES_MINUTES = 60;
	private static final int MIN_SESSION_EXPIRES_MINUTES = 30;

	/**
	 * Checks if the response is a provisional response (1xx status code).
	 *
	 * @param response the SIP response to check
	 * @return true if the status code is between 100 and 199 inclusive
	 */
	public static boolean provisional(SipServletResponse response) {
		return (response.getStatus() >= 100 && response.getStatus() < 200);
	}

	/**
	 * Checks if the response is a successful response (2xx status code).
	 *
	 * @param response the SIP response to check
	 * @return true if the status code is between 200 and 299 inclusive
	 */
	public static boolean successful(SipServletResponse response) {
		return (response.getStatus() >= 200 && response.getStatus() < 300);
	}

	/**
	 * Checks if the response is a redirection response (3xx status code).
	 *
	 * @param response the SIP response to check
	 * @return true if the status code is between 300 and 399 inclusive
	 */
	public static boolean redirection(SipServletResponse response) {
		return (response.getStatus() >= 300 && response.getStatus() < 400);
	}

	/**
	 * Checks if the response is a failure response (4xx, 5xx, or 6xx status code).
	 *
	 * @param response the SIP response to check
	 * @return true if the status code is 400 or greater
	 */
	public static boolean failure(SipServletResponse response) {
		return (response.getStatus() >= 400);
	}

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
				if (request.getMethod().equals(NOTIFY) && MESSAGE_SIPFRAG.equals(request.getContentType())) {
					String sipfrag;
					if (request.getContent() instanceof String) {
						sipfrag = (String) request.getContent();
					} else {
						sipfrag = new String(request.getRawContent()).trim();
					}

					// Keep callback for provisional NOTIFY responses (sipfrag without final status)
					if (!sipfrag.matches(".*[2-5][0-9][0-9].*")) {
						removeAttribute = false;
					}

				}
			} catch (Exception e) {
				sipLogger.warning(request, "#Q1 Callflow.pullCallback - Exception e");
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

	/**
	 * Retrieves and removes the response callback associated with the SIP session.
	 * For proxy responses, retrieves the callback from the original request's
	 * session. Only removes the callback attribute for final responses (status >=
	 * 200).
	 *
	 * @param response the SIP response
	 * @return the callback function, or null if none was registered
	 */
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

	/**
	 * Retrieves and removes the proxy callback associated with the application
	 * session. Only removes the callback attribute for final responses (status >=
	 * 200).
	 *
	 * @param response the SIP response from a proxy branch
	 * @return the callback function, or null if none was registered
	 */
	@SuppressWarnings("unchecked")
	public static Callback<SipServletResponse> pullProxyCallback(SipServletResponse response) {
		Callback<SipServletResponse> callback = null;
		SipApplicationSession appSession = response.getApplicationSession();
		String attribute = PROXY_CALLBACK_ + response.getMethod();
		callback = (Callback<SipServletResponse>) appSession.getAttribute(attribute);
		if (callback != null) {
			if (response.getProxyBranch() == null && response.getStatus() >= 200) {
				appSession.removeAttribute(attribute);
			}
		}
		return callback;
	}

	/**
	 * Retrieves the callback stored in the timer's info object.
	 *
	 * @param timer the servlet timer
	 * @return the callback function stored when the timer was created
	 */
	@SuppressWarnings("unchecked")
	public static Callback<ServletTimer> pullCallback(ServletTimer timer) {
		return (Callback<ServletTimer>) timer.getInfo();
	}

	/**
	 * Returns the SIP logger used for logging SIP messages and callflow events.
	 *
	 * @return the SIP logger instance
	 */
	public static Logger getLogger() {
		return sipLogger;
	}

	/**
	 * Sets the SIP logger used for logging SIP messages and callflow events.
	 *
	 * @param sipLogger the SIP logger instance to set
	 */
	public static void setLogger(Logger sipLogger) {
		Callflow.sipLogger = sipLogger;
	}

	/**
	 * Returns the timer service used to create and manage servlet timers.
	 *
	 * @return the timer service instance
	 */
	public static TimerService getTimerService() {
		return timerService;
	}

	/**
	 * Sets the timer service used to create and manage servlet timers.
	 *
	 * @param timerService the timer service instance to set
	 */
	public static void setTimerService(TimerService timerService) {
		Callflow.timerService = timerService;
	}

	/**
	 * Returns the SIP factory used to create SIP messages, addresses, and URIs.
	 *
	 * @return the SIP factory instance
	 */
	public static SipFactory getSipFactory() {
		return sipFactory;
	}

	/**
	 * Returns the SIP sessions utility for looking up application sessions by key.
	 *
	 * @return the SIP sessions utility instance
	 */
	public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	/**
	 * Processes an incoming SIP request. Subclasses must implement this method to
	 * define their specific callflow logic.
	 *
	 * @param request the incoming SIP request to process
	 * @throws ServletException if a servlet error occurs during processing
	 * @throws IOException      if an I/O error occurs during processing
	 */
	public abstract void process(SipServletRequest request) throws ServletException, IOException;

	@Deprecated
	public String schedulePeriodicTimer(SipApplicationSession appSession, int seconds,
			Callback<ServletTimer> lambdaFunction) throws ServletException, IOException {
		long delay = seconds * 1000;
		long period = seconds * 1000;
		boolean fixedDelay = false;
		boolean isPersistent = false;
		return timerService.createTimer(appSession, delay, period, fixedDelay, isPersistent, lambdaFunction).getId();
	}

	@Deprecated
	public String schedulePeriodicTimerInMilliseconds(SipApplicationSession appSession, long milliseconds,
			Callback<ServletTimer> lambdaFunction) throws ServletException, IOException {
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

		String attribute = REQUEST_CALLBACK_ + method;

		if (callback != null && sipSession.isValid()) {
			sipSession.setAttribute(attribute, callback);
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

		String attribute = REQUEST_CALLBACK_ + method;

		if (appSession.isValid()) {
			appSession.setAttribute(attribute, callback);
		} else {

			if (sipLogger.isLoggable(Level.WARNING)) {
				sipLogger.warning(appSession,
						"#1.2 Callflow.expectRequest - Failed to set expectation; appSession is invalid. This should not be possible. (Check your code.)");
			}

		}

		return new Expectation(appSession, method, callback);
	}

	/**
	 * This method generates a unique 8-digit hexadecimal number 'vorpal-id', to be
	 * used as a tracking number for messages traveling through the system.
	 * 
	 * @param appSession
	 * @return
	 */
	private static String createVorpalSessionId(SipApplicationSession appSession) {

		String indexKey = null;

		do {
			indexKey = String.format("%08X", //
					Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)) //
			).toUpperCase();

		} while (!getSipUtil().getSipApplicationSessionIds(indexKey).isEmpty());

		appSession.setAttribute(X_VORPAL_SESSION, indexKey);

		// X-Vorpal-Session + X-Vorpal-Timestamp will be unique.
		// Use this for a database primary key in future designs.
		String timestamp = Long.toHexString(System.currentTimeMillis()).toUpperCase();
		appSession.setAttribute(X_VORPAL_TIMESTAMP, timestamp);

		System.out.println(
				SettingsManager.getApplicationName() + "... Callflow.createVorpalSessionId - indexKey=" + indexKey);
		return indexKey;
	}

	/**
	 * Returns the Vorpal session ID for the given application session. The Vorpal
	 * session ID is a unique 8-digit hexadecimal identifier used for tracking
	 * messages across the system.
	 *
	 * @param appSession the SIP application session
	 * @return the Vorpal session ID, or null if not set
	 */
	public static String getVorpalSessionId(SipApplicationSession appSession) {
		if (appSession == null) {
			return null;
		}
		return (String) appSession.getAttribute(X_VORPAL_SESSION);
	}

	/**
	 * Returns the Vorpal session ID for the given request, creating one if
	 * necessary. If the request contains a composite X-Vorpal-Session header
	 * (session:dialog format), it will be parsed and the dialog ID will be stored
	 * separately.
	 *
	 * @param request the SIP request
	 * @return the Vorpal session ID
	 */
	public static String getVorpalSessionId(SipServletRequest request) {
		String indexKey = null;

		if (request != null) {
			SipApplicationSession appSession = request.getApplicationSession();
			indexKey = (String) appSession.getAttribute(X_VORPAL_SESSION);

			if (indexKey == null) {
				String xVorpalSession = (String) request.getHeader(X_VORPAL_SESSION);

				if (xVorpalSession != null) {
					SipSession sipSession = request.getSession();
					int colonIndex = xVorpalSession.indexOf(':');
					if (colonIndex > 0) {
						indexKey = xVorpalSession.substring(0, colonIndex);
						appSession.setAttribute(X_VORPAL_SESSION, indexKey);
						String dialogId = xVorpalSession.substring(colonIndex + 1);
						sipSession.setAttribute(X_VORPAL_DIALOG, dialogId);
					}
				}
			}

			indexKey = (indexKey != null) ? indexKey : createVorpalSessionId(appSession);
		}

		return indexKey;
	}

	/**
	 * Creates a unique 4-digit hexadecimal dialog ID for the session.
	 *
	 * @param sipSession the SIP session
	 * @return the newly created dialog ID, or null if session is null
	 * @throws Exception
	 */
	private static String createVorpalDialogId(SipSession sipSession) {

		String dialog = null;

		if (sipSession != null) {
			try {
				dialog = String.format("%04X", Math.abs(sipSession.getId().hashCode()) % 0xFFFF);
				sipSession.setAttribute(X_VORPAL_DIALOG, dialog);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return dialog;
	}

	/**
	 * Returns the Vorpal dialog ID for the given SIP session, creating one if
	 * necessary.
	 *
	 * @param sipSession the SIP session
	 * @return the Vorpal dialog ID, or null if session is null
	 */
	public static String getVorpalDialogId(SipSession sipSession) {
		String dialog = null;

		if (sipSession != null && sipSession.isValid()) {
			dialog = (String) sipSession.getAttribute(X_VORPAL_DIALOG);
			dialog = (dialog != null) ? dialog : createVorpalDialogId(sipSession);
		}

		return dialog;
	}

	/**
	 * Returns the Vorpal dialog ID for the given SIP message, creating one if
	 * necessary for requests.
	 *
	 * @param msg the SIP message (request or response)
	 * @return the Vorpal dialog ID, or null if message is null
	 */
	public static String getVorpalDialogId(SipServletMessage msg) {
		String dialog = null;

		if (msg != null) {
			dialog = getVorpalDialogId(msg.getSession());

			if (dialog == null && msg instanceof SipServletRequest) {
				dialog = createVorpalDialogId(msg.getSession());
			}
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
	@SuppressWarnings("serial")
	public void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambdaFunction)
			throws ServletException, IOException {

		SipApplicationSession appSession;
		SipSession sipSession;

		try {

			if (request != null) {
				appSession = request.getApplicationSession();
				sipSession = request.getSession();

				if (sipSession != null && sipSession.isValid()) {

					// begin KeepAlive logic...
					try {
						if (request.isInitial() && request.getMethod().equals(INVITE)) { //
							int configSessionExpiresInMinutes = DEFAULT_SESSION_EXPIRES_MINUTES;

							SessionParameters params = SettingsManager.getSessionParameters();
							if (params != null && params.getExpiration() != null) {
								configSessionExpiresInMinutes = params.getExpiration();
//								sipLogger.severe(request,
//										"Callflow.sendRequest - #GF session params exists. configSessionExpiresInMinutes="
//												+ configSessionExpiresInMinutes);

							}

							appSession.setExpires(configSessionExpiresInMinutes + 1); // and a pinch to grow on

//							
//
//							int sessionExpiresInMinutes = 0;
//							int finalSessionExpiresInMinutes = 0;
//
//							Parameterable sessionExpires = request.getParameterableHeader("Session-Expires");
//							String refresher = null;
//							if (sessionExpires != null) {
//								refresher = sessionExpires.getParameter("refresher");
//							}
//
//							if (sessionExpires == null) { // create Session-Expires
//
//								int sessionExpiresInSeconds = configSessionExpiresInMinutes * 60;
//								int minSEinSeconds = sessionExpiresInSeconds / 2;
//
//								if (sipLogger.isLoggable(Level.FINER)) {
//									sipLogger.finer(request,
//											Color.YELLOW_BOLD_BRIGHT("Callflow.sendRequest - sessionExpiresInSeconds="
//													+ sessionExpiresInSeconds + ", minSEinSeconds=" + minSEinSeconds));
//								}
//
//								request.getSessionKeepAlivePreference().setEnabled(true);
//								request.getSessionKeepAlivePreference().setExpiration(sessionExpiresInSeconds);
//								request.getSessionKeepAlivePreference().setMinimumExpiration(minSEinSeconds);
//								request.getSessionKeepAlivePreference().setRefresher(Refresher.UAC);
//								SessionKeepAlive skl = request.getSession().getKeepAlive();
//
//								skl.setRefreshCallback(new SessionKeepAlive.Callback() {
//									public void handle(SipSession session) {
//										try {
//											KeepAlive refresher = new KeepAlive();
//											refresher.handle(session);
//										} catch (Exception e100) {
//											sipLogger.warning(sipSession,
//													"#1.3 Callflow.sendRequest - catch Exception e100");
//										}
//									}
//								});
//
//								skl.setExpiryCallback(new SessionKeepAlive.Callback() {
//									public void handle(SipSession session) {
//										try {
//											KeepAliveExpiry expiry = new KeepAliveExpiry();
//											expiry.handle(sipSession);
//										} catch (Exception e200) {
//											sipLogger.warning(sipSession,
//													"#1.4 Callflow.sendRequest - catch Exception e200");
//										}
//									}
//								});
//
//							}
// 
//							else { // sessionExpires == null
//								String strSessionExpires = sessionExpires.getValue();
//								sessionExpiresInMinutes = Integer.parseInt(strSessionExpires) / 60;
//
////								sipLogger.severe(request,
////										"Callflow.sendRequest - #QZ session params exists. sessionExpiresInMinutes="
////												+ sessionExpiresInMinutes);
//
//								sessionExpires.setParameter("refresher", "uac");
//							}
//
//							finalSessionExpiresInMinutes = Math.max(sessionExpiresInMinutes,
//									configSessionExpiresInMinutes);
//
//							if (sipLogger.isLoggable(Level.FINER)) {
//								sipLogger.finer(request, Color.YELLOW_BOLD_BRIGHT(
//										"Callflow.sendRequest - appSession.setExpires sessionExpiresInMinutes="
//												+ sessionExpiresInMinutes//
//												+ ", configSessionExpiresInMinutes=" + configSessionExpiresInMinutes//
//												+ ", finalSessionExpiresInMinutes=" + finalSessionExpiresInMinutes));
//							}
//
//							appSession.setExpires(finalSessionExpiresInMinutes + 1); // and a pinch to grow on

						}
					} catch (Exception exk) {
						sipLogger.severe(request,
								"Callflow.sendRequest - Unable to set keep alive: " + exk.getMessage());
					}
					// end KeepAlive logic.

					if (lambdaFunction != null) {
						request.getSession().setAttribute(RESPONSE_CALLBACK_ + request.getMethod(), lambdaFunction);
					}

					String method = request.getMethod();

					// for GLARE handling
					switch (method) {
					case INVITE:
						sipSession.setAttribute(EXPECT_ACK, Boolean.TRUE);

						if (request.isInitial()) {
							String vorpalId = getVorpalSessionId(appSession);
							String dialogId = getVorpalDialogId(sipSession);
							String timestamp = getVorpalTimestamp(appSession);
							request.setHeader(X_VORPAL_SESSION, vorpalId + ":" + dialogId);
							request.setHeader(X_VORPAL_TIMESTAMP, timestamp);
						}

						break;
					case ACK:
						sipSession.removeAttribute(EXPECT_ACK);
						break;
					}

					// useful for identifying sessions
					sipSession.setAttribute(SIP_ADDRESS_ATTR, request.getTo());
					request.send();
					sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());

				}
			}

		} catch (

		Exception ex300) {
			sipLogger.warning(request, "#1.5 Callflow.sendRequest - catch Exception ex300");
			sipLogger.severe(request, ex300);

			if (!request.getMethod().equals(ACK) && !request.getMethod().equals(PRACK)) {

				// It's too maddening to write callflows where you have to worry about both
				// error responses and exceptions. Let's create a dummy error response.
				SipServletResponse errorResponse = new DummyResponse(request, RESPONSE_CODE_500,
						ex300.getClass().getSimpleName());
				errorResponse.setContent(ex300.getMessage(), "text/plain");

				if (lambdaFunction != null) {
					lambdaFunction.accept(errorResponse);
				}

			}

		}

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
		sendRequest(request, null);
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

		if (!requests.isEmpty()) {
			SipServletRequest request = requests.remove(0);
			String timerId = startTimer(request.getApplicationSession(), milliseconds, false, (timeout) -> {
				sendRequest(request.createCancel());
				if (!requests.isEmpty()) {
					sendRequestsInSerial(milliseconds, requests, lambdaFunction);
				} else {
					// No valid responses, create a dummy one
					lambdaFunction.accept(request.createResponse(RESPONSE_CODE_408));
				}
			});

			try {
				sendRequest(request, (response) -> {
					stopTimer(response.getApplicationSession(), timerId);
					if (!failure(response) || requests.isEmpty()) {
						lambdaFunction.accept(response);
					} else {
						if (!requests.isEmpty()) {
							sendRequestsInSerial(milliseconds, requests, lambdaFunction);
						} else {
							// No valid responses, create a dummy one
							lambdaFunction.accept(request.createResponse(RESPONSE_CODE_408));
						}
					}

				});
			} catch (Exception ex500) {
				sipLogger.warning(request,
						"#1.6 Callflow.sendRequestsInSerial - catch Exception ex500: " + ex500.getMessage());
				sipLogger.severe(request, ex500.getMessage());
				stopTimer(request.getApplicationSession(), timerId);

				if (!requests.isEmpty()) {
					sendRequestsInSerial(milliseconds, requests, lambdaFunction);
				} else {
					// No valid responses, create a dummy one
					lambdaFunction.accept(request.createResponse(RESPONSE_CODE_408));
				}

			}

		} else {
			sipLogger.warning("#1.7 Callflow.sendRequestsInSerial... Empty request list.");
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

		String id = SEND_REQUESTS_PREFIX + Math.abs(ThreadLocalRandom.current().nextInt());

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

			if (savedRequests != null && !savedRequests.isEmpty()) {

				appSession.removeAttribute(id);

				// cancel outstanding messages
				for (SipServletRequest rqst : savedRequests.values()) {
					try {
						sendRequest(rqst.createCancel());
					} catch (Exception ex900) {
						sipLogger.warning(rqst, "#1.8 Callflow.startTimer - catch Exception ex900");
						// do nothing;
					}
				}

				// create a dummy response and give it to the user
				lambdaFunction.accept(firstRequest.createResponse(RESPONSE_CODE_408));
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
							} catch (Exception exAA1) {
								sipLogger.warning(rqst, "#1.9 Callflow.startTimer - catch Exception exAA1");
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
		SipSession sipSession = response.getSession();

		if (response.getAttribute(WITHHOLD_RESPONSE) == null) {

			sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());

			if (response.getMethod().equals(INVITE)) {
				// glare handling;
				if (response.getStatus() >= 300) {
					sipSession.removeAttribute(EXPECT_ACK);

					SipSession linkedSession = getLinkedSession(sipSession);
					if (linkedSession != null) {
						linkedSession.removeAttribute(EXPECT_ACK);
					}
				}

			}

			// For inserting X-Vorpal-Session
			if (response.getRequest().isInitial()) {
				String indexKey = getVorpalSessionId(response.getApplicationSession());
				if (indexKey != null) {
					String dialog = getVorpalDialogId(response.getSession());
					response.setHeader(X_VORPAL_SESSION, indexKey + ":" + dialog);

//						String xvt = (String) response.getApplicationSession().getAttribute(X_VORPAL_TIMESTAMP);

					String xvt = getVorpalTimestamp(response.getApplicationSession());
					response.setHeader(X_VORPAL_TIMESTAMP, xvt);

				}
			}

//			// begin KeepAlive logic...
//			try {
//				if (successful(response) //
//						&& response.getMethod().equals(INVITE) //
//						&& response.getRequest().isInitial() //
//				) { //
//					int sessionExpiresInMinutes = DEFAULT_SESSION_EXPIRES_MINUTES;
//
//					Parameterable sessionExpires = response.getRequest().getParameterableHeader("Session-Expires");
//					String refresher;
//					if (sessionExpires != null//
//							&& ((refresher = sessionExpires.getParameter("refresher")) != null) //
//							&& refresher.equalsIgnoreCase("uas") //
//					) { // create Session-Expires
//
//						String strSessionExpires = sessionExpires.getValue();
//						sessionExpiresInMinutes = Integer.parseInt(strSessionExpires) / 60;
//
//						if (sipLogger.isLoggable(Level.FINER)) {
//							sipLogger.finer(response,
//									Color.YELLOW_BOLD_BRIGHT(
//											"Callflow.sendRequest - strSessionExpires=(" + strSessionExpires
//													+ ", sessionExpiresInMinutes=" + sessionExpiresInMinutes));
//						}
//
//						int sessionExpiresInSeconds = sessionExpiresInMinutes * 60;
//						int minSEinSeconds = sessionExpiresInSeconds / 2;
//
//						if (sipLogger.isLoggable(Level.FINER)) {
//							sipLogger.finer(response,
//									Color.YELLOW_BOLD_BRIGHT("Callflow.sendResponse - sessionExpiresInSeconds="
//											+ sessionExpiresInSeconds + ", minSEinSeconds=" + minSEinSeconds));
//						}
//
//						response.getSessionKeepAlivePreference().setEnabled(true);
//						response.getSessionKeepAlivePreference().setExpiration(sessionExpiresInSeconds);
//						response.getSessionKeepAlivePreference().setMinimumExpiration(minSEinSeconds);
//						response.getSessionKeepAlivePreference().setRefresher(Refresher.UAS);
//						SessionKeepAlive skl = response.getSession().getKeepAlive();
//
//						skl.setRefreshCallback(new SessionKeepAlive.Callback() {
//							public void handle(SipSession session) {
//								try {
//									KeepAlive refresher = new KeepAlive();
//									refresher.handle(session);
//								} catch (Exception e100) {
//									sipLogger.warning(sipSession, "#1.3 Callflow.sendRequest - catch Exception e100");
//								}
//							}
//						});
//
//						skl.setExpiryCallback(new SessionKeepAlive.Callback() {
//							public void handle(SipSession session) {
//								try {
//									KeepAliveExpiry expiry = new KeepAliveExpiry();
//									expiry.handle(sipSession);
//								} catch (Exception e200) {
//									sipLogger.warning(sipSession, "#1.4 Callflow.sendRequest - catch Exception e200");
//								}
//							}
//						});
//
//					} else {
//
////						SessionParameters params = Callflow.getSessionParameters(); // config file settings
//						SessionParameters params = SettingsManager.getSessionParameters(); // config file settings
//						if (params != null && params.getExpiration() != null) {
//							sessionExpiresInMinutes = params.getExpiration();
//						}
//					}
//
//					if (sipLogger.isLoggable(Level.FINER)) {
//						sipLogger.finer(response, Color.YELLOW_BOLD_BRIGHT(
//								"Callflow.sendRequest - appSession.setExpires(" + sessionExpiresInMinutes + ")"));
//					}
//					response.getApplicationSession().setExpires(sessionExpiresInMinutes + 1); // and a pinch to grow on
//
//				}
//
//			} catch (Exception exKeepAlive) {
//				sipLogger.warning(response,
//						"Callflow.doResponse - Unable to set keep alive: " + exKeepAlive.getMessage());
//				sipLogger.severe(response, exKeepAlive);
//			}
//			// end KeepAlive logic.

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

	/**
	 * Sets the SIP factory used to create SIP messages, addresses, and URIs.
	 *
	 * @param sipFactory the SIP factory instance to set
	 */
	public static void setSipFactory(SipFactory sipFactory) {
		Callflow.sipFactory = sipFactory;
	}

	/**
	 * Sets the SIP sessions utility for looking up application sessions by key.
	 *
	 * @param sipUtil the SIP sessions utility instance to set
	 */
	public static void setSipUtil(SipSessionsUtil sipUtil) {
		Callflow.sipUtil = sipUtil;
	}

	/**
	 * Creates a SipServletRequest from a SipSession, copying the method, headers
	 * and body content.
	 * 
	 * @param destSession
	 * @param originRequest
	 * @return
	 * @throws ServletParseException
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public static SipServletRequest continueRequest(SipSession destSession, SipServletRequest originRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest destRequest = destSession.createRequest(originRequest.getMethod());
		copyContentAndHeaders(originRequest, destRequest);
		return destRequest;
	}

	/**
	 * Creates a SipServletRequest from SipFactory by copying the
	 * SipApplicationSession, method, From and To. It also copies the headers, body
	 * content and sets the routing directive to continue. Finally, it sets the
	 * request URI as specified and links the two sessions.
	 * 
	 * @param uri           the SIP request URI
	 * @param originRequest to be copied
	 * @return request
	 * @throws ServletParseException
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public static SipServletRequest continueRequest(URI uri, SipServletRequest originRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest destRequest;

		destRequest = sipFactory.createRequest(originRequest.getApplicationSession(), originRequest.getMethod(),
				originRequest.getFrom(), originRequest.getTo());
		destRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, originRequest);
		copyContentAndHeaders(originRequest, destRequest);
		destRequest.setRequestURI(uri);

		return destRequest;
	}

	/**
	 * Creates a SipServletRequest from SipFactory by copying the
	 * SipApplicationSession, method, From and To. It also copies the headers, body
	 * content and sets the routing directive to continue. Finally, it sets the
	 * request URI from the specified String.
	 * 
	 * @param uri           as a Java String
	 * @param originRequest to be copied
	 * @return request
	 * @throws ServletParseException
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public static SipServletRequest continueRequest(String strUri, SipServletRequest originRequest)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		return continueRequest(sipFactory.createURI(strUri), originRequest);
	}

	/**
	 * Creates a SipServletResponse from a SipServletRequest, copying the status
	 * code, reason phrase, headers and body content.
	 * 
	 * @param destRequest
	 * @param originResponse
	 * @return
	 * @throws ServletParseException
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public static SipServletResponse continueResponse(SipServletRequest destRequest, SipServletResponse originResponse)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletResponse destResponse = null;
		destResponse = destRequest.createResponse(originResponse.getStatus(), originResponse.getReasonPhrase());

		if (destResponse != null) {
			copyContentAndHeaders(originResponse, destResponse);
		}

		return destResponse;
	}

	@Deprecated
	public static SipServletRequest createNewRequest(SipServletRequest origin)
			throws IOException, ServletParseException {

		SipServletRequest destination = sipFactory.createRequest(//
				origin.getApplicationSession(), //
				origin.getMethod(), //
				origin.getFrom(), //
				origin.getTo()); //

		copyContentAndHeaders(origin, destination);
		return destination;
	}

	/**
	 * Creates a new SIP request with a different To address, copying content and
	 * headers from the original request.
	 *
	 * @param origin the original SIP request to copy from
	 * @param to     the new destination address
	 * @return the new SIP request
	 * @throws IOException           if an I/O error occurs
	 * @throws ServletParseException if address parsing fails
	 */
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

	/**
	 * Creates a new SIP request with the specified method on the same session as
	 * the response.
	 *
	 * @param response the SIP response whose session will be used to create the
	 *                 request
	 * @param method   the SIP method for the new request (e.g., "BYE", "INFO")
	 * @return the new SIP request
	 * @throws IOException           if an I/O error occurs
	 * @throws ServletParseException if parsing fails
	 */
	public static SipServletRequest createRequest(SipServletResponse response, String method)
			throws IOException, ServletParseException {
		SipServletRequest request = response.getSession().createRequest(method);
		return request;
	}

	/**
	 * Sends an ACK or PRACK request downstream based on an upstream request. For
	 * PRACK, also sends the response back upstream. For other methods, treats it as
	 * a glare condition and sends 486 Busy.
	 *
	 * @param origin the upstream ACK or PRACK request
	 * @param dest   the downstream response to acknowledge
	 * @throws IOException      if an I/O error occurs
	 * @throws ServletException if a servlet error occurs
	 */
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
			sendResponse(origin.createResponse(RESPONSE_CODE_486));
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

		if (copyFrom != null && copyTo != null) {

			for (String header : copyFrom.getHeaderNameList()) {

				switch (header) {
				case "To":
				case "From":
				case "Via":
				case "Call-ID":
				case "Contact":
				case "Content-Length":
				case "CSeq":
				case "Max-Forwards":
				case "Content-Type":
				case "Record-Route":
				case "Route":
				case "RSeq":
				case "RAck":
					// do not copy these headers
					break;
				default:
					copyHeader(header, copyFrom, copyTo);
				}

			}
		}
	}

	/**
	 * Copies a specific header from one SIP message to another, deduplicating
	 * values.
	 *
	 * @param header   the name of the header to copy
	 * @param copyFrom the source SIP message
	 * @param copyTo   the destination SIP message
	 */
	public static void copyHeader(String header, SipServletMessage copyFrom, SipServletMessage copyTo) {
		if (copyFrom != null && copyTo != null) {
			Set<String> uniqueValues = new HashSet<>(copyFrom.getHeaderList(header));
			copyTo.setHeader(header, String.join(",", uniqueValues));
		}
	}

	/**
	 * Copies the content body from one SIP message to a request.
	 *
	 * @param copyFrom the source SIP request
	 * @param copyTo   the destination SIP request
	 * @return the destination request with copied content
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletRequest copyContent(SipServletMessage copyFrom, SipServletRequest copyTo)
			throws UnsupportedEncodingException, IOException {
		if (copyFrom != null && copyTo != null) {
			copyTo.setContent(copyFrom.getContent(), copyFrom.getContentType());

			// automatically link session
			if (copyTo.isInitial() //
					|| copyTo.getMethod().equals(INVITE) //
					|| copyTo.getMethod().equals(ACK)) {
				linkSession(copyFrom, copyTo);
			}
		}
		return copyTo;
	}

	public static SipServletResponse copyContent(SipServletMessage copyFrom, SipServletResponse copyTo)
			throws UnsupportedEncodingException, IOException {
		if (copyFrom != null && copyTo != null) {
			copyTo.setContent(copyFrom.getContent(), copyFrom.getContentType());

			// automatically link session
			if (successful(copyTo) && copyTo.getSession().getState().equals(State.EARLY)) {
				linkSession(copyFrom, copyTo);
			}

		}
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
		if (copyFrom != null && copyTo != null) {

			// Special case, copy Contact headers for REGISTER message
			if (copyFrom.getMethod().equals(REGISTER)) {
				for (String value : copyFrom.getHeaderList(Contact)) {
					copyTo.setHeader(Contact, value);
				}
			}

			copyHeadersMsg(copyFrom, copyTo);
		}
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

		if (copyFrom != null && copyTo != null) {
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

		}
		return copyTo;
	}

	/**
	 * Copies both headers and content body from one SIP request to another.
	 *
	 * @param copyFrom the source SIP request
	 * @param copyTo   the destination SIP request
	 * @return the destination request with copied headers and content
	 * @throws ServletParseException        if parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletRequest copyContentAndHeaders(SipServletMessage copyFrom, SipServletRequest copyTo)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		if (copyFrom != null && copyTo != null) {
			copyHeadersMsg(copyFrom, copyTo);
			copyContent(copyFrom, copyTo);
		}
		return copyTo;
	}

	/**
	 * Copies both headers and content body from one SIP response to another.
	 *
	 * @param copyFrom the source SIP response
	 * @param copyTo   the destination SIP response
	 * @return the destination response with copied headers and content
	 * @throws ServletParseException        if parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletResponse copyContentAndHeaders(SipServletResponse copyFrom, SipServletResponse copyTo)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		if (copyFrom != null && copyTo != null) {
			copyHeadersMsg(copyFrom, copyTo);
			copyContent(copyFrom, copyTo);
		}
		return copyTo;
	}

	public static void linkSession(SipServletMessage inbound, SipServletMessage outbound) {

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(inbound, Color.YELLOW_BOLD_BRIGHT("Callflow.linkSession - linkSession("
					+ getVorpalDialogId(inbound) + ", " + getVorpalDialogId(outbound) + ")"));
		}
		outbound.getSession().setAttribute(LINKED_SESSION, inbound.getSession().getId());

	}

	public static void linkSession(SipSession inbound, SipSession outbound) {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(inbound, Color.YELLOW_BOLD_BRIGHT("Callflow.linkSession - linkSession("
					+ getVorpalDialogId(inbound) + ", " + getVorpalDialogId(outbound) + ")"));
		}
		outbound.setAttribute(LINKED_SESSION, inbound.getId());
	}

	/**
	 * Unlinks two previously linked SIP sessions by removing the LINKED_SESSION
	 * attribute from each session. Only removes attributes from sessions that are
	 * still valid.
	 *
	 * @param ss1 the first SIP session to unlink
	 * @param ss2 the second SIP session to unlink
	 */
	public static void unlinkSessions(SipSession ss1, SipSession ss2) {
		if (ss1 != null && ss1.isValid()) {
			ss1.removeAttribute(LINKED_SESSION);
		}
		if (ss2 != null && ss2.isValid()) {
			ss2.removeAttribute(LINKED_SESSION);
		}
	}

	public static void unlinkSession(SipServletMessage msg) {
		if (msg != null && msg.getSession().isValid()) {
			msg.getSession().removeAttribute(LINKED_SESSION);
		}
	}

	/**
	 * Retrieves the linked SIP session for the given session in a B2BUA scenario.
	 *
	 * @param ss the SIP session to get the linked session for
	 * @return the linked SIP session, or null if the session is invalid or not
	 *         linked
	 */
	public static SipSession getLinkedSession(SipSession ss) {

		SipSession linkedSession = null;
		if (ss != null && ss.isValid()) {
			String strLinkedSession;

			strLinkedSession = (String) ss.getAttribute(LINKED_SESSION);
			if (strLinkedSession != null) {
				linkedSession = ss.getApplicationSession().getSipSession(strLinkedSession);
			}

		}

		return linkedSession;
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

	/**
	 * Schedules a request to be processed after a specified delay. The request is
	 * stored in the application session and a timer is created to trigger
	 * processing after the delay.
	 *
	 * @param request               the SIP request to process later
	 * @param delay_in_milliseconds the delay in milliseconds before processing
	 */
	public void processLater(SipServletRequest request, long delay_in_milliseconds) {
		if (request.getApplicationSession().isValid()) {
			request.getApplicationSession().setAttribute(DELAYED_REQUEST, request);
			timerService.createTimer(request.getApplicationSession(), delay_in_milliseconds, false, this);
		}
	}

	/**
	 * Creates a response for the upstream request by copying status, headers, and
	 * content from a downstream response. Links the sessions on successful
	 * responses.
	 *
	 * @param aliceRequest the upstream request to create a response for
	 * @param bobResponse  the downstream response to copy from
	 * @return the created response, or null if either parameter is null or session
	 *         is invalid
	 * @throws ServletParseException        if header parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletResponse createResponse(SipServletRequest aliceRequest, SipServletResponse bobResponse)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletResponse aliceResponse = null;

		if (aliceRequest != null && bobResponse != null) {

			SipSession aliceSession = aliceRequest.getSession();

			if (aliceSession != null && aliceSession.isValid() && aliceSession.getState() != State.TERMINATED) {
				aliceResponse = aliceRequest.createResponse(bobResponse.getStatus(), bobResponse.getReasonPhrase());

				if (aliceResponse != null) {
					copyContentAndHeaders(bobResponse, aliceResponse);
				}

			}
		}
		return aliceResponse;
	}

	/**
	 * Creates an ACK or PRACK request for a downstream response by copying content
	 * and headers from the upstream acknowledgement request.
	 *
	 * @param bobResponse     the downstream response to acknowledge
	 * @param aliceAckOrPrack the upstream ACK or PRACK request to copy from
	 * @return the created ACK or PRACK request
	 * @throws ServletParseException if the method is neither ACK nor PRACK, or if
	 *                               parsing fails
	 */
	public static SipServletRequest createAcknowlegement(SipServletResponse bobResponse,
			SipServletRequest aliceAckOrPrack) throws ServletParseException {
		SipServletRequest bobAckOrPrack = null;

		try {
			if (aliceAckOrPrack.getMethod().equals(PRACK)) {
				bobAckOrPrack = copyContentAndHeaders(aliceAckOrPrack, bobResponse.createPrack());
			} else if (aliceAckOrPrack.getMethod().equals(ACK)) {
				bobAckOrPrack = copyContentAndHeaders(aliceAckOrPrack, bobResponse.createAck());
			}
		} catch (Exception ex) {
			sipLogger.warning(bobResponse, "#5.99 Callflow.createAcknowlegement - Exception "
					+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
			throw new ServletParseException(ex);
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

	/**
	 * Creates a new request with the CONTINUE routing directive using a template
	 * request for content and headers, while using the original request for routing
	 * directive linkage.
	 *
	 * @param endpoint     the destination URI for the request
	 * @param template     the template request to copy content and headers from
	 * @param aliceRequest the original request for routing directive linkage
	 * @return the new SIP request with CONTINUE routing directive
	 * @throws ServletParseException        if parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
	public static SipServletRequest createContinueInitialRequest(URI endpoint, SipServletRequest template,
			SipServletRequest aliceRequest) throws ServletParseException, UnsupportedEncodingException, IOException {

		SipServletRequest bobRequest;

		bobRequest = sipFactory.createRequest( //
				template.getApplicationSession(), //
				template.getMethod(), //
				template.getFrom(), //
				template.getTo());

		copyContentAndHeaders(template, bobRequest);

		if (endpoint != null) {
			bobRequest.setRequestURI(copyParameters(template.getRequestURI(), endpoint));
		}

		bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
		return bobRequest;
	}

	/**
	 * Creates a copy of a SIP request, duplicating the method, From, To, content,
	 * headers, and request URI.
	 *
	 * @param aliceRequest the request to copy
	 * @return the new SIP request
	 * @throws ServletParseException        if parsing fails
	 * @throws UnsupportedEncodingException if the content encoding is not supported
	 * @throws IOException                  if an I/O error occurs
	 */
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

		if (from != null && to != null) {

			SipURI sipFrom = (SipURI) from;
			SipURI sipTo = (SipURI) to;

			// copy user (if it doesn't exist)
			if (sipFrom.getUser() != null && sipTo.getUser() == null) {
				sipTo.setUser(sipFrom.getUser());
			}

			// copy URI parameters (if they don't exist)
			String value;
			for (String name : from.getParameterNameSet()) {
				value = from.getParameter(name);
				if (value != null && to.getParameter(name) != null && !"tag".equals(name)) {
					to.setParameter(name, value);
				}
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

		if (from != null && to != null) {

			copyParameters(from.getURI(), to.getURI());

			String value;
			for (String name : from.getParameterNameSet()) {
				value = from.getParameter(name);
				if (value != null && to.getParameter(name) != null && !"tag".equals(name)) {
					to.setParameter(name, value);
				}
			}
		}

		return to;
	}

	/**
	 * Proxies a SIP request to multiple endpoints with a callback for the response.
	 *
	 * @param proxy          the Proxy object from the original request
	 * @param endpoints      the list of URIs to proxy the request to
	 * @param lambdaFunction callback invoked when a response is received
	 * @throws TooManyHopsException if the Max-Forwards header has reached zero
	 */
	public void proxyRequest(Proxy proxy, List<URI> endpoints, Callback<SipServletResponse> lambdaFunction)
			throws TooManyHopsException {
		SipApplicationSession appSession = proxy.getOriginalRequest().getApplicationSession();
		appSession.setAttribute(PROXY_CALLBACK_ + INVITE, lambdaFunction);
		appSession.setAttribute(IS_PROXY_ATTR, Boolean.TRUE);

		for (URI endpoint : endpoints) {
			// jwm - SUPERARROW NEEDS WORK!
			sipLogger.superArrow(Direction.SEND, false, proxy.getOriginalRequest(), null,
					this.getClass().getSimpleName(), null);
		}

		proxy.proxyTo(endpoints);
	}

	/**
	 * Proxies a SIP request to multiple endpoints without a response callback.
	 *
	 * @param proxy     the Proxy object from the original request
	 * @param endpoints the list of URIs to proxy the request to
	 * @throws TooManyHopsException if the Max-Forwards header has reached zero
	 */
	public void proxyRequest(Proxy proxy, List<URI> endpoints) throws TooManyHopsException {
		proxyRequest(proxy, endpoints, (response) -> {
			// do nothing;
		});
	}

	/**
	 * Proxies a SIP request to a single endpoint without a response callback.
	 *
	 * @param proxy    the Proxy object from the original request
	 * @param endpoint the URI to proxy the request to
	 * @throws TooManyHopsException if the Max-Forwards header has reached zero
	 */
	public void proxyRequest(Proxy proxy, URI endpoint) throws TooManyHopsException {
		List<URI> endpoints = new LinkedList<>();
		endpoints.add(endpoint);
		proxyRequest(proxy, endpoints, (response) -> {
			// do nothing;
		});
	}

	/**
	 * Proxies a SIP request using a ProxyPlan that defines tiered routing with
	 * parallel or sequential mode for each tier.
	 *
	 * @param inboundRequest the inbound SIP request to proxy
	 * @param proxyPlan      the routing plan containing tiers and endpoints
	 * @param lambdaFunction callback invoked when the final response is received
	 * @throws IOException      if an I/O error occurs
	 * @throws ServletException if a servlet error occurs
	 */
	public void proxyRequest(SipServletRequest inboundRequest, ProxyPlan proxyPlan,
			Callback<SipServletResponse> lambdaFunction) throws IOException, ServletException {

		SipApplicationSession appSession = inboundRequest.getApplicationSession();
		boolean isProxy = Boolean.TRUE.equals(appSession.getAttribute(IS_PROXY_ATTR));

		if (!proxyPlan.isEmpty()) {

			Proxy proxy = inboundRequest.getProxy();

			ProxyTier proxyTier = proxyPlan.getTiers().remove(0);

			proxy.setParallel(proxyTier.getMode().equals(Mode.parallel));

			List<URI> endpoints = new LinkedList<>();
			for (URI endpoint : proxyTier.getEndpoints()) {
				endpoints.add(endpoint);
			}
			List<ProxyBranch> proxyBranches = proxy.createProxyBranches(endpoints);

			Integer timeout = proxyTier.getTimeout();
			if (timeout != null && timeout > 0) {
				proxy.setProxyTimeout(timeout);
			}

			inboundRequest.getSession().setAttribute(RESPONSE_CALLBACK_ + inboundRequest.getMethod(), lambdaFunction);

			inboundRequest.getApplicationSession().setAttribute(IS_PROXY_ATTR, Boolean.TRUE);

			for (ProxyBranch proxyBranch : proxyBranches) {
				sipLogger.superArrow(Direction.SEND, false, proxyBranch.getRequest(), null,
						this.getClass().getSimpleName(), null);
			}

			proxy.startProxy();

		} else {
			sipLogger.finer(inboundRequest, "#8.99 Callflow.proxyRequest - proxyPlan is empty");
		}

	}

	/**
	 * Returns the SIP logger used for logging SIP messages and callflow events.
	 * Alias for {@link #getLogger()}.
	 *
	 * @return the SIP logger instance
	 */
	public static Logger getSipLogger() {
		return sipLogger;
	}

	/**
	 * Sets the SIP logger used for logging SIP messages and callflow events. Alias
	 * for {@link #setLogger(Logger)}.
	 *
	 * @param sipLogger the SIP logger instance to set
	 */
	public static void setSipLogger(Logger sipLogger) {
		Callflow.sipLogger = sipLogger;
	}

//	/**
//	 * Returns the session parameters configuration used for session attribute
//	 * extraction and session expiration settings.
//	 *
//	 * @return the current session parameters, or null if not configured
//	 */
//	public static SessionParameters getSessionParameters() {
//		return SettingsManager.getSessionParams();
//	}
//
//	/**
//	 * Sets the session parameters configuration used for session attribute
//	 * extraction and session expiration settings.
//	 *
//	 * @param sessionParameters the session parameters to set
//	 */
//	public static void setSessionParameters(SessionParameters sessionParameters) {
//		Callflow.sessionParameters = sessionParameters;
//	}

	/**
	 * Given a SipApplicationSession, this method returns a SipSession with the
	 * matching attribute value pair.
	 * 
	 * @param appSession
	 * @param name
	 * @param value
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static SipSession getSession(SipApplicationSession appSession, String name, String value) {
		SipSession sipSession = null;

		Object attribute;
		for (SipSession ss : (Set<SipSession>) appSession.getSessionSet("SIP")) {
			attribute = ss.getAttribute(name);
			if (attribute != null && attribute instanceof String) {
				if (value.equalsIgnoreCase((String) attribute)) {
					sipSession = ss;
					break;
				}
			}
		}

		return sipSession;
	}

	/**
	 * Returns the original incoming request which initiated the callStarted method.
	 * Useful when used in conjunction with 'doNotProcess'.
	 * 
	 * @param outboundRequest
	 * @return incoming request
	 */
	public static SipServletRequest getIncomingRequest(SipServletRequest outboundRequest) {
		if (outboundRequest == null) {
			return null;
		}
		SipSession linkedSession = Callflow.getLinkedSession(outboundRequest.getSession());
		if (linkedSession == null) {
			return null;
		}
		return linkedSession.getActiveInvite(UAMode.UAS);
	}

	/**
	 * Returns the Vorpal timestamp for the given application session. The timestamp
	 * is a hexadecimal representation of the session creation time, used in
	 * combination with the Vorpal session ID for unique identification.
	 *
	 * @param appSession the SIP application session
	 * @return the Vorpal timestamp, or null if session is null or invalid
	 */
	public static String getVorpalTimestamp(SipApplicationSession appSession) {
		String timestamp = null;

		if (appSession != null && appSession.isValid()) {
			timestamp = (String) appSession.getAttribute(X_VORPAL_TIMESTAMP);
			if (timestamp == null) {
				timestamp = Long.toHexString(System.currentTimeMillis()).toUpperCase();
			}
		}

		return timestamp;
	}

}
