package org.vorpal.blade.framework.v2.callflow;

/// @deprecated Use [org.vorpal.blade.framework.Expectation]. This type is that one under its old name,
///             kept so code that names it still compiles.
@Deprecated
public class Expectation extends org.vorpal.blade.framework.Expectation {
	private static final long serialVersionUID = 1L;

	public Expectation(javax.servlet.sip.SipSession sipSession, String method, org.vorpal.blade.framework.Callback<javax.servlet.sip.SipServletRequest> callback) {
		super(sipSession, method, callback);
	}

	public Expectation(javax.servlet.sip.SipApplicationSession appSession, String method, org.vorpal.blade.framework.Callback<javax.servlet.sip.SipServletRequest> callback) {
		super(appSession, method, callback);
	}
}
