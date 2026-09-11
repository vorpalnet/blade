package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Configuration parameters for SIP session management.
 */
public class SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public Integer expiration = 60; //

	@JsonPropertyDescription("Automatically index the session using Vorpal Session? Default is false.")
	public Boolean indexVorpalSessionID = null;

	public KeepAliveParameters keepAlive = null;

	public Boolean expirationProbe = null;

	public Integer maxSessionMinutes = null;

	public List<AttributeSelector> sessionSelectors = null;

	protected boolean passthru = false;

	@JsonPropertyDescription("Proxy drop-out: when true, a forwarding callflow (initial INVITE in, "
			+ "initial INVITE out) stitches the two endpoints' Contacts together and removes OCCAS from "
			+ "the dialog after setup, so the ACK and all in-dialog traffic flow directly between the "
			+ "endpoints. Off by default. Only set on apps that PURELY forward — a callflow that needs to "
			+ "stay in the dialog (hold, transfer, recording) must NOT enable this, since it drops out and "
			+ "cannot re-enter. Applies only to the initial INVITE; in-dialog sends are untouched.")
	public boolean isPassthru() {
		return passthru;
	}

	public SessionParameters setPassthru(boolean passthru) {
		this.passthru = passthru;
		return this;
	}

	@JsonPropertyDescription("List of selectors for creating session (SipApplicationSession) lookup keys.")
	public List<AttributeSelector> getSessionSelectors() {
		return sessionSelectors;
	}

	public void setSessionSelectors(List<AttributeSelector> sessionSelectors) {
		this.sessionSelectors = sessionSelectors;
	}

	@JsonPropertyDescription("Set Application Session expiration in minutes.")
	public Integer getExpiration() {
		return expiration;
	}

	public SessionParameters setExpiration(Integer expiration) {
		this.expiration = expiration;
		return this;
	}

	@JsonPropertyDescription("Set Keep-Alive parameters.")
	public KeepAliveParameters getKeepAlive() {
		return keepAlive;
	}

	public SessionParameters setKeepAlive(KeepAliveParameters keepAlive) {
		this.keepAlive = keepAlive;
		return this;
	}

	@JsonPropertyDescription("Last-chance keep-alive probe when the Application Session expires: "
			+ "re-INVITE both dialogs and keep the session alive only if both endpoints answer. A call an "
			+ "external element is still holding up survives a bookkeeping timeout; a dead call still expires. "
			+ "Default true.")
	public Boolean getExpirationProbe() {
		return expirationProbe;
	}

	public SessionParameters setExpirationProbe(Boolean expirationProbe) {
		this.expirationProbe = expirationProbe;
		return this;
	}

	@JsonPropertyDescription("Hard ceiling in minutes on total session age for the expiration probe: past "
			+ "this age the probe stops and the session is allowed to expire, so a responsive-but-dead endpoint "
			+ "cannot pin a session open forever. Default 720 (12 hours).")
	public Integer getMaxSessionMinutes() {
		return maxSessionMinutes;
	}

	public SessionParameters setMaxSessionMinutes(Integer maxSessionMinutes) {
		this.maxSessionMinutes = maxSessionMinutes;
		return this;
	}

}
