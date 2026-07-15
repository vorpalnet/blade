package org.vorpal.blade.framework.v3;

import java.io.IOException;
import java.util.LinkedList;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;

import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.callflow.Callflow.GlareState;
import org.vorpal.blade.framework.v2.callflow.CallflowAckBye;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;

/// The v3 `AsyncSipServlet` — the frozen v2 servlet plus **trace eventing at
/// the sequence-diagram spots**. `doRequest`/`doResponse`/`sendResponse` are
/// COPIES of the v2 bodies (calling the v2 helpers, widened to protected) with
/// [Callflow#traceEvent] beside each `superArrow` call — the places where the
/// message, direction, and handling callflow are all in scope, under the SAS
/// lock. The only diff vs v2 is the trace lines (and this doc); keep the copies
/// in sync with v2 manually until the shared pieces are hoisted into a v1
/// superclass.
///
/// A servlet opts in with a one-line base-class swap (same pattern as the
/// callflow v3 migration); everything else is inherited unchanged. A disarmed
/// call costs one boolean read per trace event.
public abstract class AsyncSipServlet extends org.vorpal.blade.framework.v2.AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	/// Copied from v2 AsyncSipServlet.doRequest (AsyncSipServlet.java:415–570)
	/// — sync manually until the v1 hoist. Diffs vs v2: the traceEvent lines
	/// beside each superArrow, and Callflow-qualified method-name constants
	/// (v2's private copies stay private).
	@SuppressWarnings("unchecked")
	@Override
	protected void doRequest(SipServletRequest request) throws ServletException, IOException {

		if (request == null) {
			return; // nothing to do
		}

		try {
			SipSession sipSession = request.getSession();

// UDP makes queuing necessary, sigh!
			LinkedList<SipServletRequest> glareQueue = (LinkedList<SipServletRequest>) sipSession
					.getAttribute(GLARE_QUEUE);
			if (glareQueue == null) {
				glareQueue = new LinkedList<>();
			}

			SipApplicationSession appSession = request.getApplicationSession();
			String method = request.getMethod();
			SipSession linkedSession = org.vorpal.blade.framework.v2.callflow.Callflow
					.getLinkedSession(request.getSession());

			// passively track endpoint UPDATE support for the keep-alive style
			captureAllowHeader(request, sipSession);

			// get the Vorpal ID first thing (skip short-lived, fire-and-forget methods)
			if (request.isInitial()) {
				switch (method) {
				case "OPTIONS":
				case "INFO":
				case "MESSAGE":
					break;
				default:
					String indexKey = org.vorpal.blade.framework.v2.callflow.Callflow.getVorpalSessionId(request);
					if (indexKey != null) {
						appSession.addIndexKey(indexKey);
					}
					break;
				}
			}

			// Log useful variables for debugging purposes
			logRequestDiagnostics(request, sipSession, linkedSession);

			// Check for GLARE
			switch (method) {
			case Callflow.BYE:
			case Callflow.CANCEL:
			case Callflow.ACK:
				Callflow.setGlareState(sipSession, GlareState.ALLOW);
				break;
			case Callflow.REFER:
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

			if (method.equals(Callflow.INVITE) && request.isInitial()) {
				saveCallerInfo(request, sipSession);
			}

			// ignore reINVITEs, INFO messages, etc if this is a proxy request
			if (!isProxy(request)) {

				try {
					Callback<SipServletRequest> requestLambda = Callflow.pullCallback(request);
					if (requestLambda != null) {

						Callflow.getLogger().superArrow(Direction.RECEIVE, request, null,
								requestLambda.getClass().getSimpleName());
						Callflow.traceEvent(Direction.RECEIVE, request, null, requestLambda, "received", null);

						requestLambda.accept(request);

					} else {

						org.vorpal.blade.framework.v2.callflow.Callflow callflow = chooseCallflow(request);

						if (callflow == null) {

							Callflow.getLogger().superArrow(Direction.RECEIVE, request, null, "null");
							Callflow.traceEvent(Direction.RECEIVE, request, null,
									this.getClass().getName(), "received", null);
							if (!method.equals(Callflow.ACK)) {
								SipServletResponse response = request.createResponse(501);
								Callflow.getLogger().superArrow(Direction.SEND, null, response, "null");
								Callflow.traceEvent(Direction.SEND, null, response,
										this.getClass().getName(), "doRequest", null);
								Callflow.getLogger().warning(
										"AsyncSipServlet.doRequest - No registered callflow for request method "
												+ method
												+ ", consider overriding the 'chooseCallflow' method in your SipServlet class.");
								response.send();
							}

						} else {
							sipLogger.superArrow(Direction.RECEIVE, request, null, callflow.getClass().getSimpleName());
							Callflow.traceEvent(Direction.RECEIVE, request, null, callflow, "process", null);

							// Apply session.expiration (default 60 min) on inbound initial
							// requests too. Callflow.sendRequest covers B2BUA/UAC dialogs
							// (idempotent — highest value wins), but a pure-UAS app that
							// answers locally never sends an initial request, and its SAS
							// would otherwise sit at the container default and expire under
							// the peer's eventual BYE. Not applied on the proxy branch.
							Callflow.applySessionExpiration(request, appSession);

							// create any index keys defined by selectors in the config file
							if (request.isInitial() && Callflow.getSessionParameters() != null) {
								applyOriginSelectors(request, appSession, sipSession);
							}

							logCallflowDispatch(request, callflow, sipSession, appSession);

							try {
								callflow.process(request);
							} catch (Exception ex2) {
								sipLogger.warning(request, "AsyncSipServlet.doRequest - Exception #ex2");
								throw new ServletException(ex2);
							}

							if (linkedSession != null && request.isInitial()
									&& Callflow.getSessionParameters() != null) {
								applyDestinationSelectors(request, sipSession, linkedSession);
							}

						}
					}

				} catch (Exception ex3) {
					recoverFromRequestError(request, ex3);
				}

			} else { // isProxy, for logging purposes only
				boolean diagramLeft = true;
				Callflow.getLogger().superArrow(Direction.RECEIVE, diagramLeft, request, null, "proxy", null);
				Callflow.traceEvent(Direction.RECEIVE, request, null, "proxy", "received", null);
				Callflow.getLogger().superArrow(Direction.SEND, !diagramLeft, request, null, "proxy", null);
				Callflow.traceEvent(Direction.SEND, request, null, "proxy", "send", null);
			}

			// Anything in the Queue? Use recursion
			if (!glareQueue.isEmpty()) {
				SipServletRequest glareRequest = glareQueue.removeFirst();
				sipSession.setAttribute(GLARE_QUEUE, glareQueue);
				sipLogger.warning(glareRequest, "AsyncSipServlet.doRequest - processing request in glare queue");
				doRequest(glareRequest);
			}

		} catch (Exception ex6) { // this should never happen, but if it does...
			if (sipLogger != null) {
				sipLogger.warning("AsyncSipServlet.doRequest - Exception #ex6");
				sipLogger.severe(request, ex6);
				sipLogger.getParent().severe(ex6.getClass().getName() + " " + ex6.getMessage());
			} else {
				ex6.printStackTrace();
			}
		}

	}

	/// Copied from v2 AsyncSipServlet.doResponse (AsyncSipServlet.java:861–1003)
	/// — sync manually until the v1 hoist. Diffs vs v2: the traceEvent lines
	/// beside each superArrow, and Callflow-qualified constants.
	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		try {
			boolean isProxy = isProxy(response);
			Callback<SipServletResponse> callback;
			SipSession sipSession = response.getSession();
			SipApplicationSession appSession = response.getApplicationSession();
			String method = response.getMethod();
			SipSession linkedSession = org.vorpal.blade.framework.v2.callflow.Callflow
					.getLinkedSession(response.getSession());

			// passively track endpoint UPDATE support for the keep-alive style
			captureAllowHeader(response, sipSession);

			logResponseDiagnostics(response, isProxy, sipSession, linkedSession);

			// For GLARE
			if (method.equals(Callflow.INVITE)) {
				// Release glare on any non-2xx final (3xx redirect or 4xx+ failure).
				// 2xx is intentionally excluded — the ACK arriving in doRequest releases
				// glare in that branch. The container auto-ACKs 3xx/4xx/5xx/6xx so the
				// request branch never sees one for those.
				if (Callflow.redirection(response) || Callflow.failure(response)) {
					Callflow.setGlareState(sipSession, GlareState.ALLOW);
				}
			} else {
				if (false == Callflow.provisional(response)) {
					Callflow.setGlareState(sipSession, GlareState.ALLOW);
				}
			}

			// Check for the possibility that an INVITE response comes back *after* the call
			// has been canceled
			if (method.equals(Callflow.INVITE) && Callflow.successful(response) && linkedSession != null && //
					(!linkedSession.isValid() || linkedSession.getState().equals(SipSession.State.TERMINATED))) {
				sipLogger.warning(response,
						"AsyncSipServlet.doResponse - Linked session terminated (CANCEL?), but an INVITE response came through anyway. Killing the session with CallflowAckBye");
				CallflowAckBye ackAndBye = new CallflowAckBye();
				Callflow.traceEvent(Direction.RECEIVE, null, response,
						CallflowAckBye.class.getName(), "process", null);
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
							} else if (response.getMethod().equals(Callflow.INVITE)) {
								// Sometimes a 180 Ringing comes back on a brand new SipSession
								// because the tag on the To header changed due to a failure downstream.
								callback = mergeEarlyDialogSession(response, sipSession, appSession, linkedSession);
							} else {
								// (An isProxy(response) re-check here was provably dead: isProxy
								// was false to reach this branch and nothing changes the attribute
								// in between — removed.)
								Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
										this.getClass().getSimpleName());
								Callflow.traceEvent(Direction.RECEIVE, null, response,
										this.getClass().getName(), "received", null);
							}
						}

						if (callback != null && !isProxy) {

							// Auto-follow 3xx redirects on outbound INVITEs so the developer
							// doesn't have to. Non-INVITE 3xx and proxy mode bypass naturally.
							// Failure (no Contact, send error, etc.) falls through to the
							// existing callback dispatch — the developer always gets exactly
							// one final-response callback.
							if (method.equals(Callflow.INVITE) && Callflow.redirection(response)) {
								Integer prior = (Integer) appSession.getAttribute(AUTO_REDIRECT_COUNT);
								int count = (prior == null ? 0 : prior) + 1;
								appSession.setAttribute(AUTO_REDIRECT_COUNT, count);

								if (shouldFollowRedirect(response, count)) {
									try {
										followRedirect(response, callback);
										return; // -- RETURN, response handled by auto-follow
									} catch (Exception followEx) {
										sipLogger.warning(response,
												"AsyncSipServlet.doResponse - auto-follow failed at attempt " + count
														+ ", delivering 3xx to callback: " + followEx.getMessage());
										// fall through to existing dispatch
									}
								}
							}

							Callflow.getLogger().superArrow(Direction.RECEIVE, null, response,
									callback.getClass().getSimpleName());
							Callflow.traceEvent(Direction.RECEIVE, null, response, callback, "received", null);
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
					recoverFromResponseError(response, ex3);
				}

			} else { // isProxy

				// For logging purposes
				boolean diagramLeft = false;
				Callflow.getLogger().superArrow(Direction.RECEIVE, diagramLeft, null, response, "proxy", null);
				Callflow.traceEvent(Direction.RECEIVE, null, response, "proxy", "received", null);
				Callflow.getLogger().superArrow(Direction.SEND, !diagramLeft, null, response, "proxy", null);
				Callflow.traceEvent(Direction.SEND, null, response, "proxy", "send", null);

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
			if (sipLogger != null) {
				sipLogger.warning(response, "AsyncSipServlet.doResponse - Exception #ex7");
				sipLogger.severe(response, ex7);
				sipLogger.getParent().severe(ex7.getClass().getName() + " " + ex7.getMessage());
			} else {
				ex7.printStackTrace();
			}
		}

	}

	/// Copied from v2 AsyncSipServlet.sendResponse (AsyncSipServlet.java:1438–1449)
	/// — sync manually until the v1 hoist. Diff vs v2: the traceEvent beside the
	/// superArrow (server-generated sends: glare 491, errors).
	@Override
	public void sendResponse(SipServletResponse response) throws ServletException, IOException {
		Callflow.getLogger().superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
		Callflow.traceEvent(Direction.SEND, null, response, this.getClass().getName(), "sendResponse", null);
		try {
			response.send();
		} catch (Exception ex1) {
			sipLogger.warning("AsyncSipServlet.sendResponse - Exception #ex1");
			sipLogger.severe(response, "Exception on SIP response: \n" + response.toString());
			sipLogger.severe(response, ex1);
			sipLogger.getParent().severe(
					initialSipServletContextEvent.getServletContext().getServletContextName() + " " + ex1.getMessage());
		}
	}
}
