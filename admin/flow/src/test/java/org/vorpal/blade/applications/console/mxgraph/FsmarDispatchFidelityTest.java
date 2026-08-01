package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Round-trip fidelity for the **entry** (null → ingress) dispatch transitions.
///
/// These are the transitions the editor never draws: import absorbs them and
/// export re-derives them from each ingress's `match`. Everything else they
/// carry — `subscriber`, `region`, `routes`, `routeModifier`, a custom `id`,
/// and any unknown field — has to survive that absorption, because on an
/// initial request the routing region and subscriber URI are exactly what the
/// container acts on.
///
/// Absorption only happens when the config has a `diagram.ingresses` block, so
/// each case is paired with a no-diagram control asserting the ordinary
/// (never-absorbed) path still round-trips.
class FsmarDispatchFidelityTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode roundTrip(String json) throws Exception {
		JsonNode original = MAPPER.readTree(json);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		return new FsmarExportServlet().buildFsmarJson(xml);
	}

	/// The dispatch transition for `inbound`, whichever method it is filed under.
	private static JsonNode dispatchFor(JsonNode exported, String method, String next) {
		JsonNode txs = exported.path("states").path("null")
				.path("triggers").path(method).path("transitions");
		for (JsonNode tx : txs) {
			if (next.equals(tx.path("next").asText())) {
				return tx;
			}
		}
		return MAPPER.missingNode();
	}

	private static final String ENTRY_WITH_DIAGRAM = "{"
			+ "\"defaultApplication\": \"b2bua\","
			+ "\"states\": {"
			+ "  \"null\": {\"triggers\": {\"INVITE\": {\"transitions\": ["
			+ "     {\"id\":\"from-carrier\","
			+ "      \"when\":\"${originIP} insubnet '10.0.0.0/8'\","
			+ "      \"next\":\"inbound\","
			+ "      \"subscriber\":\"From\","
			+ "      \"region\":\"ORIGINATING\"}"
			+ "  ]}}},"
			+ "  \"inbound\": {\"triggers\": {\"INVITE\": {\"transitions\": ["
			+ "     {\"id\":\"INB-1\",\"next\":\"b2bua\"}"
			+ "  ]}}},"
			+ "  \"b2bua\": {\"triggers\": {}}"
			+ "},"
			+ "\"diagram\": {"
			+ "  \"ingresses\": {\"inbound\": {\"match\":\"${originIP} insubnet '10.0.0.0/8'\"}},"
			+ "  \"states\": {\"null\":{\"x\":60,\"y\":60},\"inbound\":{\"x\":280,\"y\":60},"
			+ "              \"b2bua\":{\"x\":500,\"y\":60}}"
			+ "}}";

	@Test
	@DisplayName("absorbed entry transition keeps region and subscriber")
	void entryTransitionKeepsRegionAndSubscriber() throws Exception {
		JsonNode tx = dispatchFor(roundTrip(ENTRY_WITH_DIAGRAM), "INVITE", "inbound");

		assertTrue(tx.isObject(), "no dispatch transition was emitted for 'inbound'");
		assertEquals("ORIGINATING", tx.path("region").asText(),
				"region lost on the entry transition");
		assertEquals("From", tx.path("subscriber").asText(),
				"subscriber header lost on the entry transition");
	}

	@Test
	@DisplayName("absorbed entry transition keeps its authored id")
	void entryTransitionKeepsAuthoredId() throws Exception {
		JsonNode tx = dispatchFor(roundTrip(ENTRY_WITH_DIAGRAM), "INVITE", "inbound");

		assertEquals("from-carrier", tx.path("id").asText(),
				"authored id replaced by the generated dispatch-<name>");
	}

	@Test
	@DisplayName("entry transition without an authored id gets the generated one")
	void entryTransitionWithoutIdGetsGeneratedId() throws Exception {
		String cfg = ENTRY_WITH_DIAGRAM.replace("\"id\":\"from-carrier\",", "");
		JsonNode tx = dispatchFor(roundTrip(cfg), "INVITE", "inbound");

		assertEquals("dispatch-inbound", tx.path("id").asText());
	}

	@Test
	@DisplayName("absorbed entry transition keeps routes and routeModifier")
	void entryTransitionKeepsRoutesAndModifier() throws Exception {
		// NO_ROUTE + routes on an entry transition: the container is told not to
		// push them, but the config must still come back the way it went in.
		String cfg = ENTRY_WITH_DIAGRAM.replace("\"region\":\"ORIGINATING\"",
				"\"region\":\"ORIGINATING\","
						+ "\"routes\":[\"sip:${To.user}@edge.example.com\"],"
						+ "\"routeModifier\":\"NO_ROUTE\"");
		JsonNode tx = dispatchFor(roundTrip(cfg), "INVITE", "inbound");

		assertEquals("NO_ROUTE", tx.path("routeModifier").asText(), "routeModifier lost");
		assertEquals(1, tx.path("routes").size(), "routes lost");
		assertEquals("sip:${To.user}@edge.example.com", tx.path("routes").get(0).asText(),
				"route template mangled");
	}

	@Test
	@DisplayName("absorbed entry transition keeps unknown fields")
	void entryTransitionKeepsUnknownFields() throws Exception {
		String cfg = ENTRY_WITH_DIAGRAM.replace("\"region\":\"ORIGINATING\"",
				"\"region\":\"ORIGINATING\",\"customTx\":\"keep-me\"");
		JsonNode tx = dispatchFor(roundTrip(cfg), "INVITE", "inbound");

		assertEquals("keep-me", tx.path("customTx").asText(),
				"unknown field dropped from the entry transition");
	}

	@Test
	@DisplayName("dispatch is re-emitted for a method the ingress has no trigger for")
	void dispatchSurvivesForMethodIngressDoesNotHandle() throws Exception {
		// REGISTER is classified into `inbound`, but `inbound` has no REGISTER
		// trigger of its own — the method set must come from the absorbed
		// dispatch too, not only from the ingress's triggers.
		String cfg = ENTRY_WITH_DIAGRAM.replace(
				"\"null\": {\"triggers\": {\"INVITE\": {\"transitions\": [",
				"\"null\": {\"triggers\": {"
						+ "\"REGISTER\": {\"transitions\": ["
						+ "  {\"id\":\"reg-in\",\"when\":\"${originIP} insubnet '10.0.0.0/8'\","
						+ "   \"next\":\"inbound\",\"region\":\"TERMINATING\"}"
						+ "]},"
						+ "\"INVITE\": {\"transitions\": [");
		JsonNode tx = dispatchFor(roundTrip(cfg), "REGISTER", "inbound");

		assertTrue(tx.isObject(), "REGISTER dispatch dropped entirely");
		assertEquals("TERMINATING", tx.path("region").asText());
		assertEquals("reg-in", tx.path("id").asText());
	}

	@Test
	@DisplayName("control: same config without a diagram block round-trips too")
	void noDiagramControl() throws Exception {
		// With no diagram.ingresses there is nothing to absorb — the transition
		// is drawn as an ordinary edge. Guards against 'fixing' absorption by
		// breaking the normal path.
		String cfg = ENTRY_WITH_DIAGRAM.substring(0, ENTRY_WITH_DIAGRAM.indexOf(",\"diagram\""))
				+ "}";
		JsonNode tx = dispatchFor(roundTrip(cfg), "INVITE", "inbound");

		assertEquals("ORIGINATING", tx.path("region").asText());
		assertEquals("From", tx.path("subscriber").asText());
		assertEquals("from-carrier", tx.path("id").asText());
	}

	@Test
	@DisplayName("the ingress cell carries the fields the panel edits")
	void ingressCellExposesDispatchFields() throws Exception {
		// The entry-transition panel reads and writes `dispatchExtra` on the
		// ingress <Gateway>, keyed by SIP method. If the attribute stops being
		// written there, the panel silently edits nothing.
		String xml = new FsmarImportServlet()
				.buildMxGraphXml(MAPPER.readTree(ENTRY_WITH_DIAGRAM));

		assertTrue(xml.contains("dispatchExtra="),
				"ingress cell should carry a dispatchExtra attribute");
		assertTrue(xml.contains("ORIGINATING") && xml.contains("subscriber"),
				"dispatchExtra should hold the absorbed region and subscriber");

		// And what the panel would write must survive back out.
		JsonNode tx = dispatchFor(
				new FsmarExportServlet().buildFsmarJson(xml), "INVITE", "inbound");
		assertEquals("ORIGINATING", tx.path("region").asText());
		assertEquals("From", tx.path("subscriber").asText());
	}

	@Test
	@DisplayName("clearing the entry-transition fields removes them")
	void clearedDispatchFieldsAreDropped() throws Exception {
		// What the panel produces when an operator empties both inputs: the
		// method entry goes away entirely rather than being written as blanks.
		String xml = new FsmarImportServlet()
				.buildMxGraphXml(MAPPER.readTree(ENTRY_WITH_DIAGRAM))
				.replaceAll("dispatchExtra=\"[^\"]*\"", "");

		JsonNode tx = dispatchFor(
				new FsmarExportServlet().buildFsmarJson(xml), "INVITE", "inbound");

		assertTrue(tx.isObject(), "the dispatch transition should still be generated");
		assertTrue(!tx.has("region") && !tx.has("subscriber"),
				() -> "cleared fields should be absent, got: " + tx);
		assertEquals("dispatch-inbound", tx.path("id").asText(),
				"with nothing preserved the generated id applies");
	}

	@Test
	@DisplayName("the round trip raises no validator errors")
	void roundTripStaysValid() throws Exception {
		JsonNode exported = roundTrip(ENTRY_WITH_DIAGRAM);
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> infos = new ArrayList<>();

		new FsmarValidateServlet().validate(exported, errors, warnings, infos);

		assertTrue(errors.isEmpty(), () -> "unexpected validator errors: " + errors);
	}
}
