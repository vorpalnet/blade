package org.vorpal.blade.applications.console.tuning;

import java.util.HashSet;
import java.util.Set;

import javax.management.Attribute;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for the WebLogic domain SNMP agent and its trap destinations.
///
/// This configures the engine-side counterpart to BLADE's `Snmp` helper
/// (`framework.v2.snmp`). BLADE apps emit SIP-application traps by calling
/// `SNMPAgent.sendSipAppTrap(...)` — but a trap only leaves the box if the
/// domain SNMP agent is enabled with automatic traps on AND at least one trap
/// destination (the NMS host:port) is configured. Those live on the
/// **domain-level** `SNMPAgentMBean` (`DomainMBean.getSNMPAgent()`), which is
/// the same agent OCCAS's `SNMPAgent.isTrapEnabled()` consults — not in any
/// per-service `configuration.json`. So they belong here, edited through the
/// JMX edit tree like the other Tuning tabs, not in the Configurator.
///
/// The per-service decision "should this app emit traps, and at what severity"
/// is separate and lives in each service's logging config
/// (`LogParameters.snmpTrapLevel`).
@Path("/snmp")
@Tag(name = "SNMP", description = "WebLogic SNMP agent and trap destinations")
public class SnmpSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get the domain SNMP agent configuration and trap destinations")
	public Response get() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			// DomainRuntimeServiceMBean.DomainConfiguration. Memory: [[wls-domain-jmx-bootstrap]].
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			ObjectName agent = (ObjectName) mbs.getAttribute(domainConfig, "SNMPAgent");

			ObjectNode node = mapper.createObjectNode();
			if (agent == null) {
				// No SNMP agent bean at all — surface a disabled shell so the UI
				// renders without erroring.
				node.put("enabled", false);
				node.put("present", false);
				node.set("destinations", mapper.createArrayNode());
				return Response.ok(mapper.writeValueAsString(node)).build();
			}

			node.put("present", true);
			node.put("enabled", attrBool(mbs, agent, "Enabled", false));
			node.put("sendAutomaticTrapsEnabled", attrBool(mbs, agent, "SendAutomaticTrapsEnabled", true));
			node.put("port", attrInt(mbs, agent, "SNMPPort", 161));
			node.put("trapVersion", attrInt(mbs, agent, "SNMPTrapVersion", 2));
			node.put("communityPrefix", attr(mbs, agent, "CommunityPrefix", "public"));

			ArrayNode dests = mapper.createArrayNode();
			ObjectName[] destinations = (ObjectName[]) mbs.getAttribute(agent, "SNMPTrapDestinations");
			if (destinations != null) {
				for (ObjectName d : destinations) {
					ObjectNode dn = mapper.createObjectNode();
					dn.put("name", attr(mbs, d, "Name", ""));
					dn.put("host", attr(mbs, d, "Host", ""));
					dn.put("port", attrInt(mbs, d, "Port", 162));
					dn.put("community", attr(mbs, d, "Community", "public"));
					dests.add(dn);
				}
			}
			node.set("destinations", dests);

			return Response.ok(mapper.writeValueAsString(node)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update the domain SNMP agent and reconcile trap destinations")
	public Response update(String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domainConfig = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");
				ObjectName agent = (ObjectName) editMbs.getAttribute(domainConfig, "SNMPAgent");
				if (agent == null) {
					throw new IllegalStateException("This domain has no SNMP agent bean.");
				}

				setBoolIfPresent(editMbs, agent, "Enabled", input, "enabled");
				setBoolIfPresent(editMbs, agent, "SendAutomaticTrapsEnabled", input, "sendAutomaticTrapsEnabled");
				setIntIfPresent(editMbs, agent, "SNMPPort", input, "port");
				setIntIfPresent(editMbs, agent, "SNMPTrapVersion", input, "trapVersion");
				setStrIfPresent(editMbs, agent, "CommunityPrefix", input, "communityPrefix");

				if (input.has("destinations") && input.get("destinations").isArray()) {
					reconcileDestinations(editMbs, agent, (ArrayNode) input.get("destinations"));
				}

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				// Enabling/disabling the agent or changing its bound port only
				// takes effect when the agent (re)starts; trap-destination edits
				// apply on activate. The UI surfaces this caveat.
				return Response.ok("{\"success\":true,\"requiresRestart\":true}").build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	/// Reconciles the configured trap destinations against the submitted list:
	/// creates new ones, updates host/port/community on existing ones (matched
	/// by name), and destroys any that are no longer present.
	private void reconcileDestinations(MBeanServer mbs, ObjectName agent, ArrayNode desired) throws Exception {
		Set<String> wanted = new HashSet<>();
		for (JsonNode d : desired) {
			String name = text(d, "name");
			if (name == null || name.isEmpty()) {
				continue;
			}
			wanted.add(name);

			ObjectName dest = (ObjectName) mbs.invoke(agent, "lookupSNMPTrapDestination",
					new Object[]{name}, new String[]{"java.lang.String"});
			if (dest == null) {
				dest = (ObjectName) mbs.invoke(agent, "createSNMPTrapDestination",
						new Object[]{name}, new String[]{"java.lang.String"});
			}
			if (d.has("host")) {
				mbs.setAttribute(dest, new Attribute("Host", text(d, "host")));
			}
			if (d.has("port") && !d.get("port").isNull()) {
				mbs.setAttribute(dest, new Attribute("Port", d.get("port").asInt()));
			}
			if (d.has("community")) {
				mbs.setAttribute(dest, new Attribute("Community", text(d, "community")));
			}
		}

		// Destroy destinations the operator removed from the list.
		ObjectName[] existing = (ObjectName[]) mbs.getAttribute(agent, "SNMPTrapDestinations");
		if (existing != null) {
			for (ObjectName d : existing) {
				String name = attr(mbs, d, "Name", null);
				if (name != null && !wanted.contains(name)) {
					mbs.invoke(agent, "destroySNMPTrapDestination",
							new Object[]{d}, new String[]{"weblogic.management.configuration.SNMPTrapDestinationMBean"});
				}
			}
		}
	}

	private String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return (v != null && !v.isNull()) ? v.asText() : null;
	}

	private void setBoolIfPresent(MBeanServer mbs, ObjectName on, String attr, ObjectNode input, String field)
			throws Exception {
		if (input.has(field) && !input.get(field).isNull()) {
			mbs.setAttribute(on, new Attribute(attr, input.get(field).asBoolean()));
		}
	}

	private void setIntIfPresent(MBeanServer mbs, ObjectName on, String attr, ObjectNode input, String field)
			throws Exception {
		if (input.has(field) && !input.get(field).isNull()) {
			mbs.setAttribute(on, new Attribute(attr, input.get(field).asInt()));
		}
	}

	private void setStrIfPresent(MBeanServer mbs, ObjectName on, String attr, ObjectNode input, String field)
			throws Exception {
		if (input.has(field) && !input.get(field).isNull()) {
			mbs.setAttribute(on, new Attribute(attr, input.get(field).asText()));
		}
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

	private boolean attrBool(MBeanServer mbs, ObjectName on, String name, boolean dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? ((Boolean) val).booleanValue() : dflt;
		} catch (Exception e) {
			return dflt;
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
