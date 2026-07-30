package org.vorpal.blade.framework.v3.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Covers the metrics contract: bounded cardinality, mergeable histograms, and
/// the concurrency the whole design rests on.
///
/// The two tests that matter most are
/// [Cardinality#undeclaredLabelsCollapseIntoOneBucket] — the rule that keeps an
/// unbounded key from taking a node down — and
/// [Merging#bucketArraysFromTwoNodesAddUp], which is the property that lets the
/// admin tier compute a true cluster percentile instead of showing the worst
/// node the way the Test Console has to.
class MetricsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Nested
	@DisplayName("counters")
	class Counters {

		@Test
		void unlabeledCounterCounts() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("calls", "Calls handled");
			counter.increment();
			counter.increment();
			assertEquals(2, counter.total());
		}

		@Test
		void labeledCounterBreaksOutByLabel() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("outcomes", "Call outcomes", "outcome",
					Arrays.asList("answered", "busy", "failed"));
			counter.increment("answered");
			counter.increment("answered");
			counter.increment("busy");

			assertEquals(2, counter.value("answered"));
			assertEquals(1, counter.value("busy"));
			assertEquals(0, counter.value("failed"));
			assertEquals(3, counter.total());
		}

		@Test
		@DisplayName("a resolved Series is the same cell as the label it came from")
		void seriesHandleSharesTheCell() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("outcomes", "Call outcomes", "outcome", Arrays.asList("answered"));
			Counter.Series answered = counter.series("answered");

			answered.increment();
			counter.increment("answered");

			assertEquals(2, counter.value("answered"));
			assertEquals(2, answered.value());
		}

		@Test
		void resetZeroesEveryCell() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("outcomes", "Call outcomes", "outcome", Arrays.asList("a", "b"));
			counter.increment("a");
			counter.increment("b");
			counter.reset();
			assertEquals(0, counter.total());
		}
	}

	@Nested
	@DisplayName("cardinality is enforced, not trusted")
	class Cardinality {

		@Test
		@DisplayName("an undeclared label lands in (other) rather than creating a new cell")
		void undeclaredLabelsCollapseIntoOneBucket() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("outcomes", "Call outcomes", "outcome", Arrays.asList("answered"));

			// Stand-in for the failure mode this exists to prevent: a counter
			// keyed by something unbounded, like a Call-ID.
			for (int i = 0; i < 10000; i++) {
				counter.increment("call-id-" + i);
			}

			assertEquals(10000, counter.value(Counter.OTHER));
			assertEquals(2, counter.getLabelValues().size(), "declared value plus (other) — nothing new was created");
			assertEquals(10000, counter.total());
		}

		@Test
		void aNullLabelIsAlsoOther() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("outcomes", "Call outcomes", "outcome", Arrays.asList("answered"));
			counter.increment(null);
			assertEquals(1, counter.value(Counter.OTHER));
		}

		@Test
		void declaringALabelWithNoValuesIsRejected() {
			MetricsRegistry registry = new MetricsRegistry();
			assertThrows(IllegalArgumentException.class,
					() -> registry.counter("bad", "no values", "outcome", new ArrayList<String>()));
		}

		@Test
		@DisplayName("an oversized value set is rejected — it is an unbounded key in disguise")
		void anOversizedLabelSpaceIsRejected() {
			MetricsRegistry registry = new MetricsRegistry();
			List<String> tooMany = new ArrayList<>();
			for (int i = 0; i <= Counter.MAX_LABEL_VALUES; i++) {
				tooMany.add("v" + i);
			}
			assertThrows(IllegalArgumentException.class, () -> registry.counter("bad", "too many", "id", tooMany));
		}
	}

	@Nested
	@DisplayName("histograms")
	class Histograms {

		@Test
		void observationsLandInTheRightBuckets() {
			Histogram histogram = new Histogram("latency", "Handler latency", new long[] { 10, 100 });
			histogram.record(5);    // bucket 0  (<= 10)
			histogram.record(10);   // bucket 0  (boundary is inclusive)
			histogram.record(50);   // bucket 1  (<= 100)
			histogram.record(5000); // bucket 2  (overflow)

			long[] counts = histogram.report().getBucketCounts();
			assertEquals(3, counts.length, "one bucket per boundary plus an overflow bucket");
			assertEquals(2, counts[0]);
			assertEquals(1, counts[1]);
			assertEquals(1, counts[2]);
			assertEquals(4, histogram.count());
			assertEquals(5000, histogram.maxMillis());
		}

		@Test
		@DisplayName("a clock going backwards is ignored, not thrown")
		void negativeObservationsAreIgnored() {
			Histogram histogram = new Histogram("latency", "Handler latency");
			histogram.record(-1);
			assertEquals(0, histogram.count());
		}

		@Test
		void boundariesMustAscend() {
			assertThrows(IllegalArgumentException.class,
					() -> new Histogram("bad", "descending", new long[] { 100, 10 }));
		}

		@Test
		void defaultBoundariesMatchTheTesterSoTheyCanBeCompared() {
			assertArrayEquals(new long[] { 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000 },
					Histogram.DEFAULT_BOUNDS_MS);
		}

		@Test
		void percentilesTrackTheDistribution() {
			Histogram histogram = new Histogram("latency", "Handler latency", new long[] { 10, 100, 1000 });
			for (int i = 0; i < 99; i++) {
				histogram.record(5);
			}
			histogram.record(900);
			assertEquals(10, histogram.percentileMillis(0.50));
			assertEquals(1000, histogram.percentileMillis(0.999));
		}

		private void assertArrayEquals(long[] expected, long[] actual) {
			assertEquals(expected.length, actual.length);
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], actual[i], "index " + i);
			}
		}
	}

	@Nested
	@DisplayName("histograms merge across nodes")
	class Merging {

		@Test
		@DisplayName("summing two nodes' bucket arrays gives the true cluster distribution")
		void bucketArraysFromTwoNodesAddUp() {
			long[] bounds = { 10, 100, 1000 };

			Histogram nodeA = new Histogram("latency", "", bounds);
			Histogram nodeB = new Histogram("latency", "", bounds);

			// Node A is fast, node B is slow. A per-node percentile would report
			// one or the other; the merged distribution reports the truth.
			for (int i = 0; i < 90; i++) {
				nodeA.record(5);
			}
			for (int i = 0; i < 10; i++) {
				nodeB.record(900);
			}

			HistogramReport a = nodeA.report();
			HistogramReport b = nodeB.report();

			assertArrayEqual(a.getBoundsMillis(), b.getBoundsMillis(),
					"identical boundaries are what make the sum meaningful");

			long[] merged = new long[a.getBucketCounts().length];
			for (int i = 0; i < merged.length; i++) {
				merged[i] = a.getBucketCounts()[i] + b.getBucketCounts()[i];
			}

			assertEquals(100, merged[0] + merged[1] + merged[2] + merged[3]);
			assertEquals(90, merged[0], "the fast node's samples");
			assertEquals(10, merged[2], "the slow node's samples");
			assertEquals(a.getCount() + b.getCount(), 100);
		}

		private void assertArrayEqual(long[] expected, long[] actual, String message) {
			assertEquals(expected.length, actual.length, message);
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], actual[i], message);
			}
		}
	}

	@Nested
	@DisplayName("concurrency")
	class Concurrency {

		@Test
		@DisplayName("counts survive many threads incrementing at once — the whole point of LongAdder")
		void concurrentIncrementsAreNotLost() throws Exception {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("calls", "Calls", "outcome", Arrays.asList("answered"));
			Counter.Series answered = counter.series("answered");
			Histogram latency = registry.histogram("latency", "Latency");

			int threads = 16;
			int perThread = 5000;
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(threads);

			for (int t = 0; t < threads; t++) {
				pool.submit(() -> {
					try {
						start.await();
						for (int i = 0; i < perThread; i++) {
							answered.increment();
							latency.record(i % 50);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}

			start.countDown();
			assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
			pool.shutdownNow();

			assertEquals((long) threads * perThread, counter.value("answered"));
			assertEquals((long) threads * perThread, latency.count());
		}
	}

	@Nested
	@DisplayName("registry")
	class Registry {

		@Test
		@DisplayName("declaring the same metric twice returns the same instance, so counts survive re-init")
		void registrationIsIdempotent() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter first = registry.counter("calls", "Calls");
			first.increment();
			Counter second = registry.counter("calls", "Calls");

			assertSame(first, second);
			assertEquals(1, second.total(), "a redeploy must not silently zero the counts");
			assertEquals(1, registry.size());
		}

		@Test
		void gaugesReadThroughOnEveryCall() {
			MetricsRegistry registry = new MetricsRegistry();
			final long[] backing = { 7 };
			Gauge gauge = registry.gauge("sessions", "Active sessions", () -> backing[0]);

			assertEquals(7, gauge.value());
			backing[0] = 9;
			assertEquals(9, gauge.value());
		}

		@Test
		@DisplayName("one broken gauge does not blind the console to every other metric")
		void aThrowingGaugeYieldsZero() {
			MetricsRegistry registry = new MetricsRegistry();
			Gauge gauge = registry.gauge("broken", "Throws", () -> {
				throw new IllegalStateException("boom");
			});
			assertEquals(0, gauge.value());
		}

		@Test
		void resetClearsCountersAndHistogramsButNotGauges() {
			MetricsRegistry registry = new MetricsRegistry();
			Counter counter = registry.counter("calls", "Calls");
			Histogram histogram = registry.histogram("latency", "Latency");
			Gauge gauge = registry.gauge("sessions", "Sessions", () -> 5);

			counter.increment();
			histogram.record(10);
			registry.reset();

			assertEquals(0, counter.total());
			assertEquals(0, histogram.count());
			assertEquals(5, gauge.value(), "a gauge reads through; there is nothing to reset");
		}

		@Test
		void aNullServletContextYieldsNoRegistryRatherThanThrowing() {
			assertNull(MetricsRegistry.from(null));
		}

		@Test
		void appBindingRoundTrips() {
			MetricsRegistry registry = new MetricsRegistry();
			MetricsRegistry.bind("blade-test-app", registry);
			assertSame(registry, MetricsRegistry.forApp("blade-test-app"));
			MetricsRegistry.unbind("blade-test-app");
			assertNotNull(MetricsRegistry.forApp("blade-test-app"), "forApp creates one on demand");
		}
	}

	@Nested
	@DisplayName("the MBean is actually readable over JMX")
	class MBeanSurface {

		/// Registers for real against the platform MBean server and reads back
		/// through `getAttribute`, rather than calling the object directly.
		///
		/// This is the check that matters: WLS 14.1.1's JMX auto-introspection
		/// silently drops no-arg getters from the computed MBeanInfo, which is
		/// why `TesterControl` — and now [MetricsControl] — register through an
		/// explicit `StandardMBean(…, isMXBean=true)` wrapper. Calling
		/// `getReportJson()` in Java would pass either way; going through the
		/// MBean server is what proves the attribute is exposed.
		@Test
		void reportAndResetAreExposedAsAttributesAndOperations() throws Exception {
			MetricsRegistry registry = new MetricsRegistry();
			registry.counter("calls", "Calls handled").increment();

			MetricsControl control = new MetricsControl(registry, "blade-metrics-test", null);
			control.register();
			try {
				MBeanServer server = ManagementFactory.getPlatformMBeanServer();
				ObjectName name = new ObjectName("vorpal.blade:Name=blade-metrics-test,Type=Metrics");
				assertTrue(server.isRegistered(name), "MBean did not register");

				Object reportJson = server.getAttribute(name, "ReportJson");
				assertTrue(reportJson instanceof String, "ReportJson missing from the MBeanInfo");

				JsonNode json = MAPPER.readTree((String) reportJson);
				assertEquals("blade-metrics-test", json.path("app").asText());
				assertEquals(1, json.path("counters").get(0).path("total").asLong());

				assertEquals(1, ((Integer) server.getAttribute(name, "MetricCount")).intValue());

				server.invoke(name, "reset", null, null);
				json = MAPPER.readTree((String) server.getAttribute(name, "ReportJson"));
				assertEquals(0, json.path("counters").get(0).path("total").asLong());
			} finally {
				control.unregister();
			}
		}

		@Test
		@DisplayName("the ObjectName mirrors the app's Configuration MBean, cluster key included")
		void objectNameCarriesTheClusterKey() throws Exception {
			MetricsControl control = new MetricsControl(new MetricsRegistry(), "blade-events", "ENGINE_CLUST");
			control.register();
			try {
				MBeanServer server = ManagementFactory.getPlatformMBeanServer();
				ObjectName expected = new ObjectName(
						"vorpal.blade:Name=blade-events,Type=Metrics,Cluster=ENGINE_CLUST");
				assertTrue(server.isRegistered(expected));
			} finally {
				control.unregister();
			}
		}

		@Test
		@DisplayName("re-registering after a redeploy replaces the stale MBean instead of failing")
		void registrationIsRepeatable() throws Exception {
			MetricsControl first = new MetricsControl(new MetricsRegistry(), "blade-redeploy-test", null);
			first.register();
			MetricsControl second = new MetricsControl(new MetricsRegistry(), "blade-redeploy-test", null);
			second.register();
			try {
				ObjectName name = new ObjectName("vorpal.blade:Name=blade-redeploy-test,Type=Metrics");
				assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(name));
			} finally {
				second.unregister();
			}
		}
	}

	@Nested
	@DisplayName("the JSON report")
	class Report {

		@Test
		void carriesIdentityCountersGaugesAndHistograms() throws Exception {
			MetricsRegistry registry = new MetricsRegistry();
			registry.counter("sip.requests", "Requests", "method", Arrays.asList("INVITE", "BYE"))
					.increment("INVITE");
			registry.gauge("sessions", "Active sessions", () -> 3);
			registry.histogram("sip.service.time", "Handler time").record(42);

			MetricsReport report = registry.report("blade-events", "engine1", "BEA_ENGINE_TIER_CLUST");
			JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(report));

			assertEquals("blade-events", json.path("app").asText());
			assertEquals("engine1", json.path("node").asText());
			assertEquals("BEA_ENGINE_TIER_CLUST", json.path("cluster").asText());
			assertTrue(json.path("timestamp").asText().endsWith("Z"));
			assertTrue(json.path("uptimeMillis").isNumber());

			assertEquals(1, json.path("counters").size());
			assertEquals(1, json.path("counters").get(0).path("values").path("INVITE").asLong());
			assertEquals(3, json.path("gauges").get(0).path("value").asLong());

			JsonNode histogram = json.path("histograms").get(0);
			assertEquals(1, histogram.path("count").asLong());
			assertTrue(histogram.path("boundsMillis").isArray(), "bounds must ship or nodes cannot be merged");
			assertEquals(histogram.path("boundsMillis").size() + 1, histogram.path("bucketCounts").size());
		}

		@Test
		@DisplayName("the app name is the join key to the app's Configuration MBean")
		void appNameMatchesTheConfigurationMBeanKey() {
			MetricsReport report = new MetricsRegistry().report("blade-configurator", "AdminServer", null);
			assertEquals("blade-configurator", report.getApp());
			assertNull(report.getCluster(), "a standalone server has no cluster key");
		}
	}
}
