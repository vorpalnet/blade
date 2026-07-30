package org.vorpal.blade.services.analytics.jms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.EventType;

/// Which events the sink writes.
///
/// The tests here are all about one failure mode: **analytics silently recording
/// nothing.** The subscription is healthy, the service is up, the log is quiet,
/// and no rows appear. That is what a wrong default here looks like from the
/// outside, so the defaults are worth pinning.
class AnalyticsCatalogTest {

	@Test
	@DisplayName("a domain with no events.json still records what BLADE emits")
	void freshDomainRecordsFrameworkEvents() {
		for (EventType declared : BladeEventCatalog.analyticsTypes()) {
			assertTrue(AnalyticsCatalog.persists(declared.getType()),
					declared.getType() + " must be recorded on a domain that has never published a catalog");
		}
	}

	/// The upgrade hazard. `persist` is a new field, so an `events.json`
	/// published before it exists carries `false` on every type it declares —
	/// and reading that literally would stop analytics dead the moment the
	/// service was upgraded, with nothing anywhere saying so.
	@Test
	@DisplayName("and so does a domain whose published catalog predates the persist flag")
	void anOlderCatalogDoesNotSilenceAnalytics() {
		assertTrue(AnalyticsCatalog.persists(BladeEventTypes.CALL_STARTED));
		assertTrue(AnalyticsCatalog.persists(BladeEventTypes.TRANSFER_REQUESTED));
		assertTrue(AnalyticsCatalog.persists(BladeEventTypes.SESSION_KEY));
		assertTrue(AnalyticsCatalog.persists(BladeEventTypes.APPLICATION_STARTED));
	}

	@Test
	@DisplayName("a type nobody declared is not written")
	void undeclaredTypesAreNotWritten() {
		assertFalse(AnalyticsCatalog.persists("net.vorpal.attendant.meeting.scheduled"),
				"an application event nobody marked persisted has no declared shape to store");
		assertFalse(AnalyticsCatalog.persists("com.example.something.entirely.unknown"));
		assertFalse(AnalyticsCatalog.persists(null));
	}
}
