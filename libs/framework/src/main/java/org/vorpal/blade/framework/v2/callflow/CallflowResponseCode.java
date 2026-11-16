package org.vorpal.blade.framework.v2.callflow;

/// @deprecated Use [org.vorpal.blade.framework.v3.CallflowResponseCode]. This type is that one under its old name,
///             kept so code that names it still compiles.
@Deprecated
public class CallflowResponseCode extends org.vorpal.blade.framework.v3.CallflowResponseCode {
	private static final long serialVersionUID = 1L;

	public CallflowResponseCode(int responseCode, String reasonPhrase) {
		super(responseCode, reasonPhrase);
	}

	public CallflowResponseCode(int responseCode) {
		super(responseCode);
	}
}
