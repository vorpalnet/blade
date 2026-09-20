package org.vorpal.blade.framework.v3.configuration.connectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

class LdapConnectorFilterTest {

	private static final String TEMPLATE = "base: ou=${ou},dc=corp,dc=example,dc=com\n"
			+ "scope: SUBTREE\n"
			+ "attributes: destinationURI,displayName\n"
			+ "\n"
			+ "(&(objectClass=user)(telephoneNumber=${user}))";

	@Test
	void filterValuesBecomeArguments() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "*)(objectClass=*");
		ctx.put("ou", "Users");

		LdapConnector.SearchParams p = LdapConnector.parseTemplate(TEMPLATE, ctx);

		assertEquals("(&(objectClass=user)(telephoneNumber={0}))", p.filter);
		assertArrayEquals(new Object[] { "*)(objectClass=*" }, p.filterArgs);
		assertEquals("ou=Users,dc=corp,dc=example,dc=com", p.baseDn);
		assertEquals("SUBTREE", p.scope);
		assertArrayEquals(new String[] { "destinationURI", "displayName" }, p.returnAttributes);
	}

	@Test
	void unresolvedValueIsEmptyAndBracesAreEscaped() {
		LdapConnector.SearchParams p = LdapConnector.parseTemplate(
				"(&(cn={literal})(mail=${missing_value_xyz}))", new MemoryContext());

		assertEquals("(&(cn=\\7bliteral\\7d)(mail={0}))", p.filter);
		assertArrayEquals(new Object[] { "" }, p.filterArgs);
	}

	@Test
	void baseDnValuesAreEscapedAndUrlsRefused() {
		MemoryContext ctx = new MemoryContext();
		ctx.put("ou", "Sales,dc=other");
		assertEquals("ou=Sales\\,dc\\=other,dc=example,dc=com",
				LdapConnector.resolveBaseDn("ou=${ou},dc=example,dc=com", ctx));

		MemoryContext url = new MemoryContext();
		url.put("base", "ldap://attacker.example.net/dc=x");
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> LdapConnector.resolveBaseDn("${base}", url));
	}
}
