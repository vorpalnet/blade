package org.vorpal.blade.applications.console.tuning;

import java.util.Set;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
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

/// Administrative drain, per engine — the GUI face of the options app's Drain
/// MBean (`vorpal.blade:Name=<app>,Type=Drain[,Cluster=…]`, one per engine
/// JVM, federated onto the DomainRuntime server with a `Location=<server>`
/// key). Draining makes that node answer its OPTIONS health pings
/// `503 "Draining"`, so a SIP-aware load balancer stops offering it NEW calls;
/// established dialogs continue (state is cluster-replicated). The flag is
/// runtime state, not configuration: it resets when the server restarts.
///
/// Alongside the flag, each row carries the "is it quiet yet?" numbers from
/// OCCAS's own `SipServerRuntime` MBean — active session counts and the
/// period SIP throughput. Sessions being nonzero does NOT block a restart
/// (dialogs fail over via the replicated state tier); throughput at zero is
/// the practical safe-to-bounce signal.
///
/// No compile-time dependency on the options app: everything is reached by
/// ObjectName and attribute name over JMX.
@Path("/servers")
@Tag(name = "Drain", description = "Administrative drain: take an engine out of load-balancer rotation")
public class ServerDrain {

	private static final ObjectMapper mapper = new ObjectMapper();

	/// The options app's drain MBeans, wherever deployed (any app name, any
	/// cluster, any node).
	static final String DRAIN_PATTERN = "vorpal.blade:Name=*,Type=Drain,*";

	/// OCCAS's per-server SIP runtime (session counts, period throughput).
	static final String SIP_RUNTIME_PATTERN = "com.bea:Type=SipServerRuntime,*";

	@GET
	@Path("/drain")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Per-server drain state plus the SIP activity numbers that say when a drained node is quiet")
	public Response status() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			return Response.ok(collect(dr, adminName(dr)).toString()).build();
		} catch (Exception e) {
			return Response.serverError().entity(error(e)).build();
		}
	}

	@POST
	@Path("/{name}/drain")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Drain a server: its OPTIONS health pings answer 503 Draining until resumed or restarted")
	public Response drain(@PathParam("name") String name) {
		return setDrained(name, true);
	}

	@POST
	@Path("/{name}/resume")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Resume a drained server: OPTIONS answers 200 again; the load balancer restores it on its next ping")
	public Response resume(@PathParam("name") String name) {
		return setDrained(name, false);
	}

	private Response setDrained(String name, boolean drained) {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName on = findDrainBean(dr, name, adminName(dr));
			if (on == null) {
				return Response.status(Response.Status.NOT_FOUND)
						.entity("{\"error\":\"No drain MBean on server '" + esc(name)
								+ "' — is the options app deployed there?\"}")
						.build();
			}
			dr.setAttribute(on, new Attribute("Drained", drained));
			ObjectNode result = mapper.createObjectNode();
			result.put("success", true);
			result.put("server", name);
			result.put("drained", drained);
			return Response.ok(result.toString()).build();
		} catch (Exception e) {
			return Response.serverError().entity(error(e)).build();
		}
	}

	// ----- the JMX walks, static and container-free for the unit tests -----

	/// One row per server that has a drain MBean, decorated with that node's
	/// SIP activity when OCCAS publishes it. Rows keyed and sorted by server.
	static ArrayNode collect(MBeanServer dr, String adminName) {
		java.util.Map<String, ObjectNode> byServer = new java.util.TreeMap<>();

		try {
			Set<ObjectName> drains = dr.queryNames(new ObjectName(DRAIN_PATTERN), null);
			for (ObjectName on : drains) {
				String server = serverOf(on, adminName);
				ObjectNode n = mapper.createObjectNode();
				n.put("server", server);
				n.put("drained", bool(dr, on, "Drained"));
				n.put("drainedSinceMillis", lng(dr, on, "DrainedSinceMillis"));
				byServer.put(server, n);
			}
		} catch (Exception ignore) {
			// no drain MBeans (options app absent) — empty result, not an error
		}

		// SIP activity: OCCAS's SipServerRuntime per engine, standard WLS
		// runtime naming (Type=SipServerRuntime). Attribute names come from
		// Oracle's own BeanInfo (wlss-mbeaninfo.jar); every read fails soft to
		// -1, so a renamed attribute degrades to "n/a", never to an error.
		try {
			Set<ObjectName> runtimes = dr.queryNames(new ObjectName(SIP_RUNTIME_PATTERN), null);
			for (ObjectName on : runtimes) {
				String server = serverOf(on, adminName);
				ObjectNode n = byServer.get(server);
				if (n == null) {
					continue; // activity for a server with no drain control
				}
				n.put("activeAppSessions", lng(dr, on, "ActiveServerAppSessionCount"));
				n.put("activeSipSessions", lng(dr, on, "ActiveServerSipSessionCount"));
				n.put("sipThroughput", lng(dr, on, "PeriodCountSipThroughput"));
			}
		} catch (Exception ignore) {
			// counts stay absent; the UI shows n/a
		}

		ArrayNode result = mapper.createArrayNode();
		for (ObjectNode n : byServer.values()) {
			result.add(n);
		}
		return result;
	}

	/// The drain MBean whose Location says it lives on `server` (the
	/// AdminServer's own, if any, carries no Location key).
	static ObjectName findDrainBean(MBeanServer dr, String server, String adminName) throws Exception {
		for (ObjectName on : dr.queryNames(new ObjectName(DRAIN_PATTERN), null)) {
			if (server.equals(serverOf(on, adminName))) {
				return on;
			}
		}
		return null;
	}

	/// The server a federated ObjectName belongs to: its `Location` key, or
	/// the AdminServer for a local (unfederated) name.
	static String serverOf(ObjectName on, String adminName) {
		String loc = on.getKeyProperty("Location");
		return (loc != null && !loc.isEmpty()) ? loc : adminName;
	}

	private static String adminName(MBeanServer dr) {
		String adminName = System.getProperty("weblogic.Name", "AdminServer");
		try {
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) dr.getAttribute(service, "DomainConfiguration");
			Object an = dr.getAttribute(domainConfig, "AdminServerName");
			if (an != null && !an.toString().isEmpty()) {
				adminName = an.toString();
			}
		} catch (Exception ignore) {
		}
		return adminName;
	}

	private static boolean bool(MBeanServer mbs, ObjectName on, String name) {
		try {
			return Boolean.TRUE.equals(mbs.getAttribute(on, name));
		} catch (Exception e) {
			return false;
		}
	}

	private static long lng(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v instanceof Number ? ((Number) v).longValue() : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	private static String esc(String s) {
		return s.replace("\"", "\\\"");
	}

	private static String error(Exception e) {
		return "{\"error\":\"" + esc(String.valueOf(e.getMessage() != null ? e.getMessage() : e)) + "\"}";
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException {
			super();
		}
	}
}
