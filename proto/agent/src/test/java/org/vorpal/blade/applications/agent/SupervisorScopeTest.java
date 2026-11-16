package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javax.websocket.Session;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.access.Access;
import org.vorpal.blade.framework.v3.access.Grant;
import org.vorpal.blade.framework.v3.events.CallScope;
import org.vorpal.blade.framework.v3.security.JwtIdentity;

/// A supervisor's console shows the calls of the tenants and departments their
/// grants name; a call popped to an agent by name still reaches that agent.
class SupervisorScopeTest {

	private static List<Grant> radiologyOnly() {
		Grant g = new Grant();
		g.setGroup("radiology-supervisors");
		g.setDomains(Arrays.asList(Access.ALL));
		g.setTenants(Arrays.asList("tenant_001"));
		g.setDepartments(Arrays.asList("radiology"));
		return Arrays.asList(g);
	}

	private static JwtIdentity signedIn(String name, String... groups) {
		return new JwtIdentity(name, Collections.<String>emptySet(), null, new HashSet<>(Arrays.asList(groups)));
	}

	/// A socket whose only behaviour is being a distinct key; sends to it fail
	/// quietly, which the registry already tolerates.
	private static Session socket() {
		return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class },
				(p, m, a) -> {
					switch (m.getName()) {
					case "hashCode":
						return System.identityHashCode(p);
					case "equals":
						return p == a[0];
					case "isOpen":
						return false;
					default:
						return m.getReturnType() == boolean.class ? false : null;
					}
				});
	}

	private static void call(String vorpalId, String tenant, String department) {
		AgentConsoleRegistry.rememberCall(vorpalId,
				new AgentConsoleRegistry.CallRef(Long.parseLong(vorpalId, 16), null, null, new CallScope(tenant, department)));
	}

	@Test
	void withNoGrantsEveryConsoleSeesEveryCall() {
		assertNull(AgentConsoleEndpoint.scopeOf(signedIn("pat", "anyone"), null));
		assertNull(AgentConsoleEndpoint.scopeOf(signedIn("pat", "anyone"), Collections.emptyList()));
	}

	@Test
	void aSupervisorSeesTheirDepartmentOnly() {
		Access a = AgentConsoleEndpoint.scopeOf(signedIn("pat", "radiology-supervisors"), radiologyOnly());
		assertTrue(a.seesCall(null, "tenant_001", "radiology"));
		assertFalse(a.seesCall(null, "tenant_001", "cardiology"));
		assertFalse(a.seesCall(null, "tenant_002", "radiology"));
	}

	@Test
	void anIdentityInNoGrantedGroupSeesNoOtherCall() {
		Access a = AgentConsoleEndpoint.scopeOf(signedIn("lee", "CallCenterAgent"), radiologyOnly());
		assertFalse(a.seesCall(null, "tenant_001", "radiology"));
	}

	@Test
	void aWebLogicAdministratorSeesEveryCall() {
		Principal weblogic = () -> "weblogic";
		Access a = AgentConsoleEndpoint.scopeOf(weblogic, radiologyOnly());
		assertTrue(a.seesCall(null, "tenant_002", "cardiology"));
	}

	@Test
	void broadcastsAndReplaysFollowTheScope() {
		Session supervisor = socket();
		Session unscoped = socket();
		try {
			AgentConsoleRegistry.add(supervisor, "pat",
					AgentConsoleEndpoint.scopeOf(signedIn("pat", "radiology-supervisors"), radiologyOnly()));
			AgentConsoleRegistry.add(unscoped, "ali", null);
			call("AAA00001", "tenant_001", "radiology");
			call("AAA00002", "tenant_001", "cardiology");

			assertTrue(AgentConsoleRegistry.sees(supervisor, "AAA00001"));
			assertFalse(AgentConsoleRegistry.sees(supervisor, "AAA00002"));
			assertFalse(AgentConsoleRegistry.sees(supervisor, "AAA0FFFF"), "a call with no recorded scope");
			assertTrue(AgentConsoleRegistry.sees(unscoped, "AAA00002"));

			assertEquals(2, AgentConsoleRegistry.broadcast("AAA00001", "{}"));
			assertEquals(1, AgentConsoleRegistry.broadcast("AAA00002", "{}"));

			AgentConsoleRegistry.recordPop("AAA00001", null, "{}");
			AgentConsoleRegistry.recordPop("AAA00002", null, "{}");
			AgentConsoleRegistry.recordPop("AAA00003", "pat", "{}"); // popped to pat by name
			assertEquals(2, AgentConsoleRegistry.replayTo(supervisor, "pat"));
		} finally {
			AgentConsoleRegistry.remove(supervisor);
			AgentConsoleRegistry.remove(unscoped);
			AgentConsoleRegistry.cleared("AAA00001", null);
			AgentConsoleRegistry.cleared("AAA00002", null);
			AgentConsoleRegistry.cleared("AAA00003", null);
		}
	}
}
