package org.vorpal.blade.framework.v3.security;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Configuration for minting short-lived bearer JWTs — the issuing counterpart
/// to [JwtAuthConfig].
///
/// ## Why BLADE issues tokens at all
///
/// An enterprise IdP is the right source of identity for a *person*, and when a
/// deployment has one, [JwtAuthConfig] consumes its tokens and this class is not
/// used. But some tokens carry no new identity at all: they re-state a fact the
/// container already established, so it can survive a hop the container's own
/// session cookie cannot make — a different host, a different port, or a
/// protocol whose client API cannot set an `Authorization` header.
///
/// That is a token-carrying problem, not an identity problem, and routing it
/// through a third party would add an outage mode without adding a fact. So the
/// app that already authenticated the caller mints the token itself.
///
/// ## The key is deliberately ephemeral
///
/// [JwtIssuer] generates an RSA keypair when it is built and publishes the
/// public half as a JWKS, exactly as an IdP does. Nothing persists it and
/// nothing rotates it, because no token outlives a restart: at [#getTtlSeconds]
/// of a minute or less, every token minted before a restart has expired by the
/// time the process is serving again. Adding key storage would buy nothing and
/// would put a private key on disk.
///
/// ## Unlike [JwtAuthConfig], this defaults to enabled
///
/// [JwtAuthConfig] defaults to disabled because it is *additive* — it layers a
/// second front door onto apps that already have a working FORM/BASIC login, so
/// shipping it dormant changes nothing. An issuer is not additive: an app embeds
/// one only when something downstream requires the token it mints, and an issuer
/// that will not issue simply breaks that app. The decision to have an issuer is
/// the app's; the switch here is an operator's override.
public class JwtIssuerConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	private boolean enabled = true;
	private String issuer;
	private String audience;
	private String rolesClaim = "groups";
	private int ttlSeconds = 60;

	@JsonPropertyDescription("Master switch for minting. When false this app issues no tokens, and whatever consumes them refuses the caller. Leave on unless you are deliberately taking the feature out of service.")
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@JsonPropertyDescription("Value written to the token's 'iss' claim. The consumer matches this exactly, so the two settings must agree. e.g. urn:blade:phone")
	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	@JsonPropertyDescription("Value written to the token's 'aud' claim — who the token is FOR. Naming the intended consumer stops a token minted for one service being replayed against another. e.g. urn:blade:webrtc")
	public String getAudience() {
		return audience;
	}

	public void setAudience(String audience) {
		this.audience = audience;
	}

	@JsonPropertyDescription("Claim name that carries the caller's roles. Must match the consumer's rolesClaim setting. Default 'groups'.")
	public String getRolesClaim() {
		return rolesClaim;
	}

	public void setRolesClaim(String rolesClaim) {
		this.rolesClaim = (rolesClaim == null || rolesClaim.trim().isEmpty()) ? "groups" : rolesClaim.trim();
	}

	@JsonPropertyDescription("How long a minted token stays valid, in seconds. These tokens are presented once, immediately, so this only has to cover the round trip; keep it short. Default 60.")
	public int getTtlSeconds() {
		return ttlSeconds;
	}

	public void setTtlSeconds(int ttlSeconds) {
		this.ttlSeconds = (ttlSeconds < 1) ? 1 : ttlSeconds;
	}
}
