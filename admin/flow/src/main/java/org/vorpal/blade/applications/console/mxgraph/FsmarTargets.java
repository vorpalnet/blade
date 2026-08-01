package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Where an FSMAR configuration can be published: the domain file, or one of
/// the cluster / server overlays.
///
/// `SettingsManager` reads the three in order and merges them —
/// domain → `_clusters/<cluster>/` → `_servers/<server>/` (`Settings#reload`)
/// — so a narrower file overrides the broader one. The overlay directories are
/// created by the SettingsManager running on each server
/// (`SettingsManager#initConfigPaths`), which is why this enumerates what
/// exists on disk rather than inventing names: a file written to a directory no
/// server reads is silently dead config.
///
/// Same enumeration the Configurator's `FileManagerServlet#listTargetDirectories`
/// does for every other BLADE app config. Paths are relative to the domain root,
/// matching the rest of this module (the WAR runs on AdminServer, whose working
/// directory is the domain root).
public final class FsmarTargets {

	/// Domain-level config directory, relative to the domain root.
	static final Path CONFIG_BASE = Paths.get("config/custom/vorpal");
	static final Path CLUSTERS_BASE = CONFIG_BASE.resolve("_clusters");
	static final Path SERVERS_BASE = CONFIG_BASE.resolve("_servers");

	/// The id of the default target. 99% of configurations are domain-wide.
	public static final String DOMAIN = "domain";

	private FsmarTargets() {
	}

	/// One publishable location.
	public static final class Target {
		private final String id;
		private final String type;
		private final String name;
		private final Path directory;

		Target(String id, String type, String name, Path directory) {
			this.id = id;
			this.type = type;
			this.name = name;
			this.directory = directory;
		}

		/// Stable handle used on the wire: `domain`, `cluster:<name>`, `server:<name>`.
		public String getId() {
			return id;
		}

		public String getType() {
			return type;
		}

		public String getName() {
			return name;
		}

		public Path getDirectory() {
			return directory;
		}

		/// The fsmar.json this target owns.
		public Path getConfigFile() {
			return directory.resolve("fsmar.json");
		}

		/// What the pull-down shows.
		public String getDisplayName() {
			switch (type) {
			case "cluster":
				return "Cluster: " + name;
			case "server":
				return "Server: " + name;
			default:
				return "Domain (all servers)";
			}
		}
	}

	/// Domain first, then clusters, then servers — each sorted by name. The
	/// domain entry is always present even before anything has been published;
	/// the overlay entries only appear once their directory exists.
	public static List<Target> list() throws IOException {
		List<Target> targets = new ArrayList<>();
		targets.add(new Target(DOMAIN, "domain", "domain", CONFIG_BASE));
		targets.addAll(scan(CLUSTERS_BASE, "cluster"));
		targets.addAll(scan(SERVERS_BASE, "server"));
		return targets;
	}

	private static List<Target> scan(Path base, String type) throws IOException {
		if (!Files.isDirectory(base)) {
			return new ArrayList<>();
		}
		try (Stream<Path> stream = Files.list(base)) {
			return stream.filter(Files::isDirectory)
					.sorted(Comparator.comparing(p -> p.getFileName().toString(),
							String.CASE_INSENSITIVE_ORDER))
					.map(p -> {
						String name = p.getFileName().toString();
						return new Target(type + ":" + name, type, name, p);
					})
					.collect(Collectors.toList());
		}
	}

	/// Resolves a wire id to a target, **only** if it is one this domain
	/// actually offers. Matching against the enumerated list rather than
	/// building a path from the id is what keeps `cluster:../../..` from
	/// escaping the config directory. Null (or blank) means the domain.
	///
	/// Returns null for an id that isn't on offer — callers should treat that
	/// as a bad request rather than silently falling back to the domain, since
	/// quietly writing domain-wide config when the operator asked for one
	/// cluster is exactly the wrong failure.
	public static Target resolve(String id) throws IOException {
		String wanted = (id == null || id.trim().isEmpty()) ? DOMAIN : id.trim();
		for (Target t : list()) {
			if (t.getId().equals(wanted)) {
				return t;
			}
		}
		return null;
	}
}
