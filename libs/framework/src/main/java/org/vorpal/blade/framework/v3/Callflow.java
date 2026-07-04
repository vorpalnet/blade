package org.vorpal.blade.framework.v3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.callflow.Callback;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v2.testing.DummyResponse;
import org.vorpal.blade.framework.v3.configuration.routing.LooseRoutingHelper;
import org.vorpal.blade.framework.v3.diagnostics.Diagnostics;
import org.vorpal.blade.framework.v3.diagnostics.TraceLog;

/// The v3 Callflow — the same lambda-continuation model as v2, plus two things
/// v2 can't carry: per-step **call tracing** (source-line pins + raw SIP
/// messages, coming and going), and config-driven **proxy drop-out** (passthru).
///
/// ## Tracing — one spine, at the sequence-diagram spots
///
/// Trace events fire at the EXACT spots v2 draws its ASCII sequence-diagram
/// arrows (`Logger.superArrow` call sites) — the places where the message, the
/// direction, and the handling callflow are all in scope, under the SAS lock.
/// v2 stays frozen: the v3 [AsyncSipServlet]/[B2buaServlet] carry copies of the
/// v2 dispatch bodies (receive side), and this class carries copies of the v2
/// `send*` bodies (send side), each with [#traceEvent] beside the arrow. The
/// copied bodies call the v2 helpers (widened to protected) — the only diff vs
/// v2 is the trace lines. Shared pieces get hoisted into a v1 superclass later.
///
/// When a call is armed ([#enableTrace]), every event records a [CallStep]:
/// sends pin the exact source line that emitted the message ([#captureStep]);
/// receives pin the handling callflow's class. All steps carry the raw message
/// text and the `X-Vorpal-Session` id, so steps captured by different apps in a
/// routed chain stitch into one end-to-end timeline. Off by default; a disarmed
/// call costs one boolean read per event. Arming is programmatic
/// ([#enableTrace]) or rule-driven via the app's [TraceLog] rules (armed over
/// JMX from the Callflow Viewer), consulted once per session; recorded steps
/// are also published to the [TraceLog] ring buffer the viewer reads.
///
/// ## Passthru (proxy drop-out)
///
/// When `session.passthru` is set (off by default), a forwarding callflow — an
/// initial INVITE in, an initial INVITE out — behaves like a proxy that leaves
/// the dialog after setup: the two endpoints' Contacts are stitched together and
/// OCCAS is removed from the route set, so the ACK and every in-dialog message
/// flow directly between caller and callee. The SAME callflow runs as a full
/// B2BUA when passthru is false — the deciding vote is config, so an operator
/// picks B2BUA vs. proxy per network. Deduced entirely from the initial INVITE
/// (via [SipServletRequest#isInitial]); in-dialog sends are never touched.
///
/// Because no ACK or BYE ever reaches us after drop-out, OCCAS won't
/// auto-invalidate — so we tear down both legs and the app session ourselves.
public abstract class Callflow extends org.vorpal.blade.framework.v2.callflow.Callflow {
	private static final long serialVersionUID = 1L;

	// INVITE is inherited (public) from v2.Callflow — do not redeclare it, or it
	// shadows the inherited one and every subclass that uses INVITE breaks.
	private static final String CONTACT = "Contact";

	/// Every construction records the concrete class in this app's
	/// [org.vorpal.blade.framework.v3.source.CallflowRegistry] — how the
	/// Callflow Viewer knows which of the bundled framework callflows this app
	/// actually uses. Deserialization (cluster failover) bypasses this, which
	/// is fine: one construction per class per JVM is all the registry needs.
	protected Callflow() {
		try {
			org.vorpal.blade.framework.v3.source.CallflowRegistry.record(getClass());
		} catch (Throwable ignore) {
			// registration is best-effort; construction must never fail for it
		}
	}

	// ================================================================= tracing

	/// `SipApplicationSession` attribute (Boolean) — is this call armed for tracing?
	public static final String TRACE_ARMED_ATTR = "org.vorpal.blade.v3.callflow.traceArmed";

	/// `SipApplicationSession` attribute holding the ordered `List<CallStep>`.
	public static final String TRACE_ATTR = "org.vorpal.blade.v3.callflow.trace";

	/// `SipApplicationSession` attribute (Boolean) — has the arming decision
	/// been made for this session? Set on the session's first trace event, so
	/// the [TraceLog] rules are consulted exactly once per session.
	public static final String TRACE_DECIDED_ATTR = "org.vorpal.blade.v3.callflow.traceDecided";

