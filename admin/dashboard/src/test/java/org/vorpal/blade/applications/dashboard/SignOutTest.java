package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.security.OidcLoginFilter;

/// "Sign out" ends at the portal's sign-out of every application, by way of
/// the identity domain for a sign-in it made.
class SignOutTest {

	private static HttpServletRequest signedInWith(String authType) {
		return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
				new Class<?>[] { HttpServletRequest.class }, (p, m, a) -> {
					switch (m.getName()) {
					case "getAuthType":
						return authType;
					case "getContextPath":
						return "/blade/dashboard";
					default:
						return m.getReturnType() == boolean.class ? false : null;
					}
				});
	}

	@Test
	void anIdentityDomainSignInEndsAtTheIdentityDomainThenThePortal() {
		assertEquals("/blade/dashboard/oidc/logout?then=/blade/portal/logout",
				SignOut.target(signedInWith(OidcLoginFilter.AUTH_TYPE)));
	}

	@Test
	void aWebLogicSignInGoesStraightToThePortal() {
		assertEquals("/blade/portal/logout", SignOut.target(signedInWith("FORM")));
	}
}
