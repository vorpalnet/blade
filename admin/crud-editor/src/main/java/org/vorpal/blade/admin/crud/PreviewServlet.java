package org.vorpal.blade.admin.crud;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.crud.CrudConfiguration;
import org.vorpal.blade.framework.v3.crud.CrudConfigurationSample;
import org.vorpal.blade.framework.v3.crud.PreviewEngine;
import org.vorpal.blade.framework.v3.crud.PreviewEngine.PreviewResult;

import com.fasterxml.jackson.databind.ObjectMapper;

/// REST endpoint for the editor's "try-it" sandbox. Accepts a JSON
/// `{ruleSet, message, lifecycleEvent, variables}` body and returns the
/// transformed SIP message plus the list of rule ids that fired and the
/// final session-variable snapshot.
///
/// Loads the live CRUD configuration from `config/custom/vorpal/crud.json`
/// (the domain-scoped path written by the framework's `SettingsManager`).
/// Re-reads on every request so edits land without a redeploy. Falls back
/// to a built-in [CrudConfigurationSample] when the file is missing — useful
/// for first-run / fresh installs.
///
/// Every code path returns a JSON body — the doPost/doGet methods catch
/// `Throwable` so an uncaught engine error doesn't bubble up and trigger
/// a WebLogic HTML error page (which would defeat the client's
/// `await response.json()` parse).
///
/// URL mappings live in web.xml (so deployment doesn't depend on
/// annotation scanning); see `<servlet-mapping>` for `preview`.
public class PreviewServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Path CONFIG_FILE = Paths.get("config/custom/vorpal/crud.json");

	@Override
	public void init() throws ServletException {
		// The CRUD engine and its operation classes log via
		// SettingsManager.getSipLogger() / Callflow.getSipLogger(); on a
		// real managed node those statics are wired by the SIP servlet's
		// servletCreated. The AdminServer-side preview WAR doesn't go
		// through that path, so install a CapturingLogger that (a)
		// prevents the catch handlers from NPE'ing on a null logger, and
		// (b) stashes per-request errors so the Diagnostics panel can show
		// them.
		CapturingLogger logger = new CapturingLogger();
		if (SettingsManager.getSipLogger() == null) SettingsManager.setSipLogger(logger);
		if (Callflow.getSipLogger() == null) Callflow.setLogger(logger);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			handlePost(req, resp);
		} catch (Throwable t) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(),
					stackTrace(t));
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			handleGet(req, resp);
		} catch (Throwable t) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(),
					stackTrace(t));
		}
	}

	private void handlePost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		PreviewRequest body;
		try {
			body = MAPPER.readValue(readBody(req), PreviewRequest.class);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST,
					"malformed JSON body: " + e.getMessage(), null);
			return;
		}

		CrudConfiguration config;
		try {
			config = loadConfig();
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failed to load CRUD config: " + e.getMessage(), stackTrace(e));
			return;
		}

		PreviewResult result;
		java.util.List<String> captured;
		CapturingLogger.begin();
		try {
			result = PreviewEngine.preview(
					config, body.ruleSet, body.message, body.lifecycleEvent, body.variables);
		} catch (Throwable t) {
			captured = CapturingLogger.end();
			String hint = captured.isEmpty() ? "" : " · " + captured.size() + " logged event(s)";
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"engine threw " + t.getClass().getSimpleName() + ": " + t.getMessage() + hint,
					stackTrace(t) + (captured.isEmpty() ? "" : "\n--- captured logs ---\n" + String.join("\n", captured)));
			return;
		}
		captured = CapturingLogger.end();
		// Operations swallow their own exceptions and log them; surface the
		// log lines so the operator can see what went wrong even when the
		// engine itself completed.
		result.warnings = captured;

		// Serialize to a buffer first — if it fails, we can still send a
		// JSON error rather than a partially-written body that WebLogic
		// would patch with an HTML 500 page.
		byte[] payload;
		try {
			payload = MAPPER.writeValueAsBytes(result);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failed to serialize preview result: " + e.getMessage(), stackTrace(e));
			return;
		}

		resp.setStatus(result.error == null
				? HttpServletResponse.SC_OK
				: HttpServletResponse.SC_BAD_REQUEST);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setContentLength(payload.length);
		resp.getOutputStream().write(payload);
	}

	private void handleGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (!"/resources/api/ruleSets".equals(req.getServletPath())) {
			respondError(resp, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint", null);
			return;
		}
		CrudConfiguration config;
		try {
			config = loadConfig();
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failed to load CRUD config: " + e.getMessage(), stackTrace(e));
			return;
		}
		RuleSetsResponse out = new RuleSetsResponse();
		for (String id : config.getRuleSets().keySet()) out.ruleSets.add(id);
		byte[] payload = MAPPER.writeValueAsBytes(out);
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setContentLength(payload.length);
		resp.getOutputStream().write(payload);
	}

	private static CrudConfiguration loadConfig() throws IOException {
		if (Files.exists(CONFIG_FILE)) {
			return MAPPER.readValue(CONFIG_FILE.toFile(), CrudConfiguration.class);
		}
		return new CrudConfigurationSample();
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

	/// Always JSON, always with Content-Type + Content-Length so the
	/// client never has to guess what it received.
	private static void respondError(HttpServletResponse resp, int status, String message, String stack)
			throws IOException {
		ErrorBody err = new ErrorBody();
		err.error = message;
		err.stack = stack;
		byte[] payload;
		try {
			payload = MAPPER.writeValueAsBytes(err);
		} catch (Exception ignored) {
			// Fall back to a hand-crafted JSON literal so we at least don't
			// leave the client guessing on a serializer melt-down.
			payload = ("{\"error\":\"failed to serialize error: " + status + "\"}")
					.getBytes(StandardCharsets.UTF_8);
		}
		if (!resp.isCommitted()) {
			resp.setStatus(status);
			resp.setContentType("application/json");
			resp.setCharacterEncoding("UTF-8");
			resp.setContentLength(payload.length);
		}
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		sink.write(payload);
		resp.getOutputStream().write(payload);
	}

	private static String stackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	public static class PreviewRequest {
		public String ruleSet;
		public String message;
		public String lifecycleEvent;
		/// Pre-set session attributes — simulate Attribute Selectors having
		/// run earlier, or override env vars. Applied before rules fire.
		public java.util.Map<String, String> variables;
	}

	public static class RuleSetsResponse {
		public java.util.List<String> ruleSets = new java.util.ArrayList<>();
	}

	public static class ErrorBody {
		public String error;
		public String stack;
	}
}
