package org.vorpal.blade.framework.v2.transfer.api;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.api.SessionResponse]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class SessionResponse extends org.vorpal.blade.framework.v3.transfer.api.SessionResponse {
	private static final long serialVersionUID = 1L;

	public SessionResponse() {
		super();
	}

	public SessionResponse(javax.servlet.sip.SipApplicationSession appSession) {
		super(appSession);
	}
}
