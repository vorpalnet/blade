package org.vorpal.blade.framework.v3.media;

/// Resume the target leg after a hold/mute: RESTORES the per-stream
/// directions captured before the change ([CallflowMediaDirection]'s
/// `PRIOR_DIRECTIONS_ATTR`), so a video stream that was `inactive` before the
/// hold comes back `inactive` — not blanket `a=sendrecv`, which is what the
/// v2 `CallflowResume` did. Falls back to `sendrecv` when nothing was
/// captured or the m-line count changed.
public class CallflowResume extends CallflowMediaDirection {
	private static final long serialVersionUID = 1L;

	public CallflowResume() {
		super(MediaDirection.SENDRECV, true);
	}
}
