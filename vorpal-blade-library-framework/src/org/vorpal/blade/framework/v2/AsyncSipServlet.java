package org.vorpal.blade.framework.v2;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
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
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.Callflow481;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;

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
	private static final String RESPONSE_CALLBACK_INVITE = "RESPONSE_CALLBACK_INVITE";

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

			Package pkg = AsyncSipServlet.class.getPackage();
			String title = pkg.getSpecificationTitle();
			String version = pkg.getImplementationVersion();
			String application = event.getServletContext().getServletContextName();

			if (title != null) {
				sipLogger.info(application + " compiled using " + title + " version " + version);
			}

		} catch (Exception ex) {
			sipLogger.severe(ex);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex.getMessage());
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
		} catch (Exception ex) {
			sipLogger.severe(ex);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex.getMessage());
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
	public static String generateIndexKey(SipServletRequest request) {
		String session, indexKey, dialog;
		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();

		session = request.getHeader("X-Vorpal-Session");

		if (null != session) {
			int colonIndex = session.indexOf(':');
			indexKey = session.substring(0, colonIndex);
			appSession.setAttribute("X-Vorpal-Session", indexKey);
			dialog = session.substring(colonIndex + 1);
			sipSession.setAttribute("X-Vorpal-Dialog", dialog);
		} else {

			indexKey = (String) appSession.getAttribute("X-Vorpal-Session");
			if (indexKey == null) {
				indexKey = Callflow.createVorpalSessionId(appSession);
			}

			dialog = (String) sipSession.getAttribute("X-Vorpal-Dialog");
			if (dialog == null) {
				dialog = Callflow.createVorpalDialogId(sipSession);
			}
		}

		appSession.addIndexKey(indexKey);
		sipLogger.finer(request, "AsyncSipServlet - generateIndexKey, indexKeys=" + appSession.getIndexKeys());
		return indexKey;

	}

	@SuppressWarnings("unchecked")
	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;
		Callback<SipServletRequest> requestLambda;
		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();
		String method = request.getMethod();
		AttributesKey rr = null;

		// Glare handling; when expecting an ACK and you don't get one,
		// jam the message in a queue and process it later
		// Note: the queuing mechanism is buggy, so we're returning 491 until a better
		// algorithm is devised
