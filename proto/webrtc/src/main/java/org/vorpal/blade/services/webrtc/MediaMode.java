package org.vorpal.blade.services.webrtc;

/// Whether a call's media goes through a media server.
///
/// This mirrors `session.passthru` in [org.vorpal.blade.framework.v3.Callflow], where the *same*
/// callflow runs as a proxy that drops out of the dialog or as a full B2BUA, and config casts the
/// deciding vote per network. Anchoring is the same shape of decision: one callflow, two media
/// topologies, chosen by policy rather than by code.
public enum MediaMode {

	/// Media server in the path. Required whenever one end is not a browser — a phone cannot do ICE
	/// or DTLS-SRTP — and required for recording, conferencing, transcription, scoring or intercept.
	ANCHOR,

	/// Media flows directly between the two browsers; the gateway forwards SDP and candidates and
	/// touches no media. Needs no media server at all.
	///
	/// Not free of infrastructure, though: when both endpoints are behind symmetric NAT there is no
	/// direct path and the media has to be relayed by TURN. coturn covers that and is open source,
	/// but "no media server" is not the same as "no configuration".
	RELAY,

	/// Relay when both ends are browsers, anchor otherwise. The default.
	///
	/// Escalation stays available: a relayed call can be pulled onto the media server later with
	/// [SignalProtocol#CALL_UPDATE], at the cost of a re-key on both legs.
	AUTO;

	/// Resolve this policy for one call.
	///
	/// @param bothEndsAreBrowsers false as soon as either end is a phone or a SIP trunk
	public MediaMode resolve(boolean bothEndsAreBrowsers) {
		if (this == AUTO) {
			return bothEndsAreBrowsers ? RELAY : ANCHOR;
		}
		// RELAY is a preference, not an override: a phone leg has no ICE or DTLS, so there is
		// nothing a browser could negotiate with directly.
		if (this == RELAY && !bothEndsAreBrowsers) {
			return ANCHOR;
		}
		return this;
	}
}
