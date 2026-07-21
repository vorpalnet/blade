package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
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
import com.fasterxml.jackson.databind.node.ArrayNode;

/// Exposes a gateway app's **virtual-gateway names** to the Flow editor, so an egress node can offer
/// a dropdown instead of a hand-typed `;vgw=<name>` param.
///
/// Reads the gateway app's `SettingsManager` JSON under `config/custom/vorpal/<app>.json` (the same
/// domain-root convention {@link FsmarPublishServlet} uses), falling back to the generated
/// `_samples/<app>.json.SAMPLE`, and returns `gateways[].name` as a JSON string array. This is the
/// first piece of "one app exposes config to the Flow editor" plumbing — the editor otherwise has no
/// app/target awareness.
///
/// `?app=<name>` selects which gateway app (default `gateway`, the gateway WAR's context root).
@WebServlet("/gatewayVgws")
public class GatewayVgwsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String app = request.getParameter("app");
		if (app == null || app.isEmpty()) {
			app = "gateway";
		}
		if (!app.matches("[A-Za-z0-9_.-]+")) { // guard against path traversal
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid app name");
			return;
		}

		Path live = Paths.get("config/custom/vorpal/" + app + ".json");
		Path sample = Paths.get("config/custom/vorpal/_samples/" + app + ".json.SAMPLE");
		Path path = Files.exists(live) ? live : sample;

		ArrayNode names = mapper.createArrayNode();
		if (Files.exists(path)) {
			JsonNode tree = mapper.readTree(Files.readAllBytes(path));
			JsonNode gateways = tree.get("gateways");
			if (gateways != null && gateways.isArray()) {
				for (JsonNode g : gateways) {
					JsonNode name = g.get("name");
					if (name != null && name.isTextual()) {
						names.add(name.asText());
					}
				}
			}
		}

		response.setContentType("application/json; charset=UTF-8");
		response.getWriter().write(mapper.writeValueAsString(names));
	}
}
