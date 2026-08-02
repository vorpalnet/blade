package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// An ingress's identity: its NAME is its state id.
///
/// Export keys a named ingress on its label (never a separate stateId, never a
/// re-derived `app`), so renaming the box on the canvas renames the state. A
/// hand-written `app` on an ingress state rides the extra blob and round-trips
/// verbatim instead of being silently dropped. And when an absorbed dispatch's
/// `when` disagrees with `diagram.ingresses.match`, routing wins: the `when`
/// becomes the match rather than the (presentation-metadata) match silently
/// reverting the routing condition.
class FsmarIngressIdentityTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String INGRESS_CONFIG = "{\"states\":{"
			+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
			+ "  {\"id\":\"from-carrier\",\"when\":\"${originIP} insubnet '10.0.0.0/8'\","
			+ "   \"next\":\"inbound\"}]}}},"
			+ "\"inbound\":{\"selectors\":[{\"type\":\"attribute\",\"id\":\"cust\",\"attribute\":\"X-Cust\"}],"
			+ "  \"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"INB-1\",\"next\":\"b2bua\"}]}}},"
			+ "\"b2bua\":{\"triggers\":{}}},"
			+ "\"diagram\":{\"ingresses\":{\"inbound\":{\"match\":\"${originIP} insubnet '10.0.0.0/8'\"}}}}";

	private static JsonNode export(String xml) throws Exception {
		return new FsmarExportServlet().buildFsmarJson(xml);
	}

	private static String importXml(String json) throws Exception {
		return new FsmarImportServlet().buildMxGraphXml(MAPPER.readTree(json));
	}

	@Test
	@DisplayName("renaming an ingress box renames the state — no app is fabricated")
	void renamingIngressRenamesTheState() throws Exception {
		// The panel rename only changes the label; export must key the state on
		// it (an ingress has no separate stateId) instead of exporting the old
		// name with a bogus `app: <new name>` that changes the bypass lookup.
		String xml = importXml(INGRESS_CONFIG)
				.replace("label=\"inbound\"", "label=\"SBC-Dallas\"");
		JsonNode exported = export(xml);
		JsonNode states = exported.path("states");

		assertTrue(states.has("SBC-Dallas"), () -> "renamed state missing: " + states);
		assertFalse(states.has("inbound"), "the old state name must be gone");
		assertFalse(states.path("SBC-Dallas").has("app"),
				() -> "no app may be fabricated from the rename: " + states.path("SBC-Dallas"));
		assertEquals("X-Cust", states.path("SBC-Dallas").path("selectors").path(0)
				.path("attribute").asText(), "the state's selectors must follow the rename");
		assertEquals("SBC-Dallas", states.path("null").path("triggers").path("INVITE")
				.path("transitions").path(0).path("next").asText(),
				"the dispatch must follow the rename");
		assertTrue(exported.path("diagram").path("ingresses").has("SBC-Dallas"),
				() -> "diagram.ingresses must follow the rename: " + exported.path("diagram"));
	}

	@Test
	@DisplayName("a hand-written app on an ingress state survives the round trip")
	void appOnIngressStateSurvives() throws Exception {
		// `app` makes the dispatch invoke a deployed application under the
		// ingress's name (State.appOrId feeds the bypass lookup). It used to be
		// silently dropped — the no-silent-strip contract says it must ride.
		String cfg = INGRESS_CONFIG.replace("\"inbound\":{\"selectors\"",
				"\"inbound\":{\"app\":\"screening\",\"selectors\"");
		JsonNode original = MAPPER.readTree(cfg);
		JsonNode exported = export(importXml(cfg));

		assertEquals("screening",
				exported.path("states").path("inbound").path("app").asText(),
				() -> "app on the ingress state was dropped: "
						+ exported.path("states").path("inbound"));
		assertEquals(original.path("states"), exported.path("states"),
				"the whole states map must round-trip unchanged");
	}

	@Test
	@DisplayName("a hand-edited dispatch when beats a stale diagram match")
	void dispatchWhenBeatsStaleMatch() throws Exception {
		// The dispatch's `when` is routing; diagram.ingresses.match is derived
		// presentation data. When they disagree, the round trip must keep the
		// when and update the match — not silently revert the routing condition.
		String cfg = INGRESS_CONFIG.replace(
				"{\"id\":\"from-carrier\",\"when\":\"${originIP} insubnet '10.0.0.0/8'\",",
				"{\"id\":\"from-carrier\",\"when\":\"${originIP} insubnet '172.16.0.0/12'\",");
		JsonNode exported = export(importXml(cfg));

		assertEquals("${originIP} insubnet '172.16.0.0/12'",
				exported.path("states").path("null").path("triggers").path("INVITE")
						.path("transitions").path(0).path("when").asText(),
				"the hand-edited when must survive");
		assertEquals("${originIP} insubnet '172.16.0.0/12'",
				exported.path("diagram").path("ingresses").path("inbound").path("match").asText(),
				"the diagram match must be updated from the when");
	}

	@Test
	@DisplayName("control: the untouched ingress config round-trips exactly")
	void untouchedConfigRoundTrips() throws Exception {
		JsonNode original = MAPPER.readTree(INGRESS_CONFIG);
		JsonNode exported = export(importXml(INGRESS_CONFIG));

		assertEquals(original.path("states"), exported.path("states"));
		assertEquals(original.path("diagram").path("ingresses"),
				exported.path("diagram").path("ingresses"));
	}
}
