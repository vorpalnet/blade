package org.vorpal.blade.framework.v3.jdbc;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Brings an analytics database up to this build: the schema when there is
/// none, the migrations it has not had, and the reporting views, every time.
///
/// ## The files, in `services/analytics/sql/`
///
/// - `<Dialect>-database-schema.sql`: the whole current schema, for an empty
///   database. A database built from it has every migration already, so all
///   of them are recorded as applied.
/// - `migrations/<NNN>-<name>.<Dialect>.sql`: one change to the tables, once
///   per database, in number order. Every number exists for every database;
///   a change one database does not need is a file with only comments.
/// - `<Dialect>-analytics-views.sql`: applied whenever it differs from the
///   last one applied. It only replaces views, so it cannot touch data.
///
/// `<Dialect>` is `Oracle`, `MySQL` or `MSSQL` ([#prefix]).
///
/// ## What has been applied
///
/// The table [#HISTORY] holds one row per migration applied, and one per views
/// script applied, named for its content (`views-<sha256 prefix>`), so an
/// unchanged views file is not applied again. A database built
/// before the table existed has none, so a migration may say how to tell it is
/// already in place, in its header:
///
///     -- applied-if: column events.department
///     -- applied-if: index events.idx_event_scope
///
/// When every such line holds, the migration is recorded without running. A
/// migration without them must be safe to run on a database that already has
/// it, such as setting a default.
///
/// ## Applied here, or by a DBA
///
/// [#apply] runs the steps over the connection. [#script] writes the same
/// steps as a script in the database's own syntax (`GO` batches on SQL Server,
/// `/` after a PL/SQL block on Oracle) for a DBA to run under their own change
/// control; the history rows are in it, so the next run knows.
///
/// The connection comes from the domain's own `jdbc/BladeAnalytics` data source
/// ([DomainDataSource]) or from a URL, and the database's dialect from the
/// connection.
public final class SchemaUpgrade {

	/// The table that records each migration applied.
	public static final String HISTORY = "blade_schema_migrations";

	private static final Pattern APPLIED_IF = Pattern.compile("^\\s*--\\s*applied-if:\\s*(column|index)\\s+(\\w+)\\.(\\w+)\\s*$",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	private static final Pattern MIGRATION = Pattern.compile("^(\\d+-[\\w-]+)\\.(\\w+)\\.sql$");

	private SchemaUpgrade() {
	}

	/// One thing to do: a label, its statements (none for a migration found
	/// already in place), and the migration to record afterwards, if any.
	public static final class Step {
		public final String label;
		public final List<String> statements;
		public final String record;

		Step(String label, List<String> statements, String record) {
			this.label = label;
			this.statements = statements;
			this.record = record;
		}
	}

	/// The file-name part that names a database's scripts.
	public static String prefix(Dialect d) {
		switch (d) {
		case ORACLE:
			return "Oracle";
		case MYSQL:
			return "MySQL";
		case SQLSERVER:
			return "MSSQL";
		default:
			return "Standard";
		}
	}

	// ── what to do ──────────────────────────────────────────────────────────

	/// What this database needs, in order.
	public static List<Step> plan(Connection c, Path sqlDir) throws SQLException, IOException {
		Dialect d = Dialect.of(c);
		String p = prefix(d);
		List<Step> steps = new ArrayList<>();
		List<String[]> migrations = migrations(sqlDir, p);
		if (!tableExists(c, "events")) {
			steps.add(new Step("schema (empty database)", statements(d, read(sqlDir.resolve(p + "-database-schema.sql"))), null));
			steps.add(new Step("migration history", Collections.singletonList(historyTable(d)), null));
			for (String[] m : migrations) {
				steps.add(new Step(m[0] + " (in the schema)", Collections.emptyList(), m[0]));
			}
		} else {
			Set<String> applied = new HashSet<>();
			if (tableExists(c, HISTORY)) {
				try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM " + HISTORY)) {
					while (rs.next()) {
						applied.add(rs.getString(1));
					}
				}
			} else {
				steps.add(new Step("migration history", Collections.singletonList(historyTable(d)), null));
			}
			for (String[] m : migrations) {
				if (applied.contains(m[0])) {
					continue;
				}
				String sql = read(Paths.get(m[1]));
				if (alreadyInPlace(c, d, sql)) {
					steps.add(new Step(m[0] + " (already in place)", Collections.emptyList(), m[0]));
				} else {
					steps.add(new Step(m[0], statements(d, sql), m[0]));
				}
			}
		}
		String views = read(sqlDir.resolve(p + "-analytics-views.sql"));
		String viewsId = viewsId(views);
		if (!applied(c, viewsId)) {
			steps.add(new Step("views", statements(d, views), viewsId));
		}
		return steps;
	}

