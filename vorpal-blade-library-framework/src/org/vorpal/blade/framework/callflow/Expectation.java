package org.vorpal.blade.framework.callflow;

import java.io.Serializable;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;

/**
 * Object returned by the expectRequest method. Use the .clear() method to stop
 * expecting the request.
 * 
 * @author jeff
 *
 */
public class Expectation implements Serializable {

	private SipApplicationSession appSession = null;
	private SipSession sipSession = null;
	private String method;

	public Expectation(SipSession sipSession, String method) {
		this.sipSession = sipSession;
		this.method = method;
	}

	public Expectation(SipApplicationSession appSession, String method) {
		this.appSession = appSession;
		this.method = method;
	}

	public void clear() {
		if (sipSession != null) {
			sipSession.removeAttribute("REQUEST_CALLBACK_" + method);
		}
		if (appSession != null) {
			appSession.removeAttribute("REQUEST_CALLBACK_" + method);
		}
	}
}