package org.vorpal.blade.applications.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Discovers every BLADE application's metrics MBean and aggregates them across
/// the cluster.
///
/// **This is the half that was missing.** `ConfigurationMonitor.queryApps()` has
/// walked the JMX tree for BLADE apps for a long time — the code is correct and
/// has never had a caller. The commented-out line in it proxies `SettingsMXBean`,
/// which carries configuration and not one number, and that is where whoever
/// wrote it stopped. The walk was never the problem; there was nothing to read.
/// `Type=Metrics` is that surface, and this is the walk finally pointed at it.
///
/// **Aggregation rules, and the one that matters.** Counters sum across nodes.
/// Gauges sum, since each node reports its own share. Histograms **sum their
/// bucket arrays** — never their percentiles, which do not merge. Every node
/// publishes identical bucket boundaries precisely so the total distribution can
/// be reconstructed here and a true cluster p95 computed from it, instead of
/// showing the worst node's figure the way the Test Console has to.
public final class MetricsCollector {

	private static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// Every BLADE app that registers a metrics MBean, on any node.
	private static final String PATTERN = "vorpal.blade:Name=*,Type=Metrics,*";

	/// The same pattern without the trailing wildcard, for an AdminServer-local
	/// MBean, which carries no `Cluster` key.
	private static final String PATTERN_LOCAL = "vorpal.blade:Name=*,Type=Metrics";

	private MetricsCollector() {
	}