	/// Cap on recorded SIP message text. A step is a debugging artifact, not a
	/// media store — an oversized body (huge SDP, ISUP blob) is truncated so an
	/// armed ring buffer stays memory-bounded.
	static final int MESSAGE_MAX_CHARS = 16384;

	public static void enableTrace(SipApplicationSession appSession) {
		if (appSession != null) {
			appSession.setAttribute(TRACE_ARMED_ATTR, Boolean.TRUE);
		}
	}

	public static void disableTrace(SipApplicationSession appSession) {
		if (appSession != null) {
			appSession.removeAttribute(TRACE_ARMED_ATTR);
		}
	}

	public static boolean isTraceEnabled(SipApplicationSession appSession) {
		return appSession != null && Boolean.TRUE.equals(appSession.getAttribute(TRACE_ARMED_ATTR));
	}

	// =============================================================== passthru

	/// `SipApplicationSession` attribute (Boolean) — has this call engaged proxy
	/// drop-out? Set on the initial-INVITE forward, read when the 2xx goes back.
	/// On the app session (not a leg) because it spans both legs.
	public static final String PASSTHRU_ARMED_ATTR = "org.vorpal.blade.v3.passthru.armed";

	private static boolean passthruEnabled() {
		SessionParameters p = getSessionParameters();
		return p != null && p.isPassthru();
	}

	private static boolean isInitialInvite(SipServletRequest request) {
		return request != null && request.isInitial() && INVITE.equalsIgnoreCase(request.getMethod());
	}

	private static boolean isPassthruArmed(SipApplicationSession app) {
		return app != null && Boolean.TRUE.equals(app.getAttribute(PASSTHRU_ARMED_ATTR));
	}

	// ================================================================ send API

