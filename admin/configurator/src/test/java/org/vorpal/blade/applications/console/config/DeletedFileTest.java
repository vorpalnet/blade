package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/// A deleted domain file leaves its application pending only where it can
/// linger: on a server other than this one, with its own disk.
class DeletedFileTest {

	@Test
	void anApplicationThatRunsOnlyHereIsNotPending() {
		assertFalse(ConfigurationMonitor.elsewhere(Arrays.asList("AdminServer"), "AdminServer"));
	}

	@Test
	void anApplicationOnAnEngineIsPending() {
		assertTrue(ConfigurationMonitor.elsewhere(Arrays.asList("AdminServer", "engine0"), "AdminServer"));
		assertTrue(ConfigurationMonitor.elsewhere(Arrays.asList((String) null), "AdminServer"), "registered by cluster");
	}

	@Test
	void anApplicationThatRunsNowhereIsNotPending() {
		assertFalse(ConfigurationMonitor.elsewhere(Collections.<String>emptyList(), "AdminServer"));
	}
}