	/// The history id of a views script: its content, so a changed file differs.
	static String viewsId(String script) {
		try {
			byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder("views-");
			for (int i = 0; i < 6; i++) {
				hex.append(String.format("%02x", h[i]));
			}
			return hex.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/// Whether the history records this id; false when there is no history yet.
	private static boolean applied(Connection c, String id) throws SQLException {
		if (!tableExists(c, HISTORY)) {
			return false;
		}
		try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM " + HISTORY + " WHERE id = ?")) {
			p.setString(1, id);
			try (ResultSet rs = p.executeQuery()) {
				return rs.next() && rs.getInt(1) > 0;
			}
		}
	}

	/// Every migration for this database, in number order, as `{id, path}`.
	static List<String[]> migrations(Path sqlDir, String prefix) throws IOException {
		Path dir = sqlDir.resolve("migrations");
		if (!Files.isDirectory(dir)) {
			return Collections.emptyList();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(f -> {
				Matcher m = MIGRATION.matcher(f.getFileName().toString());
				return m.matches() && m.group(2).equals(prefix) ? new String[] { m.group(1), f.toString() } : null;
			}).filter(x -> x != null).sorted((a, b) -> a[0].compareTo(b[0])).collect(Collectors.toList());
		}
	}

	/// Whether every `applied-if` in this migration holds; false when it has none.
	static boolean alreadyInPlace(Connection c, Dialect d, String sql) throws SQLException {
		Matcher m = APPLIED_IF.matcher(sql);
		boolean any = false;
		while (m.find()) {
			any = true;
			boolean holds = "column".equalsIgnoreCase(m.group(1)) ? columnExists(c, m.group(2), m.group(3))
					: indexExists(c, d, m.group(2), m.group(3));
			if (!holds) {
				return false;
			}
		}
		return any;
	}

	private static String historyTable(Dialect d) {
		return "CREATE TABLE " + HISTORY + " (id " + d.text(100) + " NOT NULL PRIMARY KEY, applied_at " + d.timestamp() + " NOT NULL)";
	}

	// ── doing it ────────────────────────────────────────────────────────────

	/// Run the steps over this connection, each statement on its own, and
	/// record each migration as it completes.
	public static void apply(Connection c, List<Step> steps, PrintStream log) throws SQLException {
		c.setAutoCommit(true);
		for (Step step : steps) {
			for (String sql : step.statements) {
				try (Statement s = c.createStatement()) {
					s.execute(sql);
				} catch (SQLException e) {
					throw new SQLException(step.label + ": " + e.getMessage() + "\n  in: " + firstLine(sql), e.getSQLState(),
							e.getErrorCode(), e);
				}
			}
			if (step.record != null) {
				try (PreparedStatement p = c.prepareStatement("INSERT INTO " + HISTORY + " (id, applied_at) VALUES (?, ?)")) {
					p.setString(1, step.record);
					p.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
					p.executeUpdate();
				}
			}
			log.println("  " + step.label + (step.statements.isEmpty() ? "" : ": " + step.statements.size() + " statements"));
		}
	}

	/// The steps as one script in this database's syntax, for a DBA to run.
	public static String script(Dialect d, List<Step> steps) {
		StringBuilder out = new StringBuilder("-- BLADE analytics upgrade, generated by SchemaUpgrade. Run it once, in order.\n");
		for (Step step : steps) {
			out.append("\n-- ").append(step.label).append('\n');
			for (String sql : step.statements) {
				out.append(terminated(d, sql));
			}
			if (step.record != null) {
				out.append(terminated(d, "INSERT INTO " + HISTORY + " (id, applied_at) VALUES ('" + step.record + "', " + utcNow(d) + ")"));
			}
		}
		return out.toString();
	}

	private static String terminated(Dialect d, String sql) {
		switch (d) {
		case SQLSERVER:
			return sql + "\nGO\n";
		case ORACLE:
			return isBlock(sql) ? sql + "\n/\n" : sql + ";\n";
		default:
			return sql + ";\n";
		}
	}

	private static String utcNow(Dialect d) {
		switch (d) {
		case ORACLE:
			return "SYS_EXTRACT_UTC(SYSTIMESTAMP)";
		case MYSQL:
			return "UTC_TIMESTAMP(3)";
		case SQLSERVER:
			return "SYSUTCDATETIME()";
		default:
			return "CURRENT_TIMESTAMP";
		}
	}

	// ── reading a script ────────────────────────────────────────────────────

	/// A script cut into the statements a JDBC driver takes one at a time.
	///
	/// SQL Server: each `GO` batch whole, as `sqlcmd` sends it. Elsewhere: each
	/// statement ending in `;`, without it, and on Oracle a PL/SQL block
	/// (`BEGIN`/`DECLARE`) up to the `/` line that ends it, `END;` kept.
	/// Comments are dropped outside SQL Server, quotes respected, so a `;` or
	/// `--` inside a comment or a string does not cut a statement.
	public static List<String> statements(Dialect d, String script) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean code = false, block = false;
		for (String line : script.split("\r?\n")) {
			String stripped = stripComment(line);
			String t = stripped.trim();
			if (d == Dialect.SQLSERVER) {
				if (t.equalsIgnoreCase("GO")) {
					flush(out, cur, code, false);
					cur.setLength(0);
					code = false;
				} else {
					cur.append(line).append('\n');
					code |= !t.isEmpty();
				}
				continue;
			}
			if (t.equals("/")) {
				flush(out, cur, code, false);
				cur.setLength(0);
				code = block = false;
				continue;
			}
			if (t.isEmpty()) {
				continue;
			}
			if (!code && d == Dialect.ORACLE && isBlock(t)) {
				block = true;
			}
			cur.append(stripped).append('\n');
			code = true;
			if (!block && t.endsWith(";")) {
				flush(out, cur, true, true);
				cur.setLength(0);
				code = false;
			}
		}
		flush(out, cur, code, !block);
		return out;
	}

