package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParameters implements Serializable{
	private static final long serialVersionUID = 1L;

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
