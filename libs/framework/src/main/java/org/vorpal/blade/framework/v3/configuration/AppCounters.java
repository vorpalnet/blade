package org.vorpal.blade.framework.v3.configuration;

import java.util.Map;
import java.util.TreeMap;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// An application's counters across the domain, read on the AdminServer from
/// each server's `vorpal.blade:Name=<app>,Type=Metrics` MBean and summed by
/// name. Counters start at the application's deployment on each server.
public final class AppCounters {

	private AppCounters() {
	}

	/// `{name: total}` for every counter whose name starts with `prefix`,
	/// summed over the servers running `app`. Empty off the AdminServer.
	public static ObjectNode read(String app, String prefix, ObjectMapper mapper) {
		Map<String, Long> totals = new TreeMap<>();
		try {
			MBeanServer dr = ClusterMembership.domainRuntime();
			for (ObjectName on : dr.queryNames(new ObjectName("vorpal.blade:Name=" + app + ",Type=Metrics,*"), null)) {
				Object json = dr.getAttribute(on, "ReportJson");
				if (!(json instanceof String)) continue;
				for (JsonNode c : mapper.readTree((String) json).path("counters")) {
					String name = c.path("name").asText("");
					if (name.startsWith(prefix)) totals.merge(name, c.path("total").asLong(0), Long::sum);
				}
			}
		} catch (Exception e) {
			// Off the AdminServer, or JMX failed: no counters.
		}
		ObjectNode out = mapper.createObjectNode();
		totals.forEach(out::put);
		return out;
	}
}
