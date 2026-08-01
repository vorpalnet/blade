package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// What a publish would change, shown before it changes it.
///
/// The case that motivated this: an operator loads the *sample* instead of the
/// live config, edits the routing, publishes — and the live `logging`,
/// `analytics` and `events` blocks are gone, because the editor never modelled
/// them and had nothing to carry through.
class FsmarDiffTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static FsmarDiff.Result diff(String live, String proposed) throws Exception {
		JsonNode l = (live == null) ? null : MAPPER.readTree(live);
		return FsmarDiff.compare(l, MAPPER.readTree(proposed));
	}

	private static final String LIVE = "{"
			+ "\"version\":3,"
			+ "\"logging\":{\"loggingLevel\":\"FINE\"},"
			+ "\"analytics\":{\"enabled\":true},"
			+ "\"defaultApplication\":\"b2bua\","
			+ "\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
			+ "  {\"id\":\"T1\",\"next\":\"screening\"}]}}},"
			+ "\"screening\":{\"triggers\":{}}}}";

	@Test
	@DisplayName("dropping root blocks is reported, and called out separately")
	void droppedRootBlocksAreHeadlined() throws Exception {
		// The sample-then-publish scenario: same routing, no logging/analytics.
		String proposed = "{\"version\":3,\"defaultApplication\":\"b2bua\","
				+ "\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"T1\",\"next\":\"screening\"}]}}},"
				+ "\"screening\":{\"triggers\":{}}}}";

		FsmarDiff.Result r = diff(LIVE, proposed);

		assertFalse(r.isIdentical());
		List<String> lost = r.removedRootKeys();
		assertTrue(lost.contains("logging"), () -> "logging not flagged: " + lost);
		assertTrue(lost.contains("analytics"), () -> "analytics not flagged: " + lost);
		assertEquals(2, r.count(FsmarDiff.Op.REMOVED));
	}

	@Test
	@DisplayName("an identical config reports no differences")
	void identicalIsIdentical() throws Exception {
		FsmarDiff.Result r = diff(LIVE, LIVE);

		assertTrue(r.isIdentical());
		assertTrue(r.getEntries().isEmpty());
		assertTrue(r.removedRootKeys().isEmpty());
	}

	@Test
	@DisplayName("a changed value reports both sides")
	void changedValueShowsBothSides() throws Exception {
		FsmarDiff.Result r = diff(LIVE, LIVE.replace("\"b2bua\"", "\"screening\""));

		assertEquals(1, r.count(FsmarDiff.Op.CHANGED));
		FsmarDiff.Entry e = r.getEntries().get(0);
		assertEquals("/defaultApplication", e.getPath());
		assertEquals("b2bua", e.getFrom());
		assertEquals("screening", e.getTo());
	}

	@Test
	@DisplayName("structural changes sort ahead of deep ones")
	void shallowChangesLead() throws Exception {
		// A dropped root block must not be buried under transition-level noise.
		String proposed = "{\"version\":3,\"defaultApplication\":\"b2bua\","
				+ "\"analytics\":{\"enabled\":true},"
				+ "\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"CHANGED\",\"next\":\"screening\",\"when\":\"${a} == '1'\"}]}}},"
				+ "\"screening\":{\"triggers\":{}}}}";

		FsmarDiff.Result r = diff(LIVE, proposed);

		assertEquals("/logging", r.getEntries().get(0).getPath(),
				() -> "shallowest change should lead: " + r.getEntries().get(0).getPath());
	}

	@Test
	@DisplayName("array length changes are reported per position")
	void arrayGrowthIsReported() throws Exception {
		String proposed = LIVE.replace(
				"{\"id\":\"T1\",\"next\":\"screening\"}",
				"{\"id\":\"T1\",\"next\":\"screening\"},{\"id\":\"T2\",\"next\":\"screening\"}");

		FsmarDiff.Result r = diff(LIVE, proposed);

		assertEquals(1, r.count(FsmarDiff.Op.ADDED));
		assertTrue(r.getEntries().get(0).getPath().endsWith("/transitions/1"),
				() -> "unexpected path: " + r.getEntries().get(0).getPath());
	}

	@Test
	@DisplayName("no live config means nothing to lose")
	void missingTargetIsNotADiff() throws Exception {
		FsmarDiff.Result r = diff(null, LIVE);

		assertFalse(r.isTargetExists());
		assertTrue(r.isIdentical(), "a fresh target has no differences to report");
		assertTrue(r.removedRootKeys().isEmpty());
	}

	@Test
	@DisplayName("a huge diff is capped, and says so")
	void truncationIsReported() throws Exception {
		// No silent caps: the UI must be able to say "showing the first N".
		StringBuilder live = new StringBuilder("{\"states\":{");
		StringBuilder proposed = new StringBuilder("{\"states\":{");
		for (int i = 0; i < FsmarDiff.MAX_ENTRIES + 50; i++) {
			if (i > 0) {
				live.append(',');
				proposed.append(',');
			}
			live.append("\"s").append(i).append("\":{\"app\":\"a").append(i).append("\"}");
			proposed.append("\"s").append(i).append("\":{\"app\":\"b").append(i).append("\"}");
		}
		live.append("}}");
		proposed.append("}}");

		FsmarDiff.Result r = diff(live.toString(), proposed.toString());

		assertTrue(r.isTruncated(), "should report truncation");
		assertEquals(FsmarDiff.MAX_ENTRIES, r.getEntries().size());
	}
}
