package org.vorpal.blade.framework.v3;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.sip.DetachedResponse;
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
/// When `session.passthru` is set (off by default), a forwarding callflow's
/// initial INVITE goes out as a **proxy** instead of a new dialog. The
/// caller's INVITE is proxied, without Record-Route, to the Request-URIs of
/// the requests the callflow built, so one dialog runs from caller to callee
/// and the ACK, re-INVITEs and BYE never come back here. The SAME callflow
/// runs as a full B2BUA when passthru is false: the deciding vote is config,
/// so an operator picks B2BUA or proxy per network.
///
/// **Why a proxy.** A B2BUA cannot leave a call. Its two legs are two dialogs
/// with their own Call-IDs and tags, and the container writes its own host and
/// port into the Contact of every 2xx a dialog's end sends, whatever the
/// application set; the caller's ACK and BYE always come back. An earlier
/// version copied each end's Contact onto the other and never worked (the
/// servlet API refuses both Contacts). Only a proxy that does not
/// record-route is out of the path by construction.
///
/// **Where it happens.** [#sendRequest], [#sendRequestsInSerial] and
/// [#sendRequestsInParallel], in their v3 forms and the baseline signatures
/// alike, hand an initial INVITE to [#proxyPassthru]; anything else is sent
/// as before. The outbound requests are built exactly as for a B2BUA (that is
/// what keeps one callflow for both modes); each becomes a proxy branch with
/// its Request-URI and request attributes, and is never sent itself. Headers
/// the callflow added to an outbound request do not travel: a proxy forwards
/// the caller's INVITE. If the caller's INVITE cannot be found (the request is
/// not linked to it), the requests go out as a B2BUA, logged.
///
/// **What the callbacks get.** The same as a B2BUA's, through the v3
/// [AsyncSipServlet] response hook: the per-branch observer sees every
/// response of every branch, provisional and final, once each; the
/// completion callback gets the 2xx, or the failure the caller would get.
///
/// **What a callflow must not do.** Answer or relay to the caller while
/// [#isPassthru]. The container forwards the callees' responses itself, and a
/// response the application creates for a proxied request joins the proxy as
/// a branch of its own; one it sends before proxying (an early 180) leaves the
/// caller's session a user agent, which brings the Contact rewrite back.
///
/// **Serial, parallel, and tiers.** A serial hunt is a sequential proxy: each
/// branch has `milliseconds`, any failure moves on, and a failure the
/// `advance` rule does not accept cancels the rest, so the caller gets that
/// failure. A parallel batch is a parallel proxy: the container cancels the
/// other branches on a 2xx and picks the failure the caller gets when all fail
/// (RFC 3261 section 16.7). A next batch (a next tier) started from inside the
/// completion callback joins the same proxy before the caller hears the
/// failure. It must start there, on that thread: once the callback returns
/// with nothing new started, the failure goes to the caller and the proxy is
/// finished. So a lookup that cannot finish inside the callback needs the
/// B2BUA mode. Nothing can follow a 2xx, a 6xx or the caller's CANCEL. A
/// proxy may try each destination once, so a later batch skips the ones an
/// earlier batch tried; one left with nothing new fails at once with a 408.
///
/// **Timeouts.** The container times a parallel proxy once, as a whole, so a
/// later batch would get only what the first left. Each parallel batch is
/// timed here instead (see [#proxyPassthru]); when its time runs out its
/// branches are cancelled and, unless a definite failure has come in, the
/// completion callback gets a 408 at once, so a next batch can start while
/// the old branches finish.
///
/// **What the caller sees differs from the B2BUA** where proxy rules say so:
/// every 2xx is forwarded (two callees answering together means two answers,
/// and the caller ACKs and ends the second, RFC 3261 section 13.2.2.4), every
/// branch's early media reaches the caller, a 503 the caller would get goes up
/// as a 500 (the container does this), and the caller's CANCEL is the
/// container's to handle.
///
/// **After setup** nothing of the call reaches the application, so a callflow
/// that needs to stay in the dialog (hold, transfer, recording) must not run
/// with passthru. The application session ends when the proxy's transactions
/// do.
public abstract class Callflow extends org.vorpal.blade.framework.Callflow {
	private static final long serialVersionUID = 1L;

	// INVITE is inherited (public) from v2.Callflow — do not redeclare it, or it
	// shadows the inherited one and every subclass that uses INVITE breaks.
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

	/// `SipApplicationSession` attribute (Boolean) — is this call armed for
	/// tracing?
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

	/// Is `session.passthru` set? While it is, an initial INVITE goes out as a
	/// proxy (see the class notes), and the callflow leaves answering the
	/// caller to the container.
	protected static boolean isPassthru() {
		return passthruEnabled();
	}

	private static boolean passthruEnabled() {
		SessionParameters p = getSessionParameters();
		return p != null && p.isPassthru();
	}

	private static boolean isInitialInvite(SipServletRequest request) {
		return request != null && request.isInitial() && INVITE.equalsIgnoreCase(request.getMethod());
	}


