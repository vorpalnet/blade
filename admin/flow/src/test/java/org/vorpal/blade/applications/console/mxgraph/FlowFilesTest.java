package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Name validation and listing for the saved-flow library.
///
/// Like `FsmarTargetsTest`, these build the domain layout they need under the
/// module directory (surefire's working directory) and remove it afterwards,
/// and only when it wasn't already there — a real domain's `config` tree must
/// never be deleted out from under it.
class FlowFilesTest {

	private static final Path ROOT = Paths.get("config");

	private boolean rootPreexisted;

	@BeforeEach
	void noteExistingLayout() {
		rootPreexisted = Files.exists(ROOT);
	}

	@AfterEach
	void cleanUp() throws IOException {
		if (rootPreexisted || !Files.exists(ROOT)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(ROOT)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort; a leftover empty dir is harmless
				}
			});
		}
	}

	private void write(String name, String content) throws IOException {
		FlowFiles.ensureDirectory();
		Files.write(FlowFiles.FLOWS_DIR.resolve(name), content.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("a missing library directory lists as empty, and is not created by reading")
	void listsEmptyWhenAbsent() throws IOException {
		assertTrue(FlowFiles.list().isEmpty());
		assertTrue(!Files.exists(FlowFiles.FLOWS_DIR) || FlowFiles.list().isEmpty());
	}

	@Test
	@DisplayName("lists only .json files, newest first")
	void listsNewestFirst() throws IOException {
		write("older.json", "{}");
		write("newer.json", "{}");
		Files.setLastModifiedTime(FlowFiles.FLOWS_DIR.resolve("older.json"),
				java.nio.file.attribute.FileTime.fromMillis(1000L));
		Files.setLastModifiedTime(FlowFiles.FLOWS_DIR.resolve("newer.json"),
				java.nio.file.attribute.FileTime.fromMillis(2000L));
		write("notes.txt", "ignored");

		List<FlowFiles.Entry> entries = FlowFiles.list();
		assertEquals(2, entries.size());
		assertEquals("newer.json", entries.get(0).getName());
		assertEquals("older.json", entries.get(1).getName());
		assertEquals(2, entries.get(0).getBytes());
	}

	@Test
	@DisplayName("accepts a plain name ending in .json")
	void acceptsPlainName() {
		Path path = FlowFiles.resolve("demo1.json");
		assertNotNull(path);
		assertEquals("demo1.json", path.getFileName().toString());
		assertTrue(path.startsWith(FlowFiles.FLOWS_DIR));
		assertNotNull(FlowFiles.resolve("webrtc-to-pstn_v2.json"));
	}

	@Test
	@DisplayName("refuses traversal, separators and anything not ending in .json")
	void refusesEscapes() {
		assertNull(FlowFiles.resolve("../fsmar.json"));
		assertNull(FlowFiles.resolve("_clusters/x/fsmar.json"));
		assertNull(FlowFiles.resolve("..\\fsmar.json"));
		assertNull(FlowFiles.resolve("/etc/passwd.json"));
		assertNull(FlowFiles.resolve("demo1"));
		assertNull(FlowFiles.resolve("demo1.txt"));
		assertNull(FlowFiles.resolve(""));
		assertNull(FlowFiles.resolve("   "));
		assertNull(FlowFiles.resolve(null));
		assertNull(FlowFiles.resolve("demo 1.json"));
	}

	@Test
	@DisplayName("refuses a name longer than the limit")
	void refusesOverlongName() {
		StringBuilder sb = new StringBuilder();
		while (sb.length() <= FlowFiles.MAX_NAME) {
			sb.append('a');
		}
		assertNull(FlowFiles.resolve(sb + ".json"));
	}
}
