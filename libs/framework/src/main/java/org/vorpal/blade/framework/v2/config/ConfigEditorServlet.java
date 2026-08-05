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

import org.vorpal.blade.framework.v2.io.VersionedFileStore;

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
/// - `GET  …/config` — `{source, config, schema}`; config from the live
///   file, else the generated sample file, else the built-in [#sample];
///   schema from `_schemas/<app>.jschema`, else generated in-process.
/// - `POST …/config/save` — body is the full config document.
/// - `POST …/config/publish` — reload the app's Configuration MBeans.
/// - `GET  …/config/versions` — `.versions/` history.
/// - `GET  …/config/versions/{ts}` — one snapshot's content.
/// - `POST …/config/versions/{ts}/restore` — restore it (the replaced
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

	protected Path configFile() {
		return Paths.get("config/custom/vorpal/" + appName() + ".json");
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
			if (path.isEmpty()) {
				handleLoad(resp);
			} else if (path.equals("versions")) {
				handleVersionList(resp);
			} else if (path.startsWith("versions/")) {
				handleVersionContent(resp, path.substring("versions/".length()));
			} else {
				respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
			}
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
				handleSave(req, resp);
				break;
			case "publish":
				handlePublish(resp);
				break;
			default:
				if (path.startsWith("versions/") && path.endsWith("/restore")) {
					String ts = path.substring("versions/".length(), path.length() - "/restore".length());
					handleVersionRestore(resp, ts);
				} else {
					respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
				}
			}
		} catch (Throwable t) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), stackTrace(t));
		}
	}

	// ------------------------------------------------------------------
	// Handlers
	// ------------------------------------------------------------------

	private void handleLoad(HttpServletResponse resp) throws IOException {
		ObjectNode out = MAPPER.createObjectNode();

		String source;
		JsonNode config;
		if (Files.exists(configFile())) {
			source = "live";
			config = MAPPER.readTree(Files.readAllBytes(configFile()));
		} else if (Files.exists(sampleFile())) {
			source = "sample";
			config = MAPPER.readTree(Files.readAllBytes(sampleFile()));
		} else {
			source = "builtin";
			config = MAPPER.valueToTree(sample());
		}
		out.put("source", source);
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

	private void handleSave(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		JsonNode tree;
		try {
			tree = MAPPER.readTree(readBody(req));
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST, "not valid JSON: " + e.getMessage(), null);
			return;
		}

		// Reject anything the service itself couldn't load — unknown fields,
		// wrong subtypes, malformed shapes — before it can clobber a working
		// config.
		try {
			MAPPER.treeToValue(tree, settingsClass());
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST,
					"config rejected — the " + appName() + " service could not load it: " + e.getMessage(), null);
			return;
		}

		CredentialEncryption.encryptTree(tree);
		String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
		store.write(configFile(), pretty);

		ObjectNode out = MAPPER.createObjectNode();
		out.put("path", configFile().toAbsolutePath().toString());
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

	private void handleVersionList(HttpServletResponse resp) throws IOException {
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode arr = out.putArray("versions");
		for (VersionedFileStore.VersionInfo v : store.listVersions(configFile())) {
			ObjectNode item = arr.addObject();
			item.put("timestamp", v.getTimestamp());
			item.put("sizeBytes", v.getSizeBytes());
		}
		writeJson(resp, HttpServletResponse.SC_OK, out);
	}

	private void handleVersionContent(HttpServletResponse resp, String ts) throws IOException {
		long timestamp = parseTimestamp(resp, ts);
		if (timestamp < 0) return;
		String content = store.readVersion(configFile(), timestamp);
		if (content == null) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "no version " + ts, null);
			return;
		}
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write(content);
	}

	private void handleVersionRestore(HttpServletResponse resp, String ts) throws IOException {
		long timestamp = parseTimestamp(resp, ts);
		if (timestamp < 0) return;
		String restored = store.restore(configFile(), timestamp);
		if (restored == null) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "no version " + ts, null);
			return;
		}
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write(restored);
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
