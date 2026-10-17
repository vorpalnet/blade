package org.vorpal.blade.framework.v3.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaUpgradeTest {

	private static final Path SQL = Paths.get("src/test/resources/schema-upgrade");
	private static int databases;
	private Connection c;

	@BeforeEach
	void open() throws Exception {
		c = DriverManager.getConnection("jdbc:hsqldb:mem:upgrade" + (++databases), "sa", "");
	}

	@AfterEach
	void close() throws Exception {
		try (Statement s = c.createStatement()) {
			s.execute("SHUTDOWN");
		}
		c.close();
	}

	private List<String> labels(List<SchemaUpgrade.Step> steps) {
		List<String> out = new ArrayList<>();
		steps.forEach(s -> out.add(s.label));
		return out;
	}

	private List<String> history() throws Exception {
		List<String> ids = new ArrayList<>();
		try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM " + SchemaUpgrade.HISTORY + " ORDER BY id")) {
			while (rs.next()) {
				ids.add(rs.getString(1));
			}
		}
		return ids;
	}

	private void apply() throws Exception {
		SchemaUpgrade.apply(c, SchemaUpgrade.plan(c, SQL), new PrintStream(new ByteArrayOutputStream()));
	}

	@Test
	@DisplayName("an empty database gets the schema, every migration recorded, and the views")
	void emptyDatabase() throws Exception {
		assertEquals(Arrays.asList("schema (empty database)", "migration history", "001-department (in the schema)",
				"002-score (in the schema)", "views"), labels(SchemaUpgrade.plan(c, SQL)));
		apply();
		assertEquals(Arrays.asList("001-department", "002-score"), history().subList(0, 2));
		assertTrue(history().get(2).startsWith("views-"), "the views applied, by content");
		try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT note FROM v_events")) {
			assertFalse(rs.next(), "the view exists, over no rows");
		}
		assertEquals(Arrays.asList(), labels(SchemaUpgrade.plan(c, SQL)), "the next run has nothing to do");
	}

	@Test
	@DisplayName("a database built before the history: a change already in place is recorded, not run")
	void adoptsAnOlderDatabase() throws Exception {
		try (Statement s = c.createStatement()) {
			s.execute("CREATE TABLE events (id BIGINT NOT NULL PRIMARY KEY, type VARCHAR(64), department VARCHAR(64), score INT)");
			s.execute("CREATE INDEX idx_event_scope ON events(department)");
		}
		assertEquals(Arrays.asList("migration history", "001-department (already in place)", "002-score", "views"),
				labels(SchemaUpgrade.plan(c, SQL)));
		apply();
		assertEquals(Arrays.asList("001-department", "002-score"), history().subList(0, 2));
	}

	@Test
	@DisplayName("a database that lacks a change gets it, once")
	void appliesWhatIsMissing() throws Exception {
		try (Statement s = c.createStatement()) {
			s.execute("CREATE TABLE events (id BIGINT NOT NULL PRIMARY KEY, type VARCHAR(64), score INT)");
		}
		assertEquals(Arrays.asList("migration history", "001-department", "002-score", "views"), labels(SchemaUpgrade.plan(c, SQL)));
		apply();
		assertTrue(SchemaUpgrade.columnExists(c, "events", "department"));
		assertEquals(Arrays.asList(), labels(SchemaUpgrade.plan(c, SQL)));
	}

	@Test
	@DisplayName("a changed views file is applied again; an unchanged one is not")
	void viewsFollowTheirContent() throws Exception {
		apply();
		assertEquals(SchemaUpgrade.viewsId("x"), SchemaUpgrade.viewsId("x"));
		assertFalse(SchemaUpgrade.viewsId("x").equals(SchemaUpgrade.viewsId("y")));
		try (Statement s = c.createStatement()) {
			s.execute("DELETE FROM " + SchemaUpgrade.HISTORY + " WHERE id LIKE 'views-%'");
		}
		assertEquals(Arrays.asList("views"), labels(SchemaUpgrade.plan(c, SQL)));
	}

	@Test
	@DisplayName("for the DBA: the same steps as a script, history rows included")
	void writesTheScript() throws Exception {
		try (Statement s = c.createStatement()) {
			s.execute("CREATE TABLE events (id BIGINT NOT NULL PRIMARY KEY, type VARCHAR(64), score INT)");
		}
		String sql = SchemaUpgrade.script(Dialect.STANDARD, SchemaUpgrade.plan(c, SQL));
		assertTrue(sql.contains("ALTER TABLE events ADD COLUMN department VARCHAR(64);"));
		assertTrue(sql.contains("INSERT INTO blade_schema_migrations (id, applied_at) VALUES ('001-department', CURRENT_TIMESTAMP);"));
		assertFalse(SchemaUpgrade.tableExists(c, SchemaUpgrade.HISTORY), "writing the script changes nothing");
		String sqlServer = SchemaUpgrade.script(Dialect.SQLSERVER, SchemaUpgrade.plan(c, SQL));
		assertTrue(sqlServer.contains("VALUES ('002-score', SYSUTCDATETIME())\nGO\n"));
	}

	@Test
	@DisplayName("statements: a ; or -- in a comment or a string does not cut one")
	void splitsOnStatementsNotText() {
		List<String> s = SchemaUpgrade.statements(Dialect.MYSQL,
				"-- a comment; with a semicolon\nCREATE TABLE t (\n  a INT, -- RLS; NULL = all\n  b VARCHAR(9) DEFAULT 'x;--y'\n);\nDROP TABLE u;\n");
		assertEquals(2, s.size());
		assertEquals("CREATE TABLE t (\n  a INT, \n  b VARCHAR(9) DEFAULT 'x;--y'\n)", s.get(0));
		assertEquals("DROP TABLE u", s.get(1));
	}

	@Test
	@DisplayName("statements: Oracle's PL/SQL block ends at its / line, END; kept")
	void splitsOraclePlSql() {
		List<String> s = SchemaUpgrade.statements(Dialect.ORACLE,
				"BEGIN\n  FOR t IN (SELECT 1 FROM dual) LOOP\n    NULL;\n  END LOOP;\nEND;\n/\nCREATE TABLE a (x NUMBER);\n");
		assertEquals(2, s.size());
		assertTrue(s.get(0).startsWith("BEGIN") && s.get(0).endsWith("END;"));
		assertEquals("CREATE TABLE a (x NUMBER)", s.get(1));
	}

	@Test
	@DisplayName("statements: SQL Server's GO batches are sent whole")
	void splitsSqlServerBatches() {
		List<String> s = SchemaUpgrade.statements(Dialect.SQLSERVER,
				"-- header only\nGO\nCREATE OR ALTER VIEW v AS SELECT 1 AS one;\nGO\nALTER TABLE a ADD b INT;\nCREATE INDEX i ON a(b);\ngo\n");
		assertEquals(2, s.size());
		assertEquals("CREATE OR ALTER VIEW v AS SELECT 1 AS one;", s.get(0));
		assertTrue(s.get(1).contains("ALTER TABLE a ADD b INT;") && s.get(1).contains("CREATE INDEX"));
	}

	@Test
	@DisplayName("the domain's data source: URL and user from its descriptor")
	void readsTheDomainDataSource(@TempDir Path domain) throws Exception {
		Path config = Files.createDirectories(domain.resolve("config/jdbc"));
		Files.write(domain.resolve("config/config.xml"), ("<domain xmlns=\"http://xmlns.oracle.com/weblogic/domain\">"
				+ "<jdbc-system-resource><name>Other</name><descriptor-file-name>jdbc/Other-jdbc.xml</descriptor-file-name></jdbc-system-resource>"
				+ "<jdbc-system-resource><name>BladeAnalytics</name><descriptor-file-name>jdbc/BladeAnalytics-1-jdbc.xml</descriptor-file-name></jdbc-system-resource>"
				+ "</domain>").getBytes(StandardCharsets.UTF_8));
		Files.write(config.resolve("Other-jdbc.xml"), descriptor("Other", "jdbc/Other", "jdbc:x:other", "nobody").getBytes(StandardCharsets.UTF_8));
		Files.write(config.resolve("BladeAnalytics-1-jdbc.xml"),
				descriptor("BladeAnalytics", "jdbc/BladeAnalytics", "jdbc:hsqldb:mem:x", "blade").getBytes(StandardCharsets.UTF_8));
		DomainDataSource ds = DomainDataSource.find(domain, "jdbc/BladeAnalytics");
		assertEquals("BladeAnalytics", ds.name);
		assertEquals("jdbc:hsqldb:mem:x", ds.url);
		assertEquals("blade", ds.user);
		assertEquals(null, DomainDataSource.find(domain, "jdbc/Nothing"));
	}

	private static String descriptor(String name, String jndi, String url, String user) {
		return "<jdbc-data-source xmlns=\"http://xmlns.oracle.com/weblogic/jdbc-data-source\"><name>" + name + "</name>"
				+ "<jdbc-driver-params><url>" + url + "</url><driver-name>d</driver-name><properties><property><name>user</name>"
				+ "<value>" + user + "</value></property></properties><password-encrypted>plain</password-encrypted></jdbc-driver-params>"
				+ "<jdbc-data-source-params><jndi-name>" + jndi + "</jndi-name></jdbc-data-source-params></jdbc-data-source>";
	}
}
