package org.vorpal.blade.applications.console.tuning;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for WebLogic server tuning settings.
 *
 * Manages per-server settings: thread pool sizing, socket readers,
 * message size limits, and connection timeouts.
 */
@Path("/api/v1/server-tuning")
@Tag(name = "Server Tuning", description = "Per-server thread pool, network, and timeout settings")
public class ServerTuningSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get tuning settings for all servers")
	public Response getAll() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName domainConfig = new ObjectName("com.bea:Name=DomainConfiguration,Type=Domain");
			ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");

			ArrayNode result = mapper.createArrayNode();
			for (ObjectName server : servers) {
				result.add(readServerTuning(mbs, server));
			}
			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Path("/{serverName}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update tuning settings for a specific server")
	public Response update(@PathParam("serverName") String serverName, String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);
			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");

			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");
			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName serverConfig = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");

				setIfPresent(editMbs, serverConfig, "SelfTuningThreadPoolSizeMin", input, "threadPoolMin", Integer.class);
				setIfPresent(editMbs, serverConfig, "SocketReaders", input, "socketReaders", Integer.class);
				setIfPresent(editMbs, serverConfig, "MaxMessageSize", input, "maxMessageSize", Integer.class);
				setIfPresent(editMbs, serverConfig, "CompleteMessageTimeout", input, "completeMessageTimeout", Integer.class);
				setIfPresent(editMbs, serverConfig, "IdleConnectionTimeout", input, "idleConnectionTimeout", Integer.class);

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok("{\"success\":true}").build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	private ObjectNode readServerTuning(MBeanServer mbs, ObjectName server) throws Exception {
		ObjectNode node = mapper.createObjectNode();
		node.put("server", attr(mbs, server, "Name", ""));
		node.put("listenPort", attrInt(mbs, server, "ListenPort", 0));
		node.put("listenAddress", attr(mbs, server, "ListenAddress", ""));
		node.put("threadPoolMin", attrInt(mbs, server, "SelfTuningThreadPoolSizeMin", 0));
		node.put("socketReaders", attrInt(mbs, server, "SocketReaders", 0));
		node.put("maxMessageSize", attrInt(mbs, server, "MaxMessageSize", 0));
		node.put("completeMessageTimeout", attrInt(mbs, server, "CompleteMessageTimeout", 0));
		node.put("idleConnectionTimeout", attrInt(mbs, server, "IdleConnectionTimeout", 0));
		return node;
	}

	private String attr(MBeanServer mbs, ObjectName on, String name, String dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? val.toString() : dflt;
		} catch (Exception e) {
			return dflt;
		}
	}

	private int attrInt(MBeanServer mbs, ObjectName on, String name, int dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? ((Number) val).intValue() : dflt;
		} catch (Exception e) {
			return dflt;
		}
	}

	private void setIfPresent(MBeanServer mbs, ObjectName on, String mattr, ObjectNode input, String field, Class<?> type)
			throws Exception {
		if (input.has(field) && !input.get(field).isNull()) {
			Object value;
			if (type == Integer.class) {
				value = input.get(field).asInt();
			} else {
				value = input.get(field).asText();
			}
			mbs.setAttribute(on, new javax.management.Attribute(mattr, value));
		}
	}

	private Response errorResponse(Exception e) {
		String msg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error";
		return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