//		Queue<SipServletRequest> glareQueue = (Queue<SipServletRequest>) sipSession.getAttribute("GLARE_QUEUE");
//		glareQueue = (null != glareQueue) ? glareQueue : new LinkedList<>();

		Boolean expectAck = (Boolean) sipSession.getAttribute("EXPECT_ACK");
		expectAck = (expectAck != null && expectAck == true) ? true : false;

		if (method.equals("ACK")) {
			expectAck = false;
			sipSession.removeAttribute("EXPECT_ACK");
		} else {
			if (expectAck && !method.equals("CANCEL")) { // anything other than cancel
				sipLogger.fine(request,
						"Glare; " + method + " received while awaiting ACK. Queuing request for later processing...");

				SipServletResponse glareResponse = request.createResponse(491);
				glareResponse.setHeader("Retry-After", "3"); // 3 seconds should enough time to clear the line
				sendResponse(glareResponse);

//				glareQueue.add(request);
//				sipSession.setAttribute("GLARE_QUEUE", glareQueue);
				return;
			}
		}

		// Now that GLARE is taken care of, if this is an INVITE, we should expect an
		// ACK, unless we reply with an error code -- See Callflow.sendResponse
		if (method.equals("INVITE")) {
			sipSession.setAttribute("EXPECT_ACK", Boolean.TRUE);
		}

		try {

			if (request.isInitial()) {
				// creates a session tracking key, if one doesn't already exist
				generateIndexKey(request);

				// attempt to keep track of who called whom
				request.getSession().setAttribute("userAgent", "caller");
			}

			if (request.isInitial() && Callflow.getSessionParameters() != null) {
				SessionParameters sessionParameters = Callflow.getSessionParameters();

				if (sessionParameters.getExpiration() != null) {
					request.getApplicationSession().setExpires(Callflow.getSessionParameters().getExpiration());
				}

				// put the keep alive logic here

			}

			requestLambda = Callflow.pullCallback(request);
			if (requestLambda != null) {

				Callflow.getLogger().superArrow(Direction.RECEIVE, request, null,
						requestLambda.getClass().getSimpleName());

				requestLambda.accept(request);

				// For printing arrow for proxy messages
//				if (isProxy(request) == true) {
////					Callflow.getLogger().superArrow(Direction.SEND, false, request.getProxy().getOriginalRequest(),
////							null, requestLambda.getClass().getSimpleName(), null);
//
//					Callflow.getLogger().superArrow(Direction.SEND, false, request.getProxy().getOriginalRequest(),
//							null, Callflow.superclass(requestLambda.getClass()), null);
//				}

			} else {

				// Send 481 for ReINVITEs with failed linked sessions
				if (method.equals("INVITE") && false == request.isInitial()) {
					SipSession linkedSession = Callflow.getLinkedSession(request.getSession());
					if (linkedSession == null || false == linkedSession.isValid()) {
						callflow = new Callflow481();
					}
				}

				if (callflow == null) {
					callflow = chooseCallflow(request);
				}

				if (callflow == null) {

					if (method.equals("ACK")) {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
					} else {

//						if (isProxy(request)) {
////							SipServletRequest originalProxyRequest = request.getProxy().getOriginalRequest();
//							Callflow.getLogger().superArrow(Direction.RECEIVE, false, request, null, "proxy", null);
//							Callflow.getLogger().superArrow(Direction.SEND, request, null, "proxy");
//
//						} else {
						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
						SipServletResponse response = request.createResponse(501);
						Callflow.getLogger().superArrow(Direction.SEND, null, response, "null");
						Callflow.getLogger().warning("No registered callflow for request method " + method
								+ ", consider modifying the 'chooseCallflow' method.");
						response.send();
//						}

					}
				} else {
					sipLogger.superArrow(Direction.RECEIVE, request, null, callflow.getClass().getSimpleName());

					// process AttributeSelectors here!

					// create any index keys defined by selectors in the config file
					if (request.isInitial() && sessionParameters != null) {

						// sipLogger.finest(request, "request.isInitial() && sessionParameters !=
						// null");

						List<AttributeSelector> selectors = sessionParameters.getSessionSelectors();
						// sipLogger.finest(request, "Session AttributeSelectors list=" + selectors);

						if (selectors != null) {
							// sipLogger.finest(request, "Session AttributeSelectors list size=" +
							// selectors.size());
							for (AttributeSelector selector : selectors) {

								if (selector.getDialog() == null
										|| false == selector.getDialog().equals(DialogType.destination)) {

									rr = selector.findKey(request);
									sipLogger.finest(request, "AsyncSipServlet.doRequest - selector.id="
											+ selector.getId() + ", rr=" + rr);
									if (rr != null) {

										// Create an index key for the appSession
										if (rr.key != null) {
											if (sipLogger.isLoggable(Level.FINER)) {
												sipLogger.finer(request,
														"AsyncSipServlet - doRequest, addingIndexKey: " + rr.key);
											}
											request.getApplicationSession().addIndexKey(rr.key);
										}

										// Add named groups to SipSession
										for (Entry<String, String> entry : rr.attributes.entrySet()) {
											sipLogger.finer(request,
													"AsyncSipServlet.doRequest - adding SipSession attribute "
															+ entry.getKey() + "=" + entry.getValue());
											sipSession.setAttribute(entry.getKey(), entry.getValue());
										}

										// jwm - testing; this should only be for SipSession
										for (Entry<String, String> entry : rr.attributes.entrySet()) {
											sipLogger.finer(request,
													"AsyncSipServlet.doRequest - adding SipApplicationSession attribute "
															+ entry.getKey() + "=" + entry.getValue());
											appSession.setAttribute(entry.getKey(), entry.getValue());
										}

									}

								}

							}

						}

					}

					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request, "AsyncSipServlet.doRequest - appSession id=" + appSession.getId() + //
								", indexKeys=" + appSession.getIndexKeys() + //
								", sessions=" + appSession.getSessionSet().size() + //
								", timers=" + appSession.getTimers().size());

						Map<String, String> attrMap = new HashMap<>();
						Object value;
						for (String name : sipSession.getAttributeNameSet()) {
							value = sipSession.getAttribute(name);
							if (value instanceof String) {
								attrMap.put(name, (String) value);
							} else if (value instanceof Boolean) {
								attrMap.put(name, Boolean.toString((Boolean) value));
							} else if (value instanceof Integer) {
								attrMap.put(name, Integer.toString((Integer) value));
							} else {
								// too wordy
								// attrMap.put(name, value.getClass().getSimpleName());
							}
						}
						sipLogger.finer(sipSession, "AsyncSipServlet.doRequest - callflow="
								+ callflow.getClass().getSimpleName() + ", SipSession attributes: " + attrMap);
					}

					callflow.process(request);

					SipSession linkedSession = Callflow.getLinkedSession(request.getSession());
					if (request.isInitial() && linkedSession != null && sessionParameters != null) {

						List<AttributeSelector> selectors = sessionParameters.getSessionSelectors();

						if (selectors != null) {
							for (AttributeSelector selector : selectors) {

								if (selector.getDialog() != null
										&& selector.getDialog().equals(DialogType.destination)) {

									rr = selector.findKey(request);
									if (rr != null) {
										// add any origin dialog session parameters from config file
										for (Entry<String, String> entry : rr.attributes.entrySet()) {
											linkedSession.setAttribute(entry.getKey(), entry.getValue());
										}
									}

								}

							}

						}

						if (sipLogger.isLoggable(Level.FINER)) {
							Map<String, String> attrMap = new HashMap<>();

							Object value;
							for (String name : linkedSession.getAttributeNameSet()) {
								value = linkedSession.getAttribute(name);
								if (value instanceof String) {
									attrMap.put(name, (String) value);
								}
							}
							sipLogger.finer(sipSession,
									"AsyncSipServlet.doRequest - Destination session attributes: " + attrMap);
						}

					}

				}
			}

		} catch (Exception ex) {
			String error = event.getServletContext().getServletContextName() + " " + ex.getClass().getSimpleName()
					+ ", " + ex.getMessage();
			sipLogger.severe(request, "Exception on SIP request: \n" + request.toString());
			sipLogger.severe(request, ex);
			sipLogger.getParent().severe(error);
		}

		// process requests queued up from glare
		// Note: this buggy because two or more requests in the queue would result in
		// even more glare; a better algorithm needs to be devised
