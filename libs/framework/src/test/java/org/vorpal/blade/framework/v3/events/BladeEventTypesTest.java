package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Pins the canonical event-type names BLADE emits.
///
/// These are a wire contract: once nodes publish them and consumers select on
/// them, renaming one silently orphans a durable subscription. The tests are
/// deliberately about *stability and shape*, not cleverness.
class BladeEventTypesTest {

	private static List<String> allTypes() throws Exception {
		List<String> types = new ArrayList<>();
		for (Field field : BladeEventTypes.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				types.add((String) field.get(null));
			}
		}
		return types;
	}

	@Test
	@DisplayName("every type is distinct — a duplicate would silently merge two streams")
	void typesAreDistinct() throws Exception {
		List<String> types = allTypes();
		Set<String> unique = new HashSet<>(types);
		assertEquals(types.size(), unique.size(), types.toString());
		assertFalse(types.isEmpty());
	}

	@Test
	@DisplayName("every type is reverse-DNS under the framework's own namespace")
	void typesAreNamespaced() throws Exception {
		for (String type : allTypes()) {
			assertTrue(type.startsWith("org.vorpal.blade."),
					type + " is outside the framework's namespace; app events belong in their own");
			assertFalse(type.endsWith("."), type + " is truncated");
			assertFalse(type.contains(".analytics."),
					type + " names a consumer in a producer's contract. These are facts about a call that "
							+ "analytics happens to record — a transfer app subscribing to one should not have to "
							+ "read a name that says it belongs to somebody else.");
		}
	}

	@Test
	@DisplayName("start and stop are separate types, so no consumer infers intent from a null field")
	void startAndStopAreDistinct() {
		assertNotEquals(BladeEventTypes.APPLICATION_STARTED, BladeEventTypes.APPLICATION_STOPPED);
		assertNotEquals(BladeEventTypes.SESSION_STARTED, BladeEventTypes.SESSION_STOPPED);
	}

	@Test
	@DisplayName("each type is usable verbatim as a JMS message selector value")
	void typesAreSelectorSafe() throws Exception {
		for (String type : allTypes()) {
			assertFalse(type.contains("'"), type + " would break a quoted selector literal");
			assertFalse(type.contains(" "), type + " contains whitespace");
			// The shape EventType.selector() produces, and what a generated MDB
			// carries as its messageSelector.
			String selector = EventPublisher.PROP_TYPE + " = '" + type + "'";
			assertTrue(selector.startsWith("eventType = '"));
		}
	}

	@Test
	@DisplayName("the analytics stream selects on the same property the v3 publisher stamps")
	void oneSelectorVocabularyAcrossBothHalves() {
		assertEquals("eventType", EventPublisher.PROP_TYPE);
		assertEquals("eventSubject", EventPublisher.PROP_SUBJECT);
		assertEquals("eventId", EventPublisher.PROP_ID);
	}
}