	private static void flush(List<String> out, StringBuilder cur, boolean code, boolean dropSemicolon) {
		if (!code) {
			return;
		}
		String s = cur.toString().trim();
		if (dropSemicolon && s.endsWith(";")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		if (!s.isEmpty()) {
			out.add(s);
		}
	}

	private static boolean isBlock(String sql) {
		String head = sql.trim().toUpperCase(Locale.ROOT);
		return head.startsWith("BEGIN") || head.startsWith("DECLARE");
	}

	/// The line without its `--` comment, a `--` inside quotes left alone.
	static String stripComment(String line) {
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (quote != 0) {
				if (ch == quote) {
					quote = 0;
				}
			} else if (ch == '\'' || ch == '"') {
				quote = ch;
			} else if (ch == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
				return line.substring(0, i);
			}
		}
		return line;
	}

	// ── what the database has ───────────────────────────────────────────────

	static boolean tableExists(Connection c, String table) throws SQLException {
		DatabaseMetaData md = c.getMetaData();
		for (String name : spellings(table)) {
			try (ResultSet rs = md.getTables(c.getCatalog(), schema(c), name, new String[] { "TABLE" })) {
				if (rs.next()) {
					return true;
				}
			}
		}
		return false;
	}

	static boolean columnExists(Connection c, String table, String column) throws SQLException {
		DatabaseMetaData md = c.getMetaData();
		for (String t : spellings(table)) {
			for (String col : spellings(column)) {
				try (ResultSet rs = md.getColumns(c.getCatalog(), schema(c), t, col)) {
					if (rs.next()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	static boolean indexExists(Connection c, Dialect d, String table, String index) throws SQLException {
		for (String t : spellings(table)) {
			for (String i : spellings(index)) {
				if (d.indexExists(c, t, i)) {
					return true;
				}
			}
		}
		return false;
	}

	/// A name as written, upper-cased (how Oracle stores it) and lower-cased.
	private static Set<String> spellings(String name) {
		Set<String> s = new java.util.LinkedHashSet<>();
		s.add(name);
		s.add(name.toUpperCase(Locale.ROOT));
		s.add(name.toLowerCase(Locale.ROOT));
		return s;
	}

	/// The connection's own schema, so another user's table of the same name
	/// does not count; null where the driver cannot say.
	private static String schema(Connection c) {
		try {
			return c.getSchema();
		} catch (Throwable notSupported) {
			return null;
		}
	}

	private static String read(Path p) throws IOException {
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	private static String firstLine(String sql) {
		int nl = sql.indexOf('\n');
		return nl < 0 ? sql : sql.substring(0, nl) + " …";
	}

	// ── the command ─────────────────────────────────────────────────────────

	/// `SchemaUpgrade --sql-dir <dir> (--domain <dir> | --url <url> --user <user>) [--sql <file>] [--status]`
	///
	/// With `--url`, the password is read from `BLADE_DB_PASSWORD`, never an
	/// argument. With `--domain`, everything comes from the domain's own
	/// data source. Exit 0 when done, 2 when the domain has no analytics data
	/// source (nothing to upgrade), 1 on any failure.
	public static void main(String[] args) {
		String sqlDir = null, domain = null, url = null, user = null, out = null;
		boolean status = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--sql-dir":
				sqlDir = args[++i];
				break;
			case "--domain":
				domain = args[++i];
				break;
			case "--url":
				url = args[++i];
				break;
			case "--user":
				user = args[++i];
				break;
			case "--sql":
				out = args[++i];
				break;
			case "--status":
				status = true;
				break;
			default:
				System.err.println("unknown argument: " + args[i]);
				System.exit(1);
			}
		}
		try {
			if (sqlDir == null) {
				throw new IllegalArgumentException("--sql-dir is required");
			}
			String password = System.getenv("BLADE_DB_PASSWORD");
			if (domain != null) {
				DomainDataSource ds = DomainDataSource.find(Paths.get(domain), "jdbc/BladeAnalytics");
				if (ds == null) {
					System.out.println("No jdbc/BladeAnalytics data source in " + domain + ": analytics is not configured.");
					System.exit(2);
				}
				url = ds.url;
				user = ds.user;
				password = ds.password(Paths.get(domain));
			}
			DomainDataSource.loadDrivers();
			try (Connection c = DriverManager.getConnection(url, user, password)) {
				Dialect d = Dialect.of(c);
				List<Step> steps = plan(c, Paths.get(sqlDir));
				System.out.println("Analytics database: " + d + " at " + url);
				if (status) {
					for (Step s : steps) {
						System.out.println("  to do: " + s.label);
					}
				} else if (out != null) {
					Files.write(Paths.get(out), script(d, steps).getBytes(StandardCharsets.UTF_8));
					System.out.println("Wrote " + steps.size() + " steps to " + out + " for the DBA; nothing was changed.");
				} else {
					apply(c, steps, System.out);
					System.out.println("Analytics database is current.");
				}
			}
			System.exit(0);
		} catch (Exception e) {
			System.err.println("Schema upgrade failed: " + e.getMessage());
			System.exit(1);
		}
	}
}
