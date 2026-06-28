package org.vorpal.blade.applications.console.tuning;

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
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for the WebLogic domain's production-vs-development mode.
///
/// `ProductionModeEnabled` is a single boolean on the domain-level
/// `DomainMBean` (`DomainConfiguration.ProductionModeEnabled`). Unlike most of
/// the other Tuning tabs, this value is **read once at server boot** — writing
/// it through the JMX edit tree (`startEdit` → `setAttribute` → `activate`)
/// updates the persisted config immediately, but the running servers stay in
/// whatever mode they booted in until the AdminServer and every engine node
/// are restarted. Hence the PUT returns `requiresRestart`, and the UI raises
/// the shared restart-required banner.
///
/// Flipping the mode also changes WebLogic defaults (auto-deployment,
/// demo-certificate handling, default log levels), so this is a deliberate,
/// domain-wide action — not a per-service `configuration.json` setting.
@Path("/domain-mode")
@Tag(name = "Domain Mode", description = "WebLogic production vs development mode")
public class DomainModeSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get the domain's production/development mode")
	public Response get() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			// DomainRuntimeServiceMBean.DomainConfiguration. Memory: [[wls-domain-jmx-bootstrap]].
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");

			ObjectNode node = mapper.createObjectNode();
			node.put("domain", attr(mbs, domainConfig, "Name", ""));
			node.put("productionMode", attrBool(mbs, domainConfig, "ProductionModeEnabled", true));
			return Response.ok(mapper.writeValueAsString(node)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Set the domain's production/development mode (takes effect after a full domain restart)")
	public Response update(String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);
			if (!input.has("productionMode") || input.get("productionMode").isNull()) {
				return Response.status(Response.Status.BAD_REQUEST)
						.entity("{\"error\":\"Missing 'productionMode' boolean.\"}").build();
			}
			boolean desired = input.get("productionMode").asBoolean();

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domainConfig = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");

				editMbs.setAttribute(domainConfig, new Attribute("ProductionModeEnabled", desired));

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				// The config is updated, but the live servers keep their booted
				// mode until the AdminServer and every engine node restart.
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

	private String attr(MBeanServer mbs, ObjectName on, String name, String dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? val.toString() : dflt;
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
