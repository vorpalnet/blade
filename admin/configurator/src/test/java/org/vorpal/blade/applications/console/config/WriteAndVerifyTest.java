package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vorpal.blade.framework.v2.config.SettingsMXBean;

/// [ValidationAPI#writeAndVerify] against an engine that handles the file as
/// `v2.config.Settings` does: lines written without breaks, read back with
/// readLine, every failure caught and only logged.
class WriteAndVerifyTest {

	@TempDir
	Path dir;

	/// The engine's file handling, as Settings has it.
	static class Engine implements SettingsMXBean {
		final Path file;
		boolean canWrite = true;
		int writeOnly = Integer.MAX_VALUE;   // a disk that fills after this many lines
		private BufferedWriter w;
		private BufferedReader r;
		private int written;

		Engine(Path file) {
			this.file = file;
		}

		public long getLastModified(String t) { return 0; }

		public void openForWrite(String t) {
			try {
				if (!canWrite) throw new IOException("permission denied");
				w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				written = 0;
			} catch (Exception e) {
				// logged only, as Settings does
			}
		}

		public void openForRead(String t) {
			try {
				r = Files.newBufferedReader(file);
			} catch (Exception e) {
				// logged only
			}
		}

		public String read() {
			try {
				return r == null ? null : r.readLine();
			} catch (Exception e) {
				return null;
			}
		}

		public void write(String line) {
			try {
				if (w != null && written++ < writeOnly) w.write(line);
			} catch (Exception e) {
				// logged only
			}
		}

		public void close() {
			try {
				if (r != null) r.close();
				if (w != null) w.close();
			} catch (Exception e) {
				// logged only
			} finally {
				r = null;
				w = null;
			}
		}

		public void reload() {}
		public String getCurrentJson() { return null; }
		public String getSchemaJson() { return null; }
	}

	private static final String CONFIG = "{\n  \"a\" : 1,\n  \"b\" : \"x y\"\r\n}\n";

	@Test
	void aWrittenFileVerifies() throws Exception {
		Engine engine = new Engine(dir.resolve("app.json"));
		ValidationAPI.writeAndVerify(engine, "DOMAIN", CONFIG);
		assertEquals("{  \"a\" : 1,  \"b\" : \"x y\"}", new String(Files.readAllBytes(engine.file), StandardCharsets.UTF_8));
	}

	@Test
	void aLoneCarriageReturnStillVerifies() throws Exception {
		Engine engine = new Engine(dir.resolve("app.json"));
		ValidationAPI.writeAndVerify(engine, "DOMAIN", "{\"a\":1,\r\"b\":2}\n");
	}

	@Test
	void aFileTheEngineCouldNotWriteFails() throws Exception {
		Engine engine = new Engine(dir.resolve("app.json"));
		Files.write(engine.file, "{\"old\":true}".getBytes(StandardCharsets.UTF_8));
		engine.canWrite = false;
		assertThrows(IOException.class, () -> ValidationAPI.writeAndVerify(engine, "DOMAIN", CONFIG));
	}

	@Test
	void aTruncatedWriteFails() {
		Engine engine = new Engine(dir.resolve("app.json"));
		engine.writeOnly = 2;
		assertThrows(IOException.class, () -> ValidationAPI.writeAndVerify(engine, "DOMAIN", CONFIG));
	}
}
