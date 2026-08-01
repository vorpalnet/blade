package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The example configs shipped under `docs/examples/` are what an operator
/// reads first and copies from, so they have to be clean by the editor's own
/// rules — and they have to survive a round trip unchanged.
///
/// This also backstops the validator itself: a rule that wrongly rejects
/// idiomatic config shows up here as a failing example rather than as a
/// support call.
class FsmarExamplesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Path EXAMPLES = Paths.get("src/main/webapp/docs/examples");

	private static List<Path> examples() throws IOException {
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> dir = Files.newDirectoryStream(EXAMPLES, "*.json")) {
			for (Path p : dir) {
				out.add(p);
			}
		}
		out.sort(null);
		return out;
	}

	@TestFactory
	@DisplayName("every shipped example validates without errors")
	Iterable<DynamicTest> examplesValidateClean() throws IOException {
		List<Path> files = examples();
		assertTrue(!files.isEmpty(), () -> "no examples found under " + EXAMPLES.toAbsolutePath());

		List<DynamicTest> tests = new ArrayList<>();
		for (Path file : files) {
			tests.add(DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
				JsonNode cfg = MAPPER.readTree(file.toFile());
				List<String> errors = new ArrayList<>();
				new FsmarValidateServlet().validate(cfg, errors, new ArrayList<>(), new ArrayList<>());

				assertTrue(errors.isEmpty(),
						() -> file.getFileName() + " has validator errors: " + errors);
			}));
		}
		return tests;
	}

	@TestFactory
	@DisplayName("every shipped example survives import → export")
	Iterable<DynamicTest> examplesRoundTrip() throws IOException {
		List<DynamicTest> tests = new ArrayList<>();
		for (Path file : examples()) {
			tests.add(DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
				JsonNode original = MAPPER.readTree(file.toFile());
				String xml = new FsmarImportServlet().buildMxGraphXml(original);
				JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

				// Re-exported config must still be clean; a round trip that
				// introduces an error means the editor mangled something.
				List<String> errors = new ArrayList<>();
				new FsmarValidateServlet().validate(exported, errors, new ArrayList<>(), new ArrayList<>());

				assertTrue(errors.isEmpty(),
						() -> file.getFileName() + " is clean but its round trip is not: " + errors);
			}));
		}
		return tests;
	}
}
