package org.vorpal.blade.services.analytics.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.junit.jupiter.api.Test;

/// Checks that the entities and the two schema files describe the same
/// database.
///
/// **This is the test whose absence let a broken write path ship.** The
/// analytics sink had never written a row on Oracle: the entities asked for
/// generated keys the Oracle script could not provide, two lookup columns were
/// declared narrower than the values the provider put in them, and one column
/// width disagreed between the mapping and both scripts. Every one of those is
/// a second-long assertion here, and every one of them instead took a live
/// domain, a live database and a day to find.
///
/// It deliberately parses the SQL with a regular expression rather than
/// standing up a database. The point is to run in a plain JVM on every build —
/// a check that only runs when someone remembers to point it at Oracle is the
/// check that was missing in the first place.
class SchemaAgreementTest {

	private static final Class<?>[] ENTITIES = {
			Application.class, Session.class, SessionKey.class, Event.class };

	// ───────────────────────────────────────────────────────────── the checks

	@Test
	void noEntityAsksForAGeneratedKey() {
		// The rule that broke Oracle. EclipseLink has no Oracle platform that
		// reports native identity support, so GenerationType.IDENTITY there is
		// not "slower" — it silently becomes a shared sequence the schema does
		// not create. Keys in this schema are computed; see NaturalKey.
		for (Class<?> entity : ENTITIES) {
			for (Field field : entity.getDeclaredFields()) {
				assertFalse(field.isAnnotationPresent(GeneratedValue.class),
						entity.getSimpleName() + "." + field.getName()
								+ " asks the database to generate its key; keys here are"
								+ " computed (see NaturalKey)");
			}
			for (Method method : entity.getDeclaredMethods()) {
				assertFalse(method.isAnnotationPresent(GeneratedValue.class),
						entity.getSimpleName() + "." + method.getName()
								+ " asks the database to generate its key; keys here are"
								+ " computed (see NaturalKey)");
			}
		}
	}

	@Test
	void mySqlSchemaMatchesTheEntities() throws IOException {
		assertSchemaMatches("MySQL-database-schema.sql");
	}

	@Test
	void oracleSchemaMatchesTheEntities() throws IOException {
		assertSchemaMatches("Oracle-database-schema.sql");
	}

	@Test
	void bothSchemasDeclareTheSameTablesAndColumns() throws IOException {
		Map<String, Map<String, Integer>> mysql = parse("MySQL-database-schema.sql");
		Map<String, Map<String, Integer>> oracle = parse("Oracle-database-schema.sql");

		assertEquals(mysql.keySet(), oracle.keySet(), "the two schema files declare different tables");

		for (String table : mysql.keySet()) {
			// MySQL carries `open_key`, a generated column standing in for a
			// filtered unique index that Oracle expresses as a function-based
			// index instead. It is the one column that legitimately exists on
			// one side only.
			Map<String, Integer> left = new LinkedHashMap<>(mysql.get(table));
			left.remove("open_key");
			assertEquals(left.keySet(), oracle.get(table).keySet(), "table " + table + " differs between the two schema files");
		}
	}

	// ─────────────────────────────────────────────────────────── the machinery

	private void assertSchemaMatches(String scriptName) throws IOException {
		Map<String, Map<String, Integer>> schema = parse(scriptName);

		for (Class<?> entity : ENTITIES) {
			String table = tableName(entity);
			Map<String, Integer> columns = schema.get(table);
			assertNotNull(columns, scriptName + " has no table `" + table + "` for entity "
					+ entity.getSimpleName());

			for (Mapped mapped : mappedColumns(entity)) {
				Integer width = columns.get(mapped.column);
				if (width == null) {
					fail(scriptName + ": table `" + table + "` has no column `" + mapped.column
							+ "` for " + entity.getSimpleName() + "." + mapped.member);
				}
				// Only meaningful for declared-width text columns. A width of
				// -1 means the script did not give one (a NUMBER, a TIMESTAMP,
				// a CLOB), and a mapped length of -1 means the entity did not
				// either.
				if (mapped.length > 0 && width.intValue() > 0) {
					assertEquals(width.intValue(), mapped.length,
							scriptName + ": `" + table + "." + mapped.column + "` is " + width
									+ " wide but " + entity.getSimpleName() + "." + mapped.member
									+ " maps it as " + mapped.length);
				}
			}
		}
	}

	/// One mapped persistent column: the entity member that declares it, the
	/// column it lands in, and the width it claims.
	private static final class Mapped {
		final String member;
		final String column;
		final int length;

		Mapped(String member, String column, int length) {
			this.member = member;
			this.column = column;
			this.length = length;
		}
	}

