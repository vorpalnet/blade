package org.vorpal.blade.framework.v3.fsmar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// A hand-edited config with an explicit `null` where a collection belongs used
/// to deserialize to a null field, pass validation, and then throw on the first
/// request routed through it — a 500 per call until someone republished.
///
/// The empty case is deliberately NOT coerced away: an empty `transitions` list
/// is a decision (the router treats it as an implicit match and lets the request
/// route downstream), so these tests pin both behaviors together.
class NullCollectionsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("\"triggers\": null deserializes to an empty map")
	void nullTriggers() throws Exception {
		State state = MAPPER.readValue("{\"triggers\":null}", State.class);
		assertNotNull(state.getTriggers(), "null triggers must not survive into the model");
		assertTrue(state.getTriggers().isEmpty());
	}

	@Test
	@DisplayName("\"transitions\": null deserializes to an empty list")
	void nullTransitions() throws Exception {
		Trigger trigger = MAPPER.readValue("{\"transitions\":null}", Trigger.class);
		assertNotNull(trigger.getTransitions(), "null transitions must not survive into the model");
		assertTrue(trigger.getTransitions().isEmpty());
	}

	@Test
	@DisplayName("a state with null triggers still answers getTrigger()")
	void getTriggerAfterNull() throws Exception {
		State state = MAPPER.readValue("{\"triggers\":null}", State.class);
		assertNotNull(state.getTrigger("INVITE"));
		assertEquals(1, state.getTriggers().size());
	}

	@Test
	@DisplayName("an empty transitions list is preserved, not treated as absent")
	void emptyTransitionsSurvive() throws Exception {
		Trigger trigger = MAPPER.readValue("{\"transitions\":[]}", Trigger.class);
		assertNotNull(trigger.getTransitions());
		assertTrue(trigger.getTransitions().isEmpty());

		String json = MAPPER.writeValueAsString(trigger);
		assertTrue(json.contains("\"transitions\":[]"),
				"an empty trigger is a routing decision and must round-trip: " + json);
	}

	@Test
	@DisplayName("a whole config with a null triggers block loads")
	void wholeConfigLoads() throws Exception {
		String cfg = "{\"states\":{\"b2bua\":{\"triggers\":null},"
				+ "\"screening\":{\"triggers\":{\"INVITE\":{\"transitions\":null}}}}}";
		AppRouterConfiguration config = MAPPER.readValue(cfg, AppRouterConfiguration.class);

		assertNotNull(config.getStates().get("b2bua").getTriggers());
		assertTrue(config.getStates().get("b2bua").getTriggers().isEmpty());
		assertNotNull(config.getStates().get("screening").getTriggers()
				.get("INVITE").getTransitions());
	}
}
