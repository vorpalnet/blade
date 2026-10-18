package org.vorpal.blade.services.proxy.balancer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.services.proxy.balancer.config.HealthParameters;

class FailoverPolicyTest {

	private final HealthParameters defaults = new HealthParameters();

	@Test
	void onlyRouteAndServerFailuresMoveTheCallOn() {
		for (int status : new int[] { 408, 502, 503, 504 }) {
			assertTrue(FailoverPolicy.failover(status, defaults), "moves on: " + status);
		}
		for (int status : new int[] { 200, 302, 401, 404, 480, 486, 487, 500, 603 }) {
			assertFalse(FailoverPolicy.failover(status, defaults), "back to the caller: " + status);
		}
	}

	@Test
	void aRefusalOrSilenceTakesTheEndpointOutButAnAnswered408DoesNot() {
		assertTrue(FailoverPolicy.markDown(503, true, defaults));
		assertTrue(FailoverPolicy.markDown(408, false, defaults), "nothing came back");
		assertFalse(FailoverPolicy.markDown(408, true, defaults), "the endpoint answered: alive");
		assertFalse(FailoverPolicy.markDown(504, true, defaults));
		assertFalse(FailoverPolicy.markDown(404, true, defaults));
	}

	@Test
	void theListsAreConfigurableAndMissingListsMeanTheDefaults() {
		HealthParameters h = new HealthParameters().setFailoverOn(Arrays.asList(500, 503)).setMarkDownOn(Arrays.asList(503));
		assertTrue(FailoverPolicy.failover(500, h));
		assertFalse(FailoverPolicy.failover(408, h));
		assertFalse(FailoverPolicy.markDown(408, false, h));
		HealthParameters none = new HealthParameters().setFailoverOn(null).setMarkDownOn(null);
		assertTrue(FailoverPolicy.failover(408, none));
		assertTrue(FailoverPolicy.markDown(408, false, none));
		assertTrue(FailoverPolicy.failover(503, null));
	}

	@Test
	void aRelayed503ReachesTheCallerAs500() {
		org.junit.jupiter.api.Assertions.assertEquals(500, InviteCallflow.upstreamStatus(503));
		org.junit.jupiter.api.Assertions.assertEquals(404, InviteCallflow.upstreamStatus(404));
		org.junit.jupiter.api.Assertions.assertEquals(504, InviteCallflow.upstreamStatus(504));
	}
}
