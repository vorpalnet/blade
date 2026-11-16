package org.vorpal.blade.applications.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.access.Access;
import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.AccessRule;
import org.vorpal.blade.framework.v3.security.JwtIdentity;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// A log line can hold a caller's number, so reading one is `phi:transcript`
/// under the logs app's own `access` policy.
class LogsAccessTest {

	private static SubjectAttributes weblogic(String name, String... groups) {
		return SubjectAttributes.of(name, new HashSet<>(Arrays.asList(groups)), Collections.emptyMap(),
				SubjectAttributes.WEBLOGIC);
	}

	private static SubjectAttributes identityDomain(String name, String... groups) {
		return SubjectAttributes.of(name, new HashSet<>(Arrays.asList(groups)), Collections.emptyMap(),
				SubjectAttributes.OIDC);
	}

	/// The default policy with `Monitors` taken out of its one rule.
	private static LogsSettings withoutMonitors() {
		AccessPolicy policy = Access.defaultPolicy();
		AccessRule rule = policy.getRules().get(0);
		rule.setGroups(new LinkedList<>(Arrays.asList("Administrators", "Operators", "Deployers")));
		return new LogsSettings().setAccess(policy);
	}

	@Test
	void byDefaultEveryWebLogicAdminGroupReads() {
		for (String group : new String[] { "Administrators", "Operators", "Deployers", "Monitors" }) {
			assertTrue(LogQueryAPI.mayRead(weblogic("pat", group), new LogsSettings()).isAllowed(), group);
		}
	}

	@Test
	void beforeTheSettingsLoadTheDefaultPolicyAnswers() {
		assertTrue(LogQueryAPI.mayRead(weblogic("pat", "Monitors"), null).isAllowed());
		assertFalse(LogQueryAPI.mayRead(identityDomain("lee", "Administrators"), null).isAllowed());
	}

	@Test
	void anIdentityDomainAdministratorDoesNotRead() {
		assertFalse(LogQueryAPI.mayRead(identityDomain("lee", "Administrators"), new LogsSettings()).isAllowed(),
				"the cloud account's Administrators are not WebLogic's");
	}

	@Test
	void aNarrowedPolicyKeepsMonitorsOut() {
		assertFalse(LogQueryAPI.mayRead(weblogic("pat", "Monitors"), withoutMonitors()).isAllowed());
		assertTrue(LogQueryAPI.mayRead(weblogic("ali", "Administrators"), withoutMonitors()).isAllowed());
	}

	@Test
	void nobodySignedInReadsNothing() {
		assertFalse(LogQueryAPI.mayRead(null, new LogsSettings()).isAllowed());
	}

	@Test
	void everyEndpointThatReturnsLinesRefusesBeforeReading() throws Exception {
		JwtIdentity lee = new JwtIdentity("lee", Collections.<String>emptySet(), null,
				new HashSet<>(Arrays.asList("Administrators")));
		HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
				HttpServletRequest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class },
				(p, m, a) -> {
					switch (m.getName()) {
					case "getUserPrincipal":
						return lee;
					case "getRemoteAddr":
						return "192.0.2.1";
					default:
						return m.getReturnType() == boolean.class ? false : null;
					}
				});
		LogQueryAPI api = new LogQueryAPI();
		Field f = LogQueryAPI.class.getDeclaredField("request");
		f.setAccessible(true);
		f.set(api, request);

		// No log reader exists here: a 403 proves the check came first.
		assertEquals(403, api.slice("engine0", "engine0.log", -1, 0).getStatus());
		assertEquals(403, api.tail("engine0", "engine0.log", 0, 65536).getStatus());
		assertEquals(403, api.download("engine0", "engine0.log").getStatus());
		assertEquals(403, api.search("engine0", "engine0.log", "x", false, true, 0, 500, 1024).getStatus());
	}
}
