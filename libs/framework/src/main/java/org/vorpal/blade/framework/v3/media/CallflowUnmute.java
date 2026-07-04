package org.vorpal.blade.framework.v3.media;

/// Unmute the target leg: back to `a=sendrecv` on every stream, regardless of
/// what was captured before. For "put back exactly what was there" semantics
/// use [CallflowResume]. Counterpart: [CallflowMute].
public class CallflowUnmute extends CallflowMediaDirection {
	private static final long serialVersionUID = 1L;

	public CallflowUnmute() {
		super(MediaDirection.SENDRECV);
	}
}
