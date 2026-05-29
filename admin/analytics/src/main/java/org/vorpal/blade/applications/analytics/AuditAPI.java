package org.vorpal.blade.applications.analytics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for the Analytics admin app.
///
/// Phase 1 (this release): read-only audit of the WebLogic resources the
/// analytics pipeline depends on. Future phases add live operational
/// insights (queue depth, MDB state) and write-side resource creation.
@Path("/")
@Tag(name = "Analytics", description = "Analytics pipeline audit and operations")
public class AuditAPI {

	private static final Logger log = Logger.getLogger(AuditAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Path("/audit")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Audit WebLogic resources required by the analytics pipeline.")
	public Response audit() {
		try {
			List<WlsResourceAudit.Finding> findings = WlsResourceAudit.run();
			ObjectNode root = mapper.createObjectNode();
			ObjectNode expected = mapper.createObjectNode();
			expected.put("connectionFactoryJndi", WlsResourceAudit.EXPECTED_CONNECTION_FACTORY_JNDI);
			expected.put("queueJndi", WlsResourceAudit.EXPECTED_QUEUE_JNDI);
			expected.put("dataSourceJndi", WlsResourceAudit.EXPECTED_DATASOURCE_JNDI);
			root.set("expected", expected);

			ArrayNode arr = mapper.createArrayNode();
			boolean allPresent = true;
			for (WlsResourceAudit.Finding f : findings) {
				ObjectNode n = mapper.createObjectNode();
				n.put("key", f.key);
				n.put("label", f.label);
				n.put("present", f.present);
				n.put("detail", f.detail);
				arr.add(n);
				if (!f.present) {
					allPresent = false;
				}
			}
			root.set("findings", arr);
			root.put("ready", allPresent);
			return Response.ok(root.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	private static Response error(Exception e) {
		log.log(Level.WARNING, "AuditAPI: request failed", e);
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		ObjectNode n = mapper.createObjectNode();
		n.put("error", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "(no message)" : e.getMessage()));
		n.put("stacktrace", sw.toString());
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.APPLICATION_JSON)
				.entity(n.toString())
				.build();
	}
}
