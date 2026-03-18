package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Represents a primary/secondary server pair for failover routing.
 */
@JsonPropertyOrder({ //
		"primary", //
		"secondary" })
public class ServerPair implements Serializable {
	private static final long serialVersionUID = 1L;

	private String primary = null;
	private String secondary = null;

	public ServerPair() {
	}

	public ServerPair(String primary, String secondary) {
		this.primary = primary;
		this.secondary = secondary;
	}

	public String getPrimary() {
		return primary;
	}

	public ServerPair setPrimary(String primary) {
		this.primary = primary;
		return this;
	}

	public String getSecondary() {
		return secondary;
	}

	public ServerPair setSecondary(String secondary) {
		this.secondary = secondary;
		return this;
	}

}
