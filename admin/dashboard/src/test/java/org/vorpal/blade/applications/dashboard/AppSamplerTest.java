package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The delta arithmetic behind the per-minute samples, and the payload an
/// application sample carries.
class AppSamplerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static Map<String, Long> totals(long published, long failures, long invites, long errors) {
		Map<String, Long> m = new LinkedHashMap<>();
		m.put("published", published);
		m.put("failures", failures);
		m.put("invites", invites);
		m.put("sipErrors", errors);
		return m;
	}

	@Test
	void theFirstLookOnlySetsTheBaseline() {
		AppSampler s = new AppSampler();
		assertNull(s.observe("meetings", "engine0", null, 60_000, 600_000, totals(50, 0, 10, 0)),
				"publishing the totals would recount what an earlier dashboard already stored");
	}

	@Test
	void aLaterLookIsTheDifference() {
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, totals(50, 0, 10, 0));
		AppSampler.Delta d = s.observe("meetings", "engine0", null, 120_000, 660_000, totals(80, 2, 13, 1));
		assertEquals(totals(30, 2, 3, 1), d.counts);
		assertEquals(60, d.seconds);
		assertFalse(d.restarted);
	}

	@Test
	void aRestartCountsFromTheRestart() {
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, totals(50, 0, 10, 0));
		AppSampler.Delta d = s.observe("meetings", "engine0", null, 120_000, 20_000, totals(4, 0, 1, 0));
		assertTrue(d.restarted, "uptime went down");
		assertEquals(totals(4, 0, 1, 0), d.counts, "what it did since it came back");
		assertEquals(20, d.seconds, "only the time since the restart");
	}

	@Test
	void aCounterGoingBackwardsIsARestartToo() {
		// An MBean reset() zeroes the counters without restarting the application.
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, totals(50, 0, 10, 0));
		AppSampler.Delta d = s.observe("meetings", "engine0", null, 120_000, 660_000, totals(3, 0, 0, 0));
		assertTrue(d.restarted);
		assertEquals(totals(3, 0, 0, 0), d.counts);
	}

	@Test
	void aCounterFirstSeenAfterTheBaselineCountsFromZero() {
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, totals(50, 0, 10, 0));
		Map<String, Long> later = totals(50, 0, 10, 0);
		later.put("c:ears.failures", 2L);
		assertEquals(2L, s.observe("meetings", "engine0", null, 120_000, 660_000, later).count("c:ears.failures"));
	}

	@Test
	void aMinuteOfNothingIsQuietButARestartIsNot() {
		AppSampler s = new AppSampler();
		s.observe("blade-portal", "AdminServer", null, 60_000, 600_000, totals(0, 0, 0, 0));
		assertTrue(s.observe("blade-portal", "AdminServer", null, 120_000, 660_000, totals(0, 0, 0, 0)).quiet());
		assertFalse(s.observe("blade-portal", "AdminServer", null, 180_000, 5_000, totals(0, 0, 0, 0)).quiet(),
				"a restart is news even with nothing counted");
	}

	@Test
	void aSumThatDipsIsNotARestart() {
		// count x whole-millisecond mean: 1 x 10 = 10, then 3 x 3 = 9 after a 1 ms recording.
		AppSampler s = new AppSampler();
		Map<String, Long> a = totals(50, 0, 10, 0);
		a.put("t:turn#count", 1L);
		a.put("t:turn#sum", 10L);
		s.observe("meetings", "engine0", null, 60_000, 600_000, a);
		Map<String, Long> b = totals(52, 0, 10, 0);
		b.put("t:turn#count", 3L);
		b.put("t:turn#sum", 9L);
		AppSampler.Delta d = s.observe("meetings", "engine0", null, 120_000, 660_000, b);
		assertFalse(d.restarted, "a rounded sum must not republish every lifetime total");
		assertEquals(2L, d.count("published"));
		assertEquals(0L, d.count("t:turn#sum"), "a dip counts as nothing");
	}

	@Test
	void nodesAreTrackedApart() {
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, totals(50, 0, 10, 0));
		assertNull(s.observe("meetings", "engine1", null, 60_000, 600_000, totals(9, 0, 1, 0)),
				"another node's first look");
	}

	@Test
	void theExactSumIsPreferredToCountTimesMean() throws Exception {
		JsonNode report = MAPPER.readTree("{\"uptimeMillis\":5,\"histograms\":[{\"name\":\"turn\",\"count\":3,"
				+ "\"meanMillis\":3,\"sumMillis\":11}]}");
		assertEquals(11L, AppSampling.totals(report).get("t:turn#sum"));
	}

	private static final String REPORT = "{\"app\":\"meetings\",\"node\":\"engine0\",\"uptimeMillis\":5,\"counters\":["
			+ "{\"name\":\"events.published\",\"total\":7},"
			+ "{\"name\":\"ears.failures\",\"total\":%d},"
			+ "{\"name\":\"sip.requests\",\"label\":\"method\",\"total\":12,\"values\":{\"INVITE\":%d,\"BYE\":3}}],"
			+ "\"histograms\":[{\"name\":\"turn.latency\",\"count\":%d,\"meanMillis\":%d}]}";

	private static JsonNode report(long failures, long invites, long turns, long mean) throws Exception {
		return MAPPER.readTree(String.format(REPORT, failures, invites, turns, mean));
	}

	@Test
	void countersAreReadFromTheReport() throws Exception {
		JsonNode report = report(1, 4, 0, 0);
		assertEquals(7, AppSampling.counter(report, "events.published", null));
		assertEquals(4, AppSampling.counter(report, "sip.requests", "INVITE"));
		assertEquals(0, AppSampling.counter(report, "events.publish.failures", null), "undeclared reads as zero");
		Map<String, Long> totals = AppSampling.totals(report);
		assertEquals(4L, totals.get("c:sip.requests.INVITE"), "a labelled counter flattens to name.value");
		assertEquals(1L, totals.get("c:ears.failures"));
	}

	@Test
	void everyCounterAndTimingThatMovedRidesInTheSample() throws Exception {
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, AppSampling.totals(report(1, 4, 10, 1000)));
		AppSampler.Delta d = s.observe("meetings", "engine0", null, 120_000, 660_000,
				AppSampling.totals(report(3, 6, 14, 1100)));
		ObjectNode data = MAPPER.createObjectNode();
		AppSampling.fill(data, d, 2L);
		assertEquals(2, data.path("invites").asLong());
		assertEquals(2, data.path("activeAppSessions").asLong());
		assertEquals("[{\"name\":\"ears.failures\",\"delta\":2}]", data.path("counters").toString(),
				"only what moved, and not INVITE again: the sample carries it as invites");
		assertEquals("[{\"name\":\"turn.latency\",\"count\":4,\"sumMillis\":5400}]", data.path("timings").toString(),
				"14 x 1100 - 10 x 1000: four turns averaging 1350 ms");
	}

	@Test
	void aSampleWithNothingExtraCarriesNoEmptyLists() throws Exception {
		AppSampler s = new AppSampler();
		s.observe("meetings", "engine0", null, 60_000, 600_000, AppSampling.totals(report(1, 4, 10, 1000)));
		ObjectNode data = MAPPER.createObjectNode();
		AppSampling.fill(data, s.observe("meetings", "engine0", null, 120_000, 660_000,
				AppSampling.totals(report(1, 4, 10, 1000))), null);
		assertFalse(data.has("counters"));
		assertFalse(data.has("timings"));
		assertFalse(data.has("activeAppSessions"), "no reading for a non-SIP application, not a zero");
	}
}
