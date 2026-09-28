package org.vorpal.blade.services.proxy.balancer;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
