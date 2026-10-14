package org.vorpal.blade.services.analytics.jms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Which events the sink writes.
///
/// The tests here are all about one failure mode: **analytics silently recording
/// nothing.** The subscription is healthy, the service is up, the log is quiet,
/// and no rows appear. That is what a wrong default here looks like from the
/// outside, so the defaults are worth pinning.
class AnalyticsCatalogTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// An event about one call, as `Events.publish(app, ...)` shapes it.
	private static CloudEvent callEvent(String type) {
		ObjectNode data = MAPPER.createObjectNode();
		data.put("vorpalId", "0000BEEF");
		data.put("startedAt", "2026-09-28T12:00:00Z");
		return CloudEvent.create(type, "/blade/test", "0000BEEF.0", data);
	}

	/// An event about no call: a room, a node, a configuration file.
	private static CloudEvent operationsEvent(String type) {
		ObjectNode data = MAPPER.createObjectNode();
		data.put("node", "engine0");
		return CloudEvent.create(type, "/blade/test", "engine0", data);
	}

	@Test
	@DisplayName("a domain with no events.json still records what BLADE emits")
	void freshDomainRecordsFrameworkEvents() {
		for (EventType declared : BladeEventCatalog.analyticsTypes()) {
			assertTrue(AnalyticsCatalog.persists(operationsEvent(declared.getType())),
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
		assertTrue(AnalyticsCatalog.persists(callEvent(BladeEventTypes.CALL_STARTED)));
		assertTrue(AnalyticsCatalog.persists(callEvent(BladeEventTypes.TRANSFER_REQUESTED)));
		assertTrue(AnalyticsCatalog.persists(callEvent(BladeEventTypes.SESSION_KEY)));
		assertTrue(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.APPLICATION_STARTED)));
	}

	/// The reason the declaration step went away: an application adds an event
	/// and ships, and nobody edits `events.json`.
	@Test
	@DisplayName("an application's own call event is written without a declaration")
	void undeclaredCallEventsAreWritten() {
		assertTrue(AnalyticsCatalog.persists(callEvent("com.example.shuffle.fraudScored")));
	}

	/// The dashboard's look back at a past hour reads these rows: without
	/// them, Operations has nothing to show for any time but now.
	@Test
	@DisplayName("the framework's operations events are written as it marks them, without a catalog")
	void operationsEventsFollowTheFrameworksFlag() {
		assertTrue(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.ENDPOINT_DOWN)));
		assertTrue(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.TRUNK_FAILED)));
		assertTrue(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.PROBLEM_RAISED)));
		assertTrue(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.QUEUE_DEPTH)));
		assertTrue(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.CONFIG_PUBLISHED)));
		assertFalse(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.SIP_DENIED)),
				"a scanner produces thousands; they answer to network operations");
		assertFalse(AnalyticsCatalog.persists(operationsEvent(BladeEventTypes.REGISTRATION_ADDED)));
		assertTrue(AnalyticsCatalog.persistedTypes().contains(BladeEventTypes.ENDPOINT_DOWN));
		assertFalse(AnalyticsCatalog.persistedTypes().contains(BladeEventTypes.SIP_DENIED));
	}

	@Test
	@DisplayName("an undeclared event about no call is not written")
	void undeclaredOperationsEventsAreNotWritten() {
		assertFalse(AnalyticsCatalog.persists(operationsEvent("net.vorpal.attendant.meeting.scheduled")),
				"an operations event nobody marked persisted stays off the database");
		assertFalse(AnalyticsCatalog.persists(operationsEvent("com.example.something.entirely.unknown")));
		assertFalse(AnalyticsCatalog.persists(null));
	}
}
