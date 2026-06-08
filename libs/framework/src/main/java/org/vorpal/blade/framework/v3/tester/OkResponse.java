package org.vorpal.blade.framework.v3.tester;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Answers `200 OK` to an in-dialog request (BYE, CANCEL, INFO) on an
/// endpoint-mode dialog — requests that need no handling beyond
/// acknowledgement.
public class OkResponse extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sendResponse(request.createResponse(200));
	}
}
