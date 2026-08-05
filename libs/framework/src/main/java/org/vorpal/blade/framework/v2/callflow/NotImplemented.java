package org.vorpal.blade.framework.v2.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/// Answers any request with `405 Method Not Allowed`.
///
/// @deprecated Use [org.vorpal.blade.framework.v3.tester.NotImplemented], which is
///             identical and is the copy the framework actually dispatches (from
///             [org.vorpal.blade.framework.v3.tester.TesterServlet]). This one has no
///             callers. Note it already extends the **v3** Callflow despite living in
///             a v2 package — the duplicate name is the only thing v2 about it.
@Deprecated
public class NotImplemented extends org.vorpal.blade.framework.v3.Callflow {

	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(405); // Method Not Allowed
		sendResponse(response);
	}

}