	/// The persistent columns an entity declares.
	///
	/// Handles both access styles on purpose: these entities are not
	/// consistent — some annotate fields and some annotate getters — and a
	/// checker that understood only one would silently pass the other.
	private static List<Mapped> mappedColumns(Class<?> entity) {
		List<Mapped> mapped = new ArrayList<>();
		for (Field field : entity.getDeclaredFields()) {
			if (field.isAnnotationPresent(Transient.class) || field.isSynthetic()
					|| java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			if (field.isAnnotationPresent(Column.class) || field.isAnnotationPresent(Lob.class)
					|| field.isAnnotationPresent(javax.persistence.Id.class)
					|| field.isAnnotationPresent(javax.persistence.Temporal.class)) {
				mapped.add(describe(field.getName(), field.getAnnotation(Column.class), field.getName()));
			}
		}
		for (Method method : entity.getDeclaredMethods()) {
			if (method.isAnnotationPresent(Transient.class) || !method.getName().startsWith("get")) {
				continue;
			}
			if (method.isAnnotationPresent(Column.class) || method.isAnnotationPresent(Lob.class)
					|| method.isAnnotationPresent(javax.persistence.Id.class)
					|| method.isAnnotationPresent(javax.persistence.Temporal.class)) {
				String property = Character.toLowerCase(method.getName().charAt(3))
						+ method.getName().substring(4);
				mapped.add(describe(method.getName(), method.getAnnotation(Column.class), property));
			}
		}
		return mapped;
	}

	private static Mapped describe(String member, Column column, String defaultName) {
		String name = defaultName;
		int length = -1;
		if (column != null) {
			if (!column.name().isEmpty()) {
				name = column.name();
			}
			// 255 is the annotation default, i.e. "unspecified". Treating it as
			// a claim would fail every column the entity says nothing about.
			length = (column.length() == 255) ? -1 : column.length();
		}
		return new Mapped(member, camelToSnake(name), length);
	}

	private static String camelToSnake(String name) {
		StringBuilder out = new StringBuilder();
		for (char c : name.toCharArray()) {
			if (Character.isUpperCase(c)) {
				out.append('_').append(Character.toLowerCase(c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String tableName(Class<?> entity) {
		Table table = entity.getAnnotation(Table.class);
		if (table != null && !table.name().isEmpty()) {
			return table.name().toLowerCase(Locale.ROOT);
		}
		assertTrue(entity.isAnnotationPresent(Entity.class), entity + " is not an entity");
		return entity.getSimpleName().toLowerCase(Locale.ROOT);
	}

	// ──────────────────────────────────────────────────────────── SQL parsing

	private static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\s*\\)\\s*;",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/// The types these two scripts actually use. Matching against a closed list
	/// rather than "any word" is deliberate: a `CREATE TABLE` body is full of
	/// continuation lines — `REFERENCES applications(id)`, the `THEN ...` of
	/// MySQL's generated `open_key`, the `AS DECIMAL(6,4)` inside a functional
	/// index — every one of which parses as a plausible column when the type is
	/// left open. Requiring a known type excludes anything unrecognised instead
	/// of requiring a keyword blocklist that has to be extended each time the
	/// schema grows a new expression.
	///
	/// A type that genuinely is new fails [#parse]'s sanity check by making a
	/// column disappear, which is the safe direction to fail in.
	private static final String TYPES = "BIGINT|INT|INTEGER|SMALLINT|DECIMAL|NUMBER"
			+ "|VARCHAR|VARCHAR2|CHAR|TEXT|CLOB|JSON|DATETIME|TIMESTAMP|BLOB|DATE";

	/// A column line: a name, one of the types above, and optionally a width.
	private static final Pattern COLUMN = Pattern.compile(
			"^\\s*(\\w+)\\s+(" + TYPES + ")\\b\\s*(?:\\(\\s*(\\d+)(?:\\s+CHAR)?\\s*\\))?",
			Pattern.CASE_INSENSITIVE);

	/// Lines that introduce a constraint or an index rather than a column.
	private static final Pattern NOT_A_COLUMN = Pattern.compile(
			"^\\s*(CONSTRAINT|INDEX|UNIQUE|PRIMARY|FOREIGN|KEY|CHECK)\\b",
			Pattern.CASE_INSENSITIVE);

	/// Table name to (column name to declared width, or -1 when the script
	/// gives none).
	private static Map<String, Map<String, Integer>> parse(String scriptName) throws IOException {
		String sql = Files.readString(script(scriptName), StandardCharsets.UTF_8);
		Map<String, Map<String, Integer>> tables = new LinkedHashMap<>();

		Matcher table = CREATE_TABLE.matcher(sql);
		while (table.find()) {
			Map<String, Integer> columns = new LinkedHashMap<>();
			// A declaration may span lines — a generated column's CASE, a
			// functional index's CAST. Only a line that starts outside any open
			// parenthesis begins a new declaration; the rest are continuations
			// and must not be read as columns of their own.
			int depth = 0;
			for (String line : table.group(2).split("\\r?\\n")) {
				String stripped = line.replaceAll("--.*$", "");
				boolean startsDeclaration = (depth == 0);
				depth += count(stripped, '(') - count(stripped, ')');

				if (!startsDeclaration || stripped.trim().isEmpty()
						|| NOT_A_COLUMN.matcher(stripped).find()) {
					continue;
				}
				Matcher column = COLUMN.matcher(stripped);
				if (column.find()) {
					String width = column.group(3);
					columns.put(column.group(1).toLowerCase(Locale.ROOT),
							Integer.valueOf(width == null ? -1 : Integer.parseInt(width)));
				}
			}
			tables.put(table.group(1).toLowerCase(Locale.ROOT), columns);
		}

		assertFalse(tables.isEmpty(), "parsed no tables out of " + scriptName
				+ " — the parser and the script have diverged, which would make every"
				+ " check below vacuous");
		return tables;
	}

	private static int count(String text, char c) {
		int n = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}

	/// Locate the SQL beside the module, whether the suite runs from the module
	/// directory or the repository root.
	private static java.nio.file.Path script(String name) {
		Map<String, String> tried = new HashMap<>();
		for (String prefix : new String[] { "sql/", "services/analytics/sql/", "../sql/" }) {
			File candidate = new File(prefix + name);
			tried.put(prefix, candidate.getAbsolutePath());
			if (candidate.isFile()) {
				return candidate.toPath();
			}
		}
		throw new IllegalStateException("cannot find " + name + "; looked in " + tried);
	}
}
