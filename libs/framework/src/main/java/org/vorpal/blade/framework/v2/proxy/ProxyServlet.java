package org.vorpal.blade.framework.v2.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;

/**
 * Base servlet for SIP proxy applications.
 * Extend this class and implement ProxyListener to create a custom proxy routing application.
 *
 * @deprecated Write a v3 {@link org.vorpal.blade.framework.v3.Callflow} and set
 *             {@code session.passthru}: its {@code sendRequest},
 *             {@code sendRequestsInSerial} and {@code sendRequestsInParallel}
 *             proxy the caller's INVITE without Record-Route, a tier at a
 *             time, and the same callflow runs as a B2BUA when passthru is
 *             off. The proxy-balancer service is the worked example.
 */
@Deprecated
public abstract class ProxyServlet extends AsyncSipServlet implements ProxyListener {
	private static final long serialVersionUID = 1L;

	/** SIP method name for INVITE requests */
	private static final String METHOD_INVITE = "INVITE";
	/** SIP method name for CANCEL requests */
	private static final String METHOD_CANCEL = "CANCEL";

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		if (request == null) {
			return null;
		}

		Callflow callflow = null;
		final String method = request.getMethod();

		if (METHOD_INVITE.equals(method)) {
			if (request.isInitial()) {
				callflow = new ProxyInvite(this, null);
			}
		} else if (METHOD_CANCEL.equals(method)) {
			callflow = new ProxyCancel(this);
		}

		return callflow;
	}

}
