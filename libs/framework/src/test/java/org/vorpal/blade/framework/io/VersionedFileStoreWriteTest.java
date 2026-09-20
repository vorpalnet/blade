package org.vorpal.blade.framework.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// How a save reaches disk, which matters because one of the files this store
/// writes is the router's live configuration and the engine tier reloads it on
/// change. A reader arriving mid-save must see the old content or the new one.
class VersionedFileStoreWriteTest {

	private Path dir;

	@BeforeEach
	void makeDir() throws IOException {
		dir = Files.createTempDirectory("vfs-write");
	}

	@AfterEach
	void removeDir() throws IOException {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		}
	}

	private List<Path> versions(Path file) throws IOException {
		Path versionsDir = file.getParent().resolve(".versions");
		if (!Files.isDirectory(versionsDir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(versionsDir)) {
			return stream.sorted().collect(Collectors.toList());
		}
	}

	@Test
	@DisplayName("a write leaves the full content and no temp file behind")
	void writeIsClean() throws IOException {
		VersionedFileStore store = new VersionedFileStore();
		Path file = dir.resolve("fsmar.json");

		store.write(file, "{\"states\":{}}");
		store.write(file, "{\"states\":{\"a\":{}}}");

		assertEquals("{\"states\":{\"a\":{}}}",
				new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

		try (Stream<Path> stream = Files.list(dir)) {
			List<String> strays = stream.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".tmp"))
					.collect(Collectors.toList());
			assertTrue(strays.isEmpty(), "temp files left behind: " + strays);
		}
	}

	@Test
	@DisplayName("a shorter rewrite does not leave the tail of the longer one")
	void writeDoesNotAppend() throws IOException {
		VersionedFileStore store = new VersionedFileStore();
		Path file = dir.resolve("fsmar.json");

		store.write(file, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		store.write(file, "bbb");

		assertEquals("bbb", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("saves inside the same millisecond each keep their own version")
	void rapidSavesKeepEveryVersion() throws IOException {
		VersionedFileStore store = new VersionedFileStore();
		Path file = dir.resolve("fsmar.json");

		store.write(file, "v1");
		for (int i = 2; i <= 6; i++) {
			store.write(file, "v" + i);   // tight loop: several land in one millisecond
		}

		// Five overwrites of an existing file, so five prior contents retained.
		List<Path> kept = versions(file);
		assertEquals(5, kept.size(), "versions: " + kept);

		List<String> contents = kept.stream().map(p -> {
			try {
				return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}).collect(Collectors.toList());
		assertEquals(List.of("v1", "v2", "v3", "v4", "v5"), contents,
				"lexical order of the millis suffix must still read chronologically");
		assertFalse(contents.contains("v6"), "the live content is not a version");
	}
}
