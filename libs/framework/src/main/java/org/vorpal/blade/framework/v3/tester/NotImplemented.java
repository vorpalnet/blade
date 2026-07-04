package org.vorpal.blade.framework.v3.tester;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Answers `501 Not Implemented` to in-dialog requests an endpoint-mode
/// dialog doesn't handle.
public class NotImplemented extends org.vorpal.blade.framework.v3.Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sendResponse(request.createResponse(501));
	}
}