//		if (expectAck == false) {
//			if (glareQueue != null) {
//				SipServletRequest glareRequest;
//				while (glareQueue.size() > 0) {
//					glareRequest = glareQueue.remove();
//					sipLogger.warning(glareRequest, "Processing queued " + glareRequest.getMethod() + " due to glare.");
//					doRequest(glareRequest);
//				}
//			}
//			sipSession.removeAttribute("GLARE_QUEUE");
//		}

	}

	@SuppressWarnings("unchecked")
	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		boolean isProxy = false;
		Callback<SipServletResponse> callback;
		SipSession sipSession = response.getSession();
		SipApplicationSession appSession = response.getApplicationSession();
		String method = response.getMethod();

		try {
			if (sipSession != null) {

				if (sipSession.isValid()) {

					// for GLARE handling
					if (method.equals("INVITE")) {
						if (Callflow.failure(response)) {
							sipSession.removeAttribute("EXPECT_ACK");
						}
					}

					callback = Callflow.pullCallback(response);

					if (callback == null) {
						callback = Callflow.pullProxyCallback(response);

						if (callback != null) {
							isProxy = true;
						} else {
							// Sometimes a 180 Ringing comes back on a brand new SipSession
							// because the tag on the To header changed due to a failure downstream.
							if (response.getMethod().equals("INVITE")) {
								// Falling down a hole
								Set<SipSession> sessions = (Set<SipSession>) appSession.getSessionSet("SIP");

								sipLogger.finer(response, "Rogue session id=" + response.getSession().getId());
								sipLogger.finer(response, "Number of SipSessions: " + sessions.size());

								for (SipSession session : sessions) {

									sipLogger.finer(response, "Checking session id=" + session.getId());

									if (session != response.getSession()) {

										if (session.getCallId().equals(sipSession.getCallId())) {

											callback = (Callback<SipServletResponse>) session
													.getAttribute(RESPONSE_CALLBACK_INVITE);

											if (callback != null) {
												sipLogger.finer(session,
														"Early dialog session detected, merge in progress...");

												sipLogger.finer(response,
														"Setting RESPONSE_CALLBACK_INVITE on merged session...");
												sipSession.setAttribute(RESPONSE_CALLBACK_INVITE, callback);

												sipLogger.finer(response,
														"Removing RESPONSE_CALLBACK_INVITE from original session...");
												session.removeAttribute(RESPONSE_CALLBACK_INVITE);

												// link the sessions
												sipLogger.finer(session, "Linking sessions...");
												SipSession linkedSession = Callflow.getLinkedSession(session);
												session.removeAttribute("LINKED_SESSION");
												Callflow.linkSessions(linkedSession, sipSession);

												for (String attr : session.getAttributeNameSet()) {
													sipLogger.finer(session, "Copying session attribute: " + attr);
													sipSession.setAttribute(attr, session.getAttribute(attr));
												}

												// invalidate the old session
												sipLogger.finer(session, "Invalidating early session...");
												session.invalidate();
												sipLogger.finer(response, "Sessions successfully merged.");
											}
											break;
										}
									}
								}
							} else {
								Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
										this.getClass().getSimpleName());
							}
						}
					}

					if (callback != null) {
						Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
								callback.getClass().getSimpleName());

						if (isProxy) {
							if (!Callflow.provisional(response)) {
								callback.accept(response);
							}
						} else {
							callback.accept(response);
						}

					} else {
						Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
								this.getClass().getSimpleName());
					}

				} else {
					sipLogger.warning(response, "SipSession " + sipSession.getId() + "is invalid.");
				}

			} else {
				sipLogger.warning(response, "SipSession is null.");
			}
		} catch (Exception ex) {
			sipLogger.severe(response, "Exception on SIP response: \n" + response.toString());
			sipLogger.severe(response, ex);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " "
					+ ex.getClass().getName() + ": " + ex.getMessage());
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

		} catch (Exception ex) {
			sipLogger.severe("Exception on timer: " + timer.getId());
			sipLogger.severe(ex);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex.getMessage());
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

// jwm - do we need this?	
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

		} catch (NoSuchAlgorithmException ex) {
			sipLogger.severe(ex);
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

	public static String getAccountName(URI uri) {
		SipURI sipUri = (SipURI) uri;
		return (sipUri.getUser() + "@" + sipUri.getHost()).toLowerCase();
	}

	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		Callflow.getLogger().superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
		try {
			response.send();
		} catch (Exception ex) {
			sipLogger.severe(response, "Exception on SIP response: \n" + response.toString());
			sipLogger.severe(response, ex);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex.getMessage());
		}
	}

	public static SessionParameters getSessionParameters() {
		return sessionParameters;
	}

	public static void setSessionParameters(SessionParameters sessionParameters) {
		AsyncSipServlet.sessionParameters = sessionParameters;
	}

}
