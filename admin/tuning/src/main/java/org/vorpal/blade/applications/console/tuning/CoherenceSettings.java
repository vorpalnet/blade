package org.vorpal.blade.applications.console.tuning;

import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
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
 * REST API for the Coherence cluster networking config — the
 * {@code <coherence-cluster-params>} of each {@code CoherenceClusterSystemResource}
 * descriptor (config.xml -> coherence/&lt;name&gt;/&lt;name&gt;.xml): clustering mode
 * (unicast / multicast), listen addresses and ports, TTL, well-known addresses.
 *
 * These are WLS config MBeans (CoherenceClusterSystemResource ->
 * CoherenceClusterResource -> CoherenceClusterParams), so reads/writes go through
 * the same DomainConfiguration / edit-session path as the other Tuning sections.
 * Changes take effect on the next engine-tier restart.
 */
@Path("/coherence")
@Tag(name = "Coherence", description = "Coherence cluster networking (unicast/multicast, address, port)")
public class CoherenceSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	// Scalar attributes on CoherenceClusterParams we surface as form fields.
	private static final String[] PARAMS = {
			"ClusteringMode", "TimeToLive",
			"UnicastListenAddress", "UnicastListenPort", "UnicastPortAutoAdjust",
			"MulticastListenAddress", "MulticastListenPort",
			"ClusterListenPort", "SecurityFrameworkEnabled"
	};

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get Coherence cluster networking config for each Coherence system resource")
	public Response get() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			ObjectName[] resources = (ObjectName[]) mbs.getAttribute(domainConfig, "CoherenceClusterSystemResources");

			ArrayNode result = mapper.createArrayNode();
			if (resources != null) {
				for (ObjectName res : resources) {
					ObjectNode rn = result.addObject();
					try {
						rn.put("name", str(mbs, res, "Name"));
						ObjectName params = paramsOf(mbs, res);
						rn.put("found", params != null);
						if (params != null) {
							for (String p : PARAMS) {
								try {
									Object v = mbs.getAttribute(params, p);
									rn.put(p, v == null ? "" : v.toString());
								} catch (Exception ignore) {
									// attribute not present on this WLS version — skip
								}
							}
							ArrayNode wka = rn.putArray("wellKnownAddresses");
							try {
								ObjectName[] addrs = (ObjectName[]) mbs.getAttribute(params, "WellKnownAddresses");
								if (addrs != null) {
									for (ObjectName a : addrs) {
										ObjectNode an = wka.addObject();
										an.put("address", str(mbs, a, "ListenAddress"));
										an.put("port", str(mbs, a, "ListenPort"));
									}
								}
							} catch (Exception ignore) {
							}
						}
					} catch (Exception e) {
						rn.put("error", String.valueOf(e.getMessage()));
					}
				}
			}
			return Response.ok(mapper.writeValueAsString(result)).build();
		} catch (Exception e) {
			return err(e);
		}
	}

	@PUT
	@Path("/{name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update a Coherence cluster's networking config")
	public Response set(@PathParam("name") String name, String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode in = (ObjectNode) mapper.readTree(body);

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");
			editMbs.invoke(editConfigManager, "startEdit", new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domainConfig = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");
				ObjectName[] resources = (ObjectName[]) editMbs.getAttribute(domainConfig, "CoherenceClusterSystemResources");

				ObjectName target = null;
				if (resources != null) {
					for (ObjectName res : resources) {
						if (name.equals(str(editMbs, res, "Name"))) { target = res; break; }
					}
				}
				if (target == null) {
					throw new IllegalArgumentException("No Coherence cluster system resource named '" + name + "'");
				}
				ObjectName params = paramsOf(editMbs, target);
				if (params == null) {
					throw new IllegalStateException("Coherence cluster params not available for '" + name + "'");
				}

				MBeanInfo info = editMbs.getMBeanInfo(params);
				for (String p : PARAMS) {
					if (in.has(p)) {
						editMbs.setAttribute(params, new Attribute(p, coerce(in.get(p).asText(), typeOf(info, p))));
					}
				}

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate", new Object[]{120000L}, new String[]{"long"});
				return Response.ok("{\"success\":true,\"restartRequired\":true}").build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}
		} catch (Exception e) {
			return err(e);
		}
	}

	/** CoherenceClusterSystemResource -> CoherenceClusterResource -> CoherenceClusterParams. */
	private ObjectName paramsOf(MBeanServer mbs, ObjectName resource) throws Exception {
		ObjectName cohResource = (ObjectName) mbs.getAttribute(resource, "CoherenceClusterResource");
		if (cohResource == null) return null;
		return (ObjectName) mbs.getAttribute(cohResource, "CoherenceClusterParams");
	}

	private String typeOf(MBeanInfo info, String attr) {
		for (MBeanAttributeInfo a : info.getAttributes()) {
			if (a.getName().equals(attr)) return a.getType();
		}
		return "java.lang.String";
	}

	private Object coerce(String v, String type) {
		try {
			switch (type) {
			case "int": case "java.lang.Integer": return Integer.valueOf(v.trim());
			case "long": case "java.lang.Long": return Long.valueOf(v.trim());
			case "boolean": case "java.lang.Boolean": return Boolean.valueOf(v.trim());
			default: return v;
			}
		} catch (NumberFormatException e) {
			return v;
		}
	}

	private String str(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private Response err(Exception e) {
		String msg = e.getMessage() != null ? e.getMessage().replace("\\", "\\\\").replace("\"", "\\\"") : "Unknown error";
		return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
