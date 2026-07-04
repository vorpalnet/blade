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

}
