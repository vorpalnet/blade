package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.access.Access;
import org.vorpal.blade.framework.v3.access.Grant;

/// An identity-domain viewer, as [OidcLoginFilter] hands them to an admin
/// application: their grants come from the token's groups through the
/// wrapped request's roles, and their content from the same groups through
/// [SubjectAttributes#of(HttpServletRequest)].
class OidcViewerAccessTest {

	/// A request signed in by the identity domain, in `groups` and no role.
	private static HttpServletRequest signedIn(String name, String... groups) {
		HttpServletRequest container = (HttpServletRequest) Proxy.newProxyInstance(
				HttpServletRequest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class },
				(p, m, a) -> m.getReturnType() == boolean.class ? false : null);
		JwtIdentity identity = new JwtIdentity(name, Collections.<String>emptySet(), null,
				new HashSet<>(Arrays.asList(groups)));
		return new OidcLoginFilter.OidcRequest(container, identity, Collections.<String, String>emptyMap());
	}

	private static List<Grant> grants() {
		Grant radiology = new Grant();
		radiology.setGroup("radiology-supervisors");
		radiology.setTenants(Arrays.asList("tenant_001"));
		radiology.setDepartments(Arrays.asList("radiology"));
		return Arrays.asList(radiology);
	}

	private static AccessPolicy policy() {
		AccessPolicy p = Access.defaultPolicy();
		p.getRules().add(new AccessRule("radiology reads its calls", Arrays.asList("radiology-supervisors"), null,
				Arrays.asList("phi:transcript")));
		return p;
	}

	@Test
	void aGroupMemberSeesTheirDepartmentAndReadsIt() {
		Access a = Access.forRequest(signedIn("pat", "radiology-supervisors"), grants(), policy());
		assertTrue(a.seesCall("ashburn", "tenant_001", "radiology"));
		assertFalse(a.seesCall("ashburn", "tenant_001", "sales"));
		assertFalse(a.seesAnyPlatform(), "no servers for a tenant's people");
		assertTrue(a.seesContent());
		assertEquals(Arrays.asList("Calls: tenant tenant_001 · department radiology", "Servers: none",
				"Transcripts and callers: yes"), Access.describe(a));
	}

	@Test
	void aSignInInNoGrantedGroupSeesNothing() {
		Access a = Access.forRequest(signedIn("lee", "some-other-group"), grants(), policy());
		assertTrue(a.rules().isEmpty());
		assertFalse(a.seesContent());
		assertEquals("Calls: none", Access.describe(a).get(0));
	}

	@Test
	void anIdentityDomainAdministratorDoesNotReadByTheDefaultRule() {
		Access a = Access.forRequest(signedIn("jeff", "Administrators"), grants(), policy());
		assertEquals("Calls: none", Access.describe(a).get(0));
		assertFalse(a.seesContent(), "the cloud account's Administrators are not WebLogic's");
	}

	@Test
	void withNoGrantsAnIdentityDomainSignInSeesNoCalls() {
		Access a = Access.forRequest(signedIn("lee", "radiology-supervisors"), null, policy());
		assertEquals("Calls: none", Access.describe(a).get(0), "only the WebLogic admin roles see all without grants");
		assertTrue(a.seesContent(), "the policy still names the group: content of nothing");
	}
}
