package org.vorpal.blade.framework.v2.callflow;

/// B2BUA-initiated 3PCC re-INVITE that forces every m-line of the call to
/// `a=recvonly`. Counterpart to [CallflowUnmute]. See [AbstractCallflow3PCC]
/// for the wire flow.
public class CallflowMute extends AbstractCallflow3PCC {
	private static final long serialVersionUID = 1L;

	@Override
	protected String getMediaDirection() {
		return "recvonly";
	}
}
