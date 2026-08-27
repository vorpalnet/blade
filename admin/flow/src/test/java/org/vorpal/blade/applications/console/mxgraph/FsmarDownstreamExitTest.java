package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The downstream exit: a terminal transition with no `next` and no `routes`.
///
/// `AppRouter` implements it (the no-target break: application chaining stops,
/// nothing is pushed, OCCAS routes the request on its Request-URI), and FSMAR 2
/// configs could produce it (a transition whose `next` named nothing deployed),
/// so the editor must round-trip it — it used to reject the whole import with
/// "FSMAR 3 itself cannot route it", which was false. The editor draws it as an
/// egress node with no routes.
class FsmarDownstreamExitTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode roundTrip(String json) throws Exception {
		String xml = new FsmarImportServlet().buildMxGraphXml(MAPPER.readTree(json));
		return new FsmarExportServlet().buildFsmarJson(xml);
	}

	private static JsonNode stripDiagram(JsonNode cfg) {
		com.fasterxml.jackson.databind.node.ObjectNode copy =
				(com.fasterxml.jackson.databind.node.ObjectNode) cfg.deepCopy();
		copy.remove("diagram");
		return copy;
	}

	// A guarded stop on a non-entry state: after b2bua, calls to the PSTN range
	// leave the application chain; everything else loops in the registrar.
	private static final String STOP_CONFIG = "{\"states\":{"
			+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"T0\",\"next\":\"b2bua\"}]}}},"
			+ "\"b2bua\":{\"triggers\":{\"INVITE\":{\"transitions\":["
			+ "  {\"id\":\"STOP-offnet\",\"when\":\"${To.user} matches '1[2-9]\\\\d{9}'\"},"
			+ "  {\"id\":\"B2B-on\",\"next\":\"registrar\"}]}}},"
			+ "\"registrar\":{\"triggers\":{}}}}";

	@Test
	@DisplayName("a stop transition imports and round-trips unchanged")
	void stopTransitionRoundTrips() throws Exception {
		JsonNode original = MAPPER.readTree(STOP_CONFIG);
		JsonNode exported = stripDiagram(roundTrip(STOP_CONFIG));

		assertEquals(original, exported, "the stop transition must survive semantically");

		JsonNode tx = exported.path("states").path("b2bua").path("triggers")
				.path("INVITE").path("transitions").path(0);
		assertEquals("STOP-offnet", tx.path("id").asText());
		assertFalse(tx.has("next"), "a stop has no next");
		assertFalse(tx.has("routes"), "a stop pushes no routes");
		assertFalse(tx.has("routeModifier"), "a stop carries no modifier");
	}

	@Test
	@DisplayName("the stop is drawn as a synthesized 'downstream' egress")
	void stopSynthesizesDownstreamEgress() throws Exception {
		JsonNode exported = roundTrip(STOP_CONFIG);
		assertTrue(exported.path("diagram").path("egresses").has("downstream"),
				() -> "expected a 'downstream' egress in the diagram, got "
						+ exported.path("diagram"));
	}

	@Test
	@DisplayName("the downstream exit is stable across a second round trip")
	void downstreamExitIsStable() throws Exception {
		JsonNode once = roundTrip(STOP_CONFIG);
		JsonNode twice = roundTrip(once.toString());
		assertEquals(once.path("states"), twice.path("states"),
				"the stop transition drifted on a second pass");
	}

	@Test
	@DisplayName("two stops share one downstream egress node, both survive")
	void twoStopsShareOneNode() throws Exception {
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A\",\"when\":\"${To.user} == 'a'\",\"next\":\"dialogA\"},"
				+ "  {\"id\":\"B\",\"when\":\"${To.user} == 'b'\",\"next\":\"dialogB\"}]}}},"
				+ "\"dialogA\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"A-stop\"}]}}},"
				+ "\"dialogB\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"B-stop\"}]}}}}}";
		JsonNode original = MAPPER.readTree(cfg);
		JsonNode exported = roundTrip(cfg);

		assertEquals(original, stripDiagram(exported),
				"both stop transitions must survive the shared node");
		assertEquals(1, exported.path("diagram").path("egresses").size(),
				"identical exits should share one egress node");
	}

	@Test
	@DisplayName("a route-back line on a routeless egress is rejected with a named reason")
	void routeBackWithoutRoutesRejected() {
		// Hand-built canvas state: an egress with no routes but an out-edge back
		// to a state — nothing to send the call out on before it could return.
		String xml = "<mxGraphModel><root>"
				+ "<FlowModel id=\"0\" label=\"\"/>"
				+ "<Layer id=\"1\" label=\"Default Layer\"><mxCell parent=\"0\"/></Layer>"
				+ "<State label=\"b2bua\" id=\"2\"><mxCell vertex=\"1\" parent=\"1\">"
				+ "<mxGeometry x=\"0\" y=\"0\" width=\"120\" height=\"48\" as=\"geometry\"/></mxCell></State>"
				+ "<Gateway label=\"loop\" role=\"egress\" id=\"3\"><mxCell vertex=\"1\" parent=\"1\">"
				+ "<mxGeometry x=\"200\" y=\"0\" width=\"120\" height=\"114\" as=\"geometry\"/></mxCell></Gateway>"
				+ "<Transition label=\"INVITE\" id=\"4\"><mxCell edge=\"1\" parent=\"1\" source=\"2\" target=\"3\">"
				+ "<mxGeometry relative=\"1\" as=\"geometry\"/></mxCell></Transition>"
				+ "<Transition label=\"route-back\" id=\"5\"><mxCell edge=\"1\" parent=\"1\" source=\"3\" target=\"2\">"
				+ "<mxGeometry relative=\"1\" as=\"geometry\"/></mxCell></Transition>"
				+ "</root></mxGraphModel>";

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new FsmarExportServlet().buildFsmarJson(xml));
		assertTrue(e.getMessage().contains("loop") && e.getMessage().contains("route-back"),
				() -> "rejection must name the egress and the problem, got: " + e.getMessage());
	}

	@Test
	@DisplayName("the validator reports a stop as info, not an error")
	void validatorReportsStopAsInfo() throws Exception {
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(MAPPER.readTree(STOP_CONFIG), errors, warnings, infos);

		assertTrue(errors.isEmpty(), () -> "a stop transition is legal, got errors: " + errors);
		assertTrue(infos.stream().anyMatch(s -> s.contains("routes downstream")),
				() -> "the stop should be noted as info, got: " + infos);
	}

	@Test
	@DisplayName("an entry-state stop with defaultApplication raises no warning")
	void entryStateStopWithDefaultIsClean() throws Exception {
		// The engine's fallback fires only when NOTHING matched ("no matches
		// whatsoever" — AppRouter's anyMatch gate), so a matched stop on the
		// entry state is honored even with a defaultApplication configured.
		// Pinned engine-side by AppRouterFallbackTest; the validator must not
		// warn about a shape that works.
		String cfg = "{\"defaultApplication\":\"b2bua\",\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"STOP\",\"when\":\"${To.user} == 'external'\"},"
				+ "  {\"id\":\"T1\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{}}}}";
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(MAPPER.readTree(cfg), errors, warnings, infos);

		assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
		assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
	}
}
