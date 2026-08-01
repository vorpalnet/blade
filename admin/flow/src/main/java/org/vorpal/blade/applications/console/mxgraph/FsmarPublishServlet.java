package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.vorpal.blade.framework.v2.io.VersionedFileStore;

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
/// the same path and the same [VersionedFileStore] backup discipline the
/// Configurator uses for every BLADE app config (this WAR runs on AdminServer,
/// whose working directory is the domain root). The engine-tier SettingsManager
/// registered by the FSMAR AppRouter picks the change up from there; no AR
/// restart. (The config is un-versioned by filename — `fsmar.json` — with the
/// version inside; the engine auto-upgrades a legacy `fsmar2.json` on load.)
///
/// Publishing replaces a live routing config, so the previous content is copied
/// into `.versions/` first and stays restorable from the Configurator's version
/// history. Only the domain-level file is written: per-cluster and per-server
/// overlays (`_clusters/`, `_servers/`) are the Configurator's job.
///
/// The body is re-serialized through Jackson before writing: malformed JSON
/// is rejected with the parse error instead of clobbering a working config,
/// and the on-disk form is canonically pretty-printed regardless of edits
/// made in the export dialog's textarea.
@WebServlet("/fsmarPublish")
public class FsmarPublishServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	/// The domain-level config — the default target, and the one almost every
	/// deployment uses. Cluster and server overlays are addressed by `target`;
	/// see [FsmarTargets].
	static final Path CONFIG_PATH = Paths.get("config/custom/vorpal/fsmar.json");

	/// The canonical sample, generated from AppRouterConfigurationSample by
	/// the FSMAR SettingsManager at engine startup — the single source of
	/// truth for "what does an example config look like". Domain-level only:
	/// the sample is one file per domain, not per overlay.
	static final Path SAMPLE_PATH = Paths.get("config/custom/vorpal/_samples/fsmar.json.SAMPLE");

	private final ObjectMapper mapper = new ObjectMapper();

	/// Same backup discipline the Configurator uses: the prior config is copied
	/// into `.versions/` before every publish.
	private final VersionedFileStore store = new VersionedFileStore();

	/// Returns the live fsmar config for the requested `target` ("Load live
	/// fsmar" in the import dialog), or with `?sample=1` the canonical
	/// generated sample ("Load sample"). 404 with a plain-text reason when the
	/// file doesn't exist — which for an overlay simply means none has been
	/// published there yet.
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		boolean sample = "1".equals(request.getParameter("sample"));
		Path path;
		if (sample) {
			path = SAMPLE_PATH;
		} else {
			FsmarTargets.Target target = FsmarTargets.resolve(request.getParameter("target"));
			if (target == null) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, unknownTarget(
						request.getParameter("target")));
				return;
			}
			path = target.getConfigFile();
		}
		if (!Files.exists(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, sample
					? "No sample at " + path.toAbsolutePath()
							+ " — it is generated when the FSMAR 3 App Router initializes."
					: "No config at " + path.toAbsolutePath()
							+ " — nothing published to this target yet.");
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

		FsmarTargets.Target target = FsmarTargets.resolve(request.getParameter("target"));
		if (target == null) {
			// Deliberately not falling back to the domain: writing domain-wide
			// config when the operator asked for one cluster is the worst way
			// to be wrong here.
			response.sendError(HttpServletResponse.SC_BAD_REQUEST,
					unknownTarget(request.getParameter("target")));
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
			String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
			// Through VersionedFileStore, not a bare Files.write: publishing
			// overwrites the live router config, and the previous content has to
			// stay recoverable from .versions/ exactly as it does when the
			// Configurator saves. (No credential encryption pass — unlike an app
			// config, the FSMAR model has no password-bearing fields.)
			store.write(target.getConfigFile(), pretty);

			ObjectNode result = mapper.createObjectNode();
			result.put("path", target.getConfigFile().toAbsolutePath().toString());
			result.put("target", target.getId());
			result.put("displayName", target.getDisplayName());
			result.put("bytes", pretty.getBytes(StandardCharsets.UTF_8).length);

			response.setContentType("application/json; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.write(mapper.writeValueAsString(result));
			out.flush();
		} catch (IOException e) {
			throw new ServletException("FSMAR publish failed: " + e.getMessage(), e);
		}
	}

	/// A target id the domain doesn't offer. Overlay directories are created by
	/// the SettingsManager on each server, so an unknown cluster/server usually
	/// means that server has never started here rather than a typo.
	private String unknownTarget(String requested) throws IOException {
		StringBuilder sb = new StringBuilder("Unknown target '")
				.append(requested)
				.append("'. Available: ");
		boolean first = true;
		for (FsmarTargets.Target t : FsmarTargets.list()) {
			if (!first) {
				sb.append(", ");
			}
			sb.append(t.getId());
			first = false;
		}
		return sb.toString();
	}
}
