package org.vorpal.blade.framework.v3.configuration.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// [TemplateCache]: one instance until the file changes; a replaced file of
/// the same length in the same second still reads as changed.
class TemplateCacheTest {

	@TempDir
	Path dir;

	@Test
	void sameTextUntilTheFileIsReplaced() throws Exception {
		Path t = dir.resolve("q.sql");
		assertNull(TemplateCache.read(t));
		Files.writeString(t, "select 1");
		String first = TemplateCache.read(t);
		assertEquals("select 1", first);
		assertSame(first, TemplateCache.read(t));
		// A push: same length, replaced atomically, possibly within the same second.
		Path temp = dir.resolve(".q.sql.tmp");
		Files.writeString(temp, "select 2");
		Files.setLastModifiedTime(temp, Files.getLastModifiedTime(t));
		Files.move(temp, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Thread.sleep(TemplateCache.CHECK_MILLIS + 50);
		assertEquals("select 2", TemplateCache.read(t));
	}
}
