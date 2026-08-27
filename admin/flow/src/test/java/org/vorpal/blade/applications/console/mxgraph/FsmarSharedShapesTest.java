package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Two shapes the canvas represents differently from the JSON, both flagged
/// during the audit as "unverified — might be lossy".
///
/// - **Egress identity is content-based.** `FsmarImportServlet#egressKey` keys an
///   egress on `(routes, returnState)`, so two transitions that exit the same way
///   collapse onto one canvas node. That is deliberate ("an egress IS its routes
///   and return state") but it means one node stands for several transitions.
/// - **`seq` is the only carrier of evaluation order**, and the panel never
///   renumbers siblings, so duplicate `seq` values are possible.
///
/// Neither is allowed to change what the config says. These tests pin that.
class FsmarSharedShapesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode roundTrip(String json) throws Exception {
		String xml = new FsmarImportServlet().buildMxGraphXml(MAPPER.readTree(json));
		return new FsmarExportServlet().buildFsmarJson(xml);
	}

	private static JsonNode transitions(JsonNode cfg, String state, String method) {
		return cfg.path("states").path(state).path("triggers").path(method).path("transitions");
	}

	@Test
	@DisplayName("two states exiting identically both keep their routes")
	void sharedEgressKeepsBothTransitions() throws Exception {
		// Same routes, same (absent) return state -> one egress node on canvas.
		// Both transitions must still come back carrying those routes.
		JsonNode out = roundTrip("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A\",\"when\":\"${To.user} == 'a'\",\"next\":\"dialogA\"},"
				+ "  {\"id\":\"B\",\"when\":\"${To.user} == 'b'\",\"next\":\"dialogB\"}]}}},"
				+ "\"dialogA\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A-out\",\"routes\":[\"sip:carrier.example.com\"],"
				+ "   \"routeModifier\":\"ROUTE_FINAL\"}]}}},"
				+ "\"dialogB\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"B-out\",\"routes\":[\"sip:carrier.example.com\"],"
				+ "   \"routeModifier\":\"ROUTE_FINAL\"}]}}}}}");

		JsonNode a = transitions(out, "dialogA", "INVITE").get(0);
		JsonNode b = transitions(out, "dialogB", "INVITE").get(0);

		assertEquals("sip:carrier.example.com", a.path("routes").get(0).asText(),
				"dialogA lost its egress routes to the shared node");
		assertEquals("sip:carrier.example.com", b.path("routes").get(0).asText(),
				"dialogB lost its egress routes to the shared node");
		assertEquals("ROUTE_FINAL", a.path("routeModifier").asText());
		assertEquals("ROUTE_FINAL", b.path("routeModifier").asText());
	}

	@Test
	@DisplayName("exits that differ only by return state stay separate")
	void differentReturnStatesDoNotCollapse() throws Exception {
		// Same routes but different resume points — these are NOT the same exit
		// and must not share a node.
		JsonNode out = roundTrip("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A\",\"when\":\"${To.user} == 'a'\",\"next\":\"dialogA\"},"
				+ "  {\"id\":\"B\",\"when\":\"${To.user} == 'b'\",\"next\":\"dialogB\"}]}}},"
				+ "\"dialogA\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A-out\",\"next\":\"resumeA\","
				+ "   \"routes\":[\"sip:sbc.example.com\"],\"routeModifier\":\"ROUTE_BACK\"}]}}},"
				+ "\"dialogB\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"B-out\",\"next\":\"resumeB\","
				+ "   \"routes\":[\"sip:sbc.example.com\"],\"routeModifier\":\"ROUTE_BACK\"}]}}},"
				+ "\"resumeA\":{\"triggers\":{}},\"resumeB\":{\"triggers\":{}}}}");

		assertEquals("resumeA", transitions(out, "dialogA", "INVITE").get(0).path("next").asText(),
				"dialogA's resume state was merged away");
		assertEquals("resumeB", transitions(out, "dialogB", "INVITE").get(0).path("next").asText(),
				"dialogB's resume state was merged away");
	}

	@Test
	@DisplayName("duplicate seq values keep their authored order")
	void duplicateSeqKeepsOrder() throws Exception {
		// First-match-wins, so order is semantic. Export sorts by seq; ties must
		// resolve stably to the order the transitions were written in, not
		// silently reshuffle.
		JsonNode out = roundTrip("{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"first\",\"when\":\"${tier} == 'gold'\",\"next\":\"gold\"},"
				+ "  {\"id\":\"second\",\"when\":\"${tier} == 'silver'\",\"next\":\"silver\"},"
				+ "  {\"id\":\"third\",\"when\":\"${tier} == 'bronze'\",\"next\":\"bronze\"}]}}},"
				+ "\"gold\":{\"triggers\":{}},\"silver\":{\"triggers\":{}},"
				+ "\"bronze\":{\"triggers\":{}}}}");

		JsonNode txs = transitions(out, "null", "INVITE");
		assertEquals(3, txs.size());
		assertEquals("first", txs.get(0).path("id").asText(), "evaluation order changed");
		assertEquals("second", txs.get(1).path("id").asText(), "evaluation order changed");
		assertEquals("third", txs.get(2).path("id").asText(), "evaluation order changed");
	}

	@Test
	@DisplayName("order survives a second round trip")
	void orderIsStableAcrossTwoRoundTrips() throws Exception {
		// One pass could look right by luck; drift shows up on the second.
		String cfg = "{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"t1\",\"when\":\"${a} == '1'\",\"next\":\"s1\"},"
				+ "  {\"id\":\"t2\",\"when\":\"${a} == '2'\",\"next\":\"s2\"},"
				+ "  {\"id\":\"t3\",\"when\":\"${a} == '3'\",\"next\":\"s3\"},"
				+ "  {\"id\":\"t4\",\"next\":\"s4\"}]}}},"
				+ "\"s1\":{\"triggers\":{}},\"s2\":{\"triggers\":{}},"
				+ "\"s3\":{\"triggers\":{}},\"s4\":{\"triggers\":{}}}}";

		JsonNode once = roundTrip(cfg);
		JsonNode twice = roundTrip(once.toString());

		assertEquals(transitions(once, "null", "INVITE"), transitions(twice, "null", "INVITE"),
				"a second round trip reordered or altered the transitions");
		// And the unconditional one is still last, where it belongs.
		JsonNode txs = transitions(twice, "null", "INVITE");
		assertEquals("t4", txs.get(txs.size() - 1).path("id").asText());
	}

	@Test
	@DisplayName("a shared egress round-trips to itself")
	void sharedEgressIsStable() throws Exception {
		String cfg = "{\"version\":3,\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A\",\"when\":\"${To.user} == 'a'\",\"next\":\"dialogA\"},"
				+ "  {\"id\":\"B\",\"when\":\"${To.user} == 'b'\",\"next\":\"dialogB\"}]}}},"
				+ "\"dialogA\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"A-out\",\"routes\":[\"sip:carrier.example.com\"],"
				+ "   \"routeModifier\":\"ROUTE_FINAL\"}]}}},"
				+ "\"dialogB\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"B-out\",\"routes\":[\"sip:carrier.example.com\"],"
				+ "   \"routeModifier\":\"ROUTE_FINAL\"}]}}}}}";

		JsonNode once = roundTrip(cfg);
		JsonNode twice = roundTrip(once.toString());

		assertEquals(once.path("states"), twice.path("states"),
				"the shared egress drifted on a second pass");
		assertTrue(once.path("diagram").path("egresses").size() >= 1,
				"the shared exit should be recorded as a named egress");
	}
}
