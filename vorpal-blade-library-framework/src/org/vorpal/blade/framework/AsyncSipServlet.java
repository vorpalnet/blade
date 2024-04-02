package org.vorpal.blade.framework;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SessionParameters;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.framework.logging.Logger.Direction;

/**
 * This abstract SipServlet is designed to implement the features of the BLADE
 * asynchronous APIs (lambda expressions). Extend and implement this class to
 * create your own specialized SipServlet class. See B2buaServlet as an example.
 * 
 * @author Jeff McDonald
 *
 */
public abstract class AsyncSipServlet extends SipServlet
		implements SipServletListener, ServletContextListener, TimerListener {

	private static final long serialVersionUID = 1L;
	private SipServletContextEvent event;
	protected static Logger sipLogger;
	protected static SipFactory sipFactory;
	protected static SipSessionsUtil sipUtil;
	protected static TimerService timerService;
	protected static SessionParameters sessionParameters;

	/**
	 * Called when the SipServlet has been created.
	 * 
	 * @param event
	 * @throws ServletException
	 * @throws IOException
	 */
	protected abstract void servletCreated(SipServletContextEvent event) throws ServletException, IOException;

	/**
	 * Called when the SipServlet has been destroyed.
	 * 
	 * @param event
	 * @throws ServletException
	 * @throws IOException
	 */
	protected abstract void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException;

	/**
	 * Implement this method to choose various Callflow objects for incoming
	 * requests that do not already have a callflow defined.
	 * 
	 * @param request
	 * @return Callflow the chosen callflow object
	 * @throws ServletException
	 * @throws IOException
	 */
	protected abstract Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException;

	@Override
	final public void servletInitialized(SipServletContextEvent event) {
		this.event = event;
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		timerService = (TimerService) event.getServletContext().getAttribute("javax.servlet.sip.TimerService");

		Callflow.setLogger(sipLogger);
		Callflow.setSipFactory(sipFactory);
		Callflow.setSipUtil(sipUtil);
		Callflow.setTimerService(timerService);

		try {
			servletCreated(event);
			sipLogger = LogManager.getLogger(event);
		} catch (Exception e) {

			if (null != sipLogger) {
				sipLogger.logStackTrace(e);
			} else {
				e.printStackTrace();
			}

		}

	}

	/**
	 * Override this method to handle call events that may fall outside the scope of
	 * your defined callflows.
	 * 
	 * @param request a modifiable message object to be sent
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void requestEvent(SipServletRequest request) throws ServletException, IOException {
		// override this method
	}

	/**
	 * Override this method to handle call events that may fall outside the scope of
	 * your defined callflows.
	 * 
	 * @param response a modifiable message object to be sent
	 * @throws ServletException an exception
	 * @throws IOException      an exception
	 */
	public void responseEvent(SipServletResponse response) throws ServletException, IOException {
		// override this method
	}

	@Override
	final public void contextInitialized(ServletContextEvent sce) {
		// do nothing;
	}

	@Override
	final public void contextDestroyed(ServletContextEvent sce) {
		try {
			servletDestroyed(event);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	/**
	 * This function creates a header called X-Vorpal-Session, which contains a
	 * SipApplicationSession index key if one does not already exist. This session
	 * key can be used to in the future via
	 * SipApplicationSession.getApplicationSessionByKey() for creating web service
	 * requests or other types of functions.
	 * 
	 * @param request
	 */
	public static void generateIndexKey(SipServletRequest request) {
		String session, indexKey, dialog;
		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();

		indexKey = (String) appSession.getAttribute("X-Vorpal-Session");

		if (null == indexKey) {
			session = request.getHeader("X-Vorpal-Session");
			if (null != session) {
				int colonIndex = session.indexOf(':');
				indexKey = session.substring(0, colonIndex);
				dialog = session.substring(colonIndex + 1);
			} else {
				indexKey = Callflow.getVorpalSessionId(appSession);
				dialog = Callflow.getVorpalDialogId(sipSession);
			}

			appSession.setAttribute("X-Vorpal-Session", indexKey);
			sipSession.setAttribute("X-Vorpal-Dialog", dialog);
			appSession.addIndexKey(indexKey);
		}
	}

	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow;
		Callback<SipServletRequest> requestLambda;

		try {

			// creates a session tracking key, if one doesn't already exist
			generateIndexKey(request);

			if (request.isInitial() && Callflow.getSessionParameters() != null) {
				if (Callflow.getSessionParameters().getExpiration() != null) {
					request.getApplicationSession().setExpires(Callflow.getSessionParameters().getExpiration());
				}

				// put the keep alive logic here

			}

			if (request.getMethod().equals("REFER")) {
				sipLogger.finer(request, "REFER Setting ShouldInvalidate to true...");
				request.getSession().setAttribute("ShouldInvalidate", true);
			}

			requestLambda = Callflow.pullCallback(request);
			if (requestLambda != null) {
				String name = requestLambda.getClass().getSimpleName();
				Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, name);
				requestLambda.accept(request);
			} else {
				callflow = chooseCallflow(request);

				if (callflow == null) {
					if (request.getMethod().equals("ACK")) {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
					} else {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
						SipServletResponse response = request.createResponse(501);
						response.send();
						Callflow.getLogger().superArrow(Direction.SEND, null, response, "null");
						Callflow.getLogger().warning("No registered callflow for request method " + request.getMethod()
								+ ", consider modifying the 'chooseCallflow' method.");
					}
				} else {
					callflow.processWrapper(request);
				}
			}
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(request, e);
			throw e;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		Callback<SipServletResponse> callback;
		SipSession sipSession = response.getSession();
		try {
			if (sipSession != null && sipSession.isValid()) {
				callback = Callflow.pullCallback(response);
				if (callback != null) {
					Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
							callback.getClass().getSimpleName());
					callback.accept(response);
				} else {

					// Is this a response to 'proxy'?

//					Proxy proxy = response.getProxy();
//					sipLogger.warning(response, "proxy...? " + (proxy != null));
//					if (proxy != null) {
//						sipLogger.warning(response, "getProxy? " + (response.getProxy() != null));
//						sipLogger.warning(response, "supervised? " + response.getProxy().getSupervised());
//						sipLogger.warning(response, "stateful? " + response.getProxy().getStateful());
//						sipLogger.warning(response, "supervised? " + response.getProxy().getSupervised());
//					}

					// Sometimes a 180 Ringing comes back on a brand new SipSession
					// because the tag on the To header changed due to a failure downstream.
					if (response.getMethod().equals("INVITE")) {
						sipLogger.finer(response, "No callback for response to INVITE... Curious!");
						SipServletRequest rqst;
						SipApplicationSession appSession = response.getApplicationSession();
						Set<SipSession> sessions = (Set<SipSession>) appSession.getSessionSet("SIP");
						Callback<SipServletResponse> cb2 = null;
						for (SipSession session : sessions) {
							if (session != response.getSession()) {
								rqst = session.getActiveInvite(UAMode.UAC);
								if (rqst.isCommitted() == false) {
									sipLogger.finer(response, "Found an active INVITE... Does it match?");

									String requestToUser = ((SipURI) rqst.getTo().getURI()).getUser();
									String responseToUser = ((SipURI) response.getTo().getURI()).getUser();
									if (requestToUser.equals(responseToUser)) {
										sipLogger.finer(response, "The To users match.");

										String requestFromUser = ((SipURI) rqst.getFrom().getURI()).getUser();
										String responseFromUser = ((SipURI) response.getFrom().getURI()).getUser();
										if (requestFromUser.equals(responseFromUser)) {
											sipLogger.finer(response, "The From users match.");

											String attribute = "RESPONSE_CALLBACK_INVITE";
											cb2 = (Callback<SipServletResponse>) sipSession.getAttribute(attribute);

											if (cb2 != null) {
												// Caught a bat!
												sipLogger.finer(response, "A callback is defined... Let's use it!");
											}

											break;
										}

									}

								}

							} else {
								// Dinah, my dear, I wish you were down here with me! There are no mice in the
								// air, I'm afraid, but you might catch a bat, and that's very like a mouse, you
								// know. But do cats eat bats, I wonder?”
								sipLogger.finer(response, "Invalidating rogue SipSession");
								session.invalidate();
							}
						}

						if (cb2 != null) {
							Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
									cb2.getClass().getSimpleName());
							cb2.accept(response);
						} else {
							sipLogger.warning(response, "No callback for INVITE found, curiouser and curiouser! ");
							Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, "????");
						}

					} else {
						// This is to be expected
						Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, "null");
					}
				}
			}
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(response, e);
			throw e;
		} finally {
			if (response.getMethod().equals("BYE") && sipSession != null && sipSession.isValid()) {
				// sometimes the sipSession does not automatically invalidate, no idea why.
				sipSession.invalidate();
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	final public void timeout(ServletTimer timer) {
		try {
			Callback<ServletTimer> callback;
			callback = (Callback<ServletTimer>) timer.getInfo();

			if (callback != null) {
				// callback.accept(timer);
				callback.acceptThrows(timer);
			}

		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
		}
	}

	/**
	 * @return the sipLogger
	 */
	final public static Logger getSipLogger() {
		return sipLogger;
	}

	final public static void setSipLogger(Logger _sipLogger) {
		sipLogger = _sipLogger;
	}

	/**
	 * @return the sipFactory
	 */
	final public static SipFactory getSipFactory() {
		return sipFactory;
	}

	final public static void setSipFactory(SipFactory _sipFactory) {
		sipFactory = _sipFactory;
	}

	/**
	 * @return the sipUtil
	 */
	final public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	final public static void setSipUtil(SipSessionsUtil _sipUtil) {
		sipUtil = _sipUtil;
	}

	/**
	 * @return the timerService
	 */
	final public static TimerService getTimerService() {
		return timerService;
	}

//	/**
//	 * This is an alternate hashing algorithm that can be used during
//	 * the @SipApplicationKey method.
//	 * 
//	 * @param string
//	 * @return a long number written as a hexadecimal string
//	 */
//	public static String hash(String string) {
//		long h = 1125899906842597L; // prime
//		int len = string.length();
//
//		for (int i = 0; i < len; i++) {
//			h = 31 * h + string.charAt(i);
//		}
//
//		return Long.toHexString(h);
//	}

	private static String byteArray2Text(byte[] bytes) {
		final char[] alphanum = { // 62 characters for randomish pattern distribution
				'0', '1', '2', '3', '4', '5', '6', '7', //
				'8', '9', 'a', 'b', 'c', 'd', 'e', 'f', //
				'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', //
				'o', 'p', 'q', 'r', 's', 't', 'u', 'v', //
				'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', //
				'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', //
				'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', //
				'U', 'V', 'W', 'X', 'Y', 'Z' };
		StringBuffer sb = new StringBuffer();
		for (final byte b : bytes) {
			sb.append(alphanum[Byte.toUnsignedInt(b) % alphanum.length]);
		}
		return sb.toString();
	}

	private static String hexEncode(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(Integer.toHexString(Byte.toUnsignedInt(b)));
		}
		return sb.toString();
	}

	public static String hash(String stringToHash) {
		String stringHash = null;

		MessageDigest messageDigest;
		try {
//			messageDigest = MessageDigest.getInstance("SHA-256");
			messageDigest = MessageDigest.getInstance("MD5");
			messageDigest.update(stringToHash.getBytes());
			stringHash = byteArray2Text(messageDigest.digest());
//			stringHash = hexEncode(messageDigest.digest());

		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return stringHash;
	}

	public static String getAppSessionHashKey(String stringToHash) {
		SipApplicationSession appSession = null;
		String hashedString = hash(stringToHash);
		appSession = sipUtil.getApplicationSessionByKey(hashedString, true);

		String existingHashKey = (String) appSession.getAttribute("HASHKEY");

		if (null == existingHashKey) {
			// this is good because the AppSession did not previously exist
			appSession.setAttribute("HASHKEY", hashedString);
		} else {
			if (false == existingHashKey.equals(hashedString)) {
				// this is bad because the hash keys collide;
				sipLogger.severe("@SipApplicationKey hash key collision. SipApplicationSession.id: "
						+ appSession.getId() + " collides with " + existingHashKey + " and " + hashedString);
				appSession.setAttribute("HASHKEY_COLLISION", true);
			}
		}

		return hashedString;
	}

	public static String getAccountName(Address address) {
		return getAccountName(address.getURI());
	}

	public static String getAccountName(URI _uri) {
		SipURI sipUri = (SipURI) _uri;
		return sipUri.getUser().toLowerCase() + "@" + sipUri.getHost().toLowerCase();
	}

	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		Callflow.getLogger().superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
		try {
			response.send();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
			throw e;
		}
	}

	public static SessionParameters getSessionParameters() {
		return sessionParameters;
	}

	public static void setSessionParameters(SessionParameters sessionParameters) {
		AsyncSipServlet.sessionParameters = sessionParameters;
	}

}
