package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One priority band of a plan. A plan is an ordered array of tiers: a
/// failed tier fails over to the next. Within the tier, `strategy` decides
/// how its endpoints (referenced by registry name) are offered the call.
public class Tier implements Serializable {
	private static final long serialVersionUID = 1L;

	/// How a tier offers the call to its endpoints.
	public enum Strategy {
		/** Race all endpoints simultaneously; first success wins */
		parallel,
		/** Hunt one at a time in listed (priority) order */
		serial,
		/** Hunt in random order — equal-cost distribution */
		random,
		/** Hunt from a per-node rotating offset */
		roundrobin,
		/** Hunt in smooth weighted round-robin order (endpoint weights, per-node counter) */
		weighted
	}

	private String name;
	private Strategy strategy = Strategy.serial;
	private Integer timeout = 180;
	private List<String> endpoints = new ArrayList<>();

	@JsonPropertyDescription("Display name of this tier, shown on the health dashboard")
	@JsonProperty(required = true)
	public String getName() {
		return name;
	}

	public Tier setName(String name) {
		this.name = name;
		return this;
	}

	@JsonPropertyDescription("How this tier offers the call to its endpoints: parallel race, serial priority, "
			+ "random, roundrobin, or weighted")
	@JsonProperty(defaultValue = "serial", required = true)
	public Strategy getStrategy() {
		return strategy;
	}

	public Tier setStrategy(Strategy strategy) {
		this.strategy = strategy;
		return this;
	}

	@JsonPropertyDescription("Seconds a leg may ring before it is canceled (serial hunts advance; parallel races give up). "
			+ "Also the whole tier's budget in a parallel race.")
	@JsonProperty(defaultValue = "180", required = true)
	public Integer getTimeout() {
		return timeout;
	}

	public Tier setTimeout(Integer timeout) {
		this.timeout = timeout;
		return this;
	}

	@JsonPropertyDescription("Names of endpoints (from the top-level endpoints registry) in this tier")
	@JsonProperty(required = true)
	public List<String> getEndpoints() {
		return endpoints;
	}

	public Tier setEndpoints(List<String> endpoints) {
		this.endpoints = endpoints;
		return this;
	}

	public Tier addEndpoint(String name) {
		this.endpoints.add(name);
		return this;
	}

}
