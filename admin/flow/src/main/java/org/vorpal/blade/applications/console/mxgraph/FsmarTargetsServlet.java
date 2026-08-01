package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.nio.file.Files;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Lists the places an FSMAR configuration can be published — the domain file
/// and any cluster / server overlay directory that exists — so the editor can
/// offer them in a pull-down.
///
/// `exists` says whether that target already has an `fsmar.json`, which lets the
/// pull-down mark the ones that would be created fresh versus overwritten.
///
/// See [FsmarTargets] for the merge order and why the list is read off disk.
@WebServlet("/fsmarTargets")
public class FsmarTargetsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		ObjectNode result = mapper.createObjectNode();
		result.put("defaultTarget", FsmarTargets.DOMAIN);
		ArrayNode arr = result.putArray("targets");

		for (FsmarTargets.Target t : FsmarTargets.list()) {
			ObjectNode node = arr.addObject();
			node.put("id", t.getId());
			node.put("type", t.getType());
			node.put("name", t.getName());
			node.put("displayName", t.getDisplayName());
			node.put("path", t.getConfigFile().toString());
			node.put("exists", Files.exists(t.getConfigFile()));
		}

		response.setContentType("application/json; charset=UTF-8");
		// Overlay directories appear and disappear as servers start; never cache.
		response.setHeader("Cache-Control", "no-cache");
		response.getWriter().write(mapper.writeValueAsString(result));
		response.getWriter().flush();
	}
}
