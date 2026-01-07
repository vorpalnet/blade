package org.vorpal.blade.framework.v2;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
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
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.b2bua.Terminate;
import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.CallflowAckBye;
import org.vorpal.blade.framework.v2.callflow.CallflowCallConnectedError;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.config.SettingsManager;
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
	private static final String RESPONSE_CALLBACK_INVITE = "RESPONSE_CALLBACK_INVITE";
	private static final String GLARE_QUEUE = "BLADE_GLARE_QUEUE";
	private static final String EXPECT_ACK = "EXPECT_ACK";
	private static final String LINKED_SESSION = "LINKED_SESSION";
	private static final String HASHKEY = "HASHKEY";
	private static final String HASHKEY_COLLISION = "HASHKEY_COLLISION";
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

	/**
	 * Called by the container when the SIP servlet is initialized. This method
	 * initializes the SIP factory, session utilities, timer service, and logger.
	 * It also invokes the abstract {@link #servletCreated(SipServletContextEvent)}
	 * method for subclass-specific initialization.
	 *
	 * @param event the SIP servlet context event containing initialization information
	 */
	@Override
	public void servletInitialized(SipServletContextEvent event) {
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

		} catch (Exception ex1) {
			sipLogger.severe("AsyncSipServlet.servletInitialized - caught Exception #ex1");
			sipLogger.severe(ex1);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex1.getMessage());
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

	/**
	 * Called by the container when the servlet context is initialized.
	 * This implementation is empty; override in subclasses if needed.
	 *
	 * @param sce the servlet context event
	 */
	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// do nothing;
	}

	/**
	 * Called by the container when the servlet context is destroyed.
	 * Invokes the {@link #servletDestroyed(SipServletContextEvent)} method
	 * for subclass-specific cleanup.
	 *
	 * @param sce the servlet context event
	 */
	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		try {
			servletDestroyed(event);
		} catch (Exception ex1) {
			sipLogger.warning("AsyncSipServlet.contextDestroyed - Exception #ex1");
			sipLogger.severe(ex1);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}

	/**
	 * Processes incoming SIP requests. This method handles:
	 * <ul>
	 *   <li>Initial INVITE session setup and tracking</li>
	 *   <li>Glare detection and queuing (when messages arrive while awaiting ACK)</li>
	 *   <li>Callback invocation for expected requests</li>
	 *   <li>Callflow selection and processing via {@link #chooseCallflow(SipServletRequest)}</li>
	 *   <li>Session attribute extraction based on configured selectors</li>
	 *   <li>Proxy request logging</li>
	 * </ul>
	 *
	 * @param _request the incoming SIP request
	 * @throws ServletException if a servlet error occurs during processing
	 * @throws IOException if an I/O error occurs during processing
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected void doRequest(SipServletRequest _request) throws ServletException, IOException {

		try {
			SipServletRequest request = _request;

			SipSession sipSession = _request.getSession();
			LinkedList<SipServletRequest> glareQueue = //
					(LinkedList<SipServletRequest>) sipSession.getAttribute(GLARE_QUEUE);
			Callflow callflow = null;
			Callback<SipServletRequest> requestLambda;
			SipApplicationSession appSession = request.getApplicationSession();
			String method = request.getMethod();
			AttributesKey rr = null;
			SipSession linkedSession = Callflow.getLinkedSession(request.getSession());

			if (request.getMethod().equals("INVITE") && request.isInitial()) {

				// get / create the X-Vorpal-Session id, useful for tracking sessions
				String indexKey = Callflow.getVorpalSessionId(request);
				appSession.addIndexKey(indexKey);

				// attempt to keep track of who called whom
				request.getSession().setAttribute("userAgent", "caller");
				request.getSession().setAttribute("_diagramLeft", Boolean.TRUE);
				request.getSession().setAttribute("_DID", ((SipURI) request.getTo().getURI()).getUser());
				request.getSession().setAttribute("_ANI", ((SipURI) request.getFrom().getURI()).getUser());
			}

			// For processing glare, stack messages up
			boolean expectAck = Boolean.TRUE.equals(sipSession.getAttribute(EXPECT_ACK));

			if (!expectAck) {

				if (glareQueue != null && !glareQueue.isEmpty()) {
					sipLogger.warning(_request, "AsyncSipServlet.doRequest - glare; adding request to queue");
					glareQueue.add(_request);
					request = glareQueue.removeFirst();
					sipSession.setAttribute(GLARE_QUEUE, glareQueue);
				}
			}

			if (sipLogger.isLoggable(Level.FINEST)) {
				try {

					boolean linkedSessionIsValid = false;
					String linkedSessionState = "unknown";
					boolean linkedSessionIsReadyToInvalidate = false;
					if (linkedSession != null && linkedSession.isValid()) {
						linkedSessionIsValid = linkedSession.isValid();
						linkedSessionState = linkedSession.getState().toString();
						linkedSessionIsReadyToInvalidate = linkedSession.isReadyToInvalidate();
					}

					sipLogger.finest(request, //
							"AsyncSipServlet.doRequest - " + "method=" + request.getMethod() //
									+ ", isInitial=" + request.isInitial() //
									+ ", isCommitted=" + request.isCommitted() //
									+ ", expectAck=" + expectAck //
									+ ", session.isValid=" + sipSession.isValid() //
									+ ", session.state=" + sipSession.getState() //
									+ ", session.isReadyToInvalidate=" + sipSession.isReadyToInvalidate() //
									+ ", linkedSession=" + (linkedSession != null) //
									+ ", linkedSession.isValid=" + linkedSessionIsValid //
									+ ", linkedSession.state=" + linkedSessionState //
									+ ", linkedSession.isReadyToInvalidate=" + linkedSessionIsReadyToInvalidate //
					);

				} catch (Exception ex1) {
					sipLogger.warning("AsyncSipServlet.doRequest - Exception #ex1");
					sipLogger.severe(request, ex1);
				}
			}

			// ignore reINVITES, INFO messages, etc if this is a proxy request
			if (!isProxy(request)) {

				if (method.equals("ACK")) {
					expectAck = false;
					sipSession.removeAttribute(EXPECT_ACK);

				} else {
					// GLARE! Let's try to queue it up...
					if (expectAck && !method.equals("CANCEL")) { // anything other than cancel
						sipLogger.warning(request, "AsyncSipServlet.doResponse - Glare; " + method
								+ " received while awaiting ACK. Queuing message.");
						glareQueue = (glareQueue != null) ? glareQueue : new LinkedList<>();
						glareQueue.add(request);
						sipSession.setAttribute(GLARE_QUEUE, glareQueue);
						return; // wait for ACK before processing messages in the glare queue
					}

				}

				// Now that GLARE is taken care of, if this is an INVITE, we should expect an
				// ACK, unless we reply with an error code -- See Callflow.sendResponse
				if (method.equals("INVITE")) {
					sipSession.setAttribute(EXPECT_ACK, Boolean.TRUE);
				}

				try {
					requestLambda = Callflow.pullCallback(request);
					if (requestLambda != null) {

						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null,
								requestLambda.getClass().getSimpleName());

						requestLambda.accept(request);

					} else {

						if (callflow == null) {
							callflow = chooseCallflow(request);
						}

						if (callflow == null) {

							if (method.equals("ACK")) {
								Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
							} else {
								Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
								SipServletResponse response = request.createResponse(501);
								Callflow.getLogger().superArrow(Direction.SEND, null, response, "null");
								Callflow.getLogger().warning(
										"AsyncSipServlet.doResponse - No registered callflow for request method "
												+ method
												+ ", consider overriding the 'chooseCallflow' method in your SipServlet class.");
								response.send();
							}

						} else {
							sipLogger.superArrow(Direction.RECEIVE, request, null, callflow.getClass().getSimpleName());

							// process AttributeSelectors here!

							// create any index keys defined by selectors in the config file
							if (request.isInitial() && Callflow.getSessionParameters() != null) {
								List<AttributeSelector> selectors = Callflow.getSessionParameters()
										.getSessionSelectors();

								if (selectors != null) {
									for (AttributeSelector selector : selectors) {

										if (!DialogType.destination.equals(selector.getDialog())) {

											rr = selector.findKey(request);
											if (sipLogger.isLoggable(Level.FINEST)) {
												sipLogger.finest(request, "AsyncSipServlet.doRequest - selector.id="
														+ selector.getId() + ", rr=" + rr);
											}

											if (rr != null) {

												// Create an index key for the appSession
												if (rr.key != null) {
													if (sipLogger.isLoggable(Level.FINER)) {
														sipLogger.finer(request,
																"AsyncSipServlet - doRequest, addingIndexKey: "
																		+ rr.key);
													}
													request.getApplicationSession().addIndexKey(rr.key);
												}

												// Add named groups to SipSession
												for (Entry<String, String> entry : rr.attributes.entrySet()) {
													sipSession.setAttribute(entry.getKey(), entry.getValue());
												}

												// jwm - testing; this should only be for SipSession
												for (Entry<String, String> entry : rr.attributes.entrySet()) {
													appSession.setAttribute(entry.getKey(), entry.getValue());
												}

											}

										}

									}

								}

							}

							if (sipLogger.isLoggable(Level.FINER)) {
								sipLogger.finer(request,
										"AsyncSipServlet.doRequest - appSession id=" + appSession.getId() + //
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
									}
								}

								sipLogger.finer(sipSession, "AsyncSipServlet.doRequest - callflow="
										+ callflow.getClass().getSimpleName() + ", SipSession attributes: " + attrMap);
							}

							try {
								callflow.process(request);
							} catch (Exception ex2) {
								sipLogger.warning(request, "AsyncSipServlet.doRequest - Exception #ex2");
								throw new ServletException(ex2);
							}

							if (linkedSession != null
									&& request.isInitial() && Callflow.getSessionParameters() != null) {

								List<AttributeSelector> selectors = Callflow.getSessionParameters()
										.getSessionSelectors();

								if (selectors != null) {
									for (AttributeSelector selector : selectors) {

										if (DialogType.destination.equals(selector.getDialog())) {

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
											"AsyncSipServlet.doRequest - Destination session attributes: "
													+ attrMap);
								}

							}

						}
					}

				} catch (Exception ex3) {
					sipLogger.warning("AsyncSipServlet.doRequest - Exception #ex3");

					Throwable cause = ex3.getCause();
					while (cause.getCause() != null) {
						cause = cause.getCause();
					}

					String reasonPhrase = convertCamelCaseToRegularWords(
							SettingsManager.getApplicationName() + " " + cause.getClass().getSimpleName());

					try { // this is a "hail mary"; if it fails, who cares?

						if (request.getMethod().equals("INVITE")) {

							sipLogger.warning(request, "AsyncSipServlet.doRequest - hail mary!");
							SipServletResponse response = request.createResponse(500, reasonPhrase);
							response.setContent(Logger.stackTraceToString(ex3), "text/plain");
							sendResponse(response);
						} else if (request.getMethod().equals("ACK")) {

							// must send BYE back upstream
							// must send ACK downstream, followed by BYE

							Callflow cf = new CallflowCallConnectedError(ex3);
							cf.process(request);

						}

					} catch (Exception ex4) {
						// Last-resort error handler - error recovery itself failed, just log it
						sipLogger.warning("AsyncSipServlet.doRequest - Exception #ex4...");
						sipLogger.severe(request, ex4);
					}

					sipLogger.severe(request, "Exception on SIP request: \n" + request.toString());
					sipLogger.severe(request, ex3);
					sipLogger.getParent().severe(reasonPhrase);
				}

			} else { // isProxy, for logging purposes only
				boolean diagramLeft = true;
				String proxyDid = (String) request.getSession().getAttribute("_DID");
				String fromUser = ((SipURI) request.getFrom().getURI()).getUser();
				if (Objects.equals(proxyDid, fromUser)) {
					diagramLeft = false;
				}
				Callflow.getLogger().superArrow(Direction.RECEIVE, diagramLeft, request, null, "proxy", null);
				Callflow.getLogger().superArrow(Direction.SEND, !diagramLeft, request, null, "proxy", null);
			}

			// Last, but not least, process any queued up requests due to glare.
			processGlareQueue(sipSession, glareQueue);

		} catch (Exception ex6) { // this should never happen, but if it does...

			sipLogger.warning("AsyncSipServlet.doRequest - Exception #ex6");

			if (sipLogger != null) {
				sipLogger.severe(_request, ex6);
				sipLogger.getParent().severe(ex6.getClass().getName() + " " + ex6.getMessage());
			} else {
				ex6.printStackTrace();
			}
		}

	}

	/**
	 * Processes incoming SIP responses. This method handles:
	 * <ul>
	 *   <li>Detection of responses arriving after call cancellation</li>
	 *   <li>Callback invocation for pending response handlers</li>
	 *   <li>Early dialog session merging when To-tag changes</li>
	 *   <li>Glare queue processing after response handling</li>
	 *   <li>Proxy response logging and session invalidation for loose routing</li>
	 *   <li>Error recovery including upstream error notification and downstream call termination</li>
	 * </ul>
	 *
	 * @param response the incoming SIP response
	 * @throws ServletException if a servlet error occurs during processing
	 * @throws IOException if an I/O error occurs during processing
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		try {
			boolean isProxy = isProxy(response);
			Callback<SipServletResponse> callback;
			SipSession sipSession = response.getSession();
			SipApplicationSession appSession = response.getApplicationSession();
			String method = response.getMethod();
			SipSession linkedSession = Callflow.getLinkedSession(response.getSession());

			if (sipLogger.isLoggable(Level.FINER)) {
				try {
					String sessionState = "unknown";
					if (sipSession.isValid()) {
						sessionState = sipSession.getState().toString();
					}

					boolean linkedSessionIsValid = false;
					String linkedSessionState = "unknown";
					boolean linkedSessionIsReadyToInvalidate = false;
					if (linkedSession != null && linkedSession.isValid()) {
						linkedSessionIsValid = linkedSession.isValid();
						linkedSessionState = linkedSession.getState().toString();
						linkedSessionIsReadyToInvalidate = linkedSession.isReadyToInvalidate();
					}

					sipLogger.finer(response, //
							"AsyncSipServlet.doResponse - " + "method=" + response.getMethod() //
									+ ", status=" + response.getStatus() //
									+ ", reasonPhrase=" + response.getReasonPhrase() //
									+ ", isProxy=" + isProxy //
									+ ", session.isValid=" + sipSession.isValid() //
									+ ", session.state=" + sessionState //
									+ ", session.isReadyToInvalidate=" + sipSession.isReadyToInvalidate() //
									+ ", linkedSession=" + (linkedSession != null) //
									+ ", linkedSession.isValid=" + linkedSessionIsValid //
									+ ", linkedSession.state=" + linkedSessionState //
									+ ", linkedSession.isReadyToInvalidate=" + linkedSessionIsReadyToInvalidate //
					);

				} catch (Exception ex1) {
					sipLogger.warning("AsyncSipServlet.doReponse - Exception #ex1");
					sipLogger.severe(response, ex1);
				}
			}

			// Check for the possibility that an INVITE response comes back *after* the call
			// has been canceled
			if (method.equals("INVITE") && linkedSession != null && //
					(!linkedSession.isValid()
							|| linkedSession.getState().equals(SipSession.State.TERMINATED))) {
				sipLogger.warning(response,
						"AsyncSipServlet.doResponse - Linked session terminated (CANCEL?), but an INVITE response came through anyway. Killing the session with CallflowAckBye");

				CallflowAckBye ackAndBye = new CallflowAckBye();

				try {

					if (!Callflow.failure(response)) { // eat failure responses
						ackAndBye.process(response);
					}

				} catch (Exception ex2) {
					sipLogger.warning(response, "AsyncSipServlet.doResponse - Exception #ex2");
					throw new ServletException(ex2);
				}

				return;
			}

			if (!isProxy) {

				try {

					if (sipSession != null && sipSession.isValid()) {

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

										if (sipLogger.isLoggable(Level.FINER)) {
											sipLogger.finer(response, "AsyncSipServlet.doResponse - Rogue session id="
													+ response.getSession().getId());
											sipLogger.finer(response,
													"AsyncSipServlet.doResponse - Number of SipSessions: "
															+ sessions.size());
										}

										for (SipSession session : sessions) {

											if (sipLogger.isLoggable(Level.FINER)) {
												sipLogger.finer(response,
														"AsyncSipServlet.doResponse - Checking session id="
																+ session.getId());
											}

											if (session != response.getSession()) {

												if (session.getCallId().equals(sipSession.getCallId())) {

													callback = (Callback<SipServletResponse>) session
															.getAttribute(RESPONSE_CALLBACK_INVITE);

													if (callback != null) {

														if (sipLogger.isLoggable(Level.FINER)) {
															sipLogger.finer(session,
																	"AsyncSipServlet.doResponse - Early dialog session detected, merge in progress...");
															sipLogger.finer(response,
																	"AsyncSipServlet.doResponse - Setting RESPONSE_CALLBACK_INVITE on merged session...");
															sipLogger.finer(response,
																	"AsyncSipServlet.doResponse - Removing RESPONSE_CALLBACK_INVITE from original session...");
														}

														sipSession.setAttribute(RESPONSE_CALLBACK_INVITE, callback);

														session.removeAttribute(RESPONSE_CALLBACK_INVITE);

														// link the sessions
														if (sipLogger.isLoggable(Level.FINER)) {
															sipLogger.finer(session,
																	"AsyncSipServlet.doResponse - Linking sessions...");
														}

														session.removeAttribute(LINKED_SESSION);
														Callflow.linkSessions(linkedSession, sipSession);

														for (String attr : session.getAttributeNameSet()) {
															if (sipLogger.isLoggable(Level.FINER)) {
																sipLogger.finer(session,
																		"AsyncSipServlet.doResponse - Copying session attribute: "
																				+ attr);
															}
															sipSession.setAttribute(attr, session.getAttribute(attr));
														}

														// invalidate the old session
														if (sipLogger.isLoggable(Level.FINER)) {
															sipLogger.finer(session,
																	"AsyncSipServlet.doResponse - Invalidating early session...");
														}
														session.invalidate();
														if (sipLogger.isLoggable(Level.FINER)) {
															sipLogger.finer(response,
																	"AsyncSipServlet.doResponse - Sessions successfully merged.");
														}
													}
													break;
												}
											}
										}
									} else {

										if (isProxy(response)) {

											boolean leftDiagram = Boolean.TRUE.equals(
													response.getSession().getAttribute("leftDiagram"));

											Callflow.getLogger().superArrow(Direction.RECEIVE, leftDiagram, null,
													response, "proxy", null);

											Callflow.getLogger().superArrow(Direction.SEND, !leftDiagram, null,
													response, "proxy", null);

										} else {

											Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
													this.getClass().getSimpleName());

										}

									}
								}
							}

							if (callback != null && !isProxy) {
								Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
										callback.getClass().getSimpleName());
								callback.accept(response);
							}

					} else {
						if (sipSession == null) {
							sipLogger.warning(response, "AsyncSipServlet.doResponse - SipSession is null.");
						} else {
							sipLogger.warning(response,
									"AsyncSipServlet.doResponse - SipSession " + sipSession.getId() + " is invalid.");
						}
					}
				} catch (Exception ex3) {
					sipLogger.severe(response, "AsyncSipServlet.doResponse - linkedSession != nullException #ex3");
					sipLogger.severe(response,
							"AsyncSipServlet.doResponse - Exception on SIP response: \n" + response.toString());
					sipLogger.severe(response, ex3);
					sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " "
							+ ex3.getClass().getName() + ": " + ex3.getMessage());

					try { // attempt to send error back upstream;

						Throwable cause = ex3.getCause();
						while (cause.getCause() != null) {
							cause = cause.getCause();
						}
						linkedSession = Callflow.getLinkedSession(response.getSession());
						SipServletRequest linkedRequest = linkedSession.getActiveInvite(UAMode.UAS);
						SipServletResponse linkedResponse = linkedRequest.createResponse(500,
								convertCamelCaseToRegularWords(
										SettingsManager.getApplicationName() + " " + cause.getClass().getSimpleName()));
						linkedResponse.setContent(Logger.stackTraceToString(ex3), "text/plain");
						sendResponse(linkedResponse);
					} catch (Exception ex4) {
						// Failed to send error response upstream - nothing more we can do
						sipLogger.severe(response, "AsyncSipServlet.doResponse - Exception #ex4");
						sipLogger.severe(response, ex4);
					}

					try { // attempt to kill the call going downstream
						SipServletRequest rqst;
						sipLogger.severe(response,
								"AsyncSipServlet.doResponse - Attempting to kill the call going downstream");
						if (response.getMethod().equals("INVITE")) {
							if (Callflow.successful(response)) {
								rqst = response.getSession().getActiveInvite(UAMode.UAC);
								CallflowAckBye callflow = new CallflowAckBye();
								sipLogger.severe(response, "AsyncSipServlet.doResponse - invoking CallflowAckBye");
								callflow.process(response);
							} else if (Callflow.provisional(response)) {
								rqst = Callflow.getLinkedSession(response.getSession()).getActiveInvite(UAMode.UAS);
								Callflow callflow = new Terminate(null);
								sipLogger.severe(response,
										"AsyncSipServlet.doResponse - invoking Cancel, rqst=" + rqst);
								callflow.process(rqst);
							}
						}

					} catch (Exception ex5) {
						// Failed to terminate downstream call - nothing more we can do
						sipLogger.severe(response, "AsyncSipServlet.doResponse - Logging #ex5");
						sipLogger.severe(response, ex5);
					}

				}

				// For processing any queued up glare requests (e.g., INFO messages stacked up)
				LinkedList<SipServletRequest> glareQueue = (LinkedList<SipServletRequest>) sipSession
						.getAttribute(GLARE_QUEUE);
				processGlareQueue(sipSession, glareQueue);

			} else { // isProxy

				// For logging purposes
				boolean diagramLeft = false;
				String proxyDid = (String) response.getSession().getAttribute("_DID");
				String fromUser = ((SipURI) response.getFrom().getURI()).getUser();
				if (Objects.equals(proxyDid, fromUser)) {
					diagramLeft = true;
				}
				Callflow.getLogger().superArrow(Direction.RECEIVE, diagramLeft, null, response, "proxy", null);
				Callflow.getLogger().superArrow(Direction.SEND, !diagramLeft, null, response, "proxy", null);

				// For 'loose' routing, since no BYE is received, we must manually invalidate
				// the session
				if (response.getSession().getState().equals(State.EARLY) //
						&& !response.getProxy().getRecordRoute()) {
					sipLogger.finer(response,
							"AsyncSipServlet.doResponse - invalidating proxy appSession: " + appSession.getId());
					appSession.invalidate();
				}

			}

		} catch (Exception ex7) { // this should never happen, but if it does...
			sipLogger.warning("AsyncSipServlet.doReponse - Exception #5...");
			sipLogger.warning(response, "Error #3");

			if (sipLogger != null) {
				sipLogger.severe(response, ex7);
				sipLogger.getParent().severe(ex7.getClass().getName() + " " + ex7.getMessage());
			} else {
				ex7.printStackTrace();
			}
		}

	}

	/**
	 * Handles timer expiration events. Retrieves and invokes the callback
	 * function stored in the timer's info object when the timer was created.
	 *
	 * @param timer the expired servlet timer containing the callback in its info object
	 */
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

		} catch (Exception ex1) {
			sipLogger.warning("AsyncSipServlet.timeout - Exception #ex1");
			sipLogger.severe("AsyncSipServlet.doResponse - Exception on timer: " + timer.getId());
			sipLogger.severe(ex1);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}

	/**
	 * Returns the SIP logger used for logging SIP messages and application events.
	 *
	 * @return the SIP logger instance
	 */
	final public static Logger getSipLogger() {
		return sipLogger;
	}

	/**
	 * Sets the SIP logger used for logging SIP messages and application events.
	 *
	 * @param _sipLogger the SIP logger instance to set
	 */
	final public static void setSipLogger(Logger _sipLogger) {
		sipLogger = _sipLogger;
	}

	/**
	 * Returns the SIP factory used to create SIP messages, addresses, and URIs.
	 *
	 * @return the SIP factory instance
	 */
	final public static SipFactory getSipFactory() {
		return sipFactory;
	}

	/**
	 * Sets the SIP factory used to create SIP messages, addresses, and URIs.
	 *
	 * @param _sipFactory the SIP factory instance to set
	 */
	final public static void setSipFactory(SipFactory _sipFactory) {
		sipFactory = _sipFactory;
	}

	/**
	 * Returns the SIP sessions utility for looking up application sessions by key.
	 *
	 * @return the SIP sessions utility instance
	 */
	final public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	/**
	 * Sets the SIP sessions utility for looking up application sessions by key.
	 *
	 * @param _sipUtil the SIP sessions utility instance to set
	 */
	final public static void setSipUtil(SipSessionsUtil _sipUtil) {
		sipUtil = _sipUtil;
	}

	/**
	 * Returns the timer service used to create and manage servlet timers.
	 *
	 * @return the timer service instance
	 */
	final public static TimerService getTimerService() {
		return timerService;
	}

	/**
	 * Converts a byte array to an alphanumeric string representation.
	 * Uses a 62-character alphabet (0-9, a-z, A-Z) for compact encoding.
	 *
	 * @param bytes the byte array to convert
	 * @return an alphanumeric string representation of the bytes
	 */
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
		StringBuilder sb = new StringBuilder();
		for (final byte b : bytes) {
			sb.append(alphanum[Byte.toUnsignedInt(b) % alphanum.length]);
		}
		return sb.toString();
	}

	/**
	 * Computes an MD5 hash of the input string and returns it as an alphanumeric string.
	 * Note: MD5 is used here for hash distribution, not cryptographic security.
	 * This method is useful for generating unique keys for SIP application sessions.
	 *
	 * @param stringToHash the string to hash
	 * @return the hashed string in alphanumeric format, or null if hashing fails
	 */
	public static String hash(String stringToHash) {
		String stringHash = null;

		MessageDigest messageDigest;
		try {
			messageDigest = MessageDigest.getInstance("MD5");
			messageDigest.update(stringToHash.getBytes());
			stringHash = byteArray2Text(messageDigest.digest());
		} catch (NoSuchAlgorithmException ex1) {
			sipLogger.warning("AsyncSipServlet.hash - Exception #ex1");
			sipLogger.severe(ex1);
		}

		return stringHash;
	}

	/**
	 * Gets or creates a SIP application session using a hashed key derived from the input string.
	 * This method detects and logs hash collisions when different input strings produce the same hash.
	 * Useful in {@code @SipApplicationKey} methods for correlating related SIP sessions.
	 *
	 * @param stringToHash the string to hash for generating the session key
	 * @return the hashed string used as the application session key
	 */
	public static String getAppSessionHashKey(String stringToHash) {
		SipApplicationSession appSession = null;
		String hashedString = hash(stringToHash);
		appSession = sipUtil.getApplicationSessionByKey(hashedString, true);

		String existingHashKey = (String) appSession.getAttribute(HASHKEY);

		if (existingHashKey == null) {
			// this is good because the AppSession did not previously exist
			appSession.setAttribute(HASHKEY, hashedString);
		} else if (!existingHashKey.equals(hashedString)) {
			// this is bad because the hash keys collide;
			sipLogger.severe("@SipApplicationKey hash key collision. SipApplicationSession.id: "
					+ appSession.getId() + " collides with " + existingHashKey + " and " + hashedString);
			appSession.setAttribute(HASHKEY_COLLISION, true);
		}

		return hashedString;
	}

	/**
	 * Extracts the account name from a SIP address in the format "user@host".
	 *
	 * @param address the SIP address to extract the account name from
	 * @return the account name in lowercase (user@host format)
	 */
	public static String getAccountName(Address address) {
		return getAccountName(address.getURI());
	}

	/**
	 * Extracts the account name from a SIP URI in the format "user@host".
	 *
	 * @param uri the SIP URI to extract the account name from
	 * @return the account name in lowercase (user@host format)
	 */
	public static String getAccountName(URI uri) {
		SipURI sipUri = (SipURI) uri;
		return (sipUri.getUser() + "@" + sipUri.getHost()).toLowerCase();
	}

	/**
	 * Processes queued requests that were delayed due to glare conditions.
	 * Glare occurs when both endpoints send requests simultaneously, requiring
	 * one side to queue its request until the other completes.
	 *
	 * @param sipSession the SIP session containing the glare queue
	 * @param glareQueue the queue of pending requests to process
	 */
	private void processGlareQueue(SipSession sipSession, LinkedList<SipServletRequest> glareQueue) {
		if (glareQueue == null || glareQueue.isEmpty()) {
			return;
		}

		boolean expectAck = Boolean.TRUE.equals(sipSession.getAttribute(EXPECT_ACK));
		if (expectAck) {
			return;
		}

		SipServletRequest glareRequest = null;
		try {
			glareRequest = glareQueue.removeFirst();
			sipSession.setAttribute(GLARE_QUEUE, glareQueue);
			this.doRequest(glareRequest);
		} catch (Exception e) {
			sipLogger.warning("AsyncSipServlet.processGlareQueue - Exception processing glare request");

			SipSession glareSession = glareRequest.getSession();
			if (glareSession != null && glareSession.isValid()) {
				String error = event.getServletContext().getServletContextName() + " "
						+ e.getClass().getSimpleName() + ", " + e.getMessage();
				sipLogger.severe(glareRequest,
						"AsyncSipServlet.processGlareQueue - Exception attempting to process GLARE request: \n"
								+ glareRequest.toString());
				sipLogger.severe(glareRequest, e);
				sipLogger.getParent().severe(error);
				try {
					SipServletResponse glareResponse = glareRequest.createResponse(491); // Request Pending
					glareResponse.setHeader("Retry-After", "3");
					sendResponse(glareResponse);
				} catch (Exception responseException) {
					sipLogger.severe(glareRequest, "Failed to send 491 response for glare request");
					sipLogger.severe(glareRequest, responseException);
				}
			}
		}
	}

	/**
	 * Sends a SIP response and logs the outgoing message. This method provides
	 * centralized response sending with automatic logging and error handling.
	 *
	 * @param response the SIP response to send
	 * @throws ServletException if a servlet error occurs while sending
	 * @throws IOException if an I/O error occurs while sending
	 */
	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		Callflow.getLogger().superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
		try {
			response.send();
		} catch (Exception ex1) {
			sipLogger.warning("AsyncSipServlet.sendReponse - Exception #ex1");
			sipLogger.severe(response, "Exception on SIP response: \n" + response.toString());
			sipLogger.severe(response, ex1);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}

	/**
	 * Determines if the SIP message is being handled in proxy mode.
	 * Returns true if {@code proxyRequest} was previously invoked on this application session.
	 *
	 * @param msg the SIP message to check
	 * @return true if the message is in proxy mode, false otherwise
	 */
	public static boolean isProxy(SipServletMessage msg) {
		return Boolean.TRUE.equals(msg.getApplicationSession().getAttribute("isProxy"));
	}

	/**
	 * Returns the session parameters configuration used for session attribute extraction.
	 *
	 * @return the current session parameters, or null if not configured
	 */
	public static SessionParameters getSessionParameters() {
		return sessionParameters;
	}

	/**
	 * Sets the session parameters configuration used for session attribute extraction.
	 *
	 * @param _sessionParameters the session parameters to set
	 */
	public static void setSessionParameters(SessionParameters _sessionParameters) {
		sessionParameters = _sessionParameters;
	}

	/**
	 * Converts a camelCase string to regular words separated by spaces.
	 * For example, "myVariableName" becomes "My Variable Name".
	 * Used for generating human-readable error messages from exception class names.
	 *
	 * @param camelCaseString the camelCase string to convert
	 * @return the string with spaces inserted before uppercase letters and first letter capitalized
	 */
	public static String convertCamelCaseToRegularWords(String camelCaseString) {
		// Regex explanation:
		// (?<=[a-z]) looks for an uppercase letter that is preceded by a lowercase
		// letter (positive lookbehind).
		// [A-Z] matches the actual uppercase letter.
		// It replaces the matched uppercase letter with a space followed by the letter
		// itself.
		String result = camelCaseString.replaceAll("(?<=[a-z])([A-Z])", " $1");

		// Optional: Capitalize the first letter of the resulting sentence
		if (!result.isEmpty()) {
			result = result.substring(0, 1).toUpperCase() + result.substring(1);
		}

		return result;
	}

}
