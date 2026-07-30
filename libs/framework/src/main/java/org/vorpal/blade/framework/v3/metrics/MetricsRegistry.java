package org.vorpal.blade.framework.v3.metrics;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.LongSupplier;

import javax.servlet.ServletContext;

/// Every metric one deployed app keeps on one node.
///
/// **Getting one.** [#from(ServletContext)] — the same shape as
/// `TesterMetrics.from`, and the reason app code never has to know its own name
/// or wire anything up. `SettingsManager` creates the registry and publishes the
/// MBean when the app starts, so by the time app code asks, it exists.
///
/// **Declare once, use forever.** Registration is idempotent by name: calling
/// [#counter] twice with the same name returns the same instance, so a servlet
/// that re-initializes does not lose its counts or double-register. Declare
/// metrics at startup, hold the returned objects (or a [Counter.Series]) in
/// fields, and never look one up on a call path.
///
/// **Node-local, never in session state.** This is per-JVM, per-deployment
/// state; each node counts what it sees and the admin tier aggregates. Nothing
/// here is a clustered singleton and nothing here may be reachable from a
/// serialized SIP session — BLADE serializes session state for cluster failover,
/// so a counter stored there would be serialized on every call, which is exactly
/// the cost people assume counters carry.
public final class MetricsRegistry {

	/// ServletContext attribute the registry is stashed under.
	public static final String ATTR = "org.vorpal.blade.metrics";

	private final Map<String, Counter> counters = new ConcurrentSkipListMap<>();
	private final Map<String, Gauge> gauges = new ConcurrentSkipListMap<>();
	private final Map<String, Histogram> histograms = new ConcurrentSkipListMap<>();
	private final long created = System.currentTimeMillis();

	/// Per-JVM registries by app name, for the small number of callers that have
	/// a name but no ServletContext.
	private static final ConcurrentHashMap<String, MetricsRegistry> BY_APP = new ConcurrentHashMap<>();

	MetricsRegistry() {
	}

	/// This deployment's registry, creating it on first use.
	///
	/// @param servletContext the app's context; null yields null rather than
	///                       throwing, so a caller outside a container degrades
	///                       to no metrics instead of failing
	public static MetricsRegistry from(ServletContext servletContext) {
		if (servletContext == null) {
			return null;
		}
		synchronized (servletContext) {
			Object existing = servletContext.getAttribute(ATTR);
			if (existing instanceof MetricsRegistry) {
				return (MetricsRegistry) existing;
			}
			MetricsRegistry registry = new MetricsRegistry();
			servletContext.setAttribute(ATTR, registry);
			return registry;
		}
	}

	/// The registry for a named app on this node, creating it on first use.
	/// Prefer [#from(ServletContext)]; this exists for code that has only the
	/// app name.
	public static MetricsRegistry forApp(String app) {
		return BY_APP.computeIfAbsent(app, key -> new MetricsRegistry());
	}

	/// Bind a registry to an app name so [#forApp] finds the same instance the
	/// ServletContext holds.
	public static void bind(String app, MetricsRegistry registry) {
		if (app != null && registry != null) {
			BY_APP.put(app, registry);
		}
	}

	/// Forget a name binding at undeploy.
	public static void unbind(String app) {
		if (app != null) {
			BY_APP.remove(app);
		}
	}

	/// Declare an unlabeled counter.
	public Counter counter(String name, String description) {
		return counters.computeIfAbsent(name, key -> new Counter(key, description));
	}

	/// Declare a counter broken out by one label with a **finite, declared**
	/// value set.
	///
	/// The value list is the contract: anything outside it is counted under
	/// [Counter#OTHER] and logged once. This is enforced here, at registration,
	/// rather than trusted to discipline — an unbounded key is the one mistake
	/// that turns metrics into an outage.
	///
	/// @param labelName   the dimension, e.g. `outcome`
	/// @param labelValues every value it can take
	public Counter counter(String name, String description, String labelName, List<String> labelValues) {
		return counters.computeIfAbsent(name, key -> new Counter(key, description, labelName, labelValues));
	}

	/// Declare a latency histogram on the default boundaries.
	public Histogram histogram(String name, String description) {
		return histograms.computeIfAbsent(name, key -> new Histogram(key, description));
	}

	/// Declare a latency histogram on explicit boundaries. Use the default
	/// unless there is a reason — identical boundaries across apps are what let
	/// the console compare them.
	public Histogram histogram(String name, String description, long[] boundsMillis) {
		return histograms.computeIfAbsent(name, key -> new Histogram(key, description, boundsMillis));
	}

	/// Declare a gauge. The supplier runs on the reporting thread, so it must be
	/// cheap and must not block.
	public Gauge gauge(String name, String description, LongSupplier supplier) {
		return gauges.computeIfAbsent(name, key -> new Gauge(key, description, supplier));
	}

	/// An already-declared counter, or null.
	public Counter findCounter(String name) {
		return counters.get(name);
	}

	/// An already-declared histogram, or null.
	public Histogram findHistogram(String name) {
		return histograms.get(name);
	}

	/// How many metrics are declared — counters, gauges and histograms together.
	public int size() {
		return counters.size() + gauges.size() + histograms.size();
	}

	/// Zero every counter and histogram. Gauges are read-through and unaffected.
	public void reset() {
		for (Counter counter : counters.values()) {
			counter.reset();
		}
		for (Histogram histogram : histograms.values()) {
			histogram.reset();
		}
	}

	/// Snapshot everything for one node.
	///
	/// @param app     the app's flattened context name
	/// @param node    the WebLogic server name
	/// @param cluster the cluster name, or null on a standalone server
	public MetricsReport report(String app, String node, String cluster) {
		MetricsReport report = new MetricsReport();
		report.setApp(app);
		report.setNode(node);
		report.setCluster(cluster);
		report.setTimestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
		report.setUptimeMillis(System.currentTimeMillis() - created);

		List<CounterReport> counterReports = new ArrayList<>(counters.size());
		for (Counter counter : counters.values()) {
			counterReports.add(counter.report());
		}
		report.setCounters(counterReports);

		List<GaugeReport> gaugeReports = new ArrayList<>(gauges.size());
		for (Gauge gauge : gauges.values()) {
			gaugeReports.add(gauge.report());
		}
		report.setGauges(gaugeReports);

		List<HistogramReport> histogramReports = new ArrayList<>(histograms.size());
		for (Histogram histogram : histograms.values()) {
			histogramReports.add(histogram.report());
		}
		report.setHistograms(histogramReports);

		return report;
	}
}
