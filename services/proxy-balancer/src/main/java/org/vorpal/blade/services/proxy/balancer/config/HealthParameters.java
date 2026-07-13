package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Endpoint health-checking knobs.
public class HealthParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	private Boolean pingEnabled = true;
	private Integer pingInterval = 60;
	private Integer defaultBackoff = 0;

	@JsonPropertyDescription("Run the periodic OPTIONS ping cycle on every node. When false, health comes "
			+ "from live traffic only — set a defaultBackoff so a 503'd endpoint can recover.")
	@JsonProperty(defaultValue = "true")
	public Boolean getPingEnabled() {
		return pingEnabled;
	}

	public HealthParameters setPingEnabled(Boolean pingEnabled) {
		this.pingEnabled = pingEnabled;
		return this;
	}

	@JsonPropertyDescription("Seconds between OPTIONS health-check cycles")
	@JsonProperty(defaultValue = "60")
	public Integer getPingInterval() {
		return pingInterval;
	}

	public HealthParameters setPingInterval(Integer pingInterval) {
		this.pingInterval = pingInterval;
		return this;
	}

	@JsonPropertyDescription("Seconds an endpoint stays down after a 503 WITHOUT a Retry-After header. "
			+ "0 means down until an OPTIONS ping (or a later 2xx) revives it.")
	@JsonProperty(defaultValue = "0")
	public Integer getDefaultBackoff() {
		return defaultBackoff;
	}

	public HealthParameters setDefaultBackoff(Integer defaultBackoff) {
		this.defaultBackoff = defaultBackoff;
		return this;
	}

}
