package org.vorpal.blade.framework.v2.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/// Reads and writes text files, keeping a bounded history of prior versions in
/// a sibling `.versions/` directory so an edit is always recoverable.
///
/// This is the generic file-I/O-with-history core that the Configurator's
/// `FileManagerServlet` grew organically (`createVersionBackup` /
/// `cleanupOldVersions`, keep-last-20). It is factored out here so other admin
/// tools — the schema-less domain-file editor (`admin/files`) in particular —
/// reuse the same backup discipline instead of copy-pasting it.
///
/// Backups are named `<filename>.<epochMillis>.version`. Lexical sort of that
/// suffix is the same as chronological sort (fixed-width millis), so newest is
/// `compareTo`-greatest. Only the most recent [#getMaxVersions] are retained.
///
/// Stateless aside from the configured retention count; safe to share. Path
/// scoping (which files a caller is allowed to touch) is the caller's
/// responsibility — this class operates on whatever absolute `Path` it's given.
public final class VersionedFileStore {

	/// Default number of historical versions retained per file.
	public static final int DEFAULT_MAX_VERSIONS = 20;

	private static final String VERSIONS_DIR = ".versions";
	private static final String VERSION_SUFFIX = ".version";

	private final int maxVersions;

	/// A single retained backup: when it was taken and how big it is.
	public static final class VersionInfo {
		private final long timestamp;
		private final long sizeBytes;

		public VersionInfo(long timestamp, long sizeBytes) {
			this.timestamp = timestamp;
			this.sizeBytes = sizeBytes;
		}

		/// Epoch millis the backup was taken (and its filename discriminator).
		public long getTimestamp() {
			return timestamp;
		}

		public long getSizeBytes() {
			return sizeBytes;
		}
	}

	public VersionedFileStore() {
		this(DEFAULT_MAX_VERSIONS);
	}

	public VersionedFileStore(int maxVersions) {
		this.maxVersions = maxVersions < 1 ? 1 : maxVersions;
	}

	public int getMaxVersions() {
		return maxVersions;
	}

	/// Read a file as UTF-8 text.
	public String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	/// Write UTF-8 text to a file, first backing up any existing content into
	/// `.versions/`. Parent directories are created as needed.
	public void write(Path file, String content) throws IOException {
		if (Files.exists(file)) {
			createBackup(file);
		} else if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}
		Files.write(file, content.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	/// Copy the current file into `.versions/<name>.<millis>.version` and prune
	/// to [#getMaxVersions]. Assumes the file exists.
	public void createBackup(Path file) throws IOException {
		Path versionsDir = file.getParent().resolve(VERSIONS_DIR);
		Files.createDirectories(versionsDir);

		String fileName = file.getFileName().toString();
		long timestamp = System.currentTimeMillis();
		Path versionPath = versionsDir.resolve(fileName + "." + timestamp + VERSION_SUFFIX);
		Files.copy(file, versionPath, StandardCopyOption.REPLACE_EXISTING);

		cleanupOldVersions(versionsDir, fileName);
	}

	/// List retained backups for a file, newest first.
	public List<VersionInfo> listVersions(Path file) throws IOException {
		Path versionsDir = file.getParent().resolve(VERSIONS_DIR);
		List<VersionInfo> versions = new ArrayList<>();
		if (!Files.exists(versionsDir)) {
			return versions;
		}
		String prefix = file.getFileName().toString() + ".";
		try (Stream<Path> stream = Files.list(versionsDir)) {
			stream.filter(p -> isVersionOf(p, prefix)).forEach(p -> {
				Long ts = parseTimestamp(p, prefix);
				if (ts != null) {
					try {
						versions.add(new VersionInfo(ts, Files.size(p)));
					} catch (IOException ignored) {
						// File vanished between listing and stat — skip it.
					}
				}
			});
		}
		versions.sort(Comparator.comparingLong(VersionInfo::getTimestamp).reversed());
		return versions;
	}

	/// Read the content of a specific backup without touching the live file —
	/// for previewing a version before deciding whether to [#restore] it.
	public String readVersion(Path file, long timestamp) throws IOException {
		Path versionPath = versionPath(file, timestamp);
		if (!Files.exists(versionPath)) {
			throw new IOException("No such version: " + timestamp);
		}
		return new String(Files.readAllBytes(versionPath), StandardCharsets.UTF_8);
	}

	/// Restore a specific backup as the live file, backing up the (about to be
	/// overwritten) current content first. Returns the restored text.
	public String restore(Path file, long timestamp) throws IOException {
		String content = readVersion(file, timestamp);
		write(file, content);
		return content;
	}

	private Path versionPath(Path file, long timestamp) {
		Path versionsDir = file.getParent().resolve(VERSIONS_DIR);
		return versionsDir.resolve(file.getFileName().toString() + "." + timestamp + VERSION_SUFFIX);
	}

	private void cleanupOldVersions(Path versionsDir, String baseFileName) throws IOException {
		String prefix = baseFileName + ".";
		List<Path> versions = new ArrayList<>();
		try (Stream<Path> stream = Files.list(versionsDir)) {
			stream.filter(p -> isVersionOf(p, prefix)).forEach(versions::add);
		}
		// Filename suffix is fixed-width epoch millis, so reverse-lexical == newest-first.
		versions.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
		for (int i = maxVersions; i < versions.size(); i++) {
			Files.delete(versions.get(i));
		}
	}

	private static boolean isVersionOf(Path p, String prefix) {
		String name = p.getFileName().toString();
		return name.startsWith(prefix) && name.endsWith(VERSION_SUFFIX);
	}

	private static Long parseTimestamp(Path p, String prefix) {
		String name = p.getFileName().toString();
		String middle = name.substring(prefix.length(), name.length() - VERSION_SUFFIX.length());
		try {
			return Long.valueOf(middle);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
