package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Configuration parameters for SIP session management.
 */
public class SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public Integer expiration = null;

	@JsonPropertyDescription("Automatically index the session using Vorpal Session? Default is false.")
	public Boolean indexVorpalSessionID = null;

	public KeepAliveParameters keepAlive = null;

	public List<AttributeSelector> sessionSelectors = null;

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
