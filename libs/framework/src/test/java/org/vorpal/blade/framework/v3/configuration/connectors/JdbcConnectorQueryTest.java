package org.vorpal.blade.framework.v3.configuration.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.connectors.JdbcConnector.PreparedQuery;

class JdbcConnectorQueryTest {

	@Test
	void quotedPlaceholderBecomesParameter() {
		PreparedQuery q = PreparedQuery.compile(
				"SELECT uri FROM routes WHERE called = '${user}' AND active = 1");
		assertEquals("SELECT uri FROM routes WHERE called = ? AND active = 1", q.sql);
		assertEquals(Arrays.asList("user"), q.placeholders);
	}

	@Test
	void barePlaceholdersBindInOrder() {
		PreparedQuery q = PreparedQuery.compile(
				"SELECT 'block' AS listed FROM spam WHERE tn = ${ani} AND expires_at > ${now:yyyy-MM-dd}");
		assertEquals("SELECT 'block' AS listed FROM spam WHERE tn = ? AND expires_at > ?", q.sql);
		assertEquals(Arrays.asList("ani", "now:yyyy-MM-dd"), q.placeholders);
	}

	@Test
	void injectionStaysAValue() {
		PreparedQuery q = PreparedQuery.compile("SELECT uri FROM routes WHERE called = '${user}'");
		MemoryContext ctx = new MemoryContext();
		ctx.put("user", "x' OR '1'='1");
		assertEquals("SELECT uri FROM routes WHERE called = ?", q.sql);
		assertEquals(Arrays.asList("x' OR '1'='1"), q.values(ctx));
	}

	@Test
	void unresolvedPlaceholderBindsNull() {
		PreparedQuery q = PreparedQuery.compile("SELECT 1 FROM t WHERE a = ${missing_value_xyz}");
		assertEquals(Arrays.asList((String) null), q.values(new MemoryContext()));
	}

	@Test
	void literalsCommentsAndIdentifiersAreUntouched() {
		String sql = "SELECT \"it's\" FROM t -- don't\n/* won't */ WHERE a = 'O''Brien' AND b = ${b}";
		PreparedQuery q = PreparedQuery.compile(sql);
		assertEquals("SELECT \"it's\" FROM t -- don't\n/* won't */ WHERE a = 'O''Brien' AND b = ?", q.sql);
		assertEquals(Arrays.asList("b"), q.placeholders);
	}

	@Test
	void placeholderInACommentIsNotBound() {
		PreparedQuery q = PreparedQuery.compile("-- ${ani} is bound as a parameter\n"
				+ "SELECT 'block' AS listed FROM spam_numbers WHERE tn = ${ani} AND expires_at > CURRENT_TIMESTAMP");
		assertEquals(Arrays.asList("ani"), q.placeholders);
	}

	@Test
	void placeholderInsideALongerLiteralFails() {
		assertThrows(IllegalArgumentException.class,
				() -> PreparedQuery.compile("SELECT 1 FROM t WHERE name LIKE '%${user}%'"));
		assertThrows(IllegalArgumentException.class,
				() -> PreparedQuery.compile("SELECT 1 FROM t WHERE a = 'unterminated"));
	}
}
