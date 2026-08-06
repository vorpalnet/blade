package org.vorpal.blade.services.webrtc;

/// Whether a call's media goes through a media server. **Media only** — signaling
/// always rides SIP, whatever this says: every call is an INVITE through the App
/// Router, visible to the location service, FSMAR and analytics. What is decided
/// here is what goes in the SDP body of that INVITE: the browser's own offer
/// (media peer-to-peer) or a media server's (media anchored).
///
/// This mirrors `session.passthru` in [org.vorpal.blade.framework.v3.Callflow],
/// where the *same* callflow runs as a proxy that drops out of the dialog or as
/// a full B2BUA, and config casts the deciding vote per network. Anchoring is the
/// same shape of decision: one callflow, two media topologies, chosen by policy
/// rather than by code.
///
/// ## Why the resolution inputs are what they are
///
/// The caller cannot know what will answer. The INVITE goes to the App Router
/// and may fork off the location service; whether a browser, a phone or a trunk
/// picks up is decided downstream. So resolution works from what *is* knowable
/// at offer time: whether the dialed target has a live socket on this node, and
/// whether a media server exists to anchor on. The answering side needs no
/// policy at all — it can see whether the offer that arrives is a WebRTC offer.
public enum MediaMode {

	/// Media server in the path. Required whenever one end is not a browser — a
	/// phone cannot do ICE or DTLS-SRTP — and required for recording,
	/// conferencing, transcription, scoring or intercept.
	ANCHOR,

	/// The browser's own SDP goes in the INVITE; media flows directly between
	/// the endpoints and the gateway touches none of it. Needs no media server.
	///
	/// Forcing this means calls to non-WebRTC endpoints will not get audio — a
	/// phone cannot answer a DTLS-SRTP offer. It is a deployment's explicit
	/// choice, not a preference this enum second-guesses, because the code can
	/// no longer see who the far end is at offer time.
	///
	/// Not free of infrastructure, though: when both endpoints are behind
	/// symmetric NAT there is no direct path and the media has to be relayed by
	/// TURN. coturn covers that and is open source, but "no media server" is
	/// not the same as "no configuration".
	RELAY,

	/// Decide per call from what is knowable. The default.
	///
	/// A target with a live socket on this node is a browser, so relay. For
	/// anything else, anchor when a media server exists — the safe shape for a
	/// far end that might be a phone — and relay when none does, because
	/// anchoring without a media server can only fail, while a pass-through
	/// offer still connects to any WebRTC-capable answerer.
	AUTO;

	/// Resolve this policy for one outbound call.
	///
	/// @param targetIsLocalBrowser the dialed address has a live socket on this
	///                             node — proof the far end is a browser. False
	///                             proves nothing (it may be a browser on
	///                             another node, or a phone).
	/// @param mediaServerAvailable a JSR-309 factory is installed
	public MediaMode resolve(boolean targetIsLocalBrowser, boolean mediaServerAvailable) {
		if (this != AUTO) {
			return this;
		}
		if (targetIsLocalBrowser) {
			return RELAY;
		}
		return mediaServerAvailable ? ANCHOR : RELAY;
	}
}
