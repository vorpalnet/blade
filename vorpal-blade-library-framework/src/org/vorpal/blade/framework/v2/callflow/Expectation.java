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
	private static final String REQUEST_CALLBACK_PREFIX = "REQUEST_CALLBACK_";

	private final SipApplicationSession appSession;
	private final SipSession sipSession;
	private final String method;
	private final Callback<SipServletRequest> callback;

	/**
	 * Creates an expectation for a specific SIP method on a SIP session.
	 *
	 * @param sipSession the SIP session to set the expectation on
	 * @param method the SIP method to expect (e.g., "CANCEL", "BYE")
	 * @param callback the lambda function to invoke when the method arrives
	 */
	public Expectation(SipSession sipSession, String method, Callback<SipServletRequest> callback) {
		this.sipSession = sipSession;
		this.appSession = null;
		this.method = method;
		this.callback = callback;
	}

	/**
	 * Creates an expectation for a specific SIP method on an application session.
	 *
	 * @param appSession the SIP application session to set the expectation on
	 * @param method the SIP method to expect (e.g., "CANCEL", "BYE")
	 * @param callback the lambda function to invoke when the method arrives
	 */
	public Expectation(SipApplicationSession appSession, String method, Callback<SipServletRequest> callback) {
		this.appSession = appSession;
		this.sipSession = null;
		this.method = method;
		this.callback = callback;
	}

	/**
	 * Clears this expectation by removing the callback attribute from the session.
	 * Call this method when you no longer expect the SIP method to arrive.
	 */
	public void clear() {
		if (sipSession != null && sipSession.isValid()) {
			sipSession.removeAttribute(REQUEST_CALLBACK_PREFIX + method);
		}
		if (appSession != null && appSession.isValid()) {
			appSession.removeAttribute(REQUEST_CALLBACK_PREFIX + method);
		}
	}

	/**
	 * Resets this expectation by re-adding the callback attribute to the session.
	 * Call this method to re-enable an expectation that was previously cleared.
	 */
	public void reset() {
		if (sipSession != null && sipSession.isValid()) {
			sipSession.setAttribute(REQUEST_CALLBACK_PREFIX + method, callback);
		}
		if (appSession != null && appSession.isValid()) {
			appSession.setAttribute(REQUEST_CALLBACK_PREFIX + method, callback);
		}
	}

}