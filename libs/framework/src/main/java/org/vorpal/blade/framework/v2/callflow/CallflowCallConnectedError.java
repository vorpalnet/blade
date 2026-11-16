package org.vorpal.blade.framework.v2.callflow;

/// @deprecated Use [org.vorpal.blade.framework.v3.CallflowCallConnectedError]. This type is that one under its old name,
///             kept so code that names it still compiles.
@Deprecated
public class CallflowCallConnectedError extends org.vorpal.blade.framework.v3.CallflowCallConnectedError {
	private static final long serialVersionUID = 1L;

	public CallflowCallConnectedError(Exception ex) {
		super(ex);
	}
}
