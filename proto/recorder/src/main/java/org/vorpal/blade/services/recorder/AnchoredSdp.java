package org.vorpal.blade.services.recorder;

import java.nio.charset.StandardCharsets;

import org.vorpal.blade.framework.v2.sdp.Sdp;
import org.vorpal.blade.framework.v3.media.MediaDirection;
import org.vorpal.blade.framework.v3.media.SdpMedia;

/// The media server's own SDP for a leg, re-issued with a new direction.
///
/// ## Why a re-INVITE is answered with the same address
///
/// A re-INVITE must not move the media path. The initial INVITE anchored both
/// legs on the media server, and a hold changes only the direction of the
/// media, never where it goes. So the offer forwarded to the far party is the
/// media server's SDP for that leg, carrying the near party's new direction,
/// and the answer returned to the near party is the media server's SDP for its
/// leg, carrying the mirrored direction. The media server is not renegotiated:
/// its ports never changed, and it does not support renegotiation in any case.
///
/// Before this, the recorder relayed both SDPs verbatim, so a hold told each
/// party the other's address and the rest of the call bypassed the media
/// server. The recording and the transcript were silent from the hold onward,
/// and nothing said so.
///
/// ## The version must move
///
/// RFC 3264 requires the origin's session version to increase whenever the
/// session description changes, and a direction change is a change. Some
/// endpoints ignore an SDP whose version has not moved. The bump is a counter
/// per leg rather than a timestamp, so two re-INVITEs in the same second still
/// differ.
final class AnchoredSdp {

	private AnchoredSdp() {
	}

	/// `mediaServerSdp` with every m-line set to `direction` and the session
	/// version raised by `bump`. Null in, null out, so a leg the media server
	/// has not negotiated yet simply is not rewritten.
	static byte[] rewrite(byte[] mediaServerSdp, MediaDirection direction, long bump) {
		if (mediaServerSdp == null || mediaServerSdp.length == 0) {
			return null;
		}
		Sdp sdp = Sdp.parse(new String(mediaServerSdp, StandardCharsets.UTF_8));
		SdpMedia.forceDirection(sdp, direction == null ? MediaDirection.SENDRECV : direction);
		if (sdp.getOrigin() != null) {
			sdp.getOrigin().setSessionVersion(bumped(sdp.getOrigin().getSessionVersion(), bump));
		}
		return sdp.toString().getBytes(StandardCharsets.UTF_8);
	}

	/// The version plus the bump, or the bump appended when the version is not
	/// a number an endpoint could compare.
	static String bumped(String version, long bump) {
		try {
			return Long.toString(Long.parseLong(version.trim()) + bump);
		} catch (RuntimeException notANumber) {
			return (version == null ? "" : version) + bump;
		}
	}
}
