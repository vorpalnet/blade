package org.vorpal.blade.applications.phone;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.security.JwtIssuerConfig;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the WebRTC Phone admin app.
///
/// `@SchemaAbout` is what puts the card on the Admin Portal deck.
///
/// The app is a browser softphone served from the admin tier, but every call it
/// places is negotiated against the `webrtc` service on the **engine** tier. Two
/// tiers, two hosts, and a WebSocket in between — which is what the settings
/// here are about. The page's own login proves who the user is to *this* server
/// and no further; [#getGateway] says where the socket goes, and [#getJwt] and
/// [#getAorDomain] decide what the user is allowed to do once it gets there.
@SchemaAbout(
		name = "Phone",
		tagline = "Browser Softphone",
		description = "Place and receive real SIP calls from the browser, against the WebRTC gateway on the engine tier. "
				+ "Dial another signed-in browser and the audio runs peer-to-peer with no media server; dial a phone number "
				+ "and the call is anchored, because a phone cannot speak ICE or DTLS. The page mints a short-lived signed "
				+ "token for the signed-in user, which is what lets the gateway trust a socket arriving from another host.")
public class PhoneSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String gateway;
	private String aorDomain = "vorpal.net";
	private boolean allowChosenAddress = true;
	private String stunServer = "stun:stun.l.google.com:19302";
	private JwtIssuerConfig jwt = defaultIssuer();

	private static JwtIssuerConfig defaultIssuer() {
		JwtIssuerConfig cfg = new JwtIssuerConfig();
		cfg.setIssuer("urn:blade:phone");
		cfg.setAudience("urn:blade:webrtc");
		return cfg;
	}

	@JsonPropertyDescription("WebSocket URL of the webrtc service on the engine tier, e.g. wss://engine1.example.com:8002/webrtc/signal. Leave blank and the page guesses from its own address, which is right for a single-box install and wrong for every clustered one.")
	public String getGateway() {
		return gateway;
	}

	public void setGateway(String gateway) {
		this.gateway = gateway;
	}

	@JsonPropertyDescription("Domain appended to the signed-in username to form the default address other people dial. A user signed in as 'alice' becomes 'alice@<this>'.")
	public String getAorDomain() {
		return aorDomain;
	}

	public void setAorDomain(String aorDomain) {
		this.aorDomain = (aorDomain == null || aorDomain.trim().isEmpty()) ? "vorpal.net" : aorDomain.trim();
	}

	@JsonPropertyDescription("Whether a signed-in user may be issued a token for an address other than their default. On (the default) because a browser-to-browser call needs two addresses and most deployments have one operator account, so demos and testing need it. Turn it off to bind each person to exactly one address. Either way the caller must be signed in and hold a BLADE role, the token still names the one address the gateway will honour, and the token's subject is always the real username — so a registration is attributable whichever address it took.")
	public boolean isAllowChosenAddress() {
		return allowChosenAddress;
	}

	public void setAllowChosenAddress(boolean allowChosenAddress) {
		this.allowChosenAddress = allowChosenAddress;
	}

	@JsonPropertyDescription("STUN (or TURN) server offered to the browser. Browsers publish <uuid>.local mDNS candidates instead of private addresses, so a reachable server here is required, not optional.")
	public String getStunServer() {
		return stunServer;
	}

	public void setStunServer(String stunServer) {
		this.stunServer = stunServer;
	}

	@JsonPropertyDescription("How this app mints the short-lived token a browser presents to the gateway. The issuer and audience values must match the corresponding settings on the webrtc service, or the gateway will reject every browser.")
	public JwtIssuerConfig getJwt() {
		return jwt;
	}

	public void setJwt(JwtIssuerConfig jwt) {
		this.jwt = (jwt == null) ? defaultIssuer() : jwt;
	}
}
