package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

class MonitorReadOnlyFilterTest {

	private static HttpServletRequest request(String method, String... roles) {
		List<String> held = Arrays.asList(roles);
		return (HttpServletRequest) Proxy.newProxyInstance(getClass(HttpServletRequest.class),
				new Class<?>[] { HttpServletRequest.class }, (proxy, m, args) -> {
					switch (m.getName()) {
					case "getMethod":
						return method;
					case "isUserInRole":
						return held.contains(args[0]);
					default:
						return null;
					}
				});
	}

	private static ClassLoader getClass(Class<?> c) {
		return c.getClassLoader() != null ? c.getClassLoader() : MonitorReadOnlyFilterTest.class.getClassLoader();
	}

	/// Runs the filter; returns the status it sent, or 0 when it passed the request on.
	private static int run(HttpServletRequest request) throws Exception {
		AtomicInteger status = new AtomicInteger();
		HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
				MonitorReadOnlyFilterTest.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class },
				(proxy, m, args) -> {
					if ("sendError".equals(m.getName())) {
						status.set((Integer) args[0]);
					}
					return null;
				});
		AtomicBoolean passed = new AtomicBoolean();
		FilterChain chain = (req, resp) -> passed.set(true);
		new MonitorReadOnlyFilter().doFilter(request, response, chain);
		return passed.get() ? 0 : status.get();
	}

	@Test
	void monitorCannotWrite() throws Exception {
		assertEquals(403, run(request("POST", "Monitor")));
		assertEquals(403, run(request("DELETE", "Monitor")));
	}

	@Test
	void monitorCanRead() throws Exception {
		assertEquals(0, run(request("GET", "Monitor")));
	}

	@Test
	void anyWriterRoleLiftsIt() throws Exception {
		assertEquals(0, run(request("POST", "Monitor", "Operator")));
		assertEquals(0, run(request("POST", "Admin")));
		assertEquals(0, run(request("PUT", "Deployer")));
	}

	@Test
	void usersWithoutAdminRolesAreLeftToTheApp() throws Exception {
		assertEquals(0, run(request("POST")));
		assertFalse(MonitorReadOnlyFilter.readOnly(request("POST", "authenticated-users")));
		assertTrue(MonitorReadOnlyFilter.changesState("PATCH"));
	}
}
