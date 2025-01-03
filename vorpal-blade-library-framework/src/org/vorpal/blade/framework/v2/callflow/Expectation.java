package org.vorpal.blade.framework.v2.callflow;

import java.io.Serializable;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

/**
 * An Expectation is used to tell the AsyncSipServlet that a particular SIP
 * method, like CANCEL is expected. Use the 'expect' method on the Callflow
 * class to create a lambda function to be invoked if/when that CANCEL arrives.
 * Use the 'clear' method to stop expecting any more of those methods.
 * 
 * @author jeff
 *
 */
public class Expectation implements Serializable {

	private static final long serialVersionUID = 1L;
	private SipApplicationSession appSession = null;
	private SipSession sipSession = null;
	private String method;
	private Callback<SipServletRequest> callback;

	public Expectation(SipSession sipSession, String method, Callback<SipServletRequest> callback) {
		this.sipSession = sipSession;
		this.method = method;
		this.callback = callback;
	}

	public Expectation(SipApplicationSession appSession, String method, Callback<SipServletRequest> callback) {
		this.appSession = appSession;
		this.method = method;
		this.callback = callback;
	}

	public void clear() {
		if (sipSession != null && sipSession.isValid()) {
			sipSession.removeAttribute("REQUEST_CALLBACK_" + method);
		}
		if (appSession != null && appSession.isValid()) {
			appSession.removeAttribute("REQUEST_CALLBACK_" + method);
		}
	}

	public void reset() {
		if (sipSession != null && sipSession.isValid()) {
			sipSession.setAttribute("REQUEST_CALLBACK_" + method, callback);
		}
		if (appSession != null && appSession.isValid()) {
			appSession.setAttribute("REQUEST_CALLBACK_" + method, callback);
		}
	}

}