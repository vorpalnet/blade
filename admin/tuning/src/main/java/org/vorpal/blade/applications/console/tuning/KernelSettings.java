package org.vorpal.blade.applications.console.tuning;

import java.util.Set;
import java.util.TreeSet;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v3.probe.KernelProbeMXBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for the Tier-1 kernel-params readout. Fans out over the federated
 * DomainRuntime to every node's {@code vorpal.blade:Type=KernelProbe} MBean
 * (registered by the framework library on each JVM) and returns each node's
 * read-only /proc/sys + /sys + /proc/self/limits readings. Same federation
 * pattern as the LogReader. No sudo, no shelling out — the reads happen on each
 * node via plain file I/O.
 */
@Path("/kernel")
@Tag(name = "Kernel", description = "Read-only kernel tunables (sysctl / limits) per node")
public class KernelSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Per-node kernel tunables from the KernelProbe MBeans")
	public Response get() {
		ArrayNode result = mapper.createArrayNode();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			// Wildcard matches the AdminServer's bare name + remote nodes' Location-keyed names.
			Set<ObjectName> names = mbs.queryNames(new ObjectName("vorpal.blade:Type=KernelProbe,*"), null);
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (ObjectName on : new TreeSet<>(names)) {
				// Server name = Location (remote) or Name (local). Federation can list
				// the AdminServer twice (bare + Location-keyed), so dedup before invoking.
				String loc = on.getKeyProperty("Location");
				String server = (loc != null && !loc.isEmpty()) ? loc : on.getKeyProperty("Name");
				if (server == null || !seen.add(server)) continue;
				try {
					KernelProbeMXBean probe = JMX.newMXBeanProxy(mbs, on, KernelProbeMXBean.class);
					ObjectNode node = (ObjectNode) mapper.readTree(probe.readJson());
					node.put("server", server);
					result.add(node);
				} catch (Exception e) {
					ObjectNode err = result.addObject();
					err.put("server", server);
					err.put("error", String.valueOf(e.getMessage()));
				}
			}
			return Response.ok(mapper.writeValueAsString(result)).build();
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage().replace("\\", "\\\\").replace("\"", "\\\"") : "error";
			return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
		}
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
