package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.vorpal.blade.framework.io.VersionedFileStore;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The saved-flow library behind the editor's file browser: list, open, save
/// and delete named FSMAR configurations under `_flows/`.
///
/// This is the counterpart to [FsmarPublishServlet], and the split is the point.
/// Publishing writes the file the router reads and changes how live calls route;
/// saving here only parks a configuration under a name. So the file browser can
/// show a dozen flows without any of them being live, and "open demo1.json,
/// publish it" stays two deliberate steps.
///
/// Saves go through the same [VersionedFileStore] the Configurator and the
/// publish path use, so overwriting `demo1.json` leaves the previous content in
/// `.versions/` rather than gone. Names are validated by [FlowFiles#resolve],
/// never concatenated.
@WebServlet("/fsmarFiles")
public class FsmarFilesServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final VersionedFileStore store = new VersionedFileStore();

	/// With no `name`, the library listing as JSON. With `name=<file>.json`, that
	/// flow's content, so the editor can open it the same way it loads the live
	/// configuration.
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String name = request.getParameter("name");
		if (name == null) {
			ObjectNode result = mapper.createObjectNode();
			result.put("directory", FlowFiles.FLOWS_DIR.toAbsolutePath().toString());
			ArrayNode files = result.putArray("files");
			for (FlowFiles.Entry entry : FlowFiles.list()) {
				ObjectNode node = files.addObject();
				node.put("name", entry.getName());
				node.put("bytes", entry.getBytes());
				node.put("modified", entry.getModified());
			}
			response.setContentType("application/json; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.write(mapper.writeValueAsString(result));
			out.flush();
			return;
		}

		Path path = FlowFiles.resolve(name);
		if (path == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, badName(name));
			return;
		}
		if (!Files.exists(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND,
					"No saved flow named '" + name + "' in " + FlowFiles.FLOWS_DIR.toAbsolutePath());
			return;
		}
		response.setContentType("application/json; charset=UTF-8");
		response.getOutputStream().write(Files.readAllBytes(path));
		response.getOutputStream().flush();
	}

	/// Saves `json` as `name` in the library, creating the directory on first
	/// use. The content is re-serialized through Jackson first, so a malformed
	/// document is refused with its parse error instead of being written, and
	/// every saved file is pretty-printed the same way.
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String name = request.getParameter("name");
		Path path = FlowFiles.resolve(name);
		if (path == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, badName(name));
			return;
		}

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
			FlowFiles.ensureDirectory();
			String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
			store.write(path, pretty);

			ObjectNode result = mapper.createObjectNode();
			result.put("name", path.getFileName().toString());
			result.put("path", path.toAbsolutePath().toString());
			result.put("bytes", pretty.getBytes(StandardCharsets.UTF_8).length);

			response.setContentType("application/json; charset=UTF-8");
			PrintWriter out = response.getWriter();
			out.write(mapper.writeValueAsString(result));
			out.flush();
		} catch (IOException e) {
			throw new ServletException("Saving flow '" + name + "' failed: " + e.getMessage(), e);
		}
	}

	/// Removes a saved flow. The versions kept by [VersionedFileStore] stay
	/// behind, so a delete is recoverable by someone with shell access — but the
	/// browser treats it as gone, which is why the dialog confirms first.
	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String name = request.getParameter("name");
		Path path = FlowFiles.resolve(name);
		if (path == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, badName(name));
			return;
		}
		if (!Files.deleteIfExists(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "No saved flow named '" + name + "'");
			return;
		}
		response.setStatus(HttpServletResponse.SC_NO_CONTENT);
	}

	/// One message for every rejected name: the rules are short enough to state
	/// in full, which beats making someone guess which character was the problem.
	private String badName(String requested) {
		return "Invalid flow name '" + requested + "'. Use letters, digits, dot, dash or underscore, "
				+ "ending in .json (for example demo1.json), at most " + FlowFiles.MAX_NAME + " characters.";
	}
}
