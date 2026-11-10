package org.vorpal.blade.framework.v2.config;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Load / save / publish / version-history servlet base for the BLADE
/// purpose-built config editors (crud-editor, irouter-editor, …). A subclass
/// names the app and its config class; this base supplies the whole
/// editing control plane, following the Flow editor's shape: the WAR runs on
/// the AdminServer whose working directory is the domain root, the live
/// config is written directly through [VersionedFileStore] (prior content
/// backed up into `.versions/`), and Publish is a separate act that tells
/// the service's Configuration MBeans to reload via [ConfigPublisher].
///
/// Save re-parses the posted document as the subclass's settings class
/// first — malformed JSON or an unknown field is rejected with the parse
/// error rather than clobbering a working config — and runs
/// [CredentialEncryption#encryptTree] so `{CLEARTEXT}` credentials never
/// land on disk unencrypted.
///
/// Routes, under whatever `/config/*` mapping the subclass's web.xml
/// declares:
///
/// Every route takes `?file=` to name the file it works on: `domain` (the
/// default), `cluster/<name>` or `server/<name>` ([ConfigFiles]).
///
/// - `GET  …/config/files`: the app's files: the domain file, and every
///   cluster and server file that exists.
/// - `GET  …/config/merged`: read only: the file's layers in the loader's
///   order and what they merge to ([ConfigFiles#mergedView]).
/// - `GET  …/config`: `{source, file, path, version, config, schema}`;
///   config from the file, else (domain only) the generated sample file,
///   else the built-in [#sample]; a cluster or server file not written yet
///   loads as `{}`. Schema from `_schemas/<app>.jschema`, else generated
///   in-process.
/// - `POST …/config/save`: body is the full config document.
/// - `POST …/config/publish`: reload the app's Configuration MBeans.
/// - `GET  …/config/versions`: `.versions/` history.
/// - `GET  …/config/versions/{ts}`: one snapshot's content.
/// - `POST …/config/versions/{ts}/restore`: restore it (the replaced
///   content is itself backed up first).
public abstract class ConfigEditorServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	protected static final ObjectMapper MAPPER = new ObjectMapper();

	private static final VersionedFileStore store = new VersionedFileStore();

	/// The service's flattened app name — its context root — which names the
	/// config file, the schema file, and the Configuration MBeans.
	protected abstract String appName();

	/// The settings class the CRUD service itself loads the config as; used
	/// to reject documents the service couldn't load.
	protected abstract Class<?> settingsClass();

	/// The built-in sample, used when neither a live config nor a generated
	/// sample file exists yet.
	protected abstract Object sample();

	/// The file this request edits: `?file=` names the domain file (the
	/// default) or a cluster or server file ([ConfigFiles#resolve]).
	protected Path configFile(HttpServletRequest req) {
		return ConfigFiles.resolve(appName(), req.getParameter("file"));
	}

	protected Path schemaFile() {
		return Paths.get("config/custom/vorpal/_schemas/" + appName() + ".jschema");
	}

	protected Path sampleFile() {
		return Paths.get("config/custom/vorpal/_samples/" + appName() + ".json.SAMPLE");
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			String path = subPath(req);
			switch (path) {
			case "":
				handleLoad(req, resp);
				break;
			case "files":
				writeJson(resp, HttpServletResponse.SC_OK, MAPPER.valueToTree(ConfigFiles.of(appName(), ClusterMembership.read())));
				break;
			case "merged":
				writeJson(resp, HttpServletResponse.SC_OK, ConfigFiles.mergedView(appName(), req.getParameter("file"), null,
						ClusterMembership.read(), MAPPER));
				break;
			case "versions":
				handleVersionList(req, resp);
				break;
			default:
				if (path.startsWith("versions/")) {
					handleVersionContent(req, resp, path.substring("versions/".length()));
				} else {
					respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
				}
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
			String path = subPath(req);
			switch (path) {
			case "save":
				handleSave(req, resp, readBody(req));
				break;
			case "publish":
				handlePublish(resp);
				break;
			default:
				if (path.startsWith("versions/") && path.endsWith("/restore")) {
					String ts = path.substring("versions/".length(), path.length() - "/restore".length());
					handleVersionRestore(req, resp, ts);
				} else {
					respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
				}
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

	private void handleLoad(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		ObjectNode out = MAPPER.createObjectNode();
		Path file = configFile(req);

		String source;
		JsonNode config;
		String version;
		// Content and version under one lock, so a save landing between the
		// two reads cannot tag the old content with the new version.
		synchronized (org.vorpal.blade.framework.v3.configuration.FileVersion.lock(file)) {
		version = org.vorpal.blade.framework.v3.configuration.FileVersion.of(file);
		if (Files.exists(file)) {
			source = "live";
			config = MAPPER.readTree(Files.readAllBytes(file));
		} else if (!ConfigFiles.isDomain(req.getParameter("file"))) {
			// A cluster or server file holds only what it changes: start empty.
			source = "new";
			config = MAPPER.createObjectNode();
		} else if (Files.exists(sampleFile())) {
			source = "sample";
			config = MAPPER.readTree(Files.readAllBytes(sampleFile()));
		} else {
			source = "builtin";
			config = MAPPER.valueToTree(sample());
		}
		}
		out.put("source", source);
		out.put("file", ConfigFiles.isDomain(req.getParameter("file")) ? ConfigFiles.DOMAIN : req.getParameter("file"));
		out.put("path", file.toString());
		out.put("version", version);
		out.set("config", config);

		JsonNode schema = null;
		try {
			if (Files.exists(schemaFile())) {
				schema = MAPPER.readTree(Files.readAllBytes(schemaFile()));
			} else {
				// The service hasn't started on a node sharing this domain
				// directory yet — generate the schema in-process. Same
				// generator, same output.
				schema = SettingsManager.generateSchemaNode(settingsClass(), MAPPER);
			}
		} catch (Throwable ignore) {
			// The editor degrades to generic key/value forms.
		}
		out.set("schema", schema);

		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	/// Checks `body` as the file's new content and writes it. A restore
	/// comes here too, so an old version passes the checks a save does.
	private void handleSave(HttpServletRequest req, HttpServletResponse resp, String body) throws IOException {
		Path file = configFile(req);
		JsonNode tree;
		try {
			tree = MAPPER.readTree(body);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "not valid JSON: " + e.getMessage(), null);
			return;
		}

		java.util.Map<String, String> membership;
		try {
			membership = ClusterMembership.forSave(appName());
		} catch (IOException e) {
			respondError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "not saved: " + e.getMessage(), null);
			return;
		}
		// The draft as it runs, what it does to the files above it, and what
		// the service itself would refuse (DraftCheck).
		java.util.List<String> problems = org.vorpal.blade.framework.v3.configuration.DraftCheck.problems(appName(),
				req.getParameter("file"), tree, null, membership, MAPPER,
				(doc, domainFile) -> problems(MAPPER.treeToValue(doc, settingsClass()), domainFile));
		if (!problems.isEmpty()) {
			respondProblems(resp, problems);
			return;
		}
		java.util.List<String> warnings = ConfigFiles.isDomain(req.getParameter("file")) ? saveWarnings()
				: java.util.Collections.emptyList();

		CredentialEncryption.encryptTree(tree);
		String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
		String version;
		synchronized (org.vorpal.blade.framework.v3.configuration.FileVersion.lock(file)) {
			if (!org.vorpal.blade.framework.v3.configuration.FileVersion.current(req, file)) {
				respondError(resp, HttpServletResponse.SC_CONFLICT, org.vorpal.blade.framework.v3.configuration.FileVersion.conflict(file), null);
				return;
			}
			store.write(file, pretty);
			version = org.vorpal.blade.framework.v3.configuration.FileVersion.of(file); // what this save wrote
		}

		ObjectNode out = MAPPER.createObjectNode();
		out.put("version", version);
		ArrayNode warned = out.putArray("warnings");
		for (String w : warnings) warned.add(w);
		out.put("path", file.toAbsolutePath().toString());
		out.put("bytes", pretty.getBytes(StandardCharsets.UTF_8).length);
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	private void handlePublish(HttpServletResponse resp) throws IOException {
		try {
			List<String> actions = ConfigPublisher.reload(appName());
			ObjectNode out = MAPPER.createObjectNode();
			ArrayNode arr = out.putArray("actions");
			for (String action : actions) arr.add(action);
			writeJson(resp, HttpServletResponse.SC_OK, out);
		} catch (IOException e) {
			// No Configuration MBean — the service isn't deployed (or not
			// running). The file is saved; publish just has no takers.
			respondError(resp, HttpServletResponse.SC_CONFLICT, e.getMessage(), null);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"publish failed: " + e.getMessage(), stackTrace(e));
		}
	}

	private void handleVersionList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode arr = out.putArray("versions");
		for (VersionedFileStore.VersionInfo v : store.listVersions(configFile(req))) {
			ObjectNode item = arr.addObject();
			item.put("timestamp", v.getTimestamp());
			item.put("sizeBytes", v.getSizeBytes());
		}
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	private void handleVersionContent(HttpServletRequest req, HttpServletResponse resp, String ts) throws IOException {
		long timestamp = parseTimestamp(resp, ts);
		if (timestamp < 0) return;
		String content = store.readVersion(configFile(req), timestamp);
		if (content == null) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "no version " + ts, null);
			return;
		}
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write(content);
	}

	private void handleVersionRestore(HttpServletRequest req, HttpServletResponse resp, String ts) throws IOException {
		long timestamp = parseTimestamp(resp, ts);
		if (timestamp < 0) return;
		String restored;
		try {
			restored = store.readVersion(configFile(req), timestamp);
			if (restored == null) throw new IOException("no version " + ts);
		} catch (IOException e) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "no version " + ts, null);
			return;
		}
		// Saved as a draft is: an old version must pass today's checks (a
		// rule a cluster file changes may have been renamed since), and the
		// current file is backed up as any save backs it up.
		handleSave(req, resp, restored);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/// The path after the servlet's `/config` mapping, without leading slash.
	private static String subPath(HttpServletRequest req) {
		String info = req.getPathInfo();
		if (info == null || info.equals("/")) return "";
		return info.startsWith("/") ? info.substring(1) : info;
	}

	private static long parseTimestamp(HttpServletResponse resp, String ts) throws IOException {
		try {
			return Long.parseLong(ts);
		} catch (NumberFormatException e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "bad timestamp: " + ts, null);
			return -1;
		}
	}

	protected static String readBody(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = req.getReader()) {
			char[] buf = new char[4096];
			int n;
			while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
		}
		return sb.toString();
	}

	protected static void writeJson(HttpServletResponse resp, int status, JsonNode body) throws IOException {
		byte[] payload = MAPPER.writeValueAsBytes(body);
		resp.setStatus(status);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setContentLength(payload.length);
		resp.getOutputStream().write(payload);
	}

	/// What in a config that loads would still fail once calls reach it (a
	/// regex that does not compile, say). Save refuses a config with any.
	/// None by default. For a cluster or server file, `settings` is what it
	/// runs ([ConfigFiles#applied]); `domainFile` says which was saved.
	/// What a domain Save should say without refusing, such as a check that
	/// could not run. None by default.
	protected java.util.List<String> saveWarnings() {
		return java.util.Collections.emptyList();
	}

	protected java.util.List<String> problems(Object settings, boolean domainFile) {
		return java.util.Collections.emptyList();
	}

	/// 400 with `error` and the `problems` list, for the editor to show.
	public static void respondProblems(HttpServletResponse resp, java.util.List<String> problems) throws IOException {
		ObjectNode err = MAPPER.createObjectNode();
		err.put("error", "not saved: " + problems.size() + " problem(s)");
		com.fasterxml.jackson.databind.node.ArrayNode list = err.putArray("problems");
		problems.forEach(list::add);
		writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, err);
	}

	/// Always JSON, even on error, so the page can always parse what it
	/// receives.
	protected static void respondError(HttpServletResponse resp, int status, String message, String stack)
			throws IOException {
		ObjectNode err = MAPPER.createObjectNode();
		err.put("error", message);
		if (stack != null) err.put("stack", stack);
		writeJson(resp, status, err);
	}

	protected static String stackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}
}
