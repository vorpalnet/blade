package org.vorpal.blade.framework.v2;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
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
import javax.servlet.sip.Parameterable;
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

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.analytics.Event;
import org.vorpal.blade.framework.v2.analytics.JmsPublisher;
import org.vorpal.blade.framework.v2.b2bua.Terminate;
import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.CallflowAckBye;
import org.vorpal.blade.framework.v2.callflow.CallflowCallConnectedError;
import org.vorpal.blade.framework.v2.callflow.Callflow.GlareState;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;

/// Abstract SIP servlet implementing the BLADE asynchronous APIs with lambda expression support
/// 
/// This servlet provides core functionality for asynchronous SIP message processing, including:
/// - Lambda-based callback handling for SIP requests and responses
/// - Glare detection and message queuing for UDP transport reliability
/// - Session linking and management for B2BUA applications
/// - Analytics integration and configuration support
/// - Session parameter extraction using configurable attribute selectors
/// - Error recovery and graceful call termination
/// 
/// ## Usage
/// 
/// Extend this class to create specialized SIP servlet implementations:
/// 
/// ```java
/// public class MyServlet extends AsyncSipServlet {
///     protected Callflow chooseCallflow(SipServletRequest request) {
///         return new MyCallflow();
///     }
/// }
/// ```
/// 
/// See [B2buaServlet] for a complete implementation example.
/// 
/// ## Key Features
/// 
/// - **Asynchronous Processing**: Uses [Callback] lambdas for non-blocking message handling
/// - **Glare Prevention**: Queues conflicting requests to prevent 491 responses
/// - **Session Management**: Automatic session linking and attribute extraction
/// - **Analytics Integration**: Optional JMS-based event publishing
/// - **Error Recovery**: Automatic upstream error notification and downstream termination
/// 
/// @author Jeff McDonald
public abstract class AsyncSipServlet extends SipServlet
		implements SipServletListener, ServletContextListener, TimerListener {

	private static final long serialVersionUID = 1L;
	
	/// Initial SIP servlet context event, preserved for cleanup operations
	private static SipServletContextEvent initialSipServletContextEvent;
	
	/// Global SIP logger instance for message and event logging
	protected static Logger sipLogger;
	
	/// SIP factory for creating SIP messages, addresses, and URIs
	protected static SipFactory sipFactory;
	
	/// SIP sessions utility for looking up application sessions by key
	protected static SipSessionsUtil sipUtil;
	
	/// Timer service for creating and managing servlet timers
	protected static TimerService timerService;
	
	/// Session attribute key for INVITE response callbacks
	private static final String RESPONSE_CALLBACK_INVITE = "RESPONSE_CALLBACK_INVITE";
	
	/// Session attribute key for glare request queue
	private static final String GLARE_QUEUE = "GLARE_QUEUE";
	
	/// Session attribute key for linked session references
	private static final String LINKED_SESSION = "LINKED_SESSION";
	
	/// Application session attribute key for hash key storage
	private static final String HASHKEY = "HASHKEY";
	
	/// Application session attribute key for hash collision detection
	private static final String HASHKEY_COLLISION = "HASHKEY_COLLISION";

	/// SIP CANCEL method constant
	private static final String CANCEL = "CANCEL";
	
	/// SIP INVITE method constant
	private static final String INVITE = "INVITE";
	
	/// SIP BYE method constant
	private static final String BYE = "BYE";
	
	/// SIP ACK method constant
	private static final String ACK = "ACK";
	
	/// SIP REFER method constant
	private static final String REFER = "REFER";

	/// Configuration for session attribute extraction and indexing
	protected static SessionParameters sessionParameters;

	/// Called when the SipServlet has been created and initialized
	/// 
	/// Implement this method to perform application-specific initialization tasks
	/// such as loading configuration, setting up resources, or registering services.
	/// 
	/// @param event the SIP servlet context event containing initialization information
	/// @throws ServletException if servlet initialization fails
	/// @throws IOException if an I/O error occurs during initialization
	protected abstract void servletCreated(SipServletContextEvent event) throws ServletException, IOException;

	/// Called when the SipServlet is being destroyed
	/// 
	/// Implement this method to perform cleanup tasks such as releasing resources,
	/// closing connections, or persisting state before servlet shutdown.
	/// 
	/// @param event the SIP servlet context event containing destruction information
	/// @throws ServletException if servlet destruction fails
	/// @throws IOException if an I/O error occurs during destruction
	protected abstract void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException;

	/// Selects the appropriate callflow for processing incoming SIP requests
	/// 
	/// Implement this method to choose different [Callflow] objects based on request
	/// characteristics such as method, headers, or routing logic. This method is only
	/// called for requests that don't already have an associated callflow.
	/// 
	/// @param request the incoming SIP request requiring callflow assignment
	/// @return the selected [Callflow] instance, or null to send a 501 Not Implemented response
	/// @throws ServletException if callflow selection fails
	/// @throws IOException if an I/O error occurs during selection
	protected abstract Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException;

	/// Called by the container when the SIP servlet is initialized. This method
	/// initializes the SIP factory, session utilities, timer service, and logger. It
	/// also invokes the abstract {@link #servletCreated(SipServletContextEvent)}
	/// method for subclass-specific initialization.
	///
	/// @param event the SIP servlet context event containing initialization
	///              information
	@Override
	public void servletInitialized(SipServletContextEvent event) {
		initialSipServletContextEvent = event;
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		timerService = (TimerService) event.getServletContext().getAttribute("javax.servlet.sip.TimerService");

		Callflow.setLogger(sipLogger);
		Callflow.setSipFactory(sipFactory);
		Callflow.setSipUtil(sipUtil);
		Callflow.setTimerService(timerService);

		try {
			sipLogger = LogManager.getLogger(event);

			Package pkg = AsyncSipServlet.class.getPackage();
			String title = pkg.getSpecificationTitle();
			String version = pkg.getImplementationVersion();
			String application = event.getServletContext().getServletContextName();

			if (title != null) {
				sipLogger.info(application + " compiled using " + title + " version " + version);
			}

			servletCreated(event);

			Analytics analytics = SettingsManager.getAnalytics();
			if (analytics != null) {

				if (sipLogger.isLoggable(sipLogger.getConfigurationLoggingLevel())) {

					sipLogger.log(sipLogger.getConfigurationLoggingLevel(),
							"These are the system properties that can be logged through 'analytics':");

					String key, value;
					Iterator<String> itr = event.getServletContext().getInitParameterNames().asIterator();
					while (itr.hasNext()) {
						key = itr.next();
						value = event.getServletContext().getInitParameter(key);
						sipLogger.log(sipLogger.getConfigurationLoggingLevel(),
								"AsyncSipServlet.servletInitialized - init\t" + key + "=" + value);
					}

					itr = event.getServletContext().getAttributeNames().asIterator();
					while (itr.hasNext()) {
						key = itr.next();
						value = event.getServletContext().getAttribute(key).toString();
						sipLogger.log(sipLogger.getConfigurationLoggingLevel(),
								"AsyncSipServlet.servletInitialized - attr\t" + key + "=" + value);
					}

					for (Entry<String, String> entry : System.getenv().entrySet()) {
						sipLogger.log(sipLogger.getConfigurationLoggingLevel(),
								"AsyncSipServlet.servletInitialized - env\t" + entry.getKey() + "=" + entry.getValue());
					}

					for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
						sipLogger.log(sipLogger.getConfigurationLoggingLevel(),
								"AsyncSipServlet.servletInitialized - props\t" + entry.getKey().toString() + "="
										+ entry.getValue().toString());
					}

				}

				if (Boolean.TRUE.equals(analytics.isEnabled())) {
					try {
						Analytics.jmsPublisher = new JmsPublisher("jms/BladeAnalyticsConnectionFactory",
								"jms/BladeAnalyticsDistributedQueue");
						Analytics.jmsPublisher.init();
						Analytics.jmsPublisher.applicationStart();
					} catch (Exception e) {
						sipLogger.severe(
								"AsyncSipServlet.servletInitialized - Cannot create JmsPublisher for Analytics. Ensure jms/BladeAnalyticsConnectionFactory and jms/BladeAnalyticsDistributedQueue is configured.");
					}
				}

				Event servletCreated = SettingsManager.createEvent("start", event);
				start(servletCreated);
				SettingsManager.sendEvent(event);

			} else {
				servletCreated(event);
			}

		} catch (Exception ex1) {
			sipLogger.severe("AsyncSipServlet.servletInitialized - caught Exception #ex1");
			sipLogger.severe(ex1);
			sipLogger.getParent().severe(event.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}

	}

	/// Customizes the analytics start event before it is published
	/// 
	/// Override this method to add application-specific data to the analytics
	/// event that is sent when the servlet starts. The event will be automatically
	/// published after this method returns.
	/// 
	/// @param event the analytics start event to customize
	public void start(Event event) {
		// override by user
	}

	/// Customizes the analytics stop event before it is published
	/// 
	/// Override this method to add application-specific data to the analytics
	/// event that is sent when the servlet stops. The event will be automatically
	/// published after this method returns.
	/// 
	/// @param event the analytics stop event to customize
	public void stop(Event event) {
		// override by user
	}

	/// Handles SIP request events that fall outside the scope of defined callflows
	/// 
	/// Override this method to process requests that don't match any registered
	/// callflow or callback. This is useful for handling OPTIONS pings, MESSAGE
	/// requests, or other out-of-dialog messages.
	/// 
	/// @param request the SIP request to handle
	/// @throws ServletException if request processing fails
	/// @throws IOException if an I/O error occurs
	public void requestEvent(SipServletRequest request) throws ServletException, IOException {
		// override this method
	}

	/// Handles SIP response events that fall outside the scope of defined callflows
	/// 
	/// Override this method to process responses that don't match any registered
	/// callflow or callback. This is useful for handling responses to OPTIONS
	/// requests or other out-of-dialog responses.
	/// 
	/// @param response the SIP response to handle
	/// @throws ServletException if response processing fails
	/// @throws IOException if an I/O error occurs
	public void responseEvent(SipServletResponse response) throws ServletException, IOException {
		// override this method
	}

	/// Called by the container when the servlet context is initialized. This
	/// implementation is empty; override in subclasses if needed.
	///
	/// @param sce the servlet context event
	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// do nothing;
	}

	/// Called by the container when the servlet context is destroyed. Invokes the
	/// {@link #servletDestroyed(SipServletContextEvent)} method for
	/// subclass-specific cleanup.
	///
	/// @param sce the servlet context event
	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		try {

			servletDestroyed(initialSipServletContextEvent);

			if (Analytics.jmsPublisher != null) {

// jwm - FIXME				

//				Event stopEvent = SettingsManager.createEvent("stop", initialSipServletContextEvent);
//				stop(stopEvent);
//				SettingsManager.sendEvent(stopEvent);

				Analytics.jmsPublisher.applicationStop();
				Analytics.jmsPublisher.close();
				Analytics.jmsPublisher = null;
			}

		} catch (Exception ex1) {
			sipLogger.warning("AsyncSipServlet.contextDestroyed - Exception #ex1");
			sipLogger.severe(ex1);
			sipLogger.getParent().severe(
					initialSipServletContextEvent.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}

	/// Processes incoming SIP requests. This method handles:
	/// <ul>
	/// <li>Initial INVITE session setup and tracking</li>
	/// <li>Glare detection and queuing (when messages arrive while awaiting
	/// ACK)</li>
	/// <li>Callback invocation for expected requests</li>
	/// <li>Callflow selection and processing via
	/// {@link #chooseCallflow(SipServletRequest)}</li>
	/// <li>Session attribute extraction based on configured selectors</li>
	/// <li>Proxy request logging</li>
	/// </ul>
	///
	/// @param _request the incoming SIP request
	/// @throws ServletException if a servlet error occurs during processing
	/// @throws IOException      if an I/O error occurs during processing
	@SuppressWarnings("unchecked")
	@Override
	protected void doRequest(SipServletRequest _request) throws ServletException, IOException {

		try {
			SipServletRequest request = _request;
			SipSession sipSession = _request.getSession();

// UDP makes queuing necessary, sigh!			
			LinkedList<SipServletRequest> glareQueue = (LinkedList<SipServletRequest>) sipSession
					.getAttribute(GLARE_QUEUE);
			if (glareQueue == null) {
				glareQueue = new LinkedList<>();
			}

			Callflow callflow = null;
			Callback<SipServletRequest> requestLambda;
			SipApplicationSession appSession = request.getApplicationSession();
			String method = request.getMethod();
			AttributesKey rr = null;
			SipSession linkedSession = Callflow.getLinkedSession(request.getSession());

			// get the Vorpal ID first thing (skip short-lived, fire-and-forget methods)
			if (request.isInitial()) {
				switch (method) {
				case "OPTIONS":
				case "INFO":
				case "MESSAGE":
					break;
				default:
					String indexKey = Callflow.getVorpalSessionId(request);
					appSession.addIndexKey(indexKey);
					break;
				}
			}

			// Log useful variables for debugging purposes
			if (sipLogger.isLoggable(Level.FINER)) {
				try {

					String linkedDialog = null;
					boolean linkedSessionIsValid = false;
					String linkedSessionState = "unknown";
					boolean linkedSessionIsReadyToInvalidate = false;
					if (linkedSession != null && linkedSession.isValid()) {
						linkedDialog = Callflow.getVorpalDialogId(linkedSession);
						linkedSessionIsValid = linkedSession.isValid();
						linkedSessionState = linkedSession.getState().toString();
						linkedSessionIsReadyToInvalidate = linkedSession.isReadyToInvalidate();
					}

					sipLogger.finer(request, //
							"AsyncSipServlet.doRequest - " + "method=" + request.getMethod() //
									+ ", isInitial=" + request.isInitial() //
									+ ", isCommitted=" + request.isCommitted() //
									+ ", session.isValid=" + sipSession.isValid() //
									+ ", session.state=" + sipSession.getState() //
									+ ", session.isReadyToInvalidate=" + sipSession.isReadyToInvalidate() //
									+ ", session.glare=" + Callflow.getGlareState(sipSession) //
									+ ", linkedSession=" + (linkedSession != null) //
									+ ", linkedSession.dialog=" + linkedDialog //
									+ ", linkedSession.isValid=" + linkedSessionIsValid //
									+ ", linkedSession.state=" + linkedSessionState //
									+ ", linkedSession.isReadyToInvalidate=" + linkedSessionIsReadyToInvalidate //
					);

				} catch (Exception ex1) {
					sipLogger.warning("AsyncSipServlet.doRequest - Exception #ex1");
					sipLogger.severe(request, ex1);
				}
			}

			// Check for GLARE
			switch (method) {
			case BYE:
			case CANCEL:
			case ACK:
				Callflow.setGlareState(sipSession, GlareState.ALLOW);
				break;
			case REFER:
				// do not allow more refers until we get a 200 OK (NOTIFY)
				Callflow.setGlareState(sipSession, GlareState.PROTECT);
				break;
			default:
				switch (Callflow.getGlareState(sipSession)) {
				case PROTECT:
					sipLogger.warning(request, "AsyncSipServlet.doRequest - glare, sending 491 response");
					sendResponse(request.createResponse(491));
					return; // -- RETURN, STOP PROCESSING
				case QUEUE:
					sipLogger.warning(request, "AsyncSipServlet.doRequest - glare, adding to queue");
					glareQueue.add(request);
					sipSession.setAttribute(GLARE_QUEUE, glareQueue);
					return; // -- RETURN, STOP PROCESSING
				default:
					Callflow.setGlareState(sipSession, GlareState.PROTECT);
				}
			}

			if (request.getMethod().equals("INVITE") && request.isInitial()) {

				// attempt to keep track of who called whom
				request.getSession().setAttribute("userAgent", "caller");
				request.getSession().setAttribute("_diagramLeft", Boolean.TRUE);

				String user = ((SipURI) request.getTo().getURI()).getUser();
				if (user != null) {
					request.getSession().setAttribute("_DID", user);
				}

				String ani = ((SipURI) request.getFrom().getURI()).getUser();
				if (ani != null) {
					request.getSession().setAttribute("_ANI", ani);
				}

				// save keep-alive information
				Parameterable sessionExpires = request.getParameterableHeader("Session-Expires");
				if (sessionExpires != null) {
					sipSession.setAttribute("Session-Expires", sessionExpires);
					String minSE = request.getHeader("Min-SE");
					if (minSE != null) {
						sipSession.setAttribute("Min-SE", minSE);
					}
					sipLogger.finer(request,
							"AsyncSipServlet.doRequest - Saving sessionExpires=" + sessionExpires + ", minSE=" + minSE);
				}

			}

			// ignore reINVITEs, INFO messages, etc if this is a proxy request
			if (!isProxy(request)) {

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

							if (linkedSession != null && request.isInitial()
									&& Callflow.getSessionParameters() != null) {

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
											"AsyncSipServlet.doRequest - Destination session attributes: " + attrMap);
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

							sipLogger.warning(request,
									"AsyncSipServlet.doRequest - Unknown error, sending 500 response");
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

			// Anything in the Queue? Use recursion
			if (false == glareQueue.isEmpty()) {
				SipServletRequest glareRequest = glareQueue.removeFirst();
				sipSession.setAttribute(GLARE_QUEUE, glareQueue);
				sipLogger.warning(glareRequest, "AsyncSipServlet.doRequest - processing request in glare queue");
				doRequest(glareRequest);
			}

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

	/// Processes incoming SIP responses. This method handles:
	/// <ul>
	/// <li>Detection of responses arriving after call cancellation</li>
	/// <li>Callback invocation for pending response handlers</li>
	/// <li>Early dialog session merging when To-tag changes</li>
	/// <li>Glare queue processing after response handling</li>
	/// <li>Proxy response logging and session invalidation for loose routing</li>
	/// <li>Error recovery including upstream error notification and downstream call
	/// termination</li>
	/// </ul>
	///
	/// @param response the incoming SIP response
	/// @throws ServletException if a servlet error occurs during processing
	/// @throws IOException      if an I/O error occurs during processing
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

					String linkedDialog = null;
					boolean linkedSessionIsValid = false;
					String linkedSessionState = "unknown";
					boolean linkedSessionIsReadyToInvalidate = false;
					if (linkedSession != null && linkedSession.isValid()) {
						linkedDialog = Callflow.getVorpalDialogId(linkedSession);
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
									+ ", linkedSession.dialog=" + linkedDialog //
									+ ", linkedSession.isValid=" + linkedSessionIsValid //
									+ ", linkedSession.state=" + linkedSessionState //
									+ ", linkedSession.isReadyToInvalidate=" + linkedSessionIsReadyToInvalidate //
					);

				} catch (Exception ex1) {
					sipLogger.warning("AsyncSipServlet.doReponse - Exception #ex1");
					sipLogger.severe(response, ex1);
				}
			}

			// For GLARE
			if (method.equals(INVITE)) {
				if (Callflow.failure(response)) {
					Callflow.setGlareState(sipSession, GlareState.ALLOW);
				}
			} else {
				if (false == Callflow.provisional(response)) {
					Callflow.setGlareState(sipSession, GlareState.ALLOW);
				}
			}

			// Check for the possibility that an INVITE response comes back *after* the call
			// has been canceled
			if (method.equals("INVITE") && Callflow.successful(response) && linkedSession != null && //
					(!linkedSession.isValid() || linkedSession.getState().equals(SipSession.State.TERMINATED))) {
				sipLogger.warning(response,
						"AsyncSipServlet.doResponse - Linked session terminated (CANCEL?), but an INVITE response came through anyway. Killing the session with CallflowAckBye");
				CallflowAckBye ackAndBye = new CallflowAckBye();
				try {
					ackAndBye.process(response);
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
										sipLogger.finer(response, "AsyncSipServlet.doResponse - Number of SipSessions: "
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
													// Callflow.linkSessions(linkedSession, sipSession);
													Callflow.linkSession(linkedSession, sipSession);
													Callflow.linkSession(sipSession, linkedSession);

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

										boolean leftDiagram = Boolean.TRUE
												.equals(response.getSession().getAttribute("leftDiagram"));

										Callflow.getLogger().superArrow(Direction.RECEIVE, leftDiagram, null, response,
												"proxy", null);

										Callflow.getLogger().superArrow(Direction.SEND, !leftDiagram, null, response,
												"proxy", null);

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
					sipLogger.severe(response, "AsyncSipServlet.doResponse - Exception #ex3");
					sipLogger.severe(response,
							"AsyncSipServlet.doResponse - Exception on SIP response: \n" + response.toString());
					sipLogger.severe(response, ex3);
					sipLogger.getParent()
							.severe(initialSipServletContextEvent.getServletContext().getServletContextName() + " "
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
//				LinkedList<SipServletRequest> glareQueue = (LinkedList<SipServletRequest>) sipSession
//						.getAttribute(GLARE_QUEUE);
//				processGlareQueue(sipSession, glareQueue);

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

	/// Handles timer expiration events. Retrieves and invokes the callback function
	/// stored in the timer's info object when the timer was created.
	///
	/// @param timer the expired servlet timer containing the callback in its info
	///              object
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
			sipLogger.getParent().severe(
					initialSipServletContextEvent.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}

	/// Returns the global SIP logger instance used for message and event logging
	/// 
	/// @return the SIP logger instance
	final public static Logger getSipLogger() {
		return sipLogger;
	}

	/// Sets the global SIP logger instance used for message and event logging
	/// 
	/// @param _sipLogger the SIP logger instance to set
	final public static void setSipLogger(Logger _sipLogger) {
		sipLogger = _sipLogger;
	}

	/// Returns the SIP factory used for creating SIP messages, addresses, and URIs
	/// 
	/// @return the SIP factory instance
	final public static SipFactory getSipFactory() {
		return sipFactory;
	}

	/// Sets the SIP factory used for creating SIP messages, addresses, and URIs
	/// 
	/// @param _sipFactory the SIP factory instance to set
	final public static void setSipFactory(SipFactory _sipFactory) {
		sipFactory = _sipFactory;
	}

	/// Returns the SIP sessions utility for looking up application sessions by key
	/// 
	/// @return the SIP sessions utility instance
	final public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	/// Sets the SIP sessions utility for looking up application sessions by key
	/// 
	/// @param _sipUtil the SIP sessions utility instance to set
	final public static void setSipUtil(SipSessionsUtil _sipUtil) {
		sipUtil = _sipUtil;
	}

	/// Returns the timer service used for creating and managing servlet timers
	/// 
	/// @return the timer service instance
	final public static TimerService getTimerService() {
		return timerService;
	}

	/**
	 * Converts a byte array to an alphanumeric string representation. Uses a
	 * 62-character alphabet (0-9, a-z, A-Z) for compact encoding.
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

	/// Computes an MD5 hash of the input string and returns it as an alphanumeric string
	/// 
	/// Note: MD5 is used here for hash distribution, not cryptographic security.
	/// This method is useful for generating unique keys for SIP application sessions.
	/// 
	/// @param stringToHash the string to hash
	/// @return the hashed string in alphanumeric format, or null if hashing fails
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

	/// Gets or creates a SIP application session using a hashed key derived from the input string
	/// 
	/// This method detects and logs hash collisions when different input strings produce
	/// the same hash. Useful in `@SipApplicationKey` methods for correlating related SIP sessions.
	/// 
	/// @param stringToHash the string to hash for generating the session key
	/// @return the hashed string used as the application session key
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
			sipLogger.severe("@SipApplicationKey hash key collision. SipApplicationSession.id: " + appSession.getId()
					+ " collides with " + existingHashKey + " and " + hashedString);
			appSession.setAttribute(HASHKEY_COLLISION, true);
		}

		return hashedString;
	}

	/// Extracts the account name from a SIP address in the format "user@host"
	/// 
	/// @param address the SIP address to extract the account name from
	/// @return the account name in lowercase (user@host format)
	public static String getAccountName(Address address) {
		return getAccountName(address.getURI());
	}

	/// Extracts the account name from a SIP URI in the format "user@host"
	/// 
	/// @param uri the SIP URI to extract the account name from
	/// @return the account name in lowercase (user@host format)
	public static String getAccountName(URI uri) {
		SipURI sipUri = (SipURI) uri;
		return (sipUri.getUser() + "@" + sipUri.getHost()).toLowerCase();
	}

//	/**
//	 * Processes queued requests that were delayed due to glare conditions. Glare
//	 * occurs when both endpoints send requests simultaneously, requiring one side
//	 * to queue its request until the other completes.
//	 *
//	 * @param sipSession the SIP session containing the glare queue
//	 * @param glareQueue the queue of pending requests to process
//	 */
//	private void processGlareQueue(SipSession sipSession, LinkedList<SipServletRequest> glareQueue) {
//		if (glareQueue == null || glareQueue.isEmpty()) {
//			return;
//		}
//
//		boolean expectAck = Boolean.TRUE.equals(sipSession.getAttribute(EXPECT_ACK));
//		if (expectAck) {
//			return;
//		}
//
//		SipServletRequest glareRequest = null;
//		try {
//			glareRequest = glareQueue.removeFirst();
//			sipSession.setAttribute(GLARE_QUEUE, glareQueue);
//			this.doRequest(glareRequest);
//		} catch (Exception e) {
//			sipLogger.warning("AsyncSipServlet.processGlareQueue - Exception processing glare request");
//
//			SipSession glareSession = glareRequest.getSession();
//			if (glareSession != null && glareSession.isValid()) {
//				String error = initialSipServletContextEvent.getServletContext().getServletContextName() + " "
//						+ e.getClass().getSimpleName() + ", " + e.getMessage();
//				sipLogger.severe(glareRequest,
//						"AsyncSipServlet.processGlareQueue - Exception attempting to process GLARE request: \n"
//								+ glareRequest.toString());
//				sipLogger.severe(glareRequest, e);
//				sipLogger.getParent().severe(error);
//				try {
//					SipServletResponse glareResponse = glareRequest.createResponse(491); // Request Pending
//					glareResponse.setHeader("Retry-After", "3");
//					sendResponse(glareResponse);
//				} catch (Exception responseException) {
//					sipLogger.severe(glareRequest, "Failed to send 491 response for glare request");
//					sipLogger.severe(glareRequest, responseException);
//				}
//			}
//		}
//	}

	/// Sends a SIP response with centralized logging and error handling
	/// 
	/// This method provides a central point for response transmission with automatic
	/// logging and consistent error handling across the application.
	/// 
	/// @param response the SIP response to send
	/// @throws ServletException if a servlet error occurs while sending
	/// @throws IOException if an I/O error occurs while sending
	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		Callflow.getLogger().superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
		try {
			response.send();
		} catch (Exception ex1) {
			sipLogger.warning("AsyncSipServlet.sendReponse - Exception #ex1");
			sipLogger.severe(response, "Exception on SIP response: \n" + response.toString());
			sipLogger.severe(response, ex1);
			sipLogger.getParent().severe(
					initialSipServletContextEvent.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}

	/// Determines if the SIP message is being handled in proxy mode
	/// 
	/// Returns true if `proxyRequest` was previously invoked on this application session.
	/// 
	/// @param msg the SIP message to check
	/// @return true if the message is in proxy mode, false otherwise
	public static boolean isProxy(SipServletMessage msg) {
		return Boolean.TRUE.equals(msg.getApplicationSession().getAttribute("isProxy"));
	}

	/// Returns the session parameters configuration used for session attribute extraction
	/// 
	/// @return the current session parameters, or null if not configured
	public static SessionParameters getSessionParameters() {
		return sessionParameters;
	}

	/// Sets the session parameters configuration used for session attribute extraction
	/// 
	/// @param _sessionParameters the session parameters to set
	public static void setSessionParameters(SessionParameters _sessionParameters) {
		sessionParameters = _sessionParameters;
	}

	/// Converts a camelCase string to regular words separated by spaces
	/// 
	/// For example, "myVariableName" becomes "My Variable Name". Used for generating
	/// human-readable error messages from exception class names.
	/// 
	/// @param camelCaseString the camelCase string to convert
	/// @return the string with spaces inserted before uppercase letters and first letter capitalized
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

	/// Retrieves the analytics event associated with a SIP message
	/// 
	/// @param message the SIP message to get the event from
	/// @return the associated analytics event, or null if none exists
	public static Event getEvent(SipServletMessage message) {
		return (Event) message.getAttribute("event");
	}

	/// Retrieves the analytics event associated with a servlet context event
	/// 
	/// @param contextEvent the context event to get the event from
	/// @return the associated analytics event, or null if none exists
	public static Event getEvent(SipServletContextEvent contextEvent) {
		return (Event) contextEvent.getServletContext().getAttribute("event");
	}

	/// Associates an analytics event with a SIP message
	/// 
	/// @param event the analytics event to associate
	/// @param message the SIP message to associate the event with
	public static void setEvent(Event event, SipServletMessage message) {
		message.setAttribute("event", event);
	}

	/// Associates an analytics event with a servlet context event
	/// 
	/// @param event the analytics event to associate
	/// @param contextEvent the context event to associate the event with
	public static void setEvent(Event event, SipServletContextEvent contextEvent) {
		contextEvent.getServletContext().setAttribute("event", event);
	}

	/// Extracts the Vorpal session ID from a SIP message using either X-Vorpal-ID or X-Vorpal-Session headers
	/// 
	/// Tries the newer X-Vorpal-ID parameterable format first, then falls back to the
	/// legacy X-Vorpal-Session colon-separated format for backwards compatibility.
	/// 
	/// @param msg the SIP message to extract the session ID from
	/// @return the Vorpal session ID, or null if not found
	public static String getVorpalSessionIdFromMessage(SipServletMessage msg) {
		String vorpalSessionId = null;

		try {
			// Try X-Vorpal-ID (new parameterable format) first
			Parameterable xVorpalId = msg.getParameterableHeader(Callflow.X_VORPAL_ID);
			if (xVorpalId != null) {
				vorpalSessionId = xVorpalId.getValue();
			}
		} catch (Exception ex) {
			sipLogger.severe(msg, ex);
		}

		// Fall back to X-Vorpal-Session (old colon format)
		if (vorpalSessionId == null) {
			try {
				String session = msg.getHeader(Callflow.X_VORPAL_SESSION);
				if (session != null) {
					int colonIndex = session.indexOf(':');
					vorpalSessionId = (colonIndex >= 0) ? session.substring(0, colonIndex) : session;
				}
			} catch (Exception ex) {
				sipLogger.severe(msg, ex);
			}
		}

		return vorpalSessionId;
	}

	/// Extracts the Vorpal dialog ID from a SIP message using either X-Vorpal-ID or X-Vorpal-Session headers
	/// 
	/// Tries the newer X-Vorpal-ID parameterable format first, then falls back to the
	/// legacy X-Vorpal-Session colon-separated format for backwards compatibility.
	/// 
	/// @param msg the SIP message to extract the dialog ID from
	/// @return the Vorpal dialog ID, or null if not found
	public static String getVorpalDialogIdFromMessage(SipServletMessage msg) {
		String dialog = null;

		try {
			// Try X-Vorpal-ID (new parameterable format) first
			Parameterable xVorpalId = msg.getParameterableHeader(Callflow.X_VORPAL_ID);
			if (xVorpalId != null) {
				dialog = xVorpalId.getParameter(Callflow.DIALOG_PARAM);
			}
		} catch (Exception ex) {
			sipLogger.severe(msg, ex);
		}

		// Fall back to X-Vorpal-Session (old colon format)
		if (dialog == null) {
			try {
				String session = msg.getHeader(Callflow.X_VORPAL_SESSION);
				if (session != null) {
					int colonIndex = session.indexOf(':');
					if (colonIndex >= 0) {
						dialog = session.substring(colonIndex + 1);
					}
				}
			} catch (Exception ex) {
				sipLogger.severe(msg, ex);
			}
		}

		return dialog;
	}

	/// Extracts the Vorpal timestamp from a SIP message using either X-Vorpal-ID or X-Vorpal-Timestamp headers
	/// 
	/// Tries the newer X-Vorpal-ID parameterable format first, then falls back to the
	/// legacy X-Vorpal-Timestamp header for backwards compatibility.
	/// 
	/// @param msg the SIP message to extract the timestamp from
	/// @return the Vorpal timestamp, or null if not found
	public static String getVorpalTimestampFromMessage(SipServletMessage msg) {
		String timestamp = null;

		try {
			// Try X-Vorpal-ID (new parameterable format) first
			Parameterable xVorpalId = msg.getParameterableHeader(Callflow.X_VORPAL_ID);
			if (xVorpalId != null) {
				timestamp = xVorpalId.getParameter(Callflow.TIMESTAMP_PARAM);
			}
		} catch (Exception ex) {
			sipLogger.severe(msg, ex);
		}

		// Fall back to X-Vorpal-Timestamp header
		if (timestamp == null) {
			try {
				timestamp = msg.getHeader(Callflow.X_VORPAL_TIMESTAMP);
			} catch (Exception ex) {
				sipLogger.severe(msg, ex);
			}
		}

		return timestamp;
	}

}