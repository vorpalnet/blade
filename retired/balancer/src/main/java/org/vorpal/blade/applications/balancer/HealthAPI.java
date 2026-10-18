package org.vorpal.blade.applications.balancer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST endpoints for the Balancer Health admin app.
///
/// The Proxy Balancer service registers an EndpointHealth MBean on every
/// engine node it runs on
/// (`vorpal.blade:Name=proxy-balancer,Type=EndpointHealth[,Cluster=...]`),
/// each publishing THAT node's independent health view — its own OPTIONS ping
/// results and its own passive 503 markings. Unlike the Trace app's source
/// reads (byte-identical everywhere, pinned to one node), health views
/// legitimately differ per node, so this API returns EVERY node's answer and
/// the page shows them side by side — disagreement between nodes is itself a
/// diagnostic (a network problem between one engine and one endpoint).
@javax.ws.rs.Path("/")
@Tag(name = "Balancer", description = "Per-node endpoint health of the Proxy Balancer service over JMX")
public class HealthAPI {

	private static final Logger log = Logger.getLogger(HealthAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String JMX_DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final String HEALTH_PATTERN = "vorpal.blade:Name=proxy-balancer,Type=EndpointHealth,*";

	/// Every node's health view:
	/// `{"nodes":[{"server":"engine0","health":{"pingInterval":60,"plans":{...}}}]}`.
	@GET
	@javax.ws.rs.Path("/health")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Every engine node's endpoint-health view of the Proxy Balancer.")
	public Response health() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(JMX_DOMAIN_RUNTIME);

			// one MBean per node, keyed (and sorted) by Location
			Map<String, ObjectName> byServer = new TreeMap<>();
			for (ObjectInstance inst : mbs.queryMBeans(new ObjectName(HEALTH_PATTERN), null)) {
				ObjectName on = inst.getObjectName();
				String location = on.getKeyProperty("Location");
				byServer.put(location != null ? location : "local", on);
			}

			ObjectNode out = mapper.createObjectNode();
			ArrayNode nodes = out.putArray("nodes");
			for (Map.Entry<String, ObjectName> e : byServer.entrySet()) {
				try {
					String json = (String) mbs.getAttribute(e.getValue(), "HealthJson");
					ObjectNode node = nodes.addObject();
					node.put("server", e.getKey());
					node.set("health", mapper.readTree(json));
				} catch (Exception ex) {
					// one node's bad answer must not empty the whole dashboard
					log.log(Level.FINE, "health read failed for " + e.getKey() + ": "
							+ ex.getClass().getSimpleName() + ": " + ex.getMessage());
				}
			}
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	private static Response error(Exception e) {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		log.log(Level.WARNING, "HealthAPI error", e);
		ObjectNode out = mapper.createObjectNode();
		out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.APPLICATION_JSON).entity(out.toString()).build();
	}

}
