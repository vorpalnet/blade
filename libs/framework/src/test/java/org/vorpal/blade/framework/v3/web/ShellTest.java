package org.vorpal.blade.framework.v3.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.security.JwtIdentity;
import org.vorpal.blade.framework.v3.security.OidcLoginFilter;

/// The account menu's facts, as every application's frame reports them.
class ShellTest {

	@Test
	void signOutOnTheAdminServerEndsEveryApplication() {
		assertEquals("/blade/callflow/oidc/logout?then=/blade/portal/logout",
				Shell.signOut(OidcLoginFilter.AUTH_TYPE, "/blade/callflow", true));
		assertEquals("/blade/portal/logout", Shell.signOut("FORM", "/blade/callflow", true));
	}

	@Test
	void signOutElsewhereEndsThisApplicationAndTheIdentityDomain() {
		assertEquals("/blade/recordings/oidc/logout", Shell.signOut(OidcLoginFilter.AUTH_TYPE, "/blade/recordings", false));
		assertNull(Shell.signOut("FORM", "/blade/recordings", false), "no sign-out page for a WebLogic login there");
	}

	@Test
	void theNameIsTheIdentityDomainsWhenItGivesOne() {
		JwtIdentity rita = new JwtIdentity("reviewer1", Collections.<String>emptySet(),
				Map.of("user_displayname", "Rita Reviewer"), new HashSet<>(Arrays.asList("Reviewer")));
		assertEquals("Rita Reviewer", Shell.displayName(rita, "reviewer1"));
		assertEquals("weblogic", Shell.displayName(() -> "weblogic", "weblogic"));
	}

	@Test
	void signedInWithIsWorded() {
		assertEquals("identity domain", Shell.signedInWith(OidcLoginFilter.AUTH_TYPE));
		assertEquals("WebLogic", Shell.signedInWith("FORM"));
	}
}
