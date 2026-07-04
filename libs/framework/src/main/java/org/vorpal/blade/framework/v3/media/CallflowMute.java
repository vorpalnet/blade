package org.vorpal.blade.framework.v3.media;

/// Mute the target leg: it stops SENDING media (mic off) but keeps receiving.
/// `process(SipSession target)` drives the target to `a=recvonly` — the offer
/// it sees is `a=sendonly`, per the RFC 3264 perspective flip
/// ([MediaDirection#reverse]). Counterpart: [CallflowUnmute]. The v2
/// `CallflowMute` forced `recvonly` into the offer and muted the wrong leg.
public class CallflowMute extends CallflowMediaDirection {
	private static final long serialVersionUID = 1L;

	public CallflowMute() {
		super(MediaDirection.RECVONLY);
	}
}
