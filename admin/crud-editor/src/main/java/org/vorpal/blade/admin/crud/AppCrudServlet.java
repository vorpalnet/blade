package org.vorpal.blade.admin.crud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.io.VersionedFileStore;
import org.vorpal.blade.framework.v3.configuration.ClusterMembership;
import org.vorpal.blade.framework.v3.configuration.ConfigFiles;
import org.vorpal.blade.framework.v2.config.ConfigPublisher;
import org.vorpal.blade.framework.v2.config.CredentialEncryption;
import org.vorpal.blade.framework.v3.crud.Crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The editor's endpoint for the `crud` section of any application's own
/// configuration, the rules a v3 application runs on every message it sends
/// ([Crud]). [CrudConfigServlet] still owns the CRUD service's `crud.json`;
/// this servlet owns one section of everyone else's file.
///
/// The editor exchanges the section only. Save validates it as a [Crud],
/// splices it into `<app>.json` and writes the whole file through
/// [VersionedFileStore], so every other section rides through untouched (the
/// Flow editor's round-trip contract). The application's own classes are not
/// on the AdminServer, so the rest of the file is not re-validated; it is
/// written back exactly as read. When the application has no live file yet,
/// the base is its generated sample, and Save creates the file from it.
///
/// An application is listed when its generated schema
/// (`_schemas/<app>.jschema`) has a top-level `crud` property: it extends the
/// v3 `Configuration` (or implements `CrudCapable`) and has started at least
/// once on a node sharing this domain directory. Apps join the list as they
/// move to the v3 base, with no change here.
///
/// Routes, under `/resources/api/apps`:
///
/// - `GET  …/apps`: `{apps: [name, …]}`.
/// Each per-app route takes `?file=`: `domain` (the default), `cluster/<name>`
/// or `server/<name>` ([ConfigFiles]).
///
/// - `GET  …/apps/{app}/files`: the app's files that exist, domain first.
/// - `GET  …/apps/{app}/merged`: read only, the `crud` section of each layer
///   the loader merges, in its order, and what they merge to
///   (`ConfigFiles.mergedView`).
/// - `GET  …/apps/{app}`: `{source, file, path, version, config, schema}`; `config` is the
///   section (empty when absent), `schema` the section's schema, shaped like
///   `crud.jschema` so the page's form resolver needs no second path.
/// - `POST …/apps/{app}/save`: body is the section.
/// - `POST …/apps/{app}/publish`: reload the app's Configuration MBeans.
/// - `GET  …/apps/{app}/versions`, `…/versions/{ts}`,
///   `POST …/versions/{ts}/restore`: whole-file history of `<app>.json`.
public class AppCrudServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final VersionedFileStore store = new VersionedFileStore();
	private static final Path CONFIG_DIR = Paths.get("config/custom/vorpal");
	private static final String SECTION = AppCrudSection.NAME;


	/// The file this request edits: `?file=` names the domain file (the
	/// default) or a cluster or server file ([ConfigFiles#resolve]).
	private static Path configFile(HttpServletRequest req, String app) {
		return ConfigFiles.resolve(app, req.getParameter("file"));
	}

	/// True when the request edits the domain file, the only one with a
	/// sample to fall back on.
	private static boolean domain(HttpServletRequest req) {
		return ConfigFiles.isDomain(req.getParameter("file"));
	}

	private static Path sampleFile(String app) {
		return CONFIG_DIR.resolve("_samples/" + app + ".json.SAMPLE");
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			String[] path = subPath(req);
			if (path.length == 0) {
				handleList(resp);
				return;
			}
			String app = appOf(resp, path[0]);
			if (app == null) return;
			if (path.length == 1) {
				handleLoad(req, resp, app);
			} else if (path.length == 2) {
				switch (path[1]) {
				case "files":
					writeJson(resp, HttpServletResponse.SC_OK, MAPPER.valueToTree(ConfigFiles.of(app, ClusterMembership.read())));
					break;
				case "merged":
					writeJson(resp, HttpServletResponse.SC_OK,
							ConfigFiles.mergedView(app, req.getParameter("file"), SECTION, ClusterMembership.read(), MAPPER));
					break;
				case "versions":
					handleVersionList(req, resp, app);
					break;
				default:
					respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
				}
			} else if (path.length == 3 && path[1].equals("versions")) {
				handleVersionContent(req, resp, app, path[2]);
			} else {
				respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
			}
		} catch (IllegalArgumentException e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage(), null);
		} catch (Throwable t) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), stackTrace(t));
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			String[] path = subPath(req);
			if (path.length == 0) {
				respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
				return;
			}
			String app = appOf(resp, path[0]);
			if (app == null) return;
			if (path.length == 2 && path[1].equals("save")) {
				handleSave(req, resp, app, readBody(req), null);
			} else if (path.length == 2 && path[1].equals("publish")) {
				handlePublish(resp, app);
			} else if (path.length == 4 && path[1].equals("versions") && path[3].equals("restore")) {
				handleVersionRestore(req, resp, app, path[2]);
			} else {
				respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
			}
		} catch (IllegalArgumentException e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage(), null);
		} catch (Throwable t) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), stackTrace(t));
		}
	}

	// ------------------------------------------------------------------
	// Handlers
	// ------------------------------------------------------------------

	private void handleList(HttpServletResponse resp) throws IOException {
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode arr = out.putArray("apps");
		for (String app : AppCrudSection.appsWithCrud(CONFIG_DIR.resolve("_schemas"))) arr.add(app);
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	private void handleLoad(HttpServletRequest req, HttpServletResponse resp, String app) throws IOException {
		String source;
		JsonNode doc;
		String version;
		Path file = configFile(req, app);
		// Content and version under one lock, so a save landing between the
		// two reads cannot tag the old content with the new version.
		synchronized (org.vorpal.blade.framework.v3.configuration.FileVersion.lock(file)) {
			version = org.vorpal.blade.framework.v3.configuration.FileVersion.of(file);
			if (Files.exists(file)) {
				source = "live";
				doc = MAPPER.readTree(file.toFile());
			} else if (!domain(req)) {
				// A cluster or server file holds only what it changes: start empty.
				source = "new";
				doc = MAPPER.createObjectNode();
			} else if (Files.exists(sampleFile(app))) {
				source = "sample";
				doc = MAPPER.readTree(sampleFile(app).toFile());
			} else {
				source = null;
				doc = null;
			}
		}
		if (doc == null) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND,
					"no " + app + ".json and no generated sample: start " + app + " once on a node first", null);
			return;
		}
		ObjectNode out = MAPPER.createObjectNode();
		out.put("source", source);
		out.put("file", domain(req) ? ConfigFiles.DOMAIN : req.getParameter("file"));
		out.put("path", file.toString());
		out.put("version", version);
		JsonNode section = doc.get(SECTION);
		out.set("config", section != null && section.isObject() ? section : MAPPER.createObjectNode());
		out.set("schema", AppCrudSection.schema(CONFIG_DIR.resolve("_schemas"), app));
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	/// Checks `body`, the file's new `crud` section, and splices it into the
	/// file. A restore passes the whole old file as `whole` (and its section
	/// as `body`): it is checked as a save is, then written whole.
	private void handleSave(HttpServletRequest req, HttpServletResponse resp, String app, String body, ObjectNode whole)
			throws IOException {
		JsonNode section;
		try {
			section = MAPPER.readTree(body);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "not valid JSON: " + e.getMessage(), null);
			return;
		}
		if (section == null || !section.isObject()) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "the crud section must be a JSON object", null);
			return;
		}
		java.util.Map<String, String> membership;
		try {
			membership = ClusterMembership.forSave(app);
		} catch (IOException e) {
			respondError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "not saved: " + e.getMessage(), null);
			return;
		}
		// The draft as it runs, what it does to the files above it (DraftCheck).
		java.util.List<String> problems = org.vorpal.blade.framework.v3.configuration.DraftCheck.problems(app,
				req.getParameter("file"), MAPPER.createObjectNode().set(SECTION, section), SECTION, membership, MAPPER,
				(doc, domainFile) -> {
					JsonNode s = doc.get(SECTION);
					Crud c = MAPPER.treeToValue(s != null ? s : MAPPER.createObjectNode(), Crud.class);
					return Crud.problems(c.getRuleSets(), c.getDefaultRuleSet());
				});
		java.util.List<String> warnings = new java.util.ArrayList<>();
		// Saved tests guard the domain file ([CrudConfigServlet#problems]).
		if (domain(req) && problems.isEmpty()) {
			try {
				problems.addAll(CrudTests.failures(CrudTests.of(MAPPER.treeToValue(section, Crud.class)),
						CrudTests.load(app), "domain"));
			} catch (IOException unreadable) {
				// The rules still save; the operator learns why no test ran.
				warnings.addAll(CrudTests.unreadable(app));
			}
		}

		if (!problems.isEmpty()) {
			org.vorpal.blade.framework.v2.config.ConfigEditorServlet.respondProblems(resp, problems);
			return;
		}

		CredentialEncryption.encryptTree(section);
		String pretty;
		String version;
		// Read, splice and write under one lock, so two saves to the same
		// file cannot interleave and drop one another's section.
		synchronized (org.vorpal.blade.framework.v3.configuration.FileVersion.lock(configFile(req, app))) {
			if (!org.vorpal.blade.framework.v3.configuration.FileVersion.current(req, configFile(req, app))) {
				respondError(resp, HttpServletResponse.SC_CONFLICT, org.vorpal.blade.framework.v3.configuration.FileVersion.conflict(configFile(req, app)), null);
				return;
			}
			Path base = Files.exists(configFile(req, app)) ? configFile(req, app)
					: domain(req) ? sampleFile(app) : null;
			if (domain(req) && !Files.exists(base)) {
				respondError(resp, HttpServletResponse.SC_NOT_FOUND,
						"no " + app + ".json and no generated sample: start " + app + " once on a node first", null);
				return;
			}
			// A new cluster or server file starts empty: it holds only the section.
			JsonNode doc = (base != null) ? MAPPER.readTree(base.toFile()) : MAPPER.createObjectNode();
			if (!doc.isObject()) {
				respondError(resp, HttpServletResponse.SC_CONFLICT, base + " is not a JSON object", null);
				return;
			}
			if (whole != null) {
				doc = whole;
				if (whole.has(SECTION)) whole.set(SECTION, section); // as checked and encrypted
			} else {
				AppCrudSection.splice((ObjectNode) doc, (ObjectNode) section);
			}
			pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
			store.write(configFile(req, app), pretty);
			// The version of what this save wrote, taken before another
			// writer can get in.
			version = org.vorpal.blade.framework.v3.configuration.FileVersion.of(configFile(req, app));
		}

		ObjectNode out = MAPPER.createObjectNode();
		out.put("version", version);
		ArrayNode warned = out.putArray("warnings");
		for (String w : warnings) warned.add(w);
		out.put("path", configFile(req, app).toAbsolutePath().toString());
		out.put("bytes", pretty.getBytes(StandardCharsets.UTF_8).length);
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	private void handlePublish(HttpServletResponse resp, String app) throws IOException {
		try {
			List<String> actions = ConfigPublisher.reload(app);
			ObjectNode out = MAPPER.createObjectNode();
			ArrayNode arr = out.putArray("actions");
			for (String action : actions) arr.add(action);
			writeJson(resp, HttpServletResponse.SC_OK, out);
		} catch (IOException e) {
			// No Configuration MBean: the app isn't running. The file is saved.
			respondError(resp, HttpServletResponse.SC_CONFLICT, e.getMessage(), null);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"publish failed: " + e.getMessage(), stackTrace(e));
		}
	}

	private void handleVersionList(HttpServletRequest req, HttpServletResponse resp, String app) throws IOException {
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode arr = out.putArray("versions");
		for (VersionedFileStore.VersionInfo v : store.listVersions(configFile(req, app))) {
			ObjectNode item = arr.addObject();
			item.put("timestamp", v.getTimestamp());
			item.put("sizeBytes", v.getSizeBytes());
		}
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	private void handleVersionContent(HttpServletRequest req, HttpServletResponse resp, String app, String ts) throws IOException {
		long timestamp = parseTimestamp(resp, ts);
		if (timestamp < 0) return;
		writeRaw(resp, store.readVersion(configFile(req, app), timestamp), ts);
	}

	private void handleVersionRestore(HttpServletRequest req, HttpServletResponse resp, String app, String ts) throws IOException {
		long timestamp = parseTimestamp(resp, ts);
		if (timestamp < 0) return;
		JsonNode old;
		try {
			String text = store.readVersion(configFile(req, app), timestamp);
			if (text == null) throw new IOException("no version " + ts);
			old = MAPPER.readTree(text);
		} catch (IOException e) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "no version " + ts, null);
			return;
		}
		if (!(old instanceof ObjectNode)) {
			respondError(resp, HttpServletResponse.SC_CONFLICT, "version " + ts + " is not a JSON object", null);
			return;
		}
		// Saved as a draft is, whole: an old version must pass today's checks
		// (a rule a cluster file changes may have been renamed since).
		JsonNode section = old.get(SECTION);
		handleSave(req, resp, app, (section != null && section.isObject()) ? section.toString() : "{}", (ObjectNode) old);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/// The path after `/apps`, split on `/`; empty for the list.
	private static String[] subPath(HttpServletRequest req) {
		String info = req.getPathInfo();
		if (info == null) return new String[0];
		String trimmed = info.replaceAll("^/+|/+$", "");
		return trimmed.isEmpty() ? new String[0] : trimmed.split("/");
	}

	private static String appOf(HttpServletResponse resp, String app) throws IOException {
		if (AppCrudSection.APP_NAME.matcher(app).matches() && !app.equals("crud")) return app;
		respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "bad application name: " + app, null);
		return null;
	}

	private static long parseTimestamp(HttpServletResponse resp, String ts) throws IOException {
		try {
			return Long.parseLong(ts);
		} catch (NumberFormatException e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "bad timestamp: " + ts, null);
			return -1;
		}
	}

	private static void writeRaw(HttpServletResponse resp, String content, String ts) throws IOException {
		if (content == null) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "no version " + ts, null);
			return;
		}
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write(content);
	}

	private static String readBody(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = req.getReader()) {
			char[] buf = new char[4096];
			int n;
			while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
		}
		return sb.toString();
	}

	private static void writeJson(HttpServletResponse resp, int status, JsonNode body) throws IOException {
		byte[] payload = MAPPER.writeValueAsBytes(body);
		resp.setStatus(status);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setContentLength(payload.length);
		resp.getOutputStream().write(payload);
	}

	/// Always JSON, even on error, so the page can always parse what it gets.
	private static void respondError(HttpServletResponse resp, int status, String message, String stack)
			throws IOException {
		ObjectNode err = MAPPER.createObjectNode();
		err.put("error", message);
		if (stack != null) err.put("stack", stack);
		writeJson(resp, status, err);
	}

	private static String stackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}
}