	/// Collect and aggregate every app's metrics.
	///
	/// Returns a document of the shape
	/// `{ apps: [ { app, nodes, counters, gauges, histograms } ], error? }`.
	public static ObjectNode collect() {
		ObjectNode result = MAPPER.createObjectNode();
		ArrayNode apps = result.putArray("apps");

		try (Ctx ctx = new Ctx()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);

			// app name -> the per-node reports it published
			Map<String, List<JsonNode>> byApp = new LinkedHashMap<>();
			for (ObjectName name : discover(mbs)) {
				try {
					Object json = mbs.getAttribute(name, "ReportJson");
					if (!(json instanceof String)) {
						continue;
					}
					JsonNode report = MAPPER.readTree((String) json);
					String app = report.path("app").asText(name.getKeyProperty("Name"));
					byApp.computeIfAbsent(app, k -> new ArrayList<>()).add(report);
				} catch (Exception e) {
					// One unreadable app must not blank the whole page.
				}
			}

			for (Map.Entry<String, List<JsonNode>> entry : byApp.entrySet()) {
				apps.add(aggregate(entry.getKey(), entry.getValue()));
			}
		} catch (Exception e) {
			result.put("error", String.valueOf(e));
		}
		return result;
	}

	private static List<ObjectName> discover(MBeanServer mbs) throws Exception {
		List<ObjectName> found = new ArrayList<>();
		for (ObjectInstance instance : mbs.queryMBeans(new ObjectName(PATTERN), null)) {
			found.add(instance.getObjectName());
		}
		for (ObjectInstance instance : mbs.queryMBeans(new ObjectName(PATTERN_LOCAL), null)) {
			if (!found.contains(instance.getObjectName())) {
				found.add(instance.getObjectName());
			}
		}
		return found;
	}

	/// Fold one app's per-node reports into a single view.
	private static ObjectNode aggregate(String app, List<JsonNode> reports) {
		ObjectNode out = MAPPER.createObjectNode();
		out.put("app", app);

		ArrayNode nodes = out.putArray("nodes");
		long uptime = 0;
		for (JsonNode report : reports) {
			ObjectNode node = nodes.addObject();
			node.put("node", report.path("node").asText("(unnamed)"));
			node.put("cluster", report.path("cluster").asText(null));
			node.put("uptimeMillis", report.path("uptimeMillis").asLong());
			uptime = Math.max(uptime, report.path("uptimeMillis").asLong());
		}
		out.put("uptimeMillis", uptime);

		sumCounters(out, reports);
		sumGauges(out, reports);
		mergeHistograms(out, reports);
		return out;
	}

	/// Counters add, including their per-label breakdowns.
	private static void sumCounters(ObjectNode out, List<JsonNode> reports) {
		Map<String, ObjectNode> merged = new LinkedHashMap<>();
		for (JsonNode report : reports) {
			for (JsonNode counter : report.path("counters")) {
				String name = counter.path("name").asText();
				ObjectNode target = merged.get(name);
				if (target == null) {
					target = MAPPER.createObjectNode();
					target.put("name", name);
					target.put("description", counter.path("description").asText(null));
					target.put("label", counter.path("label").asText(null));
					target.put("total", 0L);
					target.putObject("values");
					merged.put(name, target);
				}
				target.put("total", target.path("total").asLong() + counter.path("total").asLong());

				ObjectNode values = (ObjectNode) target.path("values");
				counter.path("values").fields().forEachRemaining(field -> values.put(field.getKey(),
						values.path(field.getKey()).asLong() + field.getValue().asLong()));
			}
		}
		ArrayNode counters = out.putArray("counters");
		merged.values().forEach(counters::add);
	}

	/// Gauges add. Each node reports its own share of a cluster-wide quantity —
	/// active sessions on this node, not the cluster's — so the sum is the
	/// cluster figure.
	private static void sumGauges(ObjectNode out, List<JsonNode> reports) {
		Map<String, ObjectNode> merged = new LinkedHashMap<>();
		for (JsonNode report : reports) {
			for (JsonNode gauge : report.path("gauges")) {
				String name = gauge.path("name").asText();
				ObjectNode target = merged.computeIfAbsent(name, k -> {
					ObjectNode node = MAPPER.createObjectNode();
					node.put("name", k);
					node.put("description", gauge.path("description").asText(null));
					node.put("value", 0L);
					return node;
				});
				target.put("value", target.path("value").asLong() + gauge.path("value").asLong());
			}
		}
		ArrayNode gauges = out.putArray("gauges");
		merged.values().forEach(gauges::add);
	}

	/// Histograms merge by summing bucket arrays, then the cluster percentiles
	/// are computed from the total.
	///
	/// This is the whole reason the wire format carries `boundsMillis` and
	/// `bucketCounts` rather than percentiles alone. Averaging per-node
	/// percentiles is meaningless, and taking the worst node's is the compromise
	/// this exists to avoid.
	private static void mergeHistograms(ObjectNode out, List<JsonNode> reports) {
		Map<String, ObjectNode> merged = new LinkedHashMap<>();
		Map<String, long[]> buckets = new LinkedHashMap<>();
		Map<String, long[]> bounds = new LinkedHashMap<>();

		for (JsonNode report : reports) {
			for (JsonNode histogram : report.path("histograms")) {
				String name = histogram.path("name").asText();
				long[] theseBounds = longs(histogram.path("boundsMillis"));
				long[] theseCounts = longs(histogram.path("bucketCounts"));

				ObjectNode target = merged.get(name);
				if (target == null) {
					target = MAPPER.createObjectNode();
					target.put("name", name);
					target.put("description", histogram.path("description").asText(null));
					target.put("count", 0L);
					target.put("maxMillis", 0L);
					merged.put(name, target);
					bounds.put(name, theseBounds);
					buckets.put(name, new long[theseCounts.length]);
				}

				long[] running = buckets.get(name);
				if (running.length != theseCounts.length) {
					// Different boundary sets cannot be added. This means a node
					// is running a build with different bucket bounds — a real
					// condition during a rolling upgrade, and silently summing
					// them would produce a plausible, wrong distribution.
					target.put("mismatchedBounds", true);
					continue;
				}
				for (int i = 0; i < running.length; i++) {
					running[i] += theseCounts[i];
				}
				target.put("count", target.path("count").asLong() + histogram.path("count").asLong());
				target.put("maxMillis",
						Math.max(target.path("maxMillis").asLong(), histogram.path("maxMillis").asLong()));
			}
		}

		ArrayNode out2 = out.putArray("histograms");
		for (Map.Entry<String, ObjectNode> entry : merged.entrySet()) {
			ObjectNode target = entry.getValue();
			long[] counts = buckets.get(entry.getKey());
			long[] theseBounds = bounds.get(entry.getKey());

			ArrayNode countsOut = target.putArray("bucketCounts");
			for (long value : counts) {
				countsOut.add(value);
			}
			ArrayNode boundsOut = target.putArray("boundsMillis");
			for (long value : theseBounds) {
				boundsOut.add(value);
			}
			target.put("p50Millis", percentile(counts, theseBounds, 0.50, target.path("maxMillis").asLong()));
			target.put("p90Millis", percentile(counts, theseBounds, 0.90, target.path("maxMillis").asLong()));
			target.put("p99Millis", percentile(counts, theseBounds, 0.99, target.path("maxMillis").asLong()));
			out2.add(target);
		}
	}

	/// The boundary of the bucket a quantile falls into, over the summed
	/// distribution — a true cluster percentile rather than a per-node one.
	private static long percentile(long[] counts, long[] bounds, double quantile, long max) {
		long total = 0;
		for (long count : counts) {
			total += count;
		}
		if (total == 0) {
			return 0;
		}
		long target = (long) Math.ceil(quantile * total);
		long running = 0;
		for (int i = 0; i < counts.length; i++) {
			running += counts[i];
			if (running >= target) {
				return (i < bounds.length) ? bounds[i] : max;
			}
		}
		return max;
	}

	private static long[] longs(JsonNode array) {
		if (!array.isArray()) {
			return new long[0];
		}
		long[] values = new long[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).asLong();
		}
		return values;
	}

	private static final class Ctx extends InitialContext implements AutoCloseable {
		Ctx() throws javax.naming.NamingException {
			super();
		}
	}
}
