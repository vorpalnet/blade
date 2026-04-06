package org.vorpal.blade.applications.console.tuning;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
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

/**
 * REST API for reading Node Manager configuration.
 *
 * Node Manager is responsible for starting, stopping, and restarting
 * WebLogic server instances. JVM settings configured through ServerStartMBean
 * only take effect when servers are started through Node Manager.
 *
 * This API exposes the Node Manager configuration for each machine in the
 * domain so administrators can verify that Node Manager is properly configured
 * before relying on JVM setting changes.
 */
@Path("/api/v1/nodemanager")
@Tag(name = "Node Manager", description = "Node Manager configuration and status")
public class NodeManagerSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get Node Manager configuration for all machines")
	public Response getNodeManagerSettings() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName domainConfig = new ObjectName("com.bea:Name=DomainConfiguration,Type=Domain");
			ObjectName[] machines = (ObjectName[]) mbs.getAttribute(domainConfig, "Machines");

			ArrayNode result = mapper.createArrayNode();

			if (machines != null) {
				for (ObjectName machine : machines) {
					String machineName = (String) mbs.getAttribute(machine, "Name");
					ObjectName nm = (ObjectName) mbs.getAttribute(machine, "NodeManager");

					ObjectNode node = mapper.createObjectNode();
					node.put("machine", machineName);

					if (nm != null) {
						Object listenAddress = mbs.getAttribute(nm, "ListenAddress");
						Object listenPort = mbs.getAttribute(nm, "ListenPort");
						Object nmType = mbs.getAttribute(nm, "NMType");
						Object nmHome = mbs.getAttribute(nm, "NodeManagerHome");
						Object debugEnabled = mbs.getAttribute(nm, "DebugEnabled");

						node.put("listenAddress", listenAddress != null ? listenAddress.toString() : "");
						node.put("listenPort", listenPort != null ? ((Integer) listenPort) : 5556);
						node.put("type", nmType != null ? nmType.toString() : "SSL");
						node.put("nodeManagerHome", nmHome != null ? nmHome.toString() : "");
						node.put("debugEnabled", debugEnabled != null && (Boolean) debugEnabled);
						node.put("configured", true);
					} else {
						node.put("configured", false);
					}

					// Find which servers are assigned to this machine
					ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");
					ArrayNode serverList = mapper.createArrayNode();
					if (servers != null) {
						for (ObjectName server : servers) {
							ObjectName serverMachine = (ObjectName) mbs.getAttribute(server, "Machine");
							if (serverMachine != null) {
								String serverMachineName = (String) mbs.getAttribute(serverMachine, "Name");
								if (machineName.equals(serverMachineName)) {
									serverList.add((String) mbs.getAttribute(server, "Name"));
								}
							}
						}
					}
					node.set("servers", serverList);

					result.add(node);
				}
			}

			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return Response.serverError()
					.entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
					.build();
		}
	}

	private String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static class CloseableContext extends javax.naming.InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException {
			super();
		}
	}
}
