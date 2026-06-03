package org.vorpal.blade.applications.analytics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.NamingException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for the Sample Data Generator — a dev/test tool that writes
/// synthetic analytics rows (modelled on the `transfer` service) directly into
/// the analytics schema. See [SampleDataGenerator].
///
/// `POST /sample/generate` with a JSON body of generation parameters; returns
/// the row counts written. Writes go through the `jdbc/BladeAnalytics` data
/// source, which must be bound on this server.
@Path("/sample")
@Tag(name = "Sample Data", description = "Generate synthetic analytics data for testing")
public class SampleDataAPI {

	private static final Logger log = Logger.getLogger(SampleDataAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	@POST
	@Path("/generate")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Generate randomized sample calls (transfers, abandons, attributes) into the analytics DB.")
	public Response generate(String body) {
		SampleDataGenerator.Params params;
		try {
			JsonNode json = (body == null || body.isBlank())
					? mapper.createObjectNode()
					: mapper.readTree(body);
			params = SampleDataGenerator.parse(json);
		} catch (Exception e) {
			return badRequest("Invalid request body: " + e.getMessage());
		}

		try {
			SampleDataGenerator.Result result = SampleDataGenerator.generate(params);
			ObjectNode root = mapper.createObjectNode();
			root.put("success", true);
			ObjectNode counts = root.putObject("counts");
			for (Map.Entry<String, Object> e : result.counts.entrySet()) {
				counts.put(e.getKey(), ((Number) e.getValue()).longValue());
			}
			return Response.ok(root.toString()).build();
		} catch (IllegalArgumentException iae) {
			return badRequest(iae.getMessage());
		} catch (NamingException ne) {
			// jdbc/BladeAnalytics data source not bound on this server.
			return badRequest(ne.getMessage());
		} catch (Exception e) {
			return error(e);
		}
	}

	private static Response badRequest(String message) {
		ObjectNode n = mapper.createObjectNode();
		n.put("error", message == null ? "(no message)" : message);
		return Response.status(Response.Status.BAD_REQUEST)
				.type(MediaType.APPLICATION_JSON).entity(n.toString()).build();
	}

	private static Response error(Exception e) {
		log.log(Level.WARNING, "SampleDataAPI: generation failed", e);
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		ObjectNode n = mapper.createObjectNode();
		n.put("error", e.getClass().getSimpleName() + ": "
				+ (e.getMessage() == null ? "(no message)" : e.getMessage()));
		n.put("stacktrace", sw.toString());
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.APPLICATION_JSON).entity(n.toString()).build();
	}
}
