package org.vorpal.blade.services.webrtc;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.security.JwtAuthConfig;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Configuration for the WebRTC gateway.
///
/// ## The authentication setting is the whole point of this class
///
/// A browser arrives here over a WebSocket and names an address it wants to
/// receive calls on. Nothing about the socket itself says who is on the other
/// end: it came from a page served by a different server on a different port,
/// and the browser WebSocket API cannot carry an `Authorization` header. Left
/// alone, "who are you" is answered by whatever the client typed.
///
/// So the browser presents a signed token instead, and [#getJwt] is how this
/// service decides whether to believe it. The token is minted by the phone app
/// on the admin tier, which authenticated the user against the WebLogic realm
/// first — but nothing here depends on that being the issuer. These are the same
/// [JwtAuthConfig] fields that would describe Okta or Entra, and pointing them at
/// a corporate IdP instead is a configuration change with no code behind it.
///
/// **`jwt.enabled` false means anyone can claim any address.** It is not a
/// no-op the way it is on the admin tier, where the container's own FORM login
/// is still there underneath; here there is nothing underneath. The sample
/// config ships it on, and the service logs at SEVERE when it starts up without
/// it, because a gateway that will place calls for unauthenticated strangers
/// should not be a quiet default.
@SchemaAbout(
		name = "WebRTC Gateway",
		tagline = "Browsers as SIP endpoints",
		description = "Terminates browser WebSocket signaling and turns it into ordinary SIP, so a browser is a first-class "
				+ "endpoint on the network without speaking SIP itself. Browser-to-browser calls relay peer-to-peer with no "
				+ "media server; calls to a phone are anchored on one, because a phone cannot speak ICE or DTLS-SRTP. "
				+ "Browsers authenticate with a short-lived signed token, which also names the one address each may claim.")
public class WebrtcSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private JwtAuthConfig jwt = defaultJwt();
	private Integer registerExpiresSeconds = 3600;
	private MediaMode mediaMode = MediaMode.AUTO;

	private static JwtAuthConfig defaultJwt() {
		JwtAuthConfig cfg = new JwtAuthConfig();
		cfg.setIssuer("urn:blade:phone");
		cfg.setAudience("urn:blade:webrtc");
		cfg.setRolesClaim("groups");
		return cfg;
	}

	@JsonPropertyDescription("How a browser proves who it is. 'jwksUri' must point at the token issuer's public keys — for the bundled phone app that is https://<admin-host>:7002/blade/phone/api/v1/jwks.json — and 'issuer' and 'audience' must match what that app is configured to mint. Turning 'enabled' off lets any browser claim any address; there is no second login behind this one.")
	public JwtAuthConfig getJwt() {
		return jwt;
	}

	public void setJwt(JwtAuthConfig jwt) {
		this.jwt = (jwt == null) ? defaultJwt() : jwt;
	}

	@JsonPropertyDescription("Expires value, in seconds, on the SIP REGISTER this gateway sends the location service on a browser's behalf. A browser re-registers on every reconnect; a tab left open longer than this without reconnecting ages out of the location service (its socket stays up and browser-to-browser still works). Default 3600.")
	public Integer getRegisterExpiresSeconds() {
		return registerExpiresSeconds;
	}

	public void setRegisterExpiresSeconds(Integer registerExpiresSeconds) {
		this.registerExpiresSeconds = (registerExpiresSeconds == null || registerExpiresSeconds < 1)
				? 3600 : registerExpiresSeconds;
	}

	@JsonPropertyDescription("Where a call's MEDIA goes; signaling always rides SIP regardless. AUTO (default) relays peer-to-peer when the dialed target is a browser on this node, otherwise anchors on the media server when one is installed and relays when none is. ANCHOR forces the media server into every call (required for recording). RELAY forces peer-to-peer media — calls to phones will not get audio, since a phone cannot answer a DTLS-SRTP offer.")
	public MediaMode getMediaMode() {
		return mediaMode;
	}

	public void setMediaMode(MediaMode mediaMode) {
		this.mediaMode = (mediaMode == null) ? MediaMode.AUTO : mediaMode;
	}
}
