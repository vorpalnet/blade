package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The pending list survives a restart, and a stopped watcher lists what it
/// never pushed.
class PendingListTest {

	@TempDir
	Path config;

	@AfterEach
	void clean() throws Exception {
		ValidationAPI.UNPUBLISHED.clear();
		Path servers = Paths.get("servers");
		if (!Files.exists(servers)) return;
		try (Stream<Path> all = Files.walk(servers)) {
			all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	@Test
	void thePendingListIsReadBackAfterARestart() {
		ValidationAPI.saved("zz-app", true);
		ValidationAPI.UNPUBLISHED.clear();      // as a restart finds it
		ValidationAPI.loadPending();
		assertTrue(ValidationAPI.UNPUBLISHED.contains("zz-app"));
		ValidationAPI.watcherPublished("zz-app");
		ValidationAPI.loadPending();
		assertFalse(ValidationAPI.UNPUBLISHED.contains("zz-app"));
	}

	@Test
	void aDomainPushDoesNotClearAnOverlaysEntry() {
		ValidationAPI.watcherMissed("zz-ov", true);    // a cluster file failed on a server
		ValidationAPI.domainPushed("zz-ov");           // then the domain file went everywhere
		assertTrue(ValidationAPI.UNPUBLISHED.contains("zz-ov"));
		ValidationAPI.watcherMissed("zz-dom", false);
		ValidationAPI.domainPushed("zz-dom");
		assertFalse(ValidationAPI.UNPUBLISHED.contains("zz-dom"));
	}

	/// An overlay saved with auto-publish off is held like a failed one: the
	/// watcher's later push of the domain file does not carry it.
	@Test
	void anOverlaySavedOffIsNotClearedByADomainPush() {
		ValidationAPI.saved("zz-ovsave", true, true);
		ValidationAPI.domainPushed("zz-ovsave");
		assertTrue(ValidationAPI.UNPUBLISHED.contains("zz-ovsave"));
		ValidationAPI.saved("zz-domsave", true, false);
		ValidationAPI.domainPushed("zz-domsave");
		assertFalse(ValidationAPI.UNPUBLISHED.contains("zz-domsave"));
	}

	@Test
	void aStoppedWatcherListsWhatItNeverPushed() throws Exception {
		ConfigurationMonitor m = new ConfigurationMonitor();
		m.initialize(config, true);
		Path pushed = Files.writeString(config.resolve("zz-pushed.json"), "{}");
		Path missed = Files.writeString(config.resolve("zz-missed.json"), "{}");
		Files.createDirectories(config.resolve("_servers/engine9"));
		Path overlay = Files.writeString(config.resolve("_servers/engine9/zz-overlay.json"), "{}");
		assertTrue(m.firstSightOf(pushed));     // the watcher handled this one
		m.shutdown();                            // never started: stops at once, then lists
		assertFalse(ValidationAPI.UNPUBLISHED.contains("zz-pushed"));
		assertTrue(ValidationAPI.UNPUBLISHED.contains("zz-missed"));
		assertTrue(ValidationAPI.UNPUBLISHED.contains("zz-overlay"));
	}
}
