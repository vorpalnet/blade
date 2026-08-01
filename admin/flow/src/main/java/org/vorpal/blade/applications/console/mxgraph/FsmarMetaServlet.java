package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Serves the FSMAR model's closed value sets to the editor, so the browser
/// fills its dropdowns from the framework classes instead of from literals
/// duplicated across `transition.html`, `flowTasks.js` and `flowPlans.js`.
///
/// Values come from [FsmarMeta], which reads them off the model itself. The
/// page keeps its own copy as a fallback for when this request fails — a
/// stale dropdown beats an empty one — but in the normal case this is the
/// single source.
@WebServlet("/fsmarMeta")
public class FsmarMetaServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		ObjectNode meta = mapper.createObjectNode();
		putList(meta, "methods", FsmarMeta.METHODS);
		putList(meta, "regions", FsmarMeta.REGIONS);
		putList(meta, "routeModifiers", FsmarMeta.ROUTE_MODIFIERS);
		putList(meta, "selectorTypes", FsmarMeta.SELECTOR_TYPES);
		// Non-empty when a list fell back to built-in defaults because the model
		// could not be read. The editor still works; this makes the fact
		// visible instead of leaving a silently stale dropdown.
		if (!FsmarMeta.warnings().isEmpty()) {
			putList(meta, "warnings", FsmarMeta.warnings());
		}

		response.setContentType("application/json; charset=UTF-8");
		// Model-derived and constant for the life of the deployment, but the
		// deployment can change under a long-lived tab — revalidate each load.
		response.setHeader("Cache-Control", "no-cache");
		response.getWriter().write(mapper.writeValueAsString(meta));
		response.getWriter().flush();
	}

	private void putList(ObjectNode target, String name, List<String> values) {
		ArrayNode arr = target.putArray(name);
		for (String v : values) {
			arr.add(v);
		}
	}
}
