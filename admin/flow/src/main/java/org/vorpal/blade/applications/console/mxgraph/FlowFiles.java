package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/// The saved-flow library: FSMAR configurations kept as named files instead of
/// published to a live target.
///
/// A flow saved here is an ordinary FSMAR 3 JSON file — the same content the
/// router runs — parked under `config/custom/vorpal/_flows/` until someone opens
/// it and publishes it. That makes a demo or a staged routing change something
/// you can keep, name (`demo1.json`), and come back to, rather than a download
/// in a browser's file list or a paste buffer.
///
/// The directory is a sibling of the ones the SettingsManager already owns
/// (`_samples`, `_schemas`, `_clusters`, `_servers`), and is deliberately NOT
/// one of them: nothing reads `_flows/` at runtime, so a file landing here can
/// never change how calls route. Publishing is still the only way to do that,
/// and it still goes through [FsmarPublishServlet].
///
/// Path scoping lives here rather than in the servlet so it can be tested
/// without a container: every name crossing the wire goes through [#resolve],
/// which accepts a plain `<name>.json` and nothing else.
public final class FlowFiles {

	/// Where saved flows live, relative to the domain root (this WAR runs on
	/// AdminServer, whose working directory is the domain root — the same
	/// assumption [FsmarTargets] makes).
	static final Path FLOWS_DIR = Paths.get("config/custom/vorpal/_flows");

	/// Longest accepted file name. Long enough for a descriptive name, short
	/// enough that the list stays readable.
	static final int MAX_NAME = 64;

	private FlowFiles() {
	}

	/// One saved flow, as the file browser shows it.
	public static final class Entry {
		private final String name;
		private final long bytes;
		private final long modified;

		Entry(String name, long bytes, long modified) {
			this.name = name;
			this.bytes = bytes;
			this.modified = modified;
		}

		/// File name including the `.json` suffix — also the wire handle.
		public String getName() {
			return name;
		}

		public long getBytes() {
			return bytes;
		}

		/// Last-modified time in epoch millis; the browser formats it.
		public long getModified() {
			return modified;
		}
	}

	/// Every saved flow, newest first — the order a file browser wants, because
	/// the one you were just working on is the one you reach for again.
	public static List<Entry> list() throws IOException {
		List<Entry> entries = new ArrayList<>();
		if (!Files.isDirectory(FLOWS_DIR)) {
			return entries;
		}
		try (Stream<Path> stream = Files.list(FLOWS_DIR)) {
			for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))::iterator) {
				entries.add(new Entry(p.getFileName().toString(), Files.size(p),
						Files.getLastModifiedTime(p).toMillis()));
			}
		}
		entries.sort(Comparator.comparingLong(Entry::getModified).reversed()
				.thenComparing(Entry::getName, String.CASE_INSENSITIVE_ORDER));
		return entries;
	}

	/// The path a wire name refers to, or null when the name isn't one this
	/// directory will accept.
	///
	/// Rejected: anything with a path separator or `..` in it, a name that
	/// doesn't end in `.json`, an empty or over-long name, and any character
	/// outside letters, digits, dot, dash and underscore. Building the path by
	/// resolving a validated single segment (rather than sanitizing a string and
	/// hoping) is what keeps `../fsmar.json` from turning a save into a publish.
	public static Path resolve(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
			return null;
		}
		if (!trimmed.toLowerCase().endsWith(".json")) {
			return null;
		}
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '.' || c == '-' || c == '_';
			if (!ok) {
				return null;
			}
		}
		if (trimmed.contains("..")) {
			return null;
		}
		return FLOWS_DIR.resolve(trimmed);
	}

	/// Creates the library directory on first save. Reading never creates it:
	/// an empty list and a missing directory mean the same thing to the caller.
	static void ensureDirectory() throws IOException {
		Files.createDirectories(FLOWS_DIR);
	}
}
