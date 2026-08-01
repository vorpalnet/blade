package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Round-trip fidelity for the selector subclass fields that used to have no
/// control of their own: `AttributeSelector.allInstances` and
/// `XmlSelector.namespaces`.
///
/// Both are typed in the model — `allInstances` is a JSON boolean, `namespaces`
/// a JSON object — so the mxGraph cell (whose attributes are all strings) has
/// to convert in both directions rather than round-trip them as text.
class FsmarSelectorFieldsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode roundTrip(String json) throws Exception {
		JsonNode original = MAPPER.readTree(json);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		return new FsmarExportServlet().buildFsmarJson(xml);
	}

	private static JsonNode selector(JsonNode exported, int idx) {
		return exported.path("states").path("null").path("selectors").path(idx);
	}

	private static String config(String selectors) {
		return "{\"states\": {\"null\": {\"selectors\": [" + selectors
				+ "], \"triggers\": {\"INVITE\": {\"transitions\": ["
				+ "  {\"id\":\"T1\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\": {\"triggers\": {}}}}";
	}

	@Test
	@DisplayName("allInstances survives as a boolean, not a string")
	void allInstancesSurvivesTyped() throws Exception {
		JsonNode sel = selector(roundTrip(config(
				"{\"id\":\"via\",\"attribute\":\"Via\",\"allInstances\":true}")), 0);

		assertTrue(sel.path("allInstances").isBoolean(),
				() -> "allInstances should be a JSON boolean, got: " + sel.path("allInstances"));
		assertTrue(sel.path("allInstances").asBoolean(), "allInstances lost");
	}

	@Test
	@DisplayName("allInstances=false is omitted, matching @JsonInclude(NON_DEFAULT)")
	void allInstancesFalseIsOmitted() throws Exception {
		JsonNode sel = selector(roundTrip(config(
				"{\"id\":\"via\",\"attribute\":\"Via\",\"allInstances\":false}")), 0);

		assertFalse(sel.has("allInstances"),
				"allInstances=false should be omitted, not written out");
	}

	@Test
	@DisplayName("allInstances is not reported as an unknown field")
	void allInstancesIsNotUnknown() throws Exception {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> infos = new ArrayList<>();

		new FsmarValidateServlet().validate(MAPPER.readTree(config(
				"{\"id\":\"via\",\"attribute\":\"Via\",\"allInstances\":true}")),
				errors, warnings, infos);

		assertTrue(warnings.stream().noneMatch(w -> w.contains("allInstances")),
				() -> "allInstances flagged as unknown: " + warnings);
		assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
	}

	@Test
	@DisplayName("xml namespaces survive as an object")
	void namespacesSurviveTyped() throws Exception {
		JsonNode sel = selector(roundTrip(config(
				"{\"type\":\"xml\",\"id\":\"evt\",\"attribute\":\"//e:status\","
						+ "\"namespaces\":{\"e\":\"urn:example:events\","
						+ "\"s\":\"urn:example:session\"}}")), 0);

		JsonNode ns = sel.path("namespaces");
		assertTrue(ns.isObject(), () -> "namespaces should be an object, got: " + ns);
		assertEquals("urn:example:events", ns.path("e").asText());
		assertEquals("urn:example:session", ns.path("s").asText());
	}

	@Test
	@DisplayName("xml namespaces are not reported as an unknown field")
	void namespacesAreNotUnknown() throws Exception {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> infos = new ArrayList<>();

		new FsmarValidateServlet().validate(MAPPER.readTree(config(
				"{\"type\":\"xml\",\"id\":\"evt\",\"attribute\":\"//e:status\","
						+ "\"namespaces\":{\"e\":\"urn:example:events\"}}")),
				errors, warnings, infos);

		assertTrue(warnings.stream().noneMatch(w -> w.contains("namespaces")),
				() -> "namespaces flagged as unknown: " + warnings);
	}

	@Test
	@DisplayName("a selector carrying both fields plus an unknown keeps all three")
	void mixedSelectorKeepsEverything() throws Exception {
		JsonNode exported = roundTrip(config(
				"{\"id\":\"div\",\"attribute\":\"Diversion\",\"allInstances\":true,"
						+ "\"futureField\":\"keep-me\"},"
						+ "{\"type\":\"xml\",\"id\":\"evt\",\"attribute\":\"//e:s\","
						+ "\"namespaces\":{\"e\":\"urn:example:events\"}}"));

		assertTrue(selector(exported, 0).path("allInstances").asBoolean());
		assertEquals("keep-me", selector(exported, 0).path("futureField").asText());
		assertEquals("urn:example:events",
				selector(exported, 1).path("namespaces").path("e").asText());
	}
}
