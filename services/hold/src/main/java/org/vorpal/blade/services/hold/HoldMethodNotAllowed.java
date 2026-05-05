package org.vorpal.blade.services.hold;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Reply `405 Method Not Allowed` with an `Allow` header listing the methods
/// the hold service supports. RFC 3261 §21.4.5 requires `Allow` on a 405.
/// Used for in-dialog UPDATE / INFO / MESSAGE / OPTIONS / REFER / etc. — the
/// hold service is a single-leg UAS with no peer to forward them to.
public class HoldMethodNotAllowed extends Callflow {

	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(405);
		response.setHeader("Allow", "INVITE, ACK, BYE, CANCEL");
		sendResponse(response);
	}

}
