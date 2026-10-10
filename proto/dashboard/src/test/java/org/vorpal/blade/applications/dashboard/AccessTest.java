package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;

/// Who a request is, by the groups it is in, decides what it sees.
class AccessTest {

	private static HttpServletRequest inGroups(String... groups) {
		List<String> held = Arrays.asList(groups);
		return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, (p, m, a) -> {
					if (m.getName().equals("isUserInRole")) {
						return held.contains(a[0]);
					}
					return null;
				});
	}

	private static DashboardGrant grant(String group, List<String> platform, List<String> tenants) {
		DashboardGrant g = new DashboardGrant();
		g.setGroup(group);
		g.setPlatform(platform);
		g.setTenants(tenants);
		return g;
	}

	@Test
	void withoutGrantsOnlyTheAdminRolesSeeAnything() {
		DashboardSettings none = new DashboardSettings();
		assertNull(Access.forRequest(inGroups("Monitor"), none), "an admin role: no limit");
		Access stranger = Access.forRequest(inGroups("Reviewer"), none);
		assertTrue(stranger.rules.isEmpty(), "signed in, but no calls");
		assertFalse(stranger.seesAnyPlatform());
		assertFalse(stranger.seesContent());
	}

	@Test
	void aViewerHoldsTheGrantsOfTheirGroups() {
		DashboardSettings s = new DashboardSettings();
		s.setGrants(Arrays.asList(grant("operators", Arrays.asList("*"), Arrays.asList()),
				grant("uhg-sales", Arrays.asList(), Arrays.asList("uhg"))));

		Access operator = Access.forRequest(inGroups("operators"), s);
		assertTrue(operator.rules.isEmpty(), "the operator sees no calls");
		assertTrue(operator.seesPlatform("ashburn"));

		Access sales = Access.forRequest(inGroups("uhg-sales", "Admin"), s);
		assertEquals(1, sales.rules.size(), "grants exist, so Admin alone grants nothing extra");
		assertFalse(sales.seesPlatform("ashburn"));
	}

	@Test
	void starMeansAllAndAbsentMeansNone() {
		assertNull(Access.set(Arrays.asList("x", "*")));
		assertTrue(Access.set(null).isEmpty());
		assertEquals(2, Access.set(Arrays.asList("a", "b", "")).size());
	}
}
