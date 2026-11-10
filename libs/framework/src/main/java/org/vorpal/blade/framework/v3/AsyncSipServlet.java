package org.vorpal.blade.framework.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.crud.CrudHook;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;

/// The v3 `AsyncSipServlet`: the baseline servlet plus **trace eventing at the sequence-diagram
/// spots**.
///
/// The request and response handling is the baseline's, one copy for every servlet version. The
/// baseline calls [#traced] beside each sequence-diagram arrow, where the message, its direction and
/// the handling code are all in scope under the application-session lock; this class records a
/// [Callflow#traceEvent] there. A servlet opts in with a one-line base-class swap, and a call with
/// tracing disarmed costs one boolean read per event.
///
/// This class used to carry its own copies of `doRequest`, `doResponse` and `sendResponse`, differing
/// from the baseline only by the trace calls and kept in step by hand. They drifted: a cap on the
/// glare queue added to the baseline never reached them. Keep request and response handling in the
/// baseline, and add behaviour here only through hooks like [#traced].
public abstract class AsyncSipServlet extends org.vorpal.blade.framework.AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void traced(Direction direction, SipServletRequest request, SipServletResponse response, Object handler,
			String methodHint) {
		Callflow.traceEvent(direction, request, response, handler, methodHint, null);
	}

	/// Runs the application's CRUD pipeline, if its `crud` section has one,
	/// so the rule set is chosen before the callflow sends anything, then the
	/// rule set's `receive` rules on the request as it arrived.
	@Override
	protected boolean initialRequest(SipServletRequest request) {
		// The baseline marks the caller's session only for an initial INVITE;
		// `leg` filters need it for a SUBSCRIBE, MESSAGE or REFER dialog too.
		// The caller's From tag tells the legs apart on a proxy, which has one
		// session for both ([org.vorpal.blade.framework.v3.crud.Rule#legOf]).
		javax.servlet.sip.SipSession session = request.getSession();
		if (session != null) {
			if (session.getAttribute("userAgent") == null) session.setAttribute("userAgent", "caller");
			try {
				String tag = request.getFrom().getParameter("tag");
				if (tag != null) session.setAttribute(org.vorpal.blade.framework.v3.crud.Rule.CALLER_TAG, tag);
			} catch (Exception ignore) {
				// no From tag: the session alone decides the leg
			}
		}
		CrudHook.select(request);
		CrudHook.receive(request);
		// A `receive` rule refused the call: answer it here, for every v3
		// application. True even if the answer cannot be sent, so the call is
		// never placed against the rule.
		org.vorpal.blade.framework.v3.crud.RejectOperation.Refusal refusal = CrudHook.refusal(request);
		if (refusal == null) return false;
		if (!request.isCommitted()) {
			try {
				SipServletResponse response = CrudHook.refuse(request, refusal);
				// The outcome, as for a refusal on the way out ([Callflow#reportRefusal]).
				Callflow.reportRefusal(response, (this instanceof org.vorpal.blade.framework.v2.b2bua.B2buaListener)
						? (org.vorpal.blade.framework.v2.b2bua.B2buaListener) this : null);
				Callflow.getLogger().superArrow(Direction.SEND, null, response, "crud");
				traced(Direction.SEND, null, response, getClass().getName(), "initialRequest");
				response.send();
			} catch (Exception e) {
				Callflow.getLogger().warning(request, "AsyncSipServlet - a CRUD rule refused the call, but the refusal "
						+ "could not be sent: " + e.getMessage());
			}
		}
		return true;
	}


	/// Runs the call's `receive` rules on a request inside a call before the
	/// application handles it, once the baseline's glare and transfer guards
	/// have let it through. The initial request gets them in
	/// [#initialRequest], once its rule set is chosen.
	@Override
	protected void inDialogRequest(SipServletRequest request) {
		CrudHook.receive(request);
	}

	/// A response to a passthru proxy goes to the callbacks [Callflow#proxyPassthru]
	/// stored; the container has already decided what the caller gets. A
	/// branch's final that is not the one the caller gets arrives here too:
	/// `SipServlet.doResponse` is what would route it to `doBranchResponse`, and
	/// the baseline overrides it, so [SipServletResponse#isBranchResponse] tells.
	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
		// A passthru proxy's response comes from the callee but arrives on the
		// caller's proxied session: the receive rules see it on its own leg.
		boolean proxiedBranch = response.getProxyBranch() != null;
		if (proxiedBranch) response.setAttribute(org.vorpal.blade.framework.v3.crud.Rule.LEG_ATTR, "callee");
		try {
			CrudHook.receive(response);
		} finally {
			if (proxiedBranch) response.removeAttribute(org.vorpal.blade.framework.v3.crud.Rule.LEG_ATTR);
		}
		if (proxied(response, response.isBranchResponse())) {
			return;
		}
		super.doResponse(response);
	}

	/// Records the response as received and, unless it is a branch's final the
	/// caller never sees, as sent on to the caller: the container forwards it,
	/// and a call record that saw it only arrive would hold that this
	/// application never answered the caller's INVITE.
	private boolean proxied(SipServletResponse response, boolean branch) {
		if (!Callflow.passthruResponse(response, branch)) {
			return false;
		}
		Callflow.getLogger().superArrow(Direction.RECEIVE, false, null, response, "proxy", null);
		traced(Direction.RECEIVE, null, response, "proxy", branch ? "branch" : "received");
		if (!branch) {
			// The container forwards this response to the caller when doResponse
			// returns; the application's send rules get it first, as they get
			// every response the application sends itself. A rule cannot change
			// its status here: the container forwards this object, not a copy.
			CrudHook.apply(response);
			if (response.getAttribute(org.vorpal.blade.framework.v3.crud.MessageHelper.PENDING_STATUS) != null) {
				Callflow.getLogger().warning(response,
						"AsyncSipServlet - a CRUD rule changed a proxied response's status; a proxy forwards the "
								+ "response it received, so the status stays " + response.getStatus());
			}
			Callflow.getLogger().superArrow(Direction.SEND, true, null, response, "proxy", null);
			traced(Direction.SEND, null, response, "proxy", "send");
		}
		return true;
	}
}
