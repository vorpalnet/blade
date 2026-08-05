package org.vorpal.blade.admin.irouter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.RouterConfiguration;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.routing.Routing;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;
import org.vorpal.blade.framework.v3.irouter.IRouterConfigSample;
import org.vorpal.blade.framework.v3.irouter.RoutePreviewEngine;

import com.fasterxml.jackson.databind.ObjectMapper;

/// REST endpoint for the iRouter editor's routing dry-run. Accepts a JSON
/// `{message, variables, draftPipeline, draftRouting}` body and returns
/// what the router WOULD do: the resolved Route (forward / direct
/// response / no decision), stamped headers, the post-enrichment variable
/// snapshot, and any warnings the engine logged.
///
/// Loads the live `config/custom/vorpal/irouter.json` on every request so
/// edits published elsewhere land without a redeploy; the draft fields, when
/// present, replace the live pipeline/routing for that run only. Falls back
/// to the built-in [IRouterConfigSample] when no config exists yet.
///
/// A dry-run really runs the pipeline inline — an I/O connector (`rest`,
/// `jdbc`, `ldap`) contacts its backend during preview.
public class RoutePreviewServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Path CONFIG_FILE = Paths.get("config/custom/vorpal/irouter.json");

	@Override
	public void init() throws ServletException {
		// Same wiring as the crud-editor's preview: the AdminServer-side WAR
		// never initializes the SIP logging stack, so install the capturing
		// logger that both prevents NPEs and surfaces per-request warnings.
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
					"unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage(), stackTrace(t));
		}
	}

	private void handlePost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		RouteRequest body;
		try {
			body = MAPPER.readValue(readBody(req), RouteRequest.class);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_BAD_REQUEST,
					"malformed JSON body: " + e.getMessage(), null);
			return;
		}

		RouterConfiguration config;
		try {
			config = loadConfig();
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failed to load iRouter config: " + e.getMessage(), stackTrace(e));
			return;
		}
		if (body.draftPipeline != null) config.setPipeline(body.draftPipeline);
		if (body.draftRouting != null) config.setRouting(body.draftRouting);

		RoutePreviewEngine.RouteResult result;
		List<String> captured;
		CapturingLogger.begin();
		try {
			result = RoutePreviewEngine.routePreview(config, body.message, body.variables);
		} catch (Throwable t) {
			captured = CapturingLogger.end();
			String hint = captured.isEmpty() ? "" : " · " + captured.size() + " logged event(s)";
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"engine threw " + t.getClass().getSimpleName() + ": " + t.getMessage() + hint,
					stackTrace(t) + (captured.isEmpty() ? "" : "\n--- captured logs ---\n" + String.join("\n", captured)));
			return;
		}
		captured = CapturingLogger.end();
		result.warnings = captured;

		byte[] payload;
		try {
			payload = MAPPER.writeValueAsBytes(result);
		} catch (Exception e) {
			respondError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"failed to serialize route result: " + e.getMessage(), stackTrace(e));
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

	private static RouterConfiguration loadConfig() throws IOException {
		if (Files.exists(CONFIG_FILE)) {
			return MAPPER.readValue(CONFIG_FILE.toFile(), IRouterConfig.class);
		}
		return new IRouterConfigSample();
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

	private static void respondError(HttpServletResponse resp, int status, String message, String stack)
			throws IOException {
		com.fasterxml.jackson.databind.node.ObjectNode err = MAPPER.createObjectNode();
		err.put("error", message);
		if (stack != null) err.put("stack", stack);
		byte[] payload = MAPPER.writeValueAsBytes(err);
		resp.setStatus(status);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setContentLength(payload.length);
		resp.getOutputStream().write(payload);
	}

	private static String stackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	public static class RouteRequest {
		public String message;
		/// Pre-set session attributes applied before the pipeline runs.
		public java.util.Map<String, String> variables;
		/// The editor's unsaved buffer — replaces the live config's pipeline
		/// and routing for this run only. Nothing touches disk.
		public List<Connector> draftPipeline;
		public Routing draftRouting;
	}
}
