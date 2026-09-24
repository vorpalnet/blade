package org.vorpal.blade.framework.v3;

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
}