	/// Clones an inbound request into an outbound one on the peer dialog, keeping its
	/// destination. Shorthand for `createRequest(null, inboundRequest)` — see that
	/// method for what is copied and what is refused.
	///
	/// @param inboundRequest the request to clone
	/// @return the outbound request, ready to send
	/// @throws ServletParseException if a header fails to parse, or the method is
	/// one this cannot build
	/// @throws IOException if reading the body fails
	public static SipServletRequest createRequest(SipServletRequest inboundRequest)
			throws ServletParseException, IOException {
		return createRequest(null, inboundRequest);
	}

	/// Clones an inbound request into an outbound one on the peer dialog — the single
	/// request builder in v3, and the replacement for the family of builders v2
	/// once carried.
	///
	/// It picks its path from the state of the call:
	///
	/// - **The dialogs are already linked** — the request is created on the peer's
	/// `SipSession`, which makes it in-dialog: a re-INVITE, BYE, INFO, and so on.
	/// `uri` is **ignored**, because an established dialog can only be addressed
	/// at its remote target.
	/// - **No linked dialog yet** — the initial case. The request is built from the
	/// `SipFactory` carrying the inbound From and To, its routing directive set
	/// to `CONTINUE`, and its request URI taken from `uri` — merged with the
	/// inbound request URI by [#copyParameters], so the user part and any URI
	/// parameters survive — or from the inbound request URI when `uri` is null.
	///
	/// Either way the body and the non-system headers are copied over, which also
	/// links the two sessions for INVITE and ACK.
	///
	/// ## What it will not build
	///
	/// ACK, CANCEL, and PRACK are refused. Each has to be derived from the message
	/// it answers rather than created fresh. The container enforces two of those
	/// itself — `SipSession.createRequest` and `SipFactory.createRequest` both
	/// throw
	/// `IllegalArgumentException` on ACK and CANCEL — and BLADE refuses PRACK as
	/// well, so that a PRACK always carries the `RAck` of the reliable response it
	/// acknowledges. Reach for these instead:
	///
	/// - ACK and PRACK — [org.vorpal.blade.framework.Callflow#sendAcknowledgement]
	/// or [org.vorpal.blade.framework.Callflow#createAcknowledgement], which match
	/// whichever acknowledgement arrived upstream
	/// - CANCEL — `createCancel()` on the INVITE being cancelled, which
	/// `session.getActiveInvite(UAMode.UAC)` will hand you
	///
	/// @param uri where to send it, or null to reuse the inbound request
	/// URI; ignored once the dialogs are linked
	/// @param inboundRequest the request to clone
	/// @return the outbound request, ready to send
	/// @throws ServletParseException if a header fails to parse, if the method is
	/// ACK, CANCEL, or PRACK, or if the peer dialog has
	/// already terminated
	/// @throws IOException if reading the body fails
	public static SipServletRequest createRequest(URI uri, SipServletRequest inboundRequest)
			throws ServletParseException, IOException {
		SipServletRequest outboundRequest;

		String method = inboundRequest.getMethod();
		if (ACK.equalsIgnoreCase(method) || CANCEL.equalsIgnoreCase(method) || PRACK.equalsIgnoreCase(method)) {
			throw new ServletParseException("Cannot create a " + method + " with createRequest - an acknowledgement"
					+ " or a cancellation must be derived from the message it answers. Use"
					+ " sendAcknowledgement/createAcknowledgement for ACK and PRACK, or createCancel() on the"
					+ " INVITE being cancelled.");
		}

		try {

			SipSession outboundSession = getLinkedSession(inboundRequest.getSession());
			if (outboundSession != null) {
				outboundRequest = outboundSession.createRequest(inboundRequest.getMethod());
			} else {
				outboundRequest = sipFactory.createRequest( //
						inboundRequest.getApplicationSession(), //
						inboundRequest.getMethod(), //
						inboundRequest.getFrom(), //
						inboundRequest.getTo());
				outboundRequest.setRequestURI(uri != null ? copyParameters(inboundRequest.getRequestURI(), uri)
						: inboundRequest.getRequestURI());
				outboundRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, inboundRequest);
			}

			copyContentAndHeaders(inboundRequest, outboundRequest);

		} catch (RuntimeException ex) {
			throw new ServletParseException(ex);
		}

