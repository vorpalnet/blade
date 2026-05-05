package org.vorpal.blade.framework.v2.callflow;

/// B2BUA-initiated 3PCC re-INVITE that forces every m-line of the call to
/// `a=sendrecv`, taking the leg out of hold. Pair with [CallflowHold]
/// (which holds locally) to bring the call back to active.
/// See [AbstractCallflow3PCC] for the wire flow.
public class CallflowResume extends AbstractCallflow3PCC {
	private static final long serialVersionUID = 1L;

	@Override
	protected String getMediaDirection() {
		return "sendrecv";
	}
}
