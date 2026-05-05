package org.vorpal.blade.framework.v2.callflow;

/// B2BUA-initiated 3PCC re-INVITE that forces every m-line of the call to
/// `a=sendrecv`, taking the leg out of mute. Counterpart to [CallflowMute].
/// SDP-equivalent to [CallflowResume]; kept distinct for caller-side
/// semantic clarity. See [AbstractCallflow3PCC] for the wire flow.
public class CallflowUnmute extends AbstractCallflow3PCC {
	private static final long serialVersionUID = 1L;

	@Override
	protected String getMediaDirection() {
		return "sendrecv";
	}
}
