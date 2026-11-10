package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/// The Configurator names a file by the target path it was handed, which with
/// `DOMAIN_HOME` unset (as in these tests) is the relative
/// `./config/custom/vorpal`. That name, a bare name, and an absolute one all
/// land in the config directory, never in a copy of it nested inside itself.
class ConfigPathTest {

	private static final Path ROOT = Paths.get("config/custom/vorpal").toAbsolutePath().normalize();

	@Test
	void aTargetPathIsNotNestedInTheRoot() throws IOException {
		assertEquals(ROOT.resolve("blade-ai.json"), FileManagerServlet.configPath("./config/custom/vorpal/blade-ai.json"));
		assertEquals(ROOT.resolve("blade-ai.json"), FileManagerServlet.configPath("config/custom/vorpal/blade-ai.json"));
		assertEquals(ROOT.resolve("_clusters/c1/x.json"),
				FileManagerServlet.configPath("./config/custom/vorpal/_clusters/c1/x.json"));
	}

	@Test
	void aBareOrAbsoluteNameIsInTheRoot() throws IOException {
		assertEquals(ROOT.resolve("x.json"), FileManagerServlet.configPath("x.json"));
		assertEquals(ROOT.resolve("x.json"), FileManagerServlet.configPath(ROOT.resolve("x.json").toString()));
	}

	@Test
	void anythingOutsideIsStillRefused() {
		assertThrows(IOException.class, () -> FileManagerServlet.configPath("./config/custom/vorpal/../../config.xml"));
		assertThrows(IOException.class, () -> FileManagerServlet.configPath("../x.json"));
		assertThrows(IOException.class, () -> FileManagerServlet.configPath("/etc/x.json"));
		assertThrows(IOException.class, () -> FileManagerServlet.configPath("./config/custom/vorpal/startWebLogic.sh"));
	}
}
