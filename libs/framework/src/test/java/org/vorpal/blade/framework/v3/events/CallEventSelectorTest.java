package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A consumer that takes every call event: what the publisher marks and what
/// the selector asks for have to agree.
class CallEventSelectorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void anEventCarryingAVorpalIdIsACallEvent() {
		ObjectNode call = MAPPER.createObjectNode().put("vorpalId", "0000BEEF");
		ObjectNode room = MAPPER.createObjectNode().put("room", "lobby");
		assertTrue(CloudEvent.create("t", "/s", null, call).isCallScoped());
		assertFalse(CloudEvent.create("t", "/s", null, room).isCallScoped());
		assertFalse(CloudEvent.create("t", "/s", null, null).isCallScoped());
	}

	@Test
	void namedTypesAloneAreUnchanged() {
		assertEquals("eventType IN ('a', 'b')",
				SubscriptionRegistrar.selectorFor("s", Arrays.asList("a", "b"), null));
	}

	@Test
	void callEventsAreAddedToTheNamedTypes() {
		assertEquals("(eventType IN ('a')) OR (eventCall = TRUE AND eventType NOT IN ('off'))",
				SubscriptionRegistrar.selectorFor("s", Collections.singletonList("a"),
						() -> Collections.singletonList("off")));
	}

	@Test
	void callEventsAloneNeedNoNamedTypes() {
		assertEquals("eventCall = TRUE",
				SubscriptionRegistrar.selectorFor("s", Collections.emptyList(), Collections::emptyList));
	}

	/// Too many exceptions for a selector must widen to "everything", never
	/// narrow to the named types: the consumer's code filter catches the rest.
	@Test
	void tooManyExceptionsTakeEverything() {
		List<String> off = new ArrayList<>();
		for (int i = 0; i <= EventSubscription.MAX_SELECTOR_TYPES; i++) {
			off.add("type" + i);
		}
		assertNull(SubscriptionRegistrar.selectorFor("s", Collections.singletonList("a"), () -> off));
	}
}
