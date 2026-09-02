package org.vorpal.blade.applications.console.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.vorpal.blade.applications.console.tuning.ServerStartTargets.Kind;
import org.vorpal.blade.applications.console.tuning.ServerStartTargets.Target;

/// The baseline is pinned once and survives; the history keeps the last states before each
/// write and finds them by their own id after they roll into `.versions/`.
public class ServerStartSnapshotStoreTest {

	@TempDir
	Path dir;

	private static Target target(String name, Kind kind, String args) {
		return new Target(name, kind, "engines", "", kind == Kind.TEMPLATE ? Arrays.asList("engine1", "engine2")
				: Collections.emptyList(), "/x/weblogic.jar:/x/weblogic_sip.jar", args, "", "", null, null);
	}

	private static List<Target> live(String adminArgs) {
		return Arrays.asList(target("AdminServer", Kind.SERVER, adminArgs), target("engine0", Kind.SERVER, "-Xmx768m"),
				target("engines-template", Kind.TEMPLATE, "-Xmx1024m"));
	}

	@Test
	public void aSnapshotRoundTripsEveryTargetVerbatim() throws Exception {
		ServerStartSnapshot.Store store = new ServerStartSnapshot.Store(dir);
		assertFalse(store.hasBaseline());
		assertNull(store.readBaseline());

		store.writeBaseline(ServerStartSnapshot.capture(live("-Xms512m -Xmx1024m -Dwls.home=/x"), "first sight"));

		ServerStartSnapshot b = store.readBaseline();
		assertNotNull(b);
		assertEquals("first sight", b.getReason());
		assertTrue(b.getCapturedAtMillis() > 0);
		assertEquals(Arrays.asList("AdminServer", "engine0", "engines-template"),
				Arrays.asList(b.getTargets().keySet().toArray()));
		assertEquals("-Xms512m -Xmx1024m -Dwls.home=/x", b.getTargets().get("AdminServer").getArguments());
		assertEquals("/x/weblogic.jar:/x/weblogic_sip.jar", b.getTargets().get("AdminServer").getClassPath());
		assertEquals("template", b.getTargets().get("engines-template").getKind());
		assertEquals("server", b.getTargets().get("engine0").getKind());
	}

	@Test
	public void historyIsNewestFirstAndFindableByIdAfterRollingIntoVersions() throws Exception {
		ServerStartSnapshot.Store store = new ServerStartSnapshot.Store(dir);
		long[] ids = new long[3];
		for (int i = 0; i < 3; i++) {
			ServerStartSnapshot s = ServerStartSnapshot.capture(live("-Xmx" + (i + 1) + "g"), "before apply " + i);
			// Distinct ids even inside one millisecond.
			s.setCapturedAtMillis(1000L + i);
			ids[i] = s.getCapturedAtMillis();
			store.recordHistory(s);
			Thread.sleep(2); // VersionedFileStore names backups by millis; keep them distinct
		}

		List<ServerStartSnapshot> history = store.listHistory();
		assertEquals(3, history.size());
		assertEquals("before apply 2", history.get(0).getReason());
		assertEquals("before apply 0", history.get(2).getReason());

		// The oldest has rolled out of the live file into .versions/ and is still found by id.
		ServerStartSnapshot oldest = store.readHistory(ids[0]);
		assertNotNull(oldest);
		assertEquals("-Xmx1g", oldest.getTargets().get("AdminServer").getArguments());
		assertNull(store.readHistory(42L));
	}

	@Test
	public void rebaselineKeepsThePreviousBaselineAsAVersion() throws Exception {
		ServerStartSnapshot.Store store = new ServerStartSnapshot.Store(dir);
		store.writeBaseline(ServerStartSnapshot.capture(live("-Xmx1g"), "first sight"));
		Thread.sleep(2);
		store.writeBaseline(ServerStartSnapshot.capture(live("-Xmx2g"), "operator re-baseline"));

		assertEquals("-Xmx2g", store.readBaseline().getTargets().get("AdminServer").getArguments());
		assertTrue(java.nio.file.Files.list(dir.resolve(".versions"))
				.anyMatch(p -> p.getFileName().toString().startsWith(ServerStartSnapshot.Store.BASELINE_FILE + ".")));
	}
}
