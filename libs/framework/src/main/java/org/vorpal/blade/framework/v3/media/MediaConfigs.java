package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.Configuration;
import javax.media.mscontrol.networkconnection.NetworkConnection;

/// BLADE-defined JSR-309 [Configuration]s, for leg types the 2009 spec did not anticipate.
///
/// `Configuration` is an empty marker interface and
/// [NetworkConnection#BASIC]/[NetworkConnection#ECHO_CANCEL]/[NetworkConnection#DTMF_CONVERSION]
/// are simply enum constants implementing it, so defining more is the sanctioned way to extend the
/// spec rather than a workaround. Dialogic reached the same conclusion from the other direction and
/// exposed WebRTC through a vendor method on its own `NetworkConnection`.
///
/// Declaring these in the framework rather than in a driver keeps applications vendor-neutral: an
/// app asks for a [#WEBRTC] leg, and whichever driver is installed either honours it or throws
/// `MsControlException`. Nothing in the public tree names a media server.
public enum MediaConfigs implements Configuration<NetworkConnection> {

	/// A leg whose far end is a browser: the media server must terminate ICE and DTLS-SRTP on it.
	///
	/// Everything that distinguishes such a leg travels **inside the SDP** — `a=fingerprint`,
	/// `a=setup`, `a=ice-ufrag`, `a=candidate` — so the offer/answer verbs on
	/// [javax.media.mscontrol.networkconnection.SdpPortManager] need no change. The only thing the
	/// driver cannot infer is which kind of endpoint to create *before* any SDP exists, which is
	/// exactly what this constant tells it.
	///
	/// A driver honouring this must also have a reachable STUN or TURN server configured: browsers
	/// publish `<uuid>.local` mDNS host candidates instead of private addresses, so without a
	/// server-reflexive path there is frequently no routable candidate pair.
	WEBRTC,

	/// A plain RTP leg facing the SIP network — no ICE, no DTLS. Equivalent to
	/// [NetworkConnection#BASIC]; present so a callflow that chooses per leg can say which it means
	/// instead of relying on the default.
	RTP
}
