package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Set Application Session expiration in minutes.")
	public Integer expiration = null;

	@JsonPropertyDescription("Automatically index the session using X-Vorpal-Session value? Default is false.")
	public Boolean indexVorpalSessionID = null;

	@JsonPropertyDescription("Set Keep-Alive parameters.")
	public KeepAliveParameters keepAlive = null;

	@JsonPropertyDescription("List of selectors for creating session (SipApplicationSession) lookup keys.")
	public List<AttributeSelector> sessionSelectors = null;

	public List<AttributeSelector> getSessionSelectors() {
		return sessionSelectors;
	}

	public void setSessionSelectors(List<AttributeSelector> sessionSelectors) {
		this.sessionSelectors = sessionSelectors;
	}

	public Integer getExpiration() {
		return expiration;
	}

	public SessionParameters setExpiration(Integer expiration) {
		this.expiration = expiration;
		return this;
	}

	public KeepAliveParameters getKeepAlive() {
		return keepAlive;
	}

	public SessionParameters setKeepAlive(KeepAliveParameters keepAlive) {
		this.keepAlive = keepAlive;
		return this;
	}

}
