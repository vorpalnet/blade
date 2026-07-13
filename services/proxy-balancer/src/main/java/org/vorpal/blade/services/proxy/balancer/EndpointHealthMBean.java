package org.vorpal.blade.services.proxy.balancer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.vorpal.blade.services.proxy.balancer.config.BalancerConfig;
import org.vorpal.blade.services.proxy.balancer.config.Endpoint;
import org.vorpal.blade.services.proxy.balancer.config.Site;
import org.vorpal.blade.services.proxy.balancer.config.Tier;

import com.fasterxml.jackson.databind.ObjectMapper;

/// [EndpointHealthMXBean] implementation. Renders the CURRENT config's plans
/// with this node's live health joined in, so the admin GUI sees exactly what
/// this node's routing sees. Shape:
///
/// ```json
/// { "pingInterval": 60, "pingEnabled": true, "clusterSite": "dc-east",
///   "sites": { "dc-east": { "label": "Eastern DC", "lat": 39.0, "lon": -77.5 } },
///   "plans": { "test1": [ { "tier": 0, "name": "primary", "strategy": "weighted", "timeout": 15,
///       "endpoints": [ { "name": "blade1", "uri": "sip:...", "weight": 3, "enabled": true,
///                        "site": "dc-east", "status": "up", "routable": true, "downUntil": null,
///                        "lastChecked": 0, "note": "unchecked", "lastRttMs": 4,
///                        "attempts": 120, "successes": 118, "failovers": 2 } ] } ] },
///   "endpointDetails": { "blade1": {
///       "samples": [ [ 1751700000000, 1, 4, "ping", "OPTIONS 200" ] ] } } }
/// ```
///
/// The observation ring lives ONLY in `endpointDetails` (keyed by endpoint
/// name) — an endpoint referenced from several tiers would otherwise repeat
/// its full history per tier row. Each sample is a compact array:
/// `[epochMs, up(1|0), rttMs(-1 unmeasured), source, note]`.
public class EndpointHealthMBean implements EndpointHealthMXBean {

	private static final ObjectMapper mapper = new ObjectMapper();

	@Override
	public String getHealthJson() {
		try {
			BalancerConfig config = ProxyBalancerServlet.settingsManager.getCurrent();
			long now = System.currentTimeMillis();

			Map<String, Object> root = new LinkedHashMap<>();
			root.put("pingInterval", config.getHealth().getPingInterval());
			root.put("pingEnabled", config.getHealth().getPingEnabled());
			root.put("clusterSite", config.getClusterSite());

			Map<String, Object> sites = new LinkedHashMap<>();
			for (Entry<String, Site> siteEntry : config.getSites().entrySet()) {
				Map<String, Object> siteMap = new LinkedHashMap<>();
				siteMap.put("label", siteEntry.getValue().getLabel());
				siteMap.put("lat", siteEntry.getValue().getLat());
				siteMap.put("lon", siteEntry.getValue().getLon());
				sites.put(siteEntry.getKey(), siteMap);
			}
			root.put("sites", sites);

			Map<String, Object> plans = new LinkedHashMap<>();
			for (Entry<String, ArrayList<Tier>> planEntry : config.getPlans().entrySet()) {
				List<Object> tiers = new ArrayList<>();
				int index = 0;
				for (Tier tier : planEntry.getValue()) {
					Map<String, Object> tierMap = new LinkedHashMap<>();
					tierMap.put("tier", index++);
					tierMap.put("name", tier.getName());
					tierMap.put("strategy", tier.getStrategy().name());
					tierMap.put("timeout", tier.getTimeout());

					List<Object> endpoints = new ArrayList<>();
					for (String name : tier.getEndpoints()) {
						Map<String, Object> endpointMap = new LinkedHashMap<>();
						endpointMap.put("name", name);

						Endpoint endpoint = config.getEndpoints().get(name);
						if (endpoint == null) {
							endpointMap.put("status", "undefined"); // dangling reference
							endpoints.add(endpointMap);
							continue;
						}
						endpointMap.put("uri", endpoint.getUri());
						endpointMap.put("weight", endpoint.getWeight());
						endpointMap.put("enabled", endpoint.getEnabled());
						endpointMap.put("site", endpoint.getSite());

						EndpointHealth health = config.endpointHealth.get(name);
						if (health != null) {
							endpointMap.put("status", health.getStatus().name());
							endpointMap.put("routable", !endpoint.isDrained() && health.isRoutable(now));
							endpointMap.put("downUntil", health.getDownUntil());
							endpointMap.put("lastChecked", health.getLastChecked());
							endpointMap.put("note", health.getNote());
							endpointMap.put("lastRttMs", health.getLastRttMs());
							endpointMap.put("attempts", health.getAttempts());
							endpointMap.put("successes", health.getSuccesses());
							endpointMap.put("failovers", health.getFailovers());
						} else {
							endpointMap.put("status", "unknown");
							endpointMap.put("routable", !endpoint.isDrained());
						}
						endpoints.add(endpointMap);
					}
					tierMap.put("endpoints", endpoints);
					tiers.add(tierMap);
				}
				plans.put(planEntry.getKey(), tiers);
			}
			root.put("plans", plans);

			// the heavy part, once per endpoint regardless of tier fan-out
			Map<String, Object> details = new LinkedHashMap<>();
			for (Entry<String, EndpointHealth> healthEntry : config.endpointHealth.entrySet()) {
				List<Object> samples = new ArrayList<>();
				for (EndpointHealth.Sample sample : healthEntry.getValue().snapshotSamples()) {
					List<Object> row = new ArrayList<>(5);
					row.add(sample.time);
					row.add(sample.up ? 1 : 0);
					row.add(sample.rttMs);
					row.add(sample.source);
					row.add(sample.note);
					samples.add(row);
				}
				Map<String, Object> detailMap = new LinkedHashMap<>();
				detailMap.put("samples", samples);
				details.put(healthEntry.getKey(), detailMap);
			}
			root.put("endpointDetails", details);

			return mapper.writeValueAsString(root);
		} catch (Exception e) {
			return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
		}
	}

}
