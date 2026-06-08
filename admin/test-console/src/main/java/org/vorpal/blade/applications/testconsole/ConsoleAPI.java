package org.vorpal.blade.applications.testconsole;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Cluster-wide control surface for the BLADE test apps. Discovers every
/// node's `vorpal.blade:Type=TesterControl` MBean over the federated
/// DomainRuntime MBeanServer (the admin tier never uses REST inward), reads
/// each node's load status and per-scenario metrics report, and fans
/// start/stop/reset commands out to every matching node.
///
/// On the DomainRuntime MBeanServer, managed-server MBeans carry an added
/// `Location=<server>` key; the AdminServer's own MBeans have none.
@javax.ws.rs.Path("/")
public class ConsoleAPI {

	private static final Logger logger = Logger.getLogger(ConsoleAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	/// AutoCloseable InitialContext for try-with-resources.
	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException {
			super();
		}
	}

	private static class Node {
		final ObjectName objectName;
		final String app;
		final String server;

		Node(ObjectName objectName) {
			this.objectName = objectName;
			this.app = objectName.getKeyProperty("Name");
			String location = objectName.getKeyProperty("Location");
			this.server = (location != null) ? location : "AdminServer";
		}
	}

	private List<Node> discover(MBeanServer mbs, String app, String server) throws Exception {
		List<Node> nodes = new ArrayList<>();
		ObjectName pattern = new ObjectName("vorpal.blade:Type=TesterControl,*");
		for (ObjectInstance instance : mbs.queryMBeans(pattern, null)) {
			Node node = new Node(instance.getObjectName());
			if (app != null && !app.isEmpty() && !app.equals(node.app)) continue;
			if (server != null && !server.isEmpty() && !server.equals(node.server)) continue;
			nodes.add(node);
		}
		nodes.sort((a, b) -> {
			int byApp = a.app.compareToIgnoreCase(b.app);
			return (byApp != 0) ? byApp : a.server.compareToIgnoreCase(b.server);
		});
		return nodes;
	}

	/// Every tester node in the domain, with its live load status and
	/// per-scenario metrics report.
	@GET
	@javax.ws.rs.Path("/cluster")
	@Produces(MediaType.APPLICATION_JSON)
	public Response cluster(@QueryParam("app") String app) {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			ArrayNode out = mapper.createArrayNode();
			for (Node node : discover(mbs, app, null)) {
				ObjectNode entry = out.addObject();
				entry.put("app", node.app);
				entry.put("server", node.server);
				entry.set("status", readJsonAttribute(mbs, node, "StatusJson"));
				entry.set("report", readJsonAttribute(mbs, node, "ReportJson"));
			}
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Starts a load run on every matching node. The body is a LoadRequest
	/// JSON document, forwarded verbatim to each node's `startLoad`.
	@POST
	@javax.ws.rs.Path("/start")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response start(@QueryParam("app") String app, @QueryParam("server") String server, String loadRequestJson) {
		return invokeAll(app, server, "startLoad", loadRequestJson);
	}

	/// Stops the load run on every matching node; active calls drain.
	@POST
	@javax.ws.rs.Path("/stop")
	@Produces(MediaType.APPLICATION_JSON)
	public Response stop(@QueryParam("app") String app, @QueryParam("server") String server) {
		return invokeAll(app, server, "stopLoad", null);
	}

	/// Clears the per-scenario metrics on every matching node.
	@POST
	@javax.ws.rs.Path("/reset")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reset(@QueryParam("app") String app, @QueryParam("server") String server) {
		return invokeAll(app, server, "resetMetrics", null);
	}

	private Response invokeAll(String app, String server, String operation, String jsonArg) {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			List<Node> nodes = discover(mbs, app, server);
			if (nodes.isEmpty()) {
				return Response.status(Response.Status.NOT_FOUND)
						.entity("{\"error\":\"No tester nodes found"
								+ (app != null && !app.isEmpty() ? " for app '" + app + "'" : "") + "\"}")
						.build();
			}

			ArrayNode out = mapper.createArrayNode();
			for (Node node : nodes) {
				ObjectNode entry = out.addObject();
				entry.put("app", node.app);
				entry.put("server", node.server);
				try {
					Object result;
					switch (operation) {
					case "startLoad":
						result = mbs.invoke(node.objectName, "startLoad", new Object[] { jsonArg },
								new String[] { String.class.getName() });
						break;
					case "stopLoad":
						result = mbs.invoke(node.objectName, "stopLoad", new Object[0], new String[0]);
						break;
					case "resetMetrics":
						mbs.invoke(node.objectName, "resetMetrics", new Object[0], new String[0]);
						result = null;
						break;
					default:
						throw new IllegalArgumentException("unknown operation: " + operation);
					}
					if (result instanceof String) {
						entry.set("status", mapper.readTree((String) result));
					} else {
						entry.put("ok", true);
					}
				} catch (Exception e) {
					logger.log(Level.WARNING, operation + " failed on " + node.objectName, e);
					entry.put("error", String.valueOf(e.getMessage()));
				}
			}
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	private com.fasterxml.jackson.databind.JsonNode readJsonAttribute(MBeanServer mbs, Node node, String attribute) {
		try {
			String json = (String) mbs.getAttribute(node.objectName, attribute);
			return (json != null) ? mapper.readTree(json) : mapper.nullNode();
		} catch (Exception e) {
			logger.log(Level.FINE, attribute + " read failed for " + node.objectName + ": " + e.getMessage());
			ObjectNode err = mapper.createObjectNode();
			err.put("error", String.valueOf(e.getMessage()));
			return err;
		}
	}

	private Response error(Exception e) {
		logger.log(Level.WARNING, "test-console request failed", e);
		return Response.serverError().entity("{\"error\":\"" + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}")
				.build();
	}
}
