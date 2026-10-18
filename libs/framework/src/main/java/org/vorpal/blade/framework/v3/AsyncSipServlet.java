package org.vorpal.blade.framework.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

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

	/// A response to a passthru proxy goes to the callbacks [Callflow#proxyPassthru]
	/// stored; the container has already decided what the caller gets. A
	/// branch's final that is not the one the caller gets arrives here too:
	/// `SipServlet.doResponse` is what would route it to `doBranchResponse`, and
	/// the baseline overrides it, so [SipServletResponse#isBranchResponse] tells.
	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {
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
			Callflow.getLogger().superArrow(Direction.SEND, true, null, response, "proxy", null);
			traced(Direction.SEND, null, response, "proxy", "send");
		}
		return true;
	}
}
