package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Publishes FSMAR JSON straight to the live `fsmar` configuration.
///
/// Writes `config/custom/vorpal/fsmar.json` relative to the domain root —
/// the exact path and mechanism the Configurator's SaveDataServlet uses for
/// every BLADE app config (this WAR runs on AdminServer, whose working
/// directory is the domain root). The engine-tier SettingsManager registered
/// by the FSMAR AppRouter picks the change up from there; no AR restart. (The
/// config is un-versioned by filename — `fsmar.json` — with the version inside;
/// the engine auto-upgrades a legacy `fsmar2.json` on load.)
///
/// The body is re-serialized through Jackson before writing: malformed JSON
/// is rejected with the parse error instead of clobbering a working config,
/// and the on-disk form is canonically pretty-printed regardless of edits
/// made in the export dialog's textarea.
@WebServlet("/fsmarPublish")
public class FsmarPublishServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	static final Path CONFIG_PATH = Paths.get("config/custom/vorpal/fsmar.json");

	/// The canonical sample, generated from AppRouterConfigurationSample by
	/// the FSMAR SettingsManager at engine startup — the single source of
	/// truth for "what does an example config look like".
	static final Path SAMPLE_PATH = Paths.get("config/custom/vorpal/_samples/fsmar.json.SAMPLE");

	private final ObjectMapper mapper = new ObjectMapper();

	/// Returns the live fsmar3 config ("Load live fsmar3" in the import
	/// dialog), or with `?sample=1` the canonical generated sample ("Load
	/// sample"). 404 with a plain-text reason when the file doesn't exist.
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		boolean sample = "1".equals(request.getParameter("sample"));
		Path path = sample ? SAMPLE_PATH : CONFIG_PATH;
		if (!Files.exists(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, sample
					? "No sample at " + path.toAbsolutePath()
							+ " — it is generated when the FSMAR 3 App Router initializes."
					: "No live config at " + path.toAbsolutePath()
							+ " — publish one first, or import from a file.");
			return;
		}
		byte[] bytes = Files.readAllBytes(path);
		response.setContentType("application/json; charset=UTF-8");
		response.getOutputStream().write(bytes);
		response.getOutputStream().flush();
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String json = request.getParameter("json");
		if (json == null || json.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing json parameter");
			return;
		}

		JsonNode tree;
		try {
			tree = mapper.readTree(json);
		} catch (IOException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not valid JSON: " + e.getMessage());
			return;
		}

		try {
			byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree);
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.write(CONFIG_PATH, bytes);

			ObjectNode result = mapper.createObjectNode();
			result.put("path", CONFIG_PATH.toAbsolutePath().toString());
			result.put("bytes", bytes.length);

			response.setContentType("application/json; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.write(mapper.writeValueAsString(result));
			out.flush();
		} catch (IOException e) {
			throw new ServletException("FSMAR publish failed: " + e.getMessage(), e);
		}
	}
}
