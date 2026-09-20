package org.vorpal.blade.services.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;

/// The framework's own event types cannot be published over HTTP; forging an
/// audit or call record that way is the point of the check.
class ReservedEventTypeTest {

	@Test
	void everyFrameworkTypeIsReserved() {
		for (String type : new String[] { BladeEventTypes.ACCESS_PERMITTED, BladeEventTypes.ACCESS_DENIED,
				BladeEventTypes.CALL_STARTED, BladeEventTypes.CALL_EVENT, BladeEventTypes.SESSION_STOPPED,
				BladeEventTypes.SESSION_KEY }) {
			assertTrue(EventIngestResource.isReservedType(type), type);
		}
	}

	@Test
	void operatorAndEmptyTypesAreNotReserved() {
		assertFalse(EventIngestResource.isReservedType("com.example.orderPlaced"));
		assertFalse(EventIngestResource.isReservedType("callStarted"));
		assertFalse(EventIngestResource.isReservedType(null));
	}
}
