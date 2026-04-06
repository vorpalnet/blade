package org.vorpal.blade.applications.console.tuning;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for WLSS (OCCAS) self-tuning configuration.
 *
 * OCCAS uses WebLogic's self-tuning thread pool with specialized work managers
 * for different SIP processing functions. Each work manager has associated
 * constraints that control thread allocation:
 *
 * <ul>
 *   <li><b>Fair-share request class</b> — relative weight for CPU scheduling</li>
 *   <li><b>Min threads constraint</b> — guaranteed minimum threads</li>
 *   <li><b>Max threads constraint</b> — hard ceiling on threads</li>
 *   <li><b>Capacity constraint</b> — max queued requests before rejection</li>
 * </ul>
 *
 * Work managers by SIP function:
 * <ul>
 *   <li><b>transport</b> — SIP message I/O</li>
 *   <li><b>timer</b> — SIP timer events (retransmissions, expirations)</li>
 *   <li><b>replica.rmi</b> — Inter-server state replication via RMI</li>
 *   <li><b>replica.blocking</b> — Blocking replication operations</li>
 *   <li><b>replica.geo</b> — Geographic site replication</li>
 *   <li><b>tracing.domain</b> — Domain-wide SIP message tracing</li>
 *   <li><b>tracing.local</b> — Local SIP message tracing</li>
 *   <li><b>connect</b> — SIP connection management</li>
 *   <li><b>cleanup</b> — Session and resource cleanup</li>
 * </ul>
 */
