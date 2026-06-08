package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The Route Simulator endpoint: POST a `json` parameter of the shape
/// `{ config, request: {method, requestUri, headers}, pseudo, undeployed }`
/// and get back the full routing trace (the shared trace format — see
/// [RouteSimulator]) for the Flow editor to animate on the diagram.
///
/// All the work happens in [RouteSimulator]; this is parameter plumbing,
/// matching the sibling import/export/validate servlets.
@WebServlet("/fsmarSimulate")
public class FsmarSimulateServlet extends HttpServlet {
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

		JsonNode simRequest;
		try {
			simRequest = mapper.readTree(json);
		} catch (IOException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not valid JSON: " + e.getMessage());
			return;
		}
		if (!simRequest.path("config").isObject()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing config object");
			return;
		}

		try {
			ObjectNode trace = RouteSimulator.simulate(simRequest, mapper);
			response.setContentType("application/json; charset=UTF-8");
			PrintWriter w = response.getWriter();
			w.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
			w.flush();
		} catch (Exception e) {
			throw new ServletException("FSMAR simulation failed: " + e.getMessage(), e);
		}
	}
}
