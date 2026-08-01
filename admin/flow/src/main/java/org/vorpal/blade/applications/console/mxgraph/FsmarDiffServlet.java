package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Compares the configuration in the export dialog against whatever is live at
/// the selected target, so an operator can see what a publish would change
/// before it changes it.
///
/// The case this exists for: load the sample rather than the live config, edit
/// the routing, publish — and the live `logging` / `analytics` / `events`
/// blocks are gone, because the editor never modelled them and had nothing to
/// carry through. `removedRootKeys` is reported separately so the UI can lead
/// with that rather than bury it.
///
/// See [FsmarDiff] for the comparison itself.
@WebServlet("/fsmarDiff")
public class FsmarDiffServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

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
			response.sendError(HttpServletResponse.SC_BAD_REQUEST,
					"Unknown target '" + request.getParameter("target") + "'");
			return;
		}

		JsonNode proposed;
		try {
			proposed = mapper.readTree(json);
		} catch (IOException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not valid JSON: " + e.getMessage());
			return;
		}

		Path path = target.getConfigFile();
		JsonNode live = null;
		if (Files.exists(path)) {
			try {
				live = mapper.readTree(path.toFile());
			} catch (IOException e) {
				// A live config we can't parse is itself worth saying out loud
				// rather than reporting as "no differences".
				response.sendError(HttpServletResponse.SC_CONFLICT,
						"The live config at " + path + " is not valid JSON (" + e.getMessage()
								+ ") — publishing will replace it wholesale.");
				return;
			}
		}

		FsmarDiff.Result result = FsmarDiff.compare(live, proposed);

		ObjectNode out = mapper.createObjectNode();
		out.put("target", target.getId());
		out.put("displayName", target.getDisplayName());
		out.put("path", path.toString());
		out.put("targetExists", result.isTargetExists());
		out.put("identical", result.isIdentical());
		out.put("truncated", result.isTruncated());
		out.put("added", result.count(FsmarDiff.Op.ADDED));
		out.put("removed", result.count(FsmarDiff.Op.REMOVED));
		out.put("changed", result.count(FsmarDiff.Op.CHANGED));

		ArrayNode rootKeys = out.putArray("removedRootKeys");
		result.removedRootKeys().forEach(rootKeys::add);

		ArrayNode entries = out.putArray("entries");
		for (FsmarDiff.Entry e : result.getEntries()) {
			ObjectNode node = entries.addObject();
			node.put("op", e.getOp().name());
			node.put("path", e.getPath());
			if (e.getFrom() != null) {
				node.put("from", e.getFrom());
			}
			if (e.getTo() != null) {
				node.put("to", e.getTo());
			}
		}

		response.setContentType("application/json; charset=UTF-8");
		response.getWriter().write(mapper.writeValueAsString(out));
		response.getWriter().flush();
	}
}
