package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/// The report gate decides, from the signed-in identity's groups, whether a
/// report frame is honored. It is the whole authorization story for the one
/// privileged action, so it is tested as a pure function.
class ReportGateTest {

	@Test
	void noGroupsConfiguredMeansAnySignedInUserMayReport() {
		assertTrue(AgentConsoleEndpoint.mayReport(null, Set.of(), Set.of(), true));
		assertTrue(AgentConsoleEndpoint.mayReport("  ", Set.of("anything"), Set.of(), true));
	}

	@Test
	void memberOfAConfiguredGroupMayReport() {
		assertTrue(AgentConsoleEndpoint.mayReport("CallCenterAgent,Supervisor",
				Set.of("CallCenterAgent"), Set.of(), true));
	}

	@Test
	void groupMatchIgnoresCase() {
		assertTrue(AgentConsoleEndpoint.mayReport("CallCenterAgent",
				Set.of("callcenteragent"), Set.of(), true));
	}

	@Test
	void aRoleClaimAlsoCounts() {
		// Some identity providers carry the value as a role, not a group.
		assertTrue(AgentConsoleEndpoint.mayReport("Supervisor", Set.of(), Set.of("Supervisor"), true));
	}

	@Test
	void nonMemberMayWatchButNotReport() {
		assertFalse(AgentConsoleEndpoint.mayReport("CallCenterAgent,Supervisor",
				Set.of("Billing"), Set.of("SomethingElse"), true));
	}

	@Test
	void containerLoginFallbackIsAllowedEvenWhenGroupsAreConfigured() {
		// Not an OpenID identity: a real WebLogic account under the fallback login.
		assertTrue(AgentConsoleEndpoint.mayReport("CallCenterAgent", null, null, false));
	}
}
