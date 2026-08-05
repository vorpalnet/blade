package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The palette has ONE cloud symbol carrying no direction, so which way a cloud
/// faces is read off the arrows: a transition pointing AT a cloud makes it the
/// place the call leaves OCCAS; a cloud with only out-edges is where calls arrive.
///
/// Diagrams saved before the two cloud symbols were merged still carry
/// `role="egress"`, and that must keep winning outright — these tests pin both the
/// inferred and the explicit path, and prove they agree.
class FsmarCloudDirectionTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// The shipped example that exercises both exit kinds at once: `to-carrier`
	/// (nothing comes back — ROUTE_FINAL) and `media-greeting` (a line back to
	/// `b2bua` — ROUTE_BACK), plus two named entry clouds.
	private static final Path EXAMPLE =
			Paths.get("src/main/webapp/docs/examples/04-ingress-egress.json");

	private static String diagramXml(String json) throws Exception {
		return new FsmarImportServlet().buildMxGraphXml(MAPPER.readTree(json));
	}

	/// Drop the stored direction, leaving only the arrows to say which way the
	/// cloud faces — exactly what a diagram drawn with the single cloud symbol
	/// looks like.
	private static String withoutStoredDirection(String xml) {
		String stripped = xml.replace(" role=\"egress\"", "");
		assertFalse(stripped.contains("role=\"egress\""), "no stored direction may survive");
		assertTrue(stripped.length() < xml.length(), "the fixture must have had a stored direction");
		return stripped;
	}

	@Test
	@DisplayName("a cloud with no stored direction exports the same as one that has it")
	void inferredDirectionMatchesStoredDirection() throws Exception {
		String json = new String(Files.readAllBytes(EXAMPLE), StandardCharsets.UTF_8);
		String xml = diagramXml(json);

		JsonNode stored = new FsmarExportServlet().buildFsmarJson(xml);
		JsonNode inferred = new FsmarExportServlet().buildFsmarJson(withoutStoredDirection(xml));

		assertEquals(stored, inferred,
				"the arrows alone must classify every cloud exactly as the stored role did");
	}

	@Test
	@DisplayName("both exit kinds survive on the arrows alone")
	void bothExitKindsSurviveInference() throws Exception {
		String json = new String(Files.readAllBytes(EXAMPLE), StandardCharsets.UTF_8);
		JsonNode exported = new FsmarExportServlet()
				.buildFsmarJson(withoutStoredDirection(diagramXml(json)));

		JsonNode egresses = exported.path("diagram").path("egresses");
		assertTrue(egresses.has("to-carrier"), () -> "expected to-carrier, got " + egresses);
		assertTrue(egresses.has("media-greeting"), () -> "expected media-greeting, got " + egresses);

		// The exit that has a line back to a state resumes there; the one that
		// doesn't is gone for good. That distinction is pure topology.
		assertEquals("b2bua", egresses.path("media-greeting").path("returnState").asText());
		assertFalse(egresses.path("to-carrier").has("returnState"),
				"an exit with no line back must not gain a return state");

		// And the entry clouds must not have been swept up as exits.
		JsonNode ingresses = exported.path("diagram").path("ingresses");
		assertTrue(ingresses.has("Atlanta") && ingresses.has("Dallas"),
				() -> "the entry clouds must stay entries, got " + ingresses);
	}

	@Test
	@DisplayName("a cloud nothing points into is an entry, not an exit")
	void cloudWithOnlyOutEdgesIsAnEntry() throws Exception {
		// One named entry cloud feeding a state, and no exits anywhere.
		String json = "{\"states\":{"
				+ "\"null\":{\"selectors\":[{\"type\":\"attribute\",\"id\":\"originIP\","
				+ "\"attribute\":\"originIP\"}],"
				+ "\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"id\":\"D1\",\"when\":\"${originIP} insubnet '10.20.0.0/16'\",\"next\":\"Atlanta\"}]}}},"
				+ "\"Atlanta\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"A1\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{}}},"
				+ "\"diagram\":{\"ingresses\":{\"Atlanta\":{\"match\":\"${originIP} insubnet '10.20.0.0/16'\"}}}}";

		JsonNode exported = new FsmarExportServlet().buildFsmarJson(diagramXml(json));

		assertTrue(exported.path("states").has("Atlanta"),
				"a cloud with only out-edges stays a real entry state");
		assertTrue(exported.path("diagram").path("egresses").isMissingNode()
						|| exported.path("diagram").path("egresses").size() == 0,
				() -> "nothing should have been classified as an exit, got "
						+ exported.path("diagram").path("egresses"));
	}

	@Test
	@DisplayName("an unnamed cloud that a transition points into fails with a usable message")
	void unnamedExitCloudIsRejected() throws Exception {
		String json = new String(Files.readAllBytes(EXAMPLE), StandardCharsets.UTF_8);
		String xml = withoutStoredDirection(diagramXml(json))
				.replace("label=\"to-carrier\"", "label=\"\"");

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> new FsmarExportServlet().buildFsmarJson(xml));
		assertTrue(thrown.getMessage().toLowerCase().contains("name"),
				() -> "the message must say the cloud needs a name, got: " + thrown.getMessage());
	}
}
