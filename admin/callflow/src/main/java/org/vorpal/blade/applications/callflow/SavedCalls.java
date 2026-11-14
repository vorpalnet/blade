package org.vorpal.blade.applications.callflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Calls saved from the Trace page, kept on the AdminServer's host: one folder
/// per call, named for its Vorpal-ID, under the `savedTracesDirectory` setting
/// (`~/.blade/traces` unless set).
///
/// - `trace.json` is the call as the page holds it: `sources` (each app's
///   steps of that call, in the shape `api/traces` returns) and `sourceFiles`
///   (the source behind each step, so the Source tab still shows the code as it
///   was when the code has since changed), plus who saved it and when.
/// - `summary.json` is what the list of saved calls shows, so listing reads a
///   few hundred bytes a call rather than every trace with its sources.
///
/// Saving a call again replaces it: the buffer may have caught more of it
/// since. Files are written to a temporary name and moved into place, so a
/// reader never sees half a file. The folders are readable by their owner only:
/// a trace holds whole SIP messages, caller numbers included.
final class SavedCalls {

	static final String DEFAULT_DIRECTORY = "~/.blade/traces";
	private static final String TRACE = "trace.json";
	private static final String SUMMARY = "summary.json";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SavedCalls() {
	}

	/// A Vorpal-ID, upper-cased, or null when `id` is not one. It names a
	/// folder, so nothing but hexadecimal digits gets through.
	static String vorpalId(String id) {
		if (id == null || !id.trim().matches("[0-9A-Fa-f]{1,16}")) {
			return null;
		}
		return id.trim().toUpperCase(Locale.ROOT);
	}

	/// The directory `setting` names, a leading `~` expanded.
	static Path directory(String setting) {
		String dir = (setting == null || setting.trim().isEmpty()) ? DEFAULT_DIRECTORY : setting.trim();
		if (dir.equals("~") || dir.startsWith("~/")) {
			dir = System.getProperty("user.home") + dir.substring(1);
		}
		return Paths.get(dir).toAbsolutePath().normalize();
	}

	/// The saved calls, newest first: `{"calls":[summary...]}`.
	static ObjectNode list(Path root) throws IOException {
		List<ObjectNode> summaries = new ArrayList<>();
		if (Files.isDirectory(root)) {
			try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
				for (Path dir : dirs) {
					Path summary = dir.resolve(SUMMARY);
					if (vorpalId(dir.getFileName().toString()) == null || !Files.isRegularFile(summary)) {
						continue;
					}
					try {
						summaries.add((ObjectNode) MAPPER.readTree(summary.toFile()));
					} catch (IOException e) {
						// a summary being replaced, or one left broken: not listed
					}
				}
			}
		}
		summaries.sort((a, b) -> Long.compare(b.path("savedAt").asLong(), a.path("savedAt").asLong()));
		ObjectNode out = MAPPER.createObjectNode();
		out.putArray("calls").addAll(summaries);
		return out;
	}

	/// One saved call's `trace.json`, or null when there is none.
	static String read(Path root, String id) throws IOException {
		Path trace = root.resolve(id).resolve(TRACE);
		return Files.isRegularFile(trace) ? new String(Files.readAllBytes(trace), StandardCharsets.UTF_8) : null;
	}

	/// Saves call `id` from the page's `body` (`sources`, `sourceFiles`),
	/// keeping only the steps of that call, and returns its summary.
	static ObjectNode write(Path root, String id, JsonNode body, String savedBy) throws IOException {
		if (body == null || !body.path("sources").isArray()) {
			throw new IllegalArgumentException("a saved call is {\"sources\":[...], \"sourceFiles\":{...}}");
		}
		ObjectNode trace = MAPPER.createObjectNode();
		trace.put("kind", "blade-trace-snapshot");
		trace.put("version", 1);
		trace.put("vorpalId", id);
		long now = System.currentTimeMillis();
		trace.put("savedAt", now);
		trace.put("savedBy", savedBy);
		ArrayNode sources = trace.putArray("sources");
		Set<String> apps = new LinkedHashSet<>();
		int steps = 0;
		long first = Long.MAX_VALUE;
		for (JsonNode src : body.path("sources")) {
			ArrayNode kept = MAPPER.createArrayNode();
			for (JsonNode step : src.path("steps")) {
				if (id.equalsIgnoreCase(step.path("sessionId").asText())) {
					kept.add(step);
					first = Math.min(first, step.path("epochMillis").asLong(Long.MAX_VALUE));
				}
			}
			if (kept.size() == 0) {
				continue;
			}
			ObjectNode copy = ((ObjectNode) src).deepCopy();
			copy.set("steps", kept);
			sources.add(copy);
			apps.add(src.path("app").asText());
			steps += kept.size();
		}
		if (steps == 0) {
			throw new IllegalArgumentException("no steps of call " + id + " in the request");
		}
		if (body.path("sourceFiles").isObject()) {
			trace.set("sourceFiles", body.get("sourceFiles"));
		}

		ObjectNode summary = MAPPER.createObjectNode();
		summary.put("id", id);
		summary.put("savedAt", now);
		summary.put("savedBy", savedBy);
		summary.put("firstMillis", first);
		summary.put("steps", steps);
		ArrayNode appList = summary.putArray("apps");
		apps.forEach(appList::add);

		Path dir = root.resolve(id);
		createPrivate(root);
		createPrivate(dir);
		replace(dir.resolve(TRACE), MAPPER.writeValueAsBytes(trace));
		replace(dir.resolve(SUMMARY), MAPPER.writeValueAsBytes(summary));
		return summary;
	}

	/// Deletes saved call `id`; false when there was none.
	static boolean delete(Path root, String id) throws IOException {
		Path dir = root.resolve(id);
		if (!Files.isDirectory(dir)) {
			return false;
		}
		Files.deleteIfExists(dir.resolve(SUMMARY));
		Files.deleteIfExists(dir.resolve(TRACE));
		Files.deleteIfExists(dir);
		return true;
	}

	private static void createPrivate(Path dir) throws IOException {
		if (Files.isDirectory(dir)) {
			return;
		}
		Files.createDirectories(dir);
		try {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
		} catch (UnsupportedOperationException e) {
			// not a POSIX file system: the platform's default permissions stand
		}
	}

	private static void replace(Path file, byte[] content) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, content);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
