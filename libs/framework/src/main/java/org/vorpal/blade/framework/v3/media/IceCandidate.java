package org.vorpal.blade.framework.v3.media;

import java.io.Serializable;

/// One ICE candidate, shaped like the browser's `RTCIceCandidate` so it can be handed to
/// `RTCPeerConnection.addIceCandidate()` with no translation.
///
/// Candidates travel in both directions but for different reasons. A browser will *always* accept
/// candidates trickled to it, so the media server's candidates go out as they are discovered and
/// nothing waits. A browser only *sends* them incrementally if we say we can take them; when we
/// don't, it holds its offer until gathering finishes and every candidate rides inside the SDP.
/// That asymmetry is what lets a gateway trickle outbound without implementing inbound trickle.
public final class IceCandidate implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String candidate;
	private final String sdpMid;
	private final int sdpMLineIndex;

	public IceCandidate(String candidate, String sdpMid, int sdpMLineIndex) {
		this.candidate = candidate;
		this.sdpMid = sdpMid;
		this.sdpMLineIndex = sdpMLineIndex;
	}

	/// The `candidate-attribute` of RFC 5245 section 15.1 — the text after `a=candidate:`.
	///
	/// Do not parse this expecting a dotted quad. Since Chrome 76 and the equivalent Firefox
	/// release, host candidates carry a random `<uuid>.local` mDNS name instead of the private
	/// address, so an unresolvable hostname here is normal, not a fault.
	public String getCandidate() {
		return candidate;
	}

	/// The media stream identification this candidate belongs to — the `a=mid:` value.
	public String getSdpMid() {
		return sdpMid;
	}

	/// Zero-based index of the m-line this candidate belongs to.
	public int getSdpMLineIndex() {
		return sdpMLineIndex;
	}

	@Override
	public String toString() {
		return "IceCandidate[mid=" + sdpMid + " index=" + sdpMLineIndex + " " + candidate + "]";
	}
}
