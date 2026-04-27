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
 * REST API for cluster and Coherence settings.
 */
@Path("/api/v1/cluster")
@Tag(name = "Cluster", description = "Cluster topology and Coherence cache settings")
public class ClusterSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get cluster and Coherence configuration")
	public Response get() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			// Canonical WLS bootstrap: DomainRuntimeServiceMBean is the
			// well-known entry point on the federated DomainRuntime
			// MBeanServer; it exposes the (named) DomainMBean as the
			// "DomainConfiguration" attribute. The previous direct lookup of
			// `com.bea:Name=DomainConfiguration,Type=Domain` is wrong — the
			// real MBean is named after the actual domain (e.g. base_domain)
			// and that lookup throws InstanceNotFoundException on WLS 14.1.1.
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");

			ObjectNode result = mapper.createObjectNode();

			// Clusters
			ObjectName[] clusters = (ObjectName[]) mbs.getAttribute(domainConfig, "Clusters");
			ArrayNode clusterArray = mapper.createArrayNode();
			if (clusters != null) {
				for (ObjectName cluster : clusters) {
					ObjectNode cn = mapper.createObjectNode();
					cn.put("name", attr(mbs, cluster, "Name"));
					cn.put("memberWarmupTimeout", attrInt(mbs, cluster, "MemberWarmupTimeoutSeconds", 0));
					cn.put("clusterMessagingMode", attr(mbs, cluster, "ClusterMessagingMode"));

					// Members
					ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");
					ArrayNode members = mapper.createArrayNode();
					if (servers != null) {
						for (ObjectName server : servers) {
							ObjectName serverCluster = attrON(mbs, server, "Cluster");
							if (serverCluster != null) {
								String clusterName = attr(mbs, cluster, "Name");
								String serverClusterName = attr(mbs, serverCluster, "Name");
								if (clusterName.equals(serverClusterName)) {
									ObjectNode member = mapper.createObjectNode();
									member.put("name", attr(mbs, server, "Name"));
									member.put("listenPort", attrInt(mbs, server, "ListenPort", 0));
									member.put("listenAddress", attr(mbs, server, "ListenAddress"));
									members.add(member);
								}
							}
						}
					}
					cn.set("members", members);
					clusterArray.add(cn);
				}
			}
			result.set("clusters", clusterArray);

			// Migratable targets
			ObjectName[] migratables = (ObjectName[]) mbs.getAttribute(domainConfig, "MigratableTargets");
			ArrayNode migArray = mapper.createArrayNode();
			if (migratables != null) {
				for (ObjectName mig : migratables) {
					ObjectNode mn = mapper.createObjectNode();
					mn.put("name", attr(mbs, mig, "Name"));
					mn.put("migrationPolicy", attr(mbs, mig, "MigrationPolicy"));

					ObjectName preferred = attrON(mbs, mig, "UserPreferredServer");
					mn.put("preferredServer", preferred != null ? attr(mbs, preferred, "Name") : "");

					ObjectName migCluster = attrON(mbs, mig, "Cluster");
					mn.put("cluster", migCluster != null ? attr(mbs, migCluster, "Name") : "");

					migArray.add(mn);
				}
			}
			result.set("migratableTargets", migArray);

			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	private String attr(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? val.toString() : "";
		} catch (Exception e) {
			return "";
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

	private ObjectName attrON(MBeanServer mbs, ObjectName on, String name) {
		try {
			return (ObjectName) mbs.getAttribute(on, name);
		} catch (Exception e) {
			return null;
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
