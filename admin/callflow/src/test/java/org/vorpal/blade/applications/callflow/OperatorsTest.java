package org.vorpal.blade.applications.callflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.junit.jupiter.api.Test;

/// Trace has no `auth-constraint`, so every endpoint that changes what is
/// recorded, or shows the applications' source, refuses a caller with no
/// WebLogic admin role itself: an identity-domain user in a tenant's group,
/// for one.
class OperatorsTest {

	private static SecurityContext in(String... roles) {
		List<String> held = Arrays.asList(roles);
		return new SecurityContext() {
			@Override
			public Principal getUserPrincipal() {
				return () -> "viewer";
			}

			@Override
			public boolean isUserInRole(String role) {
				return held.contains(role);
			}

			@Override
			public boolean isSecure() {
				return true;
			}

			@Override
			public String getAuthenticationScheme() {
				return "OIDC";
			}
		};
	}

	private static <T> T with(T api, SecurityContext security) throws Exception {
		Field f = api.getClass().getDeclaredField("security");
		f.setAccessible(true);
		f.set(api, security);
		return api;
	}

	@Test
	void aViewerWithNoAdminRoleChangesNothing() throws Exception {
		TracesAPI api = with(new TracesAPI(), in("uhg-radiology"));
		assertEquals(403, api.arm("x", "From", ".*", 1).getStatus());
		assertEquals(403, api.disarm().getStatus());
		assertEquals(403, api.clear().getStatus());
		assertEquals(403, api.saveCall("ABCD1234", "{}").getStatus());
		assertEquals(403, api.deleteCall("ABCD1234").getStatus());
		assertEquals(403, api.rules().getStatus());
	}

	@Test
	void aViewerWithNoAdminRoleReadsNoSource() throws Exception {
		CallflowsAPI api = with(new CallflowsAPI(), in("uhg-radiology"));
		assertEquals(403, api.apps().getStatus());
		assertEquals(403, api.source("irouter", "x.Y").getStatus());
	}

	@Test
	void nobodySignedInChangesNothing() throws Exception {
		assertEquals(403, with(new TracesAPI(), null).disarm().getStatus());
	}

	@Test
	void anAdminRolePassesTheCheck() throws Exception {
		for (String role : new String[] { "Admin", "Operator", "Deployer", "Monitor" }) {
			// Past the role check, a rule with no pattern is the next refusal.
			Response r = with(new TracesAPI(), in(role)).arm(null, "From", null, 1);
			assertEquals(400, r.getStatus(), role);
		}
	}
}
