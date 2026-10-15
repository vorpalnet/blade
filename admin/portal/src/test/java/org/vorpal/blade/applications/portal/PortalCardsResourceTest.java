package org.vorpal.blade.applications.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/// The roles an application's `web.xml` admits: what the rail tells a person
/// to ask for.
class PortalCardsResourceTest {

	@Test
	void theRolesAreEveryConstraintsOnceEach() throws Exception {
		String xml = "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\">"
				+ "<security-constraint><auth-constraint><role-name>Operator</role-name>"
				+ "<role-name>Admin</role-name></auth-constraint></security-constraint>"
				+ "<security-constraint><auth-constraint><role-name>Admin</role-name></auth-constraint></security-constraint>"
				+ "<security-role><role-name>Monitor</role-name></security-role></web-app>";
		assertEquals(Arrays.asList("Admin", "Operator"), PortalCardsResource.roles(xml),
				"a role only declared, never required, does not open it");
		assertEquals(Collections.emptyList(), PortalCardsResource.roles("<web-app/>"), "no constraint: no role");
	}
}
