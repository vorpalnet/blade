package org.vorpal.blade.services.listener;

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
/// The one re-INVITE that does move something is a party moving its own
/// media, a new connection address or port in its offer ([#moved]). The media
/// server would keep sending to the old address and the party would hear
/// nothing, so that party gets a fresh leg (`ListenerAnchor.moveLeg`) and the
/// answer it receives is the fresh leg's SDP, rewritten here the same way.
/// The far party still sees the media server's unchanged SDP for its leg.
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

	/// Where a party's audio goes, as its SDP says: the connection address and
	/// the first audio port, `address:port`. A media-level `c=` line wins over
	/// the session-level one, as RFC 4566 has it. Null when the SDP names no
	/// audio.
	static String mediaAddress(byte[] sdp) {
		if (sdp == null) {
			return null;
		}
		String session = null;
		String media = null;
		String port = null;
		boolean inAudio = false;
		for (String raw : new String(sdp, StandardCharsets.UTF_8).split("\\r?\\n")) {
			String line = raw.trim();
			if (line.startsWith("m=")) {
				if (port != null) {
					break; // the first audio line is the one that matters
				}
				inAudio = line.startsWith("m=audio ");
				if (inAudio) {
					String[] parts = line.substring(2).split("\\s+");
					port = (parts.length > 1) ? parts[1] : null;
				}
			} else if (line.startsWith("c=")) {
				String[] parts = line.substring(2).split("\\s+");
				String address = (parts.length > 2) ? parts[2] : null;
				if (port == null && !inAudio) {
					session = address;
				} else if (inAudio) {
					media = address;
				}
			}
		}
		if (port == null) {
			return null;
		}
		String address = (media != null) ? media : session;
		return (address == null) ? null : address + ":" + port;
	}

	/// Whether an offer moves a party's audio somewhere other than where the
	/// media server has been sending it. The media server does not renegotiate
	/// an endpoint, so following the move means rebuilding the leg; a re-INVITE
	/// that only changes direction, or refreshes, keeps the leg it has.
	static boolean moved(byte[] previousRemoteSdp, byte[] offer) {
		String before = mediaAddress(previousRemoteSdp);
		String after = mediaAddress(offer);
		return before != null && after != null && !before.equals(after);
	}

	/// Who the SDP came from, as its origin line says: the `o=` username and
	/// session id, which RFC 3264 makes constant for one party's session while
	/// only the version moves. Null when there is no origin line.
	static String origin(byte[] sdp) {
		if (sdp == null) {
			return null;
		}
		for (String raw : new String(sdp, StandardCharsets.UTF_8).split("\\r?\\n")) {
			String line = raw.trim();
			if (line.startsWith("o=")) {
				String[] parts = line.substring(2).split("\\s+");
				return (parts.length > 1) ? parts[0] + " " + parts[1] : line.substring(2);
			}
		}
		return null;
	}

	/// Whether an offer comes from a different party than the leg has been
	/// talking to: the origin identity changed. A party moving its own media
	/// keeps its `o=` username and session id and bumps the version; a
	/// transfer completed by re-INVITE brings the target's own origin line.
	/// That is the difference between following a move and starting a new
	/// conversation, and the SDP already carries it.
	static boolean newParty(byte[] previousRemoteSdp, byte[] offer) {
		String before = origin(previousRemoteSdp);
		String after = origin(offer);
		return before != null && after != null && !before.equals(after);
	}
}
