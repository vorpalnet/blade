package org.vorpal.blade.services.proxy.balancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/// The bus hears an endpoint change state, not every ping that confirms it.
class EndpointHealthTransitionTest {

	@Test
	void onlyAChangeIsReported() {
		EndpointHealth health = new EndpointHealth();

		assertFalse(health.markUp("OPTIONS 200", "ping", 5), "a new endpoint starts up");
		assertTrue(health.markDown("OPTIONS 408", null, "ping", -1));
		assertFalse(health.markDown("OPTIONS 408", null, "ping", -1), "still down is not news");
		assertFalse(health.markDown("503 backoff 30s", 30, "call", -1), "a renewed backoff is not news");
		assertTrue(health.markUp("OPTIONS 200", "ping", 4));
		assertFalse(health.markUp("INVITE 200", "call", -1));
	}

	@Test
	void aRefusalKeepsItsReasonUntilTheEndpointAnswersOrGoesSilent() {
		EndpointHealth health = new EndpointHealth();

		assertTrue(health.markRefused(503, "Draining", null, "ping", 3));
		assertEquals("503 Draining", health.getRefused());
		assertEquals("OPTIONS 503 Draining", health.getNote());
		assertFalse(health.markRefused(503, "Busy", 30, "call", -1), "still down is not news");
		assertEquals("503 Busy", health.getRefused(), "the latest reason wins");
		assertEquals("503 Busy backoff 30s", health.getNote());
		assertTrue(health.getDownUntil() != null);

		health.markDown("OPTIONS 408", null, "ping", -1);
		assertNull(health.getRefused(), "silence is not a refusal");
		health.markRefused(503, null, null, "ping", 2);
		assertEquals("503", health.getRefused(), "no reason phrase, just the code");
		health.markUp("OPTIONS 200", "ping", 2);
		assertNull(health.getRefused());
	}
}
