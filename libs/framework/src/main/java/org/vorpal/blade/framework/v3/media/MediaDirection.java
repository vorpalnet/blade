package org.vorpal.blade.framework.v3.media;

/// The four SDP direction attributes of RFC 3264 (`a=sendrecv`, `a=sendonly`,
/// `a=recvonly`, `a=inactive`) — always read from the perspective of the party
/// that SENT the SDP. That perspective flip is where hold/mute code
/// historically goes wrong, so it lives here once: [#reverse] converts a
/// direction between the two parties' points of view (what I call `sendonly`,
/// you experience as `recvonly`).
public enum MediaDirection {
	SENDRECV("sendrecv"),
	SENDONLY("sendonly"),
	RECVONLY("recvonly"),
	INACTIVE("inactive");

	private final String sdp;

	MediaDirection(String sdp) {
		this.sdp = sdp;
	}

	/// The attribute name as it appears on the `a=` line.
	public String sdp() {
		return sdp;
	}

	/// The same media state seen from the OTHER party's perspective:
	/// `sendonly` ↔ `recvonly`; `sendrecv` and `inactive` are symmetric.
	public MediaDirection reverse() {
		switch (this) {
		case SENDONLY:
			return RECVONLY;
		case RECVONLY:
			return SENDONLY;
		default:
			return this;
		}
	}

	/// Parse an attribute name, or null if it isn't a direction attribute.
	public static MediaDirection parse(String name) {
		if (name == null) {
			return null;
		}
		switch (name.toLowerCase()) {
		case "sendrecv":
			return SENDRECV;
		case "sendonly":
			return SENDONLY;
		case "recvonly":
			return RECVONLY;
		case "inactive":
			return INACTIVE;
		default:
			return null;
		}
	}
}
