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
 * The platform MBean is local to each JVM. The AdminServer's own is read from the
 * platform MBean server directly; engine nodes are included only if WebLogic
 * federates their java.lang:* MBeans onto the DomainRuntime server (a Location
 * key). True sysctl tunables (somaxconn, tcp_*, fs.file-max) are NOT here — JMX
 * has no window into /proc/sys.
 */
@Path("/os")
@Tag(name = "OS", description = "OS / limits readout (kernel version, memory, file-descriptor limit)")
public class OsInfo {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String OS = "java.lang:type=OperatingSystem";

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Per-server OS info and limits from the platform OperatingSystem MBean")
	public Response get() {
		ArrayNode result = mapper.createArrayNode();
		java.util.Set<String> seen = new java.util.HashSet<>();

		// AdminServer (this JVM) — always available via the platform MBean server.
		try {
			MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
			String me = System.getProperty("weblogic.Name", "AdminServer");
			result.add(readOs(platform, new ObjectName(OS), me));
			seen.add(me);
		} catch (Exception ignore) {
		}

		// Engine nodes via DomainRuntime federation. The federation can surface the
		// AdminServer's own OS MBean too (bare or Location-keyed), so dedup by name.
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			Set<ObjectName> names = dr.queryNames(new ObjectName(OS + ",*"), null);
			for (ObjectName on : names) {
				String loc = on.getKeyProperty("Location");
				String server = (loc != null && !loc.isEmpty()) ? loc
						: System.getProperty("weblogic.Name", "AdminServer");
				if (!seen.add(server)) continue; // already have this server
				result.add(readOs(dr, on, server));
			}
		} catch (Exception ignore) {
		}

		return Response.ok(result.toString()).build();
	}

	private ObjectNode readOs(MBeanServer mbs, ObjectName on, String server) {
		ObjectNode n = mapper.createObjectNode();
		n.put("server", server);
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
		return n;
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