	/// Copied from v2 Callflow.sendRequest (Callflow.java:889–969) — sync
	/// manually until the v1 hoist. Diffs vs v2: the passthru arm at the top,
	/// and the [#traceEvent] beside the superArrow (fires AFTER the send, so the
	/// recorded message carries every header v2 stamped on the way out).
	@SuppressWarnings("serial")
	@Override
	public void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambdaFunction)
			throws ServletException, IOException {

		if (request == null) {
			return;
		}

		if (passthruEnabled() && isInitialInvite(request)) {
			armPassthru(request);
		}

		try {
			SipApplicationSession appSession = request.getApplicationSession();
			SipSession sipSession = request.getSession();

			if (sipSession != null && sipSession.isValid()) {

				// For GLARE
				switch (request.getMethod()) {
				case INVITE:
					if (request.isInitial()) {
						stampVorpalIdHeaders(request, appSession, sipSession);
					}
					setGlareState(sipSession, GlareState.PROTECT);
					break;

				case REFER:
					setGlareState(sipSession, GlareState.PROTECT);
					break;

				case ACK:
				case CANCEL:
					setGlareState(sipSession, GlareState.ALLOW);
					break;

				default:
					// leave glare state as-is
				}

				try {
					applySessionExpiration(request, appSession);
					applyKeepAlive(request, appSession);
				} catch (Exception exk) {
					sipLogger.severe(request,
							"Callflow.sendRequest - Unable to set keep alive: " + exk.getMessage());
				}

				if (lambdaFunction != null) {
					request.getSession().setAttribute(RESPONSE_CALLBACK_ + request.getMethod(), lambdaFunction);
				}

				// useful for identifying sessions
				if (request.getTo() != null) {
					sipSession.setAttribute(SIP_ADDRESS_ATTR, request.getTo());
				}

				// Useful for associating SIP with HTTP
				Analytics.sipServletRequest.set(request);

				request.send();
				sipLogger.superArrow(Direction.SEND, request, null, this.getClass().getSimpleName());
				traceEvent(Direction.SEND, request, null, null, null, this);

			}

		} catch (Exception ex300) {
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

	/// Copied from v2 Callflow.sendResponse (Callflow.java:1356–1432) — sync
	/// manually until the v1 hoist. Diffs vs v2: the passthru drop-out
	/// stitch/invalidate wrapping, and a [#traceEvent] beside each superArrow
	/// (BEFORE the send, like the arrows — a final response can invalidate the
	/// session, killing the trace attributes). The reliable-provisional branch
	/// gets a traceEvent too, even though v2 draws no arrow there — arguably a
	/// v2 diagram gap; the trace should not share it.
	@Override
	public void sendResponse(SipServletResponse response, Callback<SipServletRequest> lambdaFunction)
			throws ServletException, IOException {

		if (response == null) {
			return;
		}

		boolean dropOut = INVITE.equalsIgnoreCase(response.getMethod())
				&& successful(response) && isPassthruArmed(response.getApplicationSession());
		if (dropOut) {
			passthruStitchAndRoute(response);   // must happen BEFORE the 2xx is sent
		}

		SipApplicationSession appSession = response.getApplicationSession();
		SipSession sipSession = response.getSession();
		String method = response.getMethod();
		int status = response.getStatus();

		if (false == Boolean.TRUE.equals((Boolean) response.getAttribute(WITHHOLD_RESPONSE))) {

			// Glare logic
			switch (method) {
			case REFER:
				// leave in PROTECT
				break;
			case INVITE:
				// Keep the Vorpal tracking headers on responses too — useful for
				// downstream services tracing a call back to its entry point.
				if (response.getRequest().isInitial()) {
					stampVorpalIdHeaders(response, appSession, sipSession);
				}

				// Glare handling
				if (status != 491) {
					if (successful(response)) {
						// jwm - unnecessary
						setGlareState(sipSession, GlareState.QUEUE);
					} else if (failure(response)) {
						setGlareState(sipSession, GlareState.ALLOW);
					}
				}

				break;
			default:
				// Clear GLARE if final response
				if (false == (provisional(response) || status == 491)) {
					setGlareState(sipSession, GlareState.ALLOW);
				}

			}

			if (lambdaFunction != null) {
				response.getSession().setAttribute(REQUEST_CALLBACK_ + ACK, lambdaFunction);
			}

			if (provisional(response)) {

				if (response.isReliableProvisional() || null != response.getAttribute(RELIABLE)) {

					if (response.getSession().isValid() && lambdaFunction != null) {
						response.getSession().setAttribute(REQUEST_CALLBACK_ + PRACK, lambdaFunction);
						traceEvent(Direction.SEND, null, response, null, null, this);
						response.sendReliably();
					}

				} else {
					// Log before send: a final response invalidates the session,
					// after which superArrow's hexHash → getGlareState →
					// session.getAttribute path throws "Invalid attribute store!"
					// and the diagram + FINEST raw-message dump are lost.
					// AsyncSipServlet.sendResponse (this same module, ~line 1346)
					// already follows this order — keep them consistent.
					sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
					traceEvent(Direction.SEND, null, response, null, null, this);
					response.send();

				}

			} else {
				// Same reasoning as above — log before send so the final-response
				// case (3xx/4xx/5xx/6xx on initial INVITEs) doesn't lose its log
				// entry to a post-send "Invalid attribute store!".
				sipLogger.superArrow(Direction.SEND, null, response, this.getClass().getSimpleName());
				traceEvent(Direction.SEND, null, response, null, null, this);
				response.send();

			}
		}

		if (dropOut) {
			passthruInvalidate(response);        // must happen AFTER the 2xx is sent
		}

	}

	// ------------------------------------------------------------ passthru impl

	/// Arm drop-out for this call and do the first half of the symmetric Contact
	/// stitch: put the CALLER's Contact on the outbound INVITE, so the callee
	/// sends its in-dialog requests straight to the caller.
	private void armPassthru(SipServletRequest outboundInvite) {
		try {
			SipApplicationSession app = outboundInvite.getApplicationSession();
			if (app != null) {
				app.setAttribute(PASSTHRU_ARMED_ATTR, Boolean.TRUE);
			}
			Address callerContact = peerContact(outboundInvite);
			if (callerContact != null) {
				outboundInvite.setAddressHeader(CONTACT, callerContact);
			}
		} catch (Exception ignore) {
			// passthru is best-effort at the framework layer; never break the send
		}
	}

	/// Second half of the stitch and the route-set drop-out, done just before the
	/// 2xx is sent: put the CALLEE's Contact on the answer (so the caller's ACK
	/// goes straight to the callee), and pull OCCAS out of the route set (works
	/// even after the proxy has started — reflection via [LooseRoutingHelper]).
	private void passthruStitchAndRoute(SipServletResponse toCaller) {
		try {
			Address calleeContact = peerContact(toCaller);
			if (calleeContact != null) {
				toCaller.setAddressHeader(CONTACT, calleeContact);
			}
			SipServletRequest inbound = toCaller.getRequest();
			if (inbound != null) {
				LooseRoutingHelper.disableRecordRoute(inbound);
			}
		} catch (Exception ignore) {
		}
	}

	/// Tear down after the 2xx is on the wire. No ACK or BYE will ever reach us —
	/// the dialog is caller↔callee now — so OCCAS won't auto-invalidate; we
	/// invalidate the callee leg, the caller leg, and the app session ourselves.
	/// (Jeff: this is also what we're counting on to settle the caller-leg INVITE
	/// transaction so OCCAS stops retransmitting the 200 — to be confirmed live.)
	private void passthruInvalidate(SipServletResponse toCaller) {
		try {
			SipSession caller = toCaller.getSession();
			SipSession callee = getLinkedSession(caller);
			SipApplicationSession app = toCaller.getApplicationSession();
			invalidate(callee);
			invalidate(caller);
			if (app != null && app.isValid()) {
				app.invalidate();
			}
		} catch (Exception ignore) {
		}
	}

	private static void invalidate(SipSession ss) {
		try {
			if (ss != null && ss.isValid()) {
				ss.invalidate();
			}
		} catch (Exception ignore) {
		}
	}

	/// The peer endpoint's Contact — the address the OTHER UA should be reached at
	/// for in-dialog messages, which is what the symmetric stitch writes.
	///
	/// Derived from the LINKED leg's remote target: once a leg's dialog is up,
	/// OCCAS stores the peer's Contact as that session's remote target (the URI it
	/// uses for in-dialog requests). So the peer leg's remote target IS the peer's
	/// Contact — read via [LooseRoutingHelper#remoteTarget] (public
	/// `SipSessionImpl.getRemoteTarget()`, unwrapped by reflection). Null safely
	/// degrades to record-route-only drop-out. NEEDS LIVE VERIFICATION — the
	/// remote target must already be populated at the moment of each stitch (the
	/// caller leg's at the outbound-INVITE send; the callee leg's at the 2xx).
	private Address peerContact(javax.servlet.sip.SipServletMessage message) {
		try {
			if (message == null || sipFactory == null) {
				return null;
			}
			SipSession peer = getLinkedSession(message.getSession());
			if (peer == null) {
				return null;
			}
			javax.servlet.sip.URI target = LooseRoutingHelper.remoteTarget(peer);
			return target != null ? sipFactory.createAddress(target) : null;
		} catch (Exception e) {
			return null;
		}
	}

	// ================================================================= capture

	/// Decide ONCE per app session whether the [TraceLog] arming rules match
	/// this call, and arm the session if so. The hot path — no rules armed —
	/// costs a single boolean read; the decision itself runs only on a
	/// session's first trace event (guarded by [#TRACE_DECIDED_ATTR]).
	/// `request` is the initial/current request the Selectors match against
	/// (for a response event, the request it answers).
	private static void maybeArm(SipServletRequest request, SipApplicationSession appSession) {
		try {
			Diagnostics diagnostics = TraceLog.diagnostics();
			if (!diagnostics.isEnabled() || appSession == null) {
				return;
			}
			if (Boolean.TRUE.equals(appSession.getAttribute(TRACE_DECIDED_ATTR))) {
				return;
			}
			appSession.setAttribute(TRACE_DECIDED_ATTR, Boolean.TRUE);
			if (!isTraceEnabled(appSession) && request != null && diagnostics.armFor(request) != null) {
				enableTrace(appSession);
			}
		} catch (Exception ignore) {
			// arming is best-effort; it must never affect call handling
		}
	}

	/// THE trace recording spine — called beside every sequence-diagram arrow
	/// (the copied v2 bodies in this class and in the v3 [AsyncSipServlet] /
	/// [B2buaServlet]). Exactly one caller per SIP message per app, so there is
	/// nothing to dedupe.
	///
	/// - `direction` RECEIVE records an `in` step pinned to the handling code.
	///   `handler` is either a String FQN (the chosen callflow at dispatch, the
	///   servlet, or "proxy") used with `methodHint` (`process` for dispatched
	///   callflows), or the callback OBJECT — introspected via [#lambdaTarget]
	///   to its owner class + enclosing method (e.g.
	///   `InitialInvite.processContinue`). The viewer highlights the resolved
	///   method's declaration.
	/// - `direction` SEND with a non-null `sender` pins the exact source line
	///   that emitted the message ([#captureStep]); a null sender (servlet-level
	///   sends: glare 491, 501, server-generated) pins the servlet class.
	///
	/// Arming is decided here too ([#maybeArm]) — the receive of the initial
	/// request is the first event a call ever fires. The session id keeps the
	/// header-aware fallback: at an app's first dispatch the `VORPAL_SESSION`
	/// attribute isn't populated yet, but the inbound X-Vorpal-ID header names
	/// the call; `getVorpalSessionId(request)` resolves exactly like v2 does
	/// moments later (minting only at the true chain head).
	static void traceEvent(Direction direction, SipServletRequest request, SipServletResponse response,
			Object handler, String methodHint, Callflow sender) {
		try {
			javax.servlet.sip.SipServletMessage message = response != null ? response : request;
			if (message == null) {
				return;
			}
			boolean isResponse = response != null;
			SipServletRequest armingRequest = isResponse ? response.getRequest() : request;
			SipApplicationSession appSession = message.getApplicationSession();
			maybeArm(armingRequest, appSession);
			if (!isTraceEnabled(appSession)) {
				return;
			}
			// Resolve the handler pin only on armed calls. A String is used as-is
			// (servlet FQN / "proxy"); a dispatched callflow object pins the class
			// that DECLARES `process` (a thin subclass like TransferInitialInvite
			// inherits it — the declaring class is the file worth highlighting);
			// a callback lambda is introspected ([#lambdaTarget]: owner class +
			// enclosing method, e.g. `InitialInvite.processContinue`).
			String handlerClass;
			if (handler == null || handler instanceof String) {
				handlerClass = handler != null ? (String) handler : "?";
			} else if (handler instanceof org.vorpal.blade.framework.v2.callflow.Callflow) {
				handlerClass = processDeclarer(handler.getClass()).getName();
			} else {
				String[] target = lambdaTarget(handler);
				if (target != null) {
					handlerClass = target[0];
					methodHint = target[1];
				} else {
					handlerClass = handlerName(handler, "?");
				}
			}
			String sessionId = safeSessionId(appSession);
			if (sessionId.isEmpty() && armingRequest != null) {
				try {
					String resolved = getVorpalSessionId(armingRequest);
					if (resolved != null) {
						sessionId = resolved;
					}
				} catch (Exception ignore) {
					// resolver needs container services; record without an id
				}
			}
			String kind = isResponse ? "response" : "request";
			String label = isResponse ? String.valueOf(response.getStatus()) : request.getMethod();
			String text = messageText(message);
			List<CallStep> trace = traceList(appSession);
			CallStep step;
			if (Direction.SEND.equals(direction) && sender != null) {
				step = sender.captureStep(sessionId, trace.size() + 1, kind, label, text);
			} else {
				boolean in = Direction.RECEIVE.equals(direction);
				step = new CallStep(sessionId, System.currentTimeMillis(), trace.size() + 1,
						in ? CallStep.IN : CallStep.OUT, kind, label,
						handlerClass != null ? handlerClass : "?",
						methodHint != null ? methodHint : (in ? "received" : "send"), -1, text);
			}
			store(appSession, trace, step);
		} catch (Exception ignore) {
			// tracing is best-effort; it must never affect call handling
		}
	}

	/// The handler's class FQN for a receive event. A dispatched callflow gives
	/// its concrete class; a callback lambda's synthetic class name
	/// (`pkg.Owner$$Lambda…`) is trimmed to the owning class; null falls back to
	/// the receiver (servlet) class.
	static String handlerName(Object handler, String fallback) {
		if (handler == null) {
			return fallback;
		}
		String name = handler.getClass().getName();
		int lambda = name.indexOf("$$Lambda");
		return lambda > 0 ? name.substring(0, lambda) : name;
	}

	/// A serializable lambda (every [Callback] is one — that's how continuations
	/// ride the session over failover) reveals its target via `writeReplace` →
	/// `SerializedLambda`: the declaring class and the synthetic method name
	/// (`lambda$processContinue$…`), from which the ENCLOSING method falls out —
	/// so a received response pins `InitialInvite.processContinue` and the viewer
	/// highlights that declaration. Returns `{ownerFqn, methodName}` or null
	/// (method refs return the referenced method's own name). Armed calls only —
	/// the caller gates on [#isTraceEnabled] first.
	/// The class in a callflow's hierarchy that DECLARES `process(request)` —
	/// the source file the viewer should show for a dispatched receive. A thin
	/// subclass (`TransferInitialInvite`) inherits it from its base
	/// (`InitialInvite`); highlighting the empty shell would mark nothing.
	private static Class<?> processDeclarer(Class<?> concrete) {
		for (Class<?> c = concrete; c != null; c = c.getSuperclass()) {
			try {
				c.getDeclaredMethod("process", SipServletRequest.class);
				return c;
			} catch (NoSuchMethodException none) {
				// keep climbing
			}
		}
		return concrete;
	}

	private static String[] lambdaTarget(Object callback) {
		try {
			java.lang.reflect.Method writeReplace = callback.getClass().getDeclaredMethod("writeReplace");
			writeReplace.setAccessible(true);
			Object replaced = writeReplace.invoke(callback);
			if (replaced instanceof java.lang.invoke.SerializedLambda) {
				java.lang.invoke.SerializedLambda sl = (java.lang.invoke.SerializedLambda) replaced;
				String owner = sl.getImplClass().replace('/', '.');
				String impl = sl.getImplMethodName();
				String method = impl;
				if (impl.startsWith("lambda$")) {
					int from = "lambda$".length();
					int end = impl.indexOf('$', from);
					method = end > from ? impl.substring(from, end) : impl;
				}
				return new String[] { owner, method };
			}
		} catch (Throwable ignore) {
			// introspection is best-effort; fall back to the class-name trim
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static List<CallStep> traceList(SipApplicationSession appSession) {
		List<CallStep> trace = (List<CallStep>) appSession.getAttribute(TRACE_ATTR);
		return trace != null ? trace : new ArrayList<>();
	}

	private static void store(SipApplicationSession appSession, List<CallStep> trace, CallStep step) {
		trace.add(step);
		appSession.setAttribute(TRACE_ATTR, trace);
		// publish to the per-app ring buffer the Trace MBean serves, so the
		// viewer can read this node's steps without touching the session
		TraceLog.append(step);
	}

	/// The raw SIP message as the container renders it (start line, headers,
	/// body), truncated at [#MESSAGE_MAX_CHARS]. Null if rendering fails.
	static String messageText(javax.servlet.sip.SipServletMessage message) {
		try {
			String text = String.valueOf(message);
			if (text.length() > MESSAGE_MAX_CHARS) {
				text = text.substring(0, MESSAGE_MAX_CHARS) + "\n… [truncated]";
			}
			return text;
		} catch (Exception e) {
			return null;
		}
	}

	private static String safeSessionId(SipApplicationSession appSession) {
		try {
			String id = getVorpalSessionId(appSession);
			return id != null ? id : "";
		} catch (Exception e) {
			return "";
		}
	}

	/// Build an outbound [CallStep] pinning the SOURCE LINE of the callflow code
	/// that invoked this send. Walks the current stack for the first frame whose
	/// class is this callflow's concrete class OR any ancestor up to (excluding)
	/// the v3/v2 Callflow plumbing — a subclass like `TransferInitialInvite`
	/// usually sends from code INHERITED from `InitialInvite`, and the frame
	/// carries the base class's name. The step records the FRAME's class, so the
	/// line number always aligns with the source file the viewer shows.
	/// Package-visible so [CallStepSmokeTest] can exercise it without a SIP
	/// container.
	CallStep captureStep(String sessionId, int order, String kind, String label, String message) {
		long now = System.currentTimeMillis();
		java.util.HashSet<String> owners = new java.util.HashSet<>();
		for (Class<?> c = this.getClass(); c != null
				&& c != Callflow.class
				&& c != org.vorpal.blade.framework.v2.callflow.Callflow.class; c = c.getSuperclass()) {
			owners.add(c.getName());
		}
		for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
			if (owners.contains(e.getClassName())) {
				return new CallStep(sessionId, now, order, CallStep.OUT, kind, label, e.getClassName(),
						e.getMethodName(), e.getLineNumber(), message);
			}
		}
		return new CallStep(sessionId, now, order, CallStep.OUT, kind, label, this.getClass().getName(),
				"?", -1, message);
	}

	/// The ordered trace captured for this app's part of a call, or an empty list.
	public static List<CallStep> getTrace(SipApplicationSession appSession) {
		if (appSession == null) {
			return Collections.emptyList();
		}
		@SuppressWarnings("unchecked")
		List<CallStep> trace = (List<CallStep>) appSession.getAttribute(TRACE_ATTR);
		return trace == null ? Collections.emptyList() : Collections.unmodifiableList(trace);
	}
}
