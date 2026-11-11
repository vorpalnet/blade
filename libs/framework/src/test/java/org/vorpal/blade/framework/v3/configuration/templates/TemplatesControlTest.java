package org.vorpal.blade.framework.v3.configuration.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// [TemplatesControl] writes only under `_templates/`, whole, line breaks kept.
class TemplatesControlTest {

	private final TemplatesControl control = new TemplatesControl();

	@AfterEach
	void clean() throws Exception {
		if (!Files.exists(Path.of("config"))) return;
		try (Stream<Path> files = Files.walk(Path.of("config"))) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	@Test
	void writesATemplateWholeAndAnswersItsChecksum() throws Exception {
		assertNull(control.checksum("zz/body.txt"));
		String text = "line one\r\nline two\n after";
		control.write("zz/body.txt", text);
		byte[] bytes = Files.readAllBytes(TemplatesControl.BASE.resolve("zz/body.txt"));
		assertEquals(text, new String(bytes, StandardCharsets.UTF_8));
		assertEquals(TemplatesControl.checksum(bytes), control.checksum("zz/body.txt"));
	}

	@Test
	void refusesAnythingOutsideTemplates() {
		assertThrows(IllegalArgumentException.class, () -> control.write("../x.json", "{}"));
		assertThrows(IllegalArgumentException.class, () -> control.write("/etc/x", "x"));
		assertThrows(IllegalArgumentException.class, () -> control.write(".versions/x.txt.1.version", "x"));
		assertThrows(IllegalArgumentException.class, () -> control.write("", "x"));
	}
}
