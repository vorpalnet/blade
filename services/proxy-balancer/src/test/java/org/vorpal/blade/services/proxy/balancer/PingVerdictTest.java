package org.vorpal.blade.services.proxy.balancer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// The ping verdict rules — default (any-final-response-is-alive) and the
/// require2xx tightening for BLADE-engine endpoints.
class PingVerdictTest {

	@Test
	@DisplayName("default rule: alive on anything except 408/503")
	void defaultRule() {
		assertTrue(PingVerdict.marksUp(200, false));
		assertTrue(PingVerdict.marksUp(405, false),
				"405 is an endpoint that dislikes OPTIONS, not a dead one");
		assertTrue(PingVerdict.marksUp(500, false),
				"the default rule tolerates errors — the box answered");
		assertFalse(PingVerdict.marksUp(408, false), "nothing answered");
		assertFalse(PingVerdict.marksUp(503, false),
				"the endpoint said so: overloaded, draining, or starting");
	}

	@Test
	@DisplayName("require2xx: a BLADE engine is up only when it affirms with 2xx")
	void require2xxRule() {
		assertTrue(PingVerdict.marksUp(200, true));
		assertFalse(PingVerdict.marksUp(405, true));
		assertFalse(PingVerdict.marksUp(500, true),
				"a booting container's errors must not enroll a half-started node");
		assertFalse(PingVerdict.marksUp(408, true));
		assertFalse(PingVerdict.marksUp(503, true));
	}
}