		return outboundRequest;
	}

	// ================================================================ send API

	/// Copied from v2 Callflow.sendRequest (Callflow.java:889–969) — sync
	/// manually until the v1 hoist. Diffs vs v2: the passthru hand-off at the
	/// top (an initial INVITE becomes a one-branch proxy, see the class notes;
	/// `lambdaFunction` then gets the branch's responses as it would the
	/// dialog's), and the [#traceEvent] beside the superArrow (fires AFTER the
	/// send, so the recorded message carries every header v2 stamped on the way
	/// out).
	@SuppressWarnings("serial")
	@Override
	public void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambdaFunction)
			throws ServletException, IOException {

		if (request == null) {
			return;
		}

		if (passthruEnabled() && isInitialInvite(request)
				&& proxyPassthru(new java.util.ArrayList<>(java.util.Collections.singletonList(request)), true, 0,
						lambdaFunction, lambdaFunction, null)) {
			return;
		}

		try {
			SipApplicationSession appSession = request.getApplicationSession();
			SipSession sipSession = request.getSession();

			if (sipSession != null && sipSession.isValid()) {

				// For GLARE
				switch (request.getMethod()) {
				case UPDATE:
				case INVITE:
					// One transaction at a time per dialog, the rule doRequest applies to what arrives,
					// applied to what we send. For INVITEs it is RFC 3261 section 14.1: a UAC "MUST NOT
					// initiate a new INVITE transaction within a dialog while another INVITE transaction
					// is in progress in either direction". An UPDATE carries an offer the same way, and
					// RFC 3311 section 5.2 has the far end refuse one that crosses ours, so it is guarded
					// and protected alike. Sending anyway would let the next ACK or final response clear
					// protection while this request is still open. The callback gets the 491 the peer
					// would owe it and handles it as a peer's: a B2BUA relays it, a refresh waits.
					if (getGlareState(sipSession) != GlareState.ALLOW) {
						sipLogger.warning(request,
								"Callflow.sendRequest - transaction in progress on this dialog, "
										+ request.getMethod() + " not sent, 491 returned");
						if (lambdaFunction != null) {
							lambdaFunction.accept(requestPending(request));
						}
						return;
					}
					if (request.isInitial()) {
						stampVorpalIdHeaders(request, appSession, sipSession);
					}
					setGlareState(sipSession, GlareState.PROTECT);
					break;

				case REFER:
					setGlareState(sipSession, GlareState.PROTECT);
					// the transfer lasts until its final NOTIFY, past this transaction; see isReferPending
					setReferPending(sipSession, true);
					break;

				case NOTIFY:
					// our final NOTIFY ends the transfer we accepted
					if (endsRefer(request)) {
						setReferPending(sipSession, false);
					}
					break;

				case ACK:
				case CANCEL:
					setGlareState(sipSession, GlareState.ALLOW);
					break;

				case BYE:
					// our BYE ends the dialog: answer what is parked rather than replay it later
					rejectGlareQueue(sipSession);
					break;

				default:
					// leave glare state as-is
				}

				try {
					applySessionExpiration(request, appSession);
					applyKeepAlive(request, appSession);
				} catch (Exception exk) {
					sipLogger.severe(request, "Callflow.sendRequest - Unable to set keep alive: " + exk.getMessage());
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
			// The server is unreachable: no response will pass doResponse to say so.
			org.vorpal.blade.framework.v3.net.Legs.notSent(request, ex300);

			if (!request.getMethod().equals(ACK) && !request.getMethod().equals(PRACK)) {

				// It's too maddening to write callflows where you have to worry about both
				// error responses and exceptions. Let's create a dummy error response.
				SipServletResponse errorResponse = new DetachedResponse(request, RESPONSE_CODE_500,
						ex300.getClass().getSimpleName());
				errorResponse.setContent(ex300.getMessage(), "text/plain");

				if (lambdaFunction != null) {
					lambdaFunction.accept(errorResponse);
				}

			}

		}

	}

	/// Copied from v2 Callflow.sendResponse (Callflow.java:1356–1432) — sync
	/// manually until the v1 hoist. Diff vs v2: a [#traceEvent] beside each superArrow
	/// (BEFORE the send, like the arrows — a final response can invalidate the
	/// session, killing the trace attributes). The reliable-provisional branch
	/// gets a traceEvent too, even though v2 draws no arrow there — arguably a
	/// v2 diagram gap; the trace should not share it.
	@Override
	public void sendResponse(SipServletResponse response, Callback<SipServletRequest> lambdaFunction)
			throws ServletException, IOException {

		if (response == null || dropResponseToCancel(response)) {
			return;
		}

		SipApplicationSession appSession = response.getApplicationSession();
		SipSession sipSession = response.getSession();
		String method = response.getMethod();
		int status = response.getStatus();

		if (false == Boolean.TRUE.equals((Boolean) response.getAttribute(WITHHOLD_RESPONSE))) {

			// Glare logic
			switch (method) {
			case REFER:
				// A 2xx leaves PROTECT: the dialog clears when the transferor answers our first NOTIFY.
				// A refusal ends the transfer outright, since no subscription exists. A 491 refuses a
				// second REFER and leaves the first one's state alone.
				if (failure(response) && status != 491) {
					setGlareState(sipSession, GlareState.ALLOW);
					setReferPending(sipSession, false);
				}
				break;
			case PRACK:
				// A PRACK and its response live inside the INVITE transaction they acknowledge a
				// reliable provisional for (RFC 3262). They neither open nor close glare protection:
				// answering one while the INVITE is still open must not release the INVITE's guard.
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

		// A final response releases the dialog: what was parked behind the request goes next.
		replayParked(sipSession);

	}

	// ============================================================ serial hunt

	/// Decides whether a final response sends a serial hunt on to the next
	/// request. Serializable, because the hunt's callbacks are stored with the
	/// call and must survive failover with it.
	@FunctionalInterface
	public interface Advance extends java.io.Serializable {
		boolean test(SipServletResponse response);
	}

	/// Request attribute marking a hunt request abandoned by its timer, so its
	/// late final response (a 487 after our CANCEL) does not advance the hunt
	/// a second time.
	private static final String HUNT_ABANDONED = "org.vorpal.blade.v3.hunt.abandoned";

	/// Sends `requests` (INVITEs) one at a time, like the v2 serial hunt,
	/// except that `advance` decides which final responses move on to the
	/// next request. Any other final response, success or failure, ends the
	/// hunt and goes to `lambdaFunction`. The v2 hunt moves on for every 4xx
	/// and up, so a 404 or a 486 is asked of every endpoint in turn and
	/// answered the same way by each, multiplying the traffic of a call that
	/// was never going to complete.
	///
	/// A request with no final response before `milliseconds` is cancelled
	/// and the hunt moves on, as does one that cannot be sent. When the last
	/// request times out, `lambdaFunction` gets a 408 made from it.
	/// `dialogResponse` (may be null) sees every response every dialog
	/// produces, provisional and final, before the hunt acts on it. Emptying
	/// `requests` ends the hunt after the request in flight.
	///
	/// Under passthru an initial INVITE's hunt is a sequential proxy instead
	/// (see the class notes): the same callbacks, the same `advance` rule, but
	/// the container sends the caller the answer, and `requests` is emptied at
	/// once.
	///
	/// @param milliseconds   how long each request may go without a final response
	/// @param requests       the requests, in order; consumed as the hunt runs
	/// @param lambdaFunction the response that ended the hunt
	/// @param dialogResponse observer for every per-dialog response, or null
	/// @param advance        true for a final response that should try the next request
	public void sendRequestsInSerial(long milliseconds, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction, Callback<SipServletResponse> dialogResponse, Advance advance)
			throws ServletException, IOException {

		if (requests.isEmpty()) {
			sipLogger.warning("Callflow.sendRequestsInSerial - empty request list");
			return;
		}
		if (passthruEnabled() && isInitialInvite(requests.get(0)) && proxyPassthru(
				new java.util.ArrayList<>(requests), false, milliseconds, lambdaFunction, dialogResponse, advance)) {
			requests.clear();
			return;
		}

		SipServletRequest request = requests.remove(0);
		String timerId = startTimer(request.getApplicationSession(), milliseconds, false, (timeout) -> {
			request.setAttribute(HUNT_ABANDONED, Boolean.TRUE);
			if (!Boolean.TRUE.equals(request.getSession().getAttribute(ATTR_CANCEL_SENT))) {
				sendRequest(request.createCancel());
				request.getSession().setAttribute(ATTR_CANCEL_SENT, true);
			}
			huntNextOr408(milliseconds, requests, lambdaFunction, dialogResponse, advance, request);
		});

		try {
			sendRequest(request, (response) -> {
				if (dialogResponse != null) {
					dialogResponse.accept(response);
				}
				if (provisional(response)
						|| Boolean.TRUE.equals(response.getRequest().getAttribute(HUNT_ABANDONED))) {
					return;
				}
				stopTimer(response.getApplicationSession(), timerId);
				if (successful(response) || requests.isEmpty() || !advance.test(response)) {
					lambdaFunction.accept(response);
				} else {
					sendRequestsInSerial(milliseconds, requests, lambdaFunction, dialogResponse, advance);
				}
			});
		} catch (Exception e) {
			sipLogger.warning(request, "Callflow.sendRequestsInSerial - not sent: " + e.getMessage());
			stopTimer(request.getApplicationSession(), timerId);
			huntNextOr408(milliseconds, requests, lambdaFunction, dialogResponse, advance, request);
		}
	}

	/// The baseline serial hunt, except under passthru: an initial INVITE then
	/// goes out as a sequential proxy (see [#proxyPassthru]) that, like the
	/// baseline hunt, moves on after any failure.
	@Override
	public void sendRequestsInSerial(long milliseconds, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction, Callback<SipServletResponse> dialogResponse)
			throws ServletException, IOException {
		if (!requests.isEmpty() && passthruEnabled() && isInitialInvite(requests.get(0))) {
			sendRequestsInSerial(milliseconds, requests, lambdaFunction, dialogResponse, (response) -> true);
		} else {
			super.sendRequestsInSerial(milliseconds, requests, lambdaFunction, dialogResponse);
		}
	}

	private void huntNextOr408(long milliseconds, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction, Callback<SipServletResponse> dialogResponse, Advance advance,
			SipServletRequest last) throws ServletException, IOException {
		if (!requests.isEmpty()) {
			sendRequestsInSerial(milliseconds, requests, lambdaFunction, dialogResponse, advance);
		} else {
			lambdaFunction.accept(last.createResponse(408));
		}
	}

	// ============================================================ parallel race

	/// App-session attribute prefix for one race's state: the outstanding
	/// requests by session id, then `.over` once the race is decided and
	/// `.best` for the failure that would answer it.
	private static final String RACE = "org.vorpal.blade.v3.race.";
	private static final String OVER = ".over";
	private static final String BEST = ".best";

	/// Sends `requests` (INVITEs) all at once and gives `lambdaFunction` the one
	/// response that decides the call. Unlike the v2 race:
	///
	/// - **The first 2xx wins and every other branch is cancelled,** the winner
	///   excepted. A 2xx that arrives after that, because two branches answered
	///   together or one answered as our CANCEL reached it, is acknowledged and
	///   ended with a BYE, so that callee is not left holding an answered call
	///   with nobody on it.
	/// - **A 6xx ends the race at once** (RFC 3261 §16.7): the other branches are
	///   cancelled and the 6xx is the answer.
	/// - **When every branch fails, a definite failure outranks a retryable one.**
	///   `retryable` says which failures another destination might fix (a 503, a
	///   408); any other (a 404, a 486, a challenge) is the same everywhere, so
	///   the earliest of those answers the call. Only when every failure is
	///   retryable does the earliest retryable one. The v2 race answers with
	///   whichever failure happened to arrive last.
	/// - **At `timeout`** the branches still out are cancelled; the answer is the
	///   best definite failure so far, else a 408.
	///
	/// `dialogResponse` (may be null) sees every response every branch produces,
	/// provisional and final, before the race acts on it.
	///
	/// Under passthru an initial INVITE's race is a parallel proxy instead (see
	/// the class notes): the same callbacks and timeout, but every 2xx reaches
	/// the caller and the container picks the failure the caller gets;
	/// `retryable` then only decides whether a timeout hands `lambdaFunction` a
	/// 408 or waits for the definite failure already in.
	///
	/// @param timeout        milliseconds before unanswered branches are cancelled; 0 for none
	/// @param requests       the branches
	/// @param lambdaFunction the response that decides the call; called once
	/// @param dialogResponse observer for every per-branch response, or null
	/// @param retryable      true for a failure another destination might not repeat
	public void sendRequestsInParallel(long timeout, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction, Callback<SipServletResponse> dialogResponse,
			Advance retryable) throws ServletException, IOException {

		if (requests.isEmpty()) {
			sipLogger.warning("Callflow.sendRequestsInParallel - empty request list");
			return;
		}
		if (passthruEnabled() && isInitialInvite(requests.get(0)) && proxyPassthru(
				new java.util.ArrayList<>(requests), true, timeout, lambdaFunction, dialogResponse, retryable)) {
			return;
		}
		SipServletRequest firstRequest = requests.get(0);
		SipApplicationSession appSession = firstRequest.getApplicationSession();
		String id = RACE + Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt());

		java.util.HashMap<String, SipServletRequest> outstanding = new java.util.HashMap<>();
		for (SipServletRequest request : requests) {
			outstanding.put(request.getSession().getId(), request);
		}
		appSession.setAttribute(id, outstanding);

		final String timerId = (timeout > 0) ? startTimer(appSession, timeout, false, (timer) -> {
			if (Boolean.TRUE.equals(appSession.getAttribute(id + OVER))) {
				return;
			}
			appSession.setAttribute(id + OVER, Boolean.TRUE);
			cancelBranches(appSession, id, null);
			SipServletResponse best = (SipServletResponse) appSession.getAttribute(id + BEST);
			appSession.removeAttribute(id + BEST);
			lambdaFunction.accept(best != null && !retryable.test(best) ? best : firstRequest.createResponse(408));
		}) : null;

		for (SipServletRequest request : requests) {
			sendRequest(request, (response) -> {
				if (dialogResponse != null) {
					dialogResponse.accept(response);
				}
				if (provisional(response)) {
					return;
				}
				boolean over = Boolean.TRUE.equals(appSession.getAttribute(id + OVER));

				if (successful(response)) {
					if (over) {
						endExtraAnswer(response);
						return;
					}
					appSession.setAttribute(id + OVER, Boolean.TRUE);
					stopTimer(appSession, timerId);
					cancelBranches(appSession, id, response.getSession().getId());
					appSession.removeAttribute(id + BEST);
					lambdaFunction.accept(response);
					return;
				}

				if (over) {
					return; // a cancelled branch's 487, after the race
				}
				@SuppressWarnings("unchecked")
				java.util.Map<String, SipServletRequest> left = (java.util.Map<String, SipServletRequest>) appSession
						.getAttribute(id);
				if (left == null) {
					return;
				}
				left.remove(response.getSession().getId());

				if (response.getStatus() >= 600) {
					appSession.setAttribute(id + OVER, Boolean.TRUE);
					stopTimer(appSession, timerId);
					appSession.setAttribute(id, left);
					cancelBranches(appSession, id, null);
					appSession.removeAttribute(id + BEST);
					lambdaFunction.accept(response);
					return;
				}

				SipServletResponse best = (SipServletResponse) appSession.getAttribute(id + BEST);
				if (best == null || outranks(retryable.test(response), retryable.test(best))) {
					best = response;
					appSession.setAttribute(id + BEST, best);
				}
				if (left.isEmpty()) {
					appSession.setAttribute(id + OVER, Boolean.TRUE);
					stopTimer(appSession, timerId);
					appSession.removeAttribute(id);
					appSession.removeAttribute(id + BEST);
					lambdaFunction.accept(best);
				} else {
					appSession.setAttribute(id, left);
				}
			});
		}
	}

	/// The baseline race, except under passthru: an initial INVITE then goes out
	/// as a parallel proxy (see [#proxyPassthru]), where the container picks
	/// the failure the caller gets.
	@Override
	public void sendRequestsInParallel(long timeout, List<SipServletRequest> requests,
			Callback<SipServletResponse> lambdaFunction, Callback<SipServletResponse> dialogResponse)
			throws ServletException, IOException {
		if (!requests.isEmpty() && passthruEnabled() && isInitialInvite(requests.get(0))) {
			sendRequestsInParallel(timeout, requests, lambdaFunction, dialogResponse, (response) -> true);
		} else {
			super.sendRequestsInParallel(timeout, requests, lambdaFunction, dialogResponse);
		}
	}

	/// A failure replaces the best so far only when it is definite and the best
	/// is retryable; otherwise the earlier one stands.
	static boolean outranks(boolean candidateRetryable, boolean bestRetryable) {
		return !candidateRetryable && bestRetryable;
	}

	/// CANCELs every branch still outstanding in race `id`, except `keep`, and
	/// forgets them.
	private void cancelBranches(SipApplicationSession appSession, String id, String keep) {
		@SuppressWarnings("unchecked")
		java.util.Map<String, SipServletRequest> left = (java.util.Map<String, SipServletRequest>) appSession
				.getAttribute(id);
		appSession.removeAttribute(id);
		if (left == null) {
			return;
		}
		for (java.util.Map.Entry<String, SipServletRequest> branch : left.entrySet()) {
			if (branch.getKey().equals(keep)) {
				continue;
			}
			SipServletRequest request = branch.getValue();
			try {
				if (request.getSession().isValid()
						&& !Boolean.TRUE.equals(request.getSession().getAttribute(ATTR_CANCEL_SENT))) {
					sendRequest(request.createCancel());
					request.getSession().setAttribute(ATTR_CANCEL_SENT, true);
				}
			} catch (Exception e) {
				sipLogger.fine(request, "Callflow.cancelBranches - not cancelled: " + e.getMessage());
			}
		}
	}

	/// A 2xx from a branch that lost the race: acknowledge it, as every 2xx to
	/// an INVITE must be, then end that dialog.
	private void endExtraAnswer(SipServletResponse response) {
		try {
			sendRequest(response.createAck());
			sendRequest(response.getSession().createRequest(BYE));
		} catch (Exception e) {
			sipLogger.warning(response, "Callflow.endExtraAnswer - " + e.getMessage());
		}
	}

	// ------------------------------------------------------------ passthru impl

	/// App-session attribute holding the [PassthruProxy] of a call that went
	/// out as a proxy.
	static final String PASSTHRU_PROXY_ATTR = "org.vorpal.blade.v3.passthru.proxy";

	/// What a passthru proxy reports to, kept with the call so it survives
	/// failover like any other callback.
	static final class PassthruProxy implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		Callback<SipServletResponse> complete;
		Callback<SipServletResponse> observer;
		Advance advance;
		boolean parallel;
		boolean answered;
		/// The batch in flight (one per [#proxyPassthru] call), and the last
		/// batch `complete` has already been told about.
		int batch;
		int completedBatch;
		/// A failure no other branch would fix has arrived in this batch.
		boolean definite;
		String batchTimer;
		/// Every destination this call's proxy has tried: a proxy may try a
		/// destination only once.
		java.util.HashSet<String> tried = new java.util.HashSet<>();
		/// Finals already given to `observer`: the container reports a branch's
		/// final once as a branch response and may report it again as the best.
		java.util.HashSet<String> seen = new java.util.HashSet<>();
	}

	/// Sends an initial INVITE's `requests` as branches of a proxy on the
	/// caller's INVITE, without Record-Route, instead of as new dialogs. Each
	/// branch gets its request's Request-URI and attributes; the requests
	/// themselves are never sent, and their sessions are invalidated.
	///
	/// `parallel` forks to all branches at once, otherwise one after another
	/// (`timeoutMillis` then limits each branch; for a parallel batch, the
	/// batch). The container cancels the rest on a 2xx, sends every 2xx to
	/// the caller, and picks the failure the caller gets (RFC 3261 section
	/// 16.7). `observer` sees every response of every branch, provisional and
	/// final; `complete` gets the 2xx, or the failure the caller would get. A
	/// next tier started from inside `complete` is added to the same proxy, and
	/// the caller then hears from it instead. `advance`, for a sequential proxy,
	/// stops the search on a failure it does not accept; for a parallel one it
	/// says which failures are retryable.
	///
	/// A parallel batch with a `timeoutMillis` gets its own timer, and the
	/// proxy's own timeout is set out of reach (see [#passthruBatchTimeout]). A
	/// sequential proxy's timeout is per branch, so it is the container's.
	/// Called again for a next tier, it replaces the callbacks, stops the old
	/// batch's timer, and starts the new branches on the same proxy. A proxy
	/// may try a destination only once, so one already tried, or listed twice,
	/// is skipped; a batch left with nothing new hands `complete` a 408 at
	/// once, so a later tier still gets its turn.
	///
	/// @return false when there is no caller INVITE to proxy (the requests are
	///         then sent as a B2BUA, as without passthru)
	private boolean proxyPassthru(List<SipServletRequest> requests, boolean parallel, long timeoutMillis,
			Callback<SipServletResponse> complete, Callback<SipServletResponse> observer, Advance advance)
			throws ServletException, IOException {
		SipServletRequest first = requests.get(0);
		SipSession callerLeg = getLinkedSession(first.getSession());
		SipServletRequest inbound = (callerLeg != null) ? callerLeg.getActiveInvite(UAMode.UAS) : null;
		if (inbound == null) {
			sipLogger.warning(first, "Callflow.proxyPassthru - no caller INVITE to proxy; sending as a B2BUA");
			return false;
		}

		SipApplicationSession appSession = inbound.getApplicationSession();
		PassthruProxy state = (PassthruProxy) appSession.getAttribute(PASSTHRU_PROXY_ATTR);
		javax.servlet.sip.Proxy proxy = inbound.getProxy();
		if (state == null) {
			proxy.setRecordRoute(false);
			proxy.setSupervised(true);
			state = new PassthruProxy();
		}
		proxy.setParallel(parallel);
		if (parallel) {
			// The container times a parallel proxy as a whole, once: a batch started
			// after the first would get only what is left of its time. Each batch is
			// timed here instead, see the timer below.
			proxy.setProxyTimeout(PASSTHRU_NO_TIMEOUT_SECONDS);
		} else if (timeoutMillis > 0) {
			proxy.setProxyTimeout((int) Math.max(1, (timeoutMillis + 999) / 1000));
		}
		stopTimer(appSession, state.batchTimer);
		state.complete = complete;
		state.observer = observer;
		state.advance = advance;
		state.parallel = parallel;
		state.answered = false;
		state.definite = false;
		state.batchTimer = null;
		final int batch = ++state.batch;
		if (parallel && timeoutMillis > 0) {
			state.batchTimer = startTimer(appSession, timeoutMillis, false,
					(timer) -> passthruBatchTimeout(inbound, batch));
		}

		// A proxy refuses a destination it has tried before, or one listed twice:
		// skip those, so an endpoint in two tiers does not stop the second.
		List<URI> targets = new java.util.ArrayList<>();
		List<SipServletRequest> sending = new java.util.ArrayList<>();
		for (SipServletRequest request : requests) {
			if (state.tried.add(request.getRequestURI().toString())) {
				targets.add(request.getRequestURI());
				sending.add(request);
			} else {
				sipLogger.fine(request, "Callflow.proxyPassthru - already tried " + request.getRequestURI());
			}
		}
		for (SipServletRequest request : requests) {
			SipSession unsent = request.getSession();
			if (unsent != null && unsent.isValid()) {
				unsent.invalidate();
			}
		}
		if (targets.isEmpty()) {
			// nothing new to try: this batch fails at once, as if every branch timed out
			stopTimer(appSession, state.batchTimer);
			state.batchTimer = null;
			state.completedBatch = batch;
			appSession.setAttribute(PASSTHRU_PROXY_ATTR, state);
			appSession.setAttribute(IS_PROXY_ATTR, Boolean.TRUE);
			if (complete != null) {
				complete.accept(new DetachedResponse(inbound, 408));
			}
			return true;
		}
		List<javax.servlet.sip.ProxyBranch> branches = proxy.createProxyBranches(targets);
		for (int i = 0; i < branches.size() && i < sending.size(); i++) {
			SipServletRequest request = sending.get(i);
			SipServletRequest branchRequest = branches.get(i).getRequest();
			for (String name : request.getAttributeNameSet()) {
				branchRequest.setAttribute(name, request.getAttribute(name));
			}
			sipLogger.superArrow(Direction.SEND, false, branchRequest, null, this.getClass().getSimpleName(), null);
		}

		appSession.setAttribute(PASSTHRU_PROXY_ATTR, state);
		appSession.setAttribute(IS_PROXY_ATTR, Boolean.TRUE); // the caller's CANCEL is the container's to handle
		proxy.startProxy();
		return true;
	}

	/// A parallel proxy's own timeout, in seconds: long enough never to fire, so
	/// that each batch's timer decides.
	static final int PASSTHRU_NO_TIMEOUT_SECONDS = 24 * 60 * 60;

	/// A parallel batch ran out of time: cancel what is still ringing. Unless a
	/// failure no other branch would fix has come in, `complete` gets a 408 now,
	/// made here rather than by the container, because the container sends the
	/// 408 it makes for cancelled branches to the caller even when a next batch
	/// has started. A next batch started from `complete` goes out while the old
	/// branches finish, so the caller hears only from it.
	private static void passthruBatchTimeout(SipServletRequest inbound, int batch) {
		SipApplicationSession appSession = inbound.getApplicationSession();
		PassthruProxy state = (PassthruProxy) appSession.getAttribute(PASSTHRU_PROXY_ATTR);
		if (state == null || state.batch != batch || state.completedBatch == batch) {
			return;
		}
		try {
			javax.servlet.sip.Proxy proxy = inbound.getProxy(false);
			if (proxy != null) {
				for (javax.servlet.sip.ProxyBranch branch : proxy.getProxyBranches()) {
					SipServletResponse last = branch.getResponse();
					if (last == null || last.getStatus() < 200) {
						try {
							branch.cancel();
						} catch (IllegalStateException finished) {
							// answered or already cancelled
						}
					}
				}
			}
			state.batchTimer = null;
			if (!state.definite) {
				state.completedBatch = batch;
				appSession.setAttribute(PASSTHRU_PROXY_ATTR, state);
				if (state.complete != null) {
					state.complete.accept(new DetachedResponse(inbound, 408));
				}
			} else {
				appSession.setAttribute(PASSTHRU_PROXY_ATTR, state);
			}
		} catch (Exception e) {
			sipLogger.severe(inbound, "Callflow.passthruBatchTimeout - " + e);
			sipLogger.severe(inbound, e);
		}
	}

	/// Hands a response of a passthru proxy to its callbacks; the v3
	/// [AsyncSipServlet] calls it for every response. `branch` is true for a
	/// branch's final that is not (yet) the one the caller gets. Returns false
	/// for any other response, which the servlet then handles as usual.
	///
	/// The observer sees each response once: the container may report a
	/// branch's final twice, as a branch response and then as the best, so
	/// finals are remembered by Via, To tag and status. The completion callback
	/// sees the best response once per batch, and not at all when the batch
	/// timer has already given it a 408, unless the late one is a 2xx. A 487 is
	/// a cancel's echo, ours or the caller's, never a definite failure.
	static boolean passthruResponse(SipServletResponse response, boolean branch) {
		SipApplicationSession appSession = response.getApplicationSession();
		PassthruProxy state = (appSession != null && appSession.isValid())
				? (PassthruProxy) appSession.getAttribute(PASSTHRU_PROXY_ATTR)
				: null;
		if (state == null || !INVITE.equals(response.getMethod())) {
			return false;
		}
		try {
			boolean fresh = provisional(response) || state.seen.add(response.getHeader("Via") + "|"
					+ response.getTo().getParameter("tag") + "|" + response.getStatus());
			if (fresh && state.observer != null) {
				state.observer.accept(response);
			}

			// a 487 is a cancel's echo (ours, or the caller's), not a callee's verdict
			boolean definite = !provisional(response) && !successful(response) && response.getStatus() != 487
					&& state.advance != null && !state.advance.test(response);
			if (branch) {
				state.definite |= definite;
				// a sequential search moves on by itself; stop it on a failure no other branch would fix
				if (!state.parallel && definite) {
					response.getProxy().cancel();
				}
			} else if (!provisional(response) && !state.answered) {
				state.answered = successful(response);
				stopTimer(appSession, state.batchTimer);
				state.batchTimer = null;
				boolean told = state.completedBatch == state.batch; // by the batch timer
				state.completedBatch = state.batch;
				appSession.setAttribute(PASSTHRU_PROXY_ATTR, state);
				if (state.complete != null && state.complete != state.observer && (!told || state.answered)) {
					state.complete.accept(response);
				}
			}
			if (appSession.isValid()) {
				appSession.setAttribute(PASSTHRU_PROXY_ATTR, state);
			}
		} catch (Exception e) {
			sipLogger.severe(response, "Callflow.passthruResponse - " + e);
			sipLogger.severe(response, e);
		}
		return true;
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
	/// `handler` is either a String FQN (the chosen callflow at dispatch, the
	/// servlet, or "proxy") used with `methodHint` (`process` for dispatched
	/// callflows), or the callback OBJECT — introspected via [#lambdaTarget]
	/// to its owner class + enclosing method (e.g.
	/// `InitialInvite.processContinue`). The viewer highlights the resolved
	/// method's declaration.
	/// - `direction` SEND with a non-null `sender` pins the exact source line
	/// that emitted the message ([#captureStep]); a null sender (servlet-level
	/// sends: glare 491, 501, server-generated) pins the servlet class.
	///
	/// Arming is decided here too ([#maybeArm]) — the receive of the initial
	/// request is the first event a call ever fires. The session id keeps the
	/// header-aware fallback: at an app's first dispatch the `VORPAL_SESSION`
	/// attribute isn't populated yet, but the inbound X-Vorpal-ID header names
	/// the call; `getVorpalSessionId(request)` resolves exactly like v2 does
	/// moments later (minting only at the true chain head).
	static void traceEvent(Direction direction, SipServletRequest request, SipServletResponse response, Object handler,
			String methodHint, Callflow sender) {
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
			} else if (handler instanceof org.vorpal.blade.framework.Callflow) {
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
						in ? CallStep.IN : CallStep.OUT, kind, label, handlerClass != null ? handlerClass : "?",
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
		for (Class<?> c = this.getClass(); c != null && c != Callflow.class
				&& c != org.vorpal.blade.framework.Callflow.class; c = c.getSuperclass()) {
			owners.add(c.getName());
		}
		for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
			if (owners.contains(e.getClassName())) {
				return new CallStep(sessionId, now, order, CallStep.OUT, kind, label, e.getClassName(),
						e.getMethodName(), e.getLineNumber(), message);
			}
		}
		return new CallStep(sessionId, now, order, CallStep.OUT, kind, label, this.getClass().getName(), "?", -1,
				message);
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

	public SipServletRequest createCancel(SipServletRequest inboundCancel)
			throws ServletParseException, UnsupportedEncodingException, IOException {
		SipServletRequest outboundCancel = null;

		SipSession sipSession = getLinkedSession(inboundCancel.getSession());
		SipServletRequest activeInvite = sipSession.getActiveInvite(UAMode.UAC);
		outboundCancel = activeInvite.createCancel();
		copyContentAndHeaders(inboundCancel, outboundCancel);

		return outboundCancel;
	}

}
