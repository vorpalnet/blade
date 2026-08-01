package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Publish-target enumeration and resolution.
///
/// `FsmarTargets` reads the domain's own directory layout, so these tests build
/// that layout under the module directory (surefire's working directory) and
/// clean it up afterwards. The domain entry is synthetic — it is always offered
/// — so only the overlay cases need directories on disk.
class FsmarTargetsTest {

	/// Root of the tree these tests may create. Only removed again if it wasn't
	/// already there — a real domain has this directory and it must never be
	/// deleted out from under one.
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

	private void makeOverlay(Path base, String name) throws IOException {
		Files.createDirectories(base.resolve(name));
	}

	@Test
	@DisplayName("the domain is always offered, and is the default")
	void domainIsAlwaysPresent() throws IOException {
		List<FsmarTargets.Target> targets = FsmarTargets.list();

		assertTrue(!targets.isEmpty());
		assertEquals(FsmarTargets.DOMAIN, targets.get(0).getId(), "domain must come first");
		assertEquals("domain", targets.get(0).getType());
		assertEquals(FsmarTargets.DOMAIN, FsmarTargets.resolve(null).getId(),
				"a missing target means the domain");
		assertEquals(FsmarTargets.DOMAIN, FsmarTargets.resolve("").getId());
		assertEquals(FsmarTargets.DOMAIN, FsmarTargets.resolve("  ").getId());
	}

	@Test
	@DisplayName("the domain target writes the plain fsmar.json")
	void domainPath() throws IOException {
		FsmarTargets.Target domain = FsmarTargets.resolve(FsmarTargets.DOMAIN);

		assertEquals(FsmarPublishServlet.CONFIG_PATH, domain.getConfigFile(),
				"domain publish must still write the file the engine reads");
	}

	@Test
	@DisplayName("a cluster directory becomes a target with the right overlay path")
	void clusterOverlay() throws IOException {
		makeOverlay(FsmarTargets.CLUSTERS_BASE, "engine-cluster");

		FsmarTargets.Target t = FsmarTargets.resolve("cluster:engine-cluster");

		assertNotNull(t, "cluster directory should have produced a target");
		assertEquals("cluster", t.getType());
		assertEquals("engine-cluster", t.getName());
		assertEquals("Cluster: engine-cluster", t.getDisplayName());
		// Must match SettingsManager#initConfigPaths, or the engine never reads it.
		assertEquals(
				FsmarTargets.CONFIG_BASE.resolve("_clusters/engine-cluster/fsmar.json"),
				t.getConfigFile());
	}

	@Test
	@DisplayName("a server directory becomes a target with the right overlay path")
	void serverOverlay() throws IOException {
		makeOverlay(FsmarTargets.SERVERS_BASE, "engine1");

		FsmarTargets.Target t = FsmarTargets.resolve("server:engine1");

		assertNotNull(t);
		assertEquals("server", t.getType());
		assertEquals("Server: engine1", t.getDisplayName());
		assertEquals(
				FsmarTargets.CONFIG_BASE.resolve("_servers/engine1/fsmar.json"),
				t.getConfigFile());
	}

	@Test
	@DisplayName("an unknown target resolves to null, never to the domain")
	void unknownTargetIsRejected() throws IOException {
		// Falling back to the domain would publish cluster-scoped config
		// domain-wide — the worst available failure.
		assertNull(FsmarTargets.resolve("cluster:does-not-exist"));
		assertNull(FsmarTargets.resolve("server:nope"));
		assertNull(FsmarTargets.resolve("nonsense"));
	}

	@Test
	@DisplayName("a traversal attempt cannot escape the config directory")
	void pathTraversalIsRejected() throws IOException {
		// Resolution matches against the enumerated list rather than building a
		// path from the id, so these are simply not on offer.
		assertNull(FsmarTargets.resolve("cluster:../../../../etc"));
		assertNull(FsmarTargets.resolve("server:.."));
		assertNull(FsmarTargets.resolve("cluster:/etc/passwd"));
	}

	@Test
	@DisplayName("overlay ids are distinct from each other and from the domain")
	void idsAreDistinct() throws IOException {
		makeOverlay(FsmarTargets.CLUSTERS_BASE, "shared-name");

		List<FsmarTargets.Target> targets = FsmarTargets.list();
		long distinct = targets.stream().map(FsmarTargets.Target::getId).distinct().count();

		assertEquals(targets.size(), distinct, () -> "duplicate target ids in " + targets);
	}
}
