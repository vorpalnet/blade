package org.vorpal.blade.framework.config;

import java.text.ParseException;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParameters {

	@JsonPropertyDescription("Set Application Session expiration in minutes.")
	protected Integer expiration = null;

	@JsonPropertyDescription("Set Keep-Alive parameters.")
	protected KeepAliveParameters keepAlive = null;

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
