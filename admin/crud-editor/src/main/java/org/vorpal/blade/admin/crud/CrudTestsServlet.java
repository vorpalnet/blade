package org.vorpal.blade.admin.crud;

import java.io.BufferedReader;
import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v3.crud.CrudConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// An application's saved CRUD tests ([CrudTests]), under
/// `/resources/api/tests/*`:
///
/// - `GET  …/tests/{app}`: the tests, `{}` when none.
/// - `POST …/tests/{app}`: replace them with the body.
/// - `POST …/tests/{app}/run?file=`: run the tests recorded on that file
///   against the draft in the body (the preview's draft fields: what the
///   file runs) and return `[{name, pass, why}]`.
///
/// `{app}` is `crud` for the CRUD service.
public class CrudTestsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			String[] path = path(req);
			if (path.length != 1 || !AppCrudSection.APP_NAME.matcher(path[0]).matches()) {
				write(resp, 404, error("unknown endpoint"));
				return;
			}
			// The version a later save must name (If-Match), so one page's
			// save cannot drop a test another page saved meanwhile.
			// Read first: a file that does not parse gets no version, so no
			// page can save over it.
			// Content and version under the save lock, so a save between the two
			// reads cannot tag the old tests with the new version.
			JsonNode tests;
			String version;
			synchronized (org.vorpal.blade.framework.v3.configuration.FileVersion.lock(CrudTests.file(path[0]))) {
				tests = CrudTests.load(path[0]);
				version = org.vorpal.blade.framework.v3.configuration.FileVersion.of(CrudTests.file(path[0]));
			}
			resp.setHeader("X-Version", version);
			write(resp, 200, tests);
		} catch (Exception e) {
			write(resp, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			String[] path = path(req);
			if (path.length < 1 || !AppCrudSection.APP_NAME.matcher(path[0]).matches()) {
				write(resp, 404, error("unknown endpoint"));
				return;
			}
			String body = body(req);
			if (path.length == 1) {
				JsonNode tests = MAPPER.readTree(body);
				if (!(tests instanceof ObjectNode)) {
					write(resp, 400, error("tests must be an object keyed by test name"));
					return;
				}
				java.nio.file.Path file = CrudTests.file(path[0]);
				String version;
				// The tests replace the file whole, so the page must name the
				// version it read: without one it may never have read them.
				String named = req.getHeader("If-Match");
				if (named == null || named.isEmpty() || "*".equals(named)) {
					write(resp, 409, error("the saved tests were not read first; reload them and save again"));
					return;
				}
				synchronized (org.vorpal.blade.framework.v3.configuration.FileVersion.lock(file)) {
					if (!org.vorpal.blade.framework.v3.configuration.FileVersion.current(req, file)) {
						write(resp, 409, error("the tests changed since this page read them; reload them and save again"));
						return;
					}
					CrudTests.save(path[0], (ObjectNode) tests);
					version = org.vorpal.blade.framework.v3.configuration.FileVersion.of(file);
				}
				write(resp, 200, MAPPER.createObjectNode().put("saved", tests.size()).put("version", version));
			} else if (path.length == 2 && path[1].equals("run")) {
				PreviewServlet.DraftFields draft = MAPPER.readValue(body, PreviewServlet.DraftFields.class);
				CrudConfiguration config = new CrudConfiguration();
				PreviewServlet.applyDraft(config, draft);
				write(resp, 200, CrudTests.run(config, CrudTests.load(path[0]), req.getParameter("file")));
			} else {
				write(resp, 404, error("unknown endpoint"));
			}
		} catch (Exception e) {
			write(resp, 400, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
		}
	}

	private static String[] path(HttpServletRequest req) {
		String p = req.getPathInfo();
		if (p == null) return new String[0];
		p = p.replaceAll("^/+|/+$", "");
		return p.isEmpty() ? new String[0] : p.split("/");
	}

	private static String body(HttpServletRequest req) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = req.getReader()) {
			char[] buf = new char[4096];
			for (int n; (n = r.read(buf)) > 0;) sb.append(buf, 0, n);
		}
		return sb.toString();
	}

	private static ObjectNode error(String message) {
		return MAPPER.createObjectNode().put("error", message);
	}

	private static void write(HttpServletResponse resp, int status, JsonNode body) throws IOException {
		resp.setStatus(status);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write(MAPPER.writeValueAsString(body));
	}
}