@Path("/api/v1/work-managers")
@Tag(name = "Work Managers", description = "OCCAS thread scheduling and capacity configuration")
public class WorkManagerSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * WLSS work manager definitions. Key = work manager name,
	 * value = array of [fairShareName, minThreadsName, maxThreadsName, capacityName].
	 * null means that constraint is not used by this work manager.
	 */
	private static final Map<String, String[]> WLSS_WORK_MANAGERS = new LinkedHashMap<>();
	static {
		//                            name                  fairShare                     minThreads                       maxThreads                       capacity
		WLSS_WORK_MANAGERS.put("wlss.transport",         new String[]{"wlss.transport.fsrc",         null,                            null,                            "wlss.transport.capacity"});
		WLSS_WORK_MANAGERS.put("wlss.timer",             new String[]{"wlss.timer.fsrc",             null,                            "wlss.timer.maxthreads",         "wlss.timer.capacity"});
		WLSS_WORK_MANAGERS.put("wlss.replica.rmi",       new String[]{"wlss.replica.rmi.fsrc",       null,                            null,                            null});
		WLSS_WORK_MANAGERS.put("wlss.replica.blocking",  new String[]{"wlss.replica.blocking.fsrc",  "wlss.replica.blocking.minthreads", "wlss.replica.blocking.maxthreads", null});
		WLSS_WORK_MANAGERS.put("wlss.replica.geo",       new String[]{"wlss.replica.geo.fsrc",       null,                            "wlss.replica.geo.maxthreads",   null});
		WLSS_WORK_MANAGERS.put("wlss.tracing.domain",    new String[]{"wlss.tracing.fsrc",           "wlss.tracing.minthreads",       "wlss.tracing.maxthreads",       null});
		WLSS_WORK_MANAGERS.put("wlss.tracing.local",     new String[]{"wlss.tracing.fsrc",           "wlss.tracing.minthreads",       "wlss.tracing.maxthreads",       null});
		WLSS_WORK_MANAGERS.put("wlss.connect",           new String[]{"wlss.connect.fsrc",           "wlss.connect.minthreads",       null,                            null});
		WLSS_WORK_MANAGERS.put("wlss.cleanup",           new String[]{"wlss.cleanup.fsrc",           null,                            "wlss.cleanup.maxthreads",       "wlss.timer.capacity"});
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get all WLSS work manager and constraint configurations")
	public Response getAll() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			// Read all constraint values into maps for fast lookup
			ObjectName domainConfig = new ObjectName("com.bea:Name=DomainConfiguration,Type=Domain");
			ObjectName selfTuning = (ObjectName) mbs.getAttribute(domainConfig, "SelfTuning");

			Map<String, Integer> fairShares = readConstraints(mbs, selfTuning, "FairShareRequestClasses", "FairShare");
			Map<String, Integer> minThreads = readConstraints(mbs, selfTuning, "MinThreadsConstraints", "Count");
			Map<String, Integer> maxThreads = readConstraints(mbs, selfTuning, "MaxThreadsConstraints", "Count");
			Map<String, Integer> capacities = readConstraints(mbs, selfTuning, "Capacities", "Count");

			// Build work manager entries
			ArrayNode result = mapper.createArrayNode();
			for (Map.Entry<String, String[]> entry : WLSS_WORK_MANAGERS.entrySet()) {
				String wmName = entry.getKey();
				String[] refs = entry.getValue();

				ObjectNode wm = mapper.createObjectNode();
				wm.put("name", wmName);

				// Fair share
				if (refs[0] != null) {
					wm.put("fairShareName", refs[0]);
					wm.put("fairShare", fairShares.getOrDefault(refs[0], 0));
				}

				// Min threads
				if (refs[1] != null) {
					wm.put("minThreadsName", refs[1]);
					wm.put("minThreads", minThreads.getOrDefault(refs[1], 0));
				}

				// Max threads
				if (refs[2] != null) {
					wm.put("maxThreadsName", refs[2]);
					wm.put("maxThreads", maxThreads.getOrDefault(refs[2], 0));
				}

				// Capacity
				if (refs[3] != null) {
					wm.put("capacityName", refs[3]);
					wm.put("capacity", capacities.getOrDefault(refs[3], 0));
				}

				// Ignore stuck threads (read from work manager MBean)
				try {
					ObjectName[] workManagers = (ObjectName[]) mbs.getAttribute(selfTuning, "WorkManagers");
					for (ObjectName wmObj : workManagers) {
						if (wmName.equals(mbs.getAttribute(wmObj, "Name"))) {
							Object ist = mbs.getAttribute(wmObj, "IgnoreStuckThreads");
							wm.put("ignoreStuckThreads", ist != null && (Boolean) ist);
							break;
						}
					}
				} catch (Exception ignored) {
				}

				result.add(wm);
			}

			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update WLSS work manager constraints")
	public Response updateAll(String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ArrayNode input = (ArrayNode) mapper.readTree(body);

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName domainConfig = new ObjectName("com.bea:Name=DomainConfiguration,Type=Domain");
				ObjectName selfTuning = (ObjectName) editMbs.getAttribute(domainConfig, "SelfTuning");

				for (JsonNode wmNode : input) {
					// Update fair share
					if (wmNode.has("fairShareName") && wmNode.has("fairShare")) {
						updateConstraint(editMbs, selfTuning, "FairShareRequestClasses",
								wmNode.get("fairShareName").asText(), "FairShare", wmNode.get("fairShare").asInt());
					}

					// Update min threads
					if (wmNode.has("minThreadsName") && wmNode.has("minThreads")) {
						updateConstraint(editMbs, selfTuning, "MinThreadsConstraints",
								wmNode.get("minThreadsName").asText(), "Count", wmNode.get("minThreads").asInt());
					}

					// Update max threads
					if (wmNode.has("maxThreadsName") && wmNode.has("maxThreads")) {
						updateConstraint(editMbs, selfTuning, "MaxThreadsConstraints",
								wmNode.get("maxThreadsName").asText(), "Count", wmNode.get("maxThreads").asInt());
					}

					// Update capacity
					if (wmNode.has("capacityName") && wmNode.has("capacity")) {
						updateConstraint(editMbs, selfTuning, "Capacities",
								wmNode.get("capacityName").asText(), "Count", wmNode.get("capacity").asInt());
					}
				}

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

	private Map<String, Integer> readConstraints(MBeanServer mbs, ObjectName selfTuning,
			String attribute, String valueAttribute) throws Exception {
		Map<String, Integer> map = new LinkedHashMap<>();
		try {
			ObjectName[] constraints = (ObjectName[]) mbs.getAttribute(selfTuning, attribute);
			if (constraints != null) {
				for (ObjectName c : constraints) {
					String name = (String) mbs.getAttribute(c, "Name");
					if (name != null && name.startsWith("wlss.")) {
						Object val = mbs.getAttribute(c, valueAttribute);
						map.put(name, val != null ? ((Number) val).intValue() : 0);
					}
				}
			}
		} catch (Exception ignored) {
		}
		return map;
	}

	private void updateConstraint(MBeanServer editMbs, ObjectName selfTuning,
			String collectionAttribute, String constraintName, String valueAttribute, int value) throws Exception {
		ObjectName[] constraints = (ObjectName[]) editMbs.getAttribute(selfTuning, collectionAttribute);
		if (constraints != null) {
			for (ObjectName c : constraints) {
				String name = (String) editMbs.getAttribute(c, "Name");
				if (constraintName.equals(name)) {
					editMbs.setAttribute(c, new javax.management.Attribute(valueAttribute, value));
					return;
				}
			}
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
