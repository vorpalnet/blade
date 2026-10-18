package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Endpoint health-checking knobs.
public class HealthParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	private Boolean pingEnabled = true;
	private Integer pingInterval = 60;
	private Integer defaultBackoff = 0;
	private List<Integer> failoverOn = new ArrayList<>(Arrays.asList(408, 502, 503, 504));
	private List<Integer> markDownOn = new ArrayList<>(Arrays.asList(408, 503));

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

	@JsonPropertyDescription("INVITE responses that move the call on: to the tier's next endpoint, then to the next tier. "
			+ "Only route and server failures belong here. A 404, 486 or 401 would come back the same from every "
			+ "endpoint, so retrying it multiplies traffic for a call that cannot complete. Anything not listed "
			+ "goes straight back to the caller. Default [408, 502, 503, 504].")
	public List<Integer> getFailoverOn() {
		return failoverOn;
	}

	public HealthParameters setFailoverOn(List<Integer> failoverOn) {
		this.failoverOn = failoverOn;
		return this;
	}

	@JsonPropertyDescription("INVITE responses that take the endpoint out of service. A 503 is a refusal; its "
			+ "Retry-After is honored, else defaultBackoff. A 408 counts only when nothing came back at all, so "
			+ "the container made the 408 itself: an endpoint that answered 408 is alive, and stays in service "
			+ "while the call moves on. Out until the Retry-After or backoff passes, or a ping answers. "
			+ "Default [408, 503].")
	public List<Integer> getMarkDownOn() {
		return markDownOn;
	}

	public HealthParameters setMarkDownOn(List<Integer> markDownOn) {
		this.markDownOn = markDownOn;
		return this;
	}

}
