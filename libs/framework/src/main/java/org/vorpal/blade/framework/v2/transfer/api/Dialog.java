package org.vorpal.blade.framework.v2.transfer.api;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.api.Dialog]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Dialog extends org.vorpal.blade.framework.v3.transfer.api.Dialog {
	private static final long serialVersionUID = 1L;

	public Dialog() {
		super();
	}

	public Dialog(javax.servlet.sip.SipSession sipSession) {
		super(sipSession);
	}
}
