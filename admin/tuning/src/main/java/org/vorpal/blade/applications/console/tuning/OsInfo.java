package org.vorpal.blade.applications.console.tuning;

import java.lang.management.ManagementFactory;
import java.util.Set;

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
 * Read-only OS / limits readout from the JVM platform OperatingSystem MBean
 * (java.lang:type=OperatingSystem + the com.sun.management extension): kernel
 * version, CPU count, load, physical/swap memory, and the process file-descriptor
 * limit (ulimit -n) + current open count.
 *
 * Rows are seeded from the ServerLifeCycleRuntimes (one per configured server,
 * with its lifecycle State), so a down engine still appears — just with no OS
 * readout attached. The platform MBean is local to each JVM: the AdminServer's
 * own is read from the platform MBean server directly; engine nodes are included
 * only if WebLogic federates their java.lang:* MBeans onto the DomainRuntime
 * server (a Location key). True sysctl tunables (somaxconn, tcp_*, fs.file-max)
 * are NOT here — JMX has no window into /proc/sys.
 */
@Path("/os")
@Tag(name = "OS", description = "OS / limits readout (kernel version, memory, file-descriptor limit)")
public class OsInfo {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String OS = "java.lang:type=OperatingSystem";

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Per-server lifecycle state, OS info, and limits")
	public Response get() {
		// Keyed by server so lifecycle rows and OS readouts merge; LinkedHashMap
		// keeps the lifecycle ordering (admin first, then engines).
		java.util.Map<String, ObjectNode> byServer = new java.util.LinkedHashMap<>();
		String adminName = System.getProperty("weblogic.Name", "AdminServer");

		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");

			try {
				ObjectName domainConfig = (ObjectName) dr.getAttribute(service, "DomainConfiguration");
				Object an = dr.getAttribute(domainConfig, "AdminServerName");
				if (an != null && !an.toString().isEmpty()) adminName = an.toString();
			} catch (Exception ignore) {
			}

			// Lifecycle first: one row per configured server, even the down ones
			// (a down engine has no federated OS MBean and would otherwise vanish).
			try {
				ObjectName domainRuntime = (ObjectName) dr.getAttribute(service, "DomainRuntime");
				ObjectName[] lifecycles = (ObjectName[]) dr.getAttribute(domainRuntime, "ServerLifeCycleRuntimes");
				if (lifecycles != null) {
					for (ObjectName lc : lifecycles) {
						String name = str(dr, lc, "Name");
						if (name.isEmpty()) continue;
						ObjectNode n = mapper.createObjectNode();
						n.put("server", name);
						n.put("state", str(dr, lc, "State"));
						n.put("adminServer", name.equals(adminName));
						byServer.put(name, n);
					}
				}
			} catch (Exception ignore) {
			}

			// AdminServer OS readout (this JVM) via the platform MBean server.
			try {
				MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
				readOsInto(platform, new ObjectName(OS), row(byServer, adminName, adminName));
			} catch (Exception ignore) {
			}

			// Engine OS readouts via DomainRuntime federation (Location-keyed).
			// The federation can surface the AdminServer's own OS MBean too; the
			// ramTotalMB presence check keeps the platform readout authoritative.
			try {
				Set<ObjectName> names = dr.queryNames(new ObjectName(OS + ",*"), null);
				for (ObjectName on : names) {
					String loc = on.getKeyProperty("Location");
					String server = (loc != null && !loc.isEmpty()) ? loc : adminName;
					ObjectNode n = row(byServer, server, adminName);
					if (!n.has("ramTotalMB")) readOsInto(dr, on, n);
				}
			} catch (Exception ignore) {
			}
		} catch (Exception ignore) {
		}

		ArrayNode result = mapper.createArrayNode();
		for (ObjectNode n : byServer.values()) {
			result.add(n);
		}
		return Response.ok(result.toString()).build();
	}

	/** The (possibly new) row for a server, so OS readouts merge onto lifecycle rows. */
	private ObjectNode row(java.util.Map<String, ObjectNode> byServer, String server, String adminName) {
		ObjectNode n = byServer.get(server);
		if (n == null) {
			n = mapper.createObjectNode();
			n.put("server", server);
			n.put("adminServer", server.equals(adminName));
			byServer.put(server, n);
		}
		return n;
	}

	private void readOsInto(MBeanServer mbs, ObjectName on, ObjectNode n) {
		n.put("name", str(mbs, on, "Name"));
		n.put("arch", str(mbs, on, "Arch"));
		n.put("kernel", str(mbs, on, "Version"));               // kernel release string on Linux
		n.put("processors", lng(mbs, on, "AvailableProcessors"));
		n.put("loadAverage", dbl(mbs, on, "SystemLoadAverage"));
		n.put("ramTotalMB", toMB(lng(mbs, on, "TotalPhysicalMemorySize")));
		n.put("ramFreeMB", toMB(lng(mbs, on, "FreePhysicalMemorySize")));
		n.put("swapTotalMB", toMB(lng(mbs, on, "TotalSwapSpaceSize")));
		n.put("swapFreeMB", toMB(lng(mbs, on, "FreeSwapSpaceSize")));
		n.put("maxFds", lng(mbs, on, "MaxFileDescriptorCount")); // == ulimit -n for the process
		n.put("openFds", lng(mbs, on, "OpenFileDescriptorCount"));
		n.put("processCpuLoad", dbl(mbs, on, "ProcessCpuLoad"));
		n.put("systemCpuLoad", dbl(mbs, on, "SystemCpuLoad"));
	}

	private long toMB(long bytes) { return bytes < 0 ? -1 : bytes / (1024L * 1024L); }

	private String str(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private long lng(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v instanceof Number ? ((Number) v).longValue() : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	private double dbl(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v instanceof Number ? ((Number) v).doubleValue() : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
