package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.services.proxy.balancer.EndpointHealth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// The v3 Proxy Balancer configuration.
///
/// Endpoints are defined ONCE in the [#getEndpoints] registry (keyed by name
/// — the stable identity health tracking uses) and referenced by name from
/// plan tiers. A plan is just an ordered array of [Tier]s, keyed by the
/// request URI's host: exact match first, then the longest matching
/// `*.suffix` wildcard key, then `"*"`.
@SchemaAbout(
		name = "Proxy Balancer",
		tagline = "Load-Balancing SIP Proxy",
		description = "Distributes inbound calls across tiered pools of named endpoints — order tiers "
				+ "cheapest-first for least-cost routing. Each tier picks endpoints by strategy — "
				+ "parallel race, serial priority, random, round-robin, or weighted — skips endpoints "
				+ "that are drained or marked down by OPTIONS pings and 503s, and fails over to the "
				+ "next tier on route-level failures (user responses like 486 are relayed, never "
				+ "escalated). Plans are selected by request-URI host with *.suffix wildcards and a "
				+ "\"*\" default. Set session:passthru to drop out of the dialog after call setup; "
				+ "leave it off to stay in the path as a B2BUA.")
public class BalancerConfig extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Runtime health per endpoint NAME (the registry key). Seeded on config
	/// load; written by the OPTIONS ping cycle and live call legs; consulted
	/// by routing; published per node via the EndpointHealth MBean. Health
	/// objects are CARRIED ACROSS config publishes for names that still exist
	/// (see ProxyBalancerSettingsManager) — history and traffic counters
	/// survive; only new names start fresh.
	@JsonIgnore
	public ConcurrentHashMap<String, EndpointHealth> endpointHealth = new ConcurrentHashMap<>();

	/// Balancer config baseline version. The v3 shape (named endpoint
	/// registry, plans as bare tier arrays) is generation 3: a config with no
	/// version reads as 3, and an explicitly-versioned file keeps its value —
	/// so a future upgrader can recognize and transform an older file. Stays
	/// read-only in the Configurator.
	@Override
	@JsonPropertyDescription("Config schema version (framework-managed). The v3 shape — named endpoint registry, plans as tier arrays — is version 3.")
	@FormLayout(readOnly = true)
	public Integer getVersion() {
		return (version == null) ? 3 : version;
	}

	private HealthParameters health = new HealthParameters();

	private HashMap<String, Site> sites = new HashMap<>();

	private String clusterSite;

	private HashMap<String, Endpoint> endpoints = new HashMap<>();

	private HashMap<String, ArrayList<Tier>> plans = new HashMap<>();

	@JsonPropertyDescription("Endpoint health-checking knobs")
	public HealthParameters getHealth() {
		return health;
	}

	public BalancerConfig setHealth(HealthParameters health) {
		this.health = health;
		return this;
	}

	@JsonPropertyDescription("Registry of named sites (datacenters, regions) for the map view; endpoints "
			+ "reference these via their 'site' field. Coordinates optional — a site without lat/lon "
			+ "shows in the map's unplaced tray.")
	public HashMap<String, Site> getSites() {
		return sites;
	}

	public BalancerConfig setSites(HashMap<String, Site> sites) {
		this.sites = sites;
		return this;
	}

	@JsonPropertyDescription("Optional site (a key in 'sites') where this OCCAS cluster runs; the map view "
			+ "draws traffic arcs from here to endpoint sites")
	public String getClusterSite() {
		return clusterSite;
	}

	public BalancerConfig setClusterSite(String clusterSite) {
		this.clusterSite = clusterSite;
		return this;
	}

	@JsonPropertyDescription("Registry of named endpoints; tiers reference these by name. The name is the "
			+ "endpoint's stable identity on the health dashboard.")
	public HashMap<String, Endpoint> getEndpoints() {
		return endpoints;
	}

	public BalancerConfig setEndpoints(HashMap<String, Endpoint> endpoints) {
		this.endpoints = endpoints;
		return this;
	}

	@JsonPropertyDescription("Plans keyed by request-URI host: exact match, then longest *.suffix wildcard, "
			+ "then \"*\" as the default. A plan is an ordered array of tiers — a failed tier fails over "
			+ "to the next.")
	public HashMap<String, ArrayList<Tier>> getPlans() {
		return plans;
	}

	public BalancerConfig setPlans(HashMap<String, ArrayList<Tier>> plans) {
		this.plans = plans;
		return this;
	}

	/// The plan key serving `host`: exact, else the LONGEST matching
	/// `*.suffix` wildcard key, else `"*"` if present, else null. A null host
	/// (non-SIP request URI) goes straight to the default.
	@JsonIgnore
	public String findPlanKey(String host) {
		if (host != null) {
			if (plans.containsKey(host)) {
				return host;
			}
			String best = null;
			for (String key : plans.keySet()) {
				if (key.startsWith("*.") && host.endsWith(key.substring(1))
						&& (best == null || key.length() > best.length())) {
					best = key;
				}
			}
			if (best != null) {
				return best;
			}
		}
		return plans.containsKey("*") ? "*" : null;
	}

}
