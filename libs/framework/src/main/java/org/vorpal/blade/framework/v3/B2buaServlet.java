package org.vorpal.blade.framework.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;
import org.vorpal.blade.framework.v2.b2bua.Passthru;
import org.vorpal.blade.framework.v2.b2bua.Reinvite;
import org.vorpal.blade.framework.v2.b2bua.Terminate;

/// The v3 `B2buaServlet` — the v2 B2BUA behavior on top of the v3
/// [AsyncSipServlet] dispatch (which carries the trace eventing at the
/// sequence-diagram spots). Extends v3 AsyncSipServlet rather than the v2
/// B2buaServlet so the traced dispatch is inherited ONCE; the small v2
/// B2buaServlet layer below is a copy (v2 B2buaServlet.java:48–181 — sync
/// manually until the v1 hoist). Verified: v2 B2buaServlet overrides only
/// `chooseCallflow` and the listener hooks, never doRequest/doResponse.
public abstract class B2buaServlet extends AsyncSipServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	/// Attribute key used to mark messages that should not be processed. Same
	/// literal as v2's private copy (and the b2bua callflows' own copies) — the
	/// attribute is the contract, not the constant.
	private static final String ATTR_DO_NOT_PROCESS = "doNotProcess";

	@Override
	protected org.vorpal.blade.framework.v2.callflow.Callflow chooseCallflow(SipServletRequest inboundRequest)
			throws ServletException, IOException {
		org.vorpal.blade.framework.v2.callflow.Callflow callflow;

		switch (inboundRequest.getMethod()) {
		case Callflow.INVITE:
			if (inboundRequest.isInitial()) {
				callflow = new InitialInvite(this);
			} else {
				callflow = new Reinvite(this);
			}
			break;

		case Callflow.BYE:
		case Callflow.CANCEL:
			callflow = new Terminate(this);
			break;
		default:
			callflow = new Passthru(this);
		}

		return callflow;
	}

	@Override
	public abstract void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException;

	@Override
	public abstract void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException;

	@Override
	public abstract void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException;

	@Override
	public abstract void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException;

	@Override
	public abstract void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException;

	@Override
	public abstract void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException;

	/// Tells the B2buaServlet not to send the message (request or response). You
	/// are now responsible for completing the callflow.
	public void doNotProcess(SipServletMessage msg) {
		if (msg == null) {
			return;
		}
		msg.setAttribute(ATTR_DO_NOT_PROCESS, true);
	}

	/// Tells the B2buaServlet not to send the outbound request and send a reply
	/// back upstream with the supplied status code.
	public void doNotProcess(SipServletRequest outboundRequest, int statusCode) throws ServletException, IOException {
		if (outboundRequest == null) {
			return;
		}
		outboundRequest.setAttribute(ATTR_DO_NOT_PROCESS, true);
		SipSession linkedSession = org.vorpal.blade.framework.v2.callflow.Callflow
				.getLinkedSession(outboundRequest.getSession());
		if (linkedSession == null) {
			return;
		}
		SipServletRequest incomingRequest = linkedSession.getActiveInvite(UAMode.UAS);
		if (incomingRequest == null) {
			return;
		}
		SipServletResponse errorResponse = incomingRequest.createResponse(statusCode);
		sendResponse(errorResponse);
	}

	/// Tells the B2buaServlet not to send the outbound request and send a reply
	/// back upstream with the supplied status code and custom reason phrase.
	public void doNotProcess(SipServletRequest outboundRequest, int statusCode, String reasonPhrase)
			throws ServletException, IOException {
		if (outboundRequest == null) {
			return;
		}
		outboundRequest.setAttribute(ATTR_DO_NOT_PROCESS, true);
		SipSession linkedSession = org.vorpal.blade.framework.v2.callflow.Callflow
				.getLinkedSession(outboundRequest.getSession());
		if (linkedSession == null) {
			return;
		}
		SipServletRequest incomingRequest = linkedSession.getActiveInvite(UAMode.UAS);
		if (incomingRequest == null) {
			return;
		}
		SipServletResponse errorResponse = incomingRequest.createResponse(statusCode, reasonPhrase);
		sendResponse(errorResponse);
	}

	/// Returns the original incoming request which initiated the callStarted
	/// method. Useful when used in conjunction with 'doNotProcess'.
	public static SipServletRequest getIncomingRequest(SipServletRequest outboundRequest) {
		if (outboundRequest == null) {
			return null;
		}
		SipSession linkedSession = org.vorpal.blade.framework.v2.callflow.Callflow
				.getLinkedSession(outboundRequest.getSession());
		if (linkedSession == null) {
			return null;
		}
		return linkedSession.getActiveInvite(UAMode.UAS);
	}

}
