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

/// REST API for domain-level WebLogic settings on the `DomainMBean`:
///
/// - `ProductionModeEnabled` — production vs development mode. **Read once at
///   server boot**: writing it through the JMX edit tree updates the persisted
///   config immediately, but the running servers stay in whatever mode they
///   booted in until the AdminServer and every engine node are restarted.
///   The PUT returns `requiresRestart` only when this value actually changed,
///   and the UI raises the shared restart-required banner. Flipping the mode
///   also changes WebLogic defaults (auto-deployment, demo-certificate
///   handling, default log levels).
/// - `ConfigBackupEnabled` / `ArchiveConfigurationCount` — archive the previous
///   domain configuration under `DOMAIN_HOME/configArchive` on each activation,
///   and how many archives to keep.
/// - `ConfigurationAuditType` — write configuration-change records to the
///   server log and/or the security audit provider. Legal values (DomainMBean
///   CONFIG_CHANGE_* constants): `none`, `log`, `audit`, `logaudit`.
@Path("/domain-mode")
@Tag(name = "Domain", description = "Domain-level settings: production mode, config archive, config audit")
public class DomainModeSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get domain-level settings (mode, config archive, config audit)")
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
			node.put("configBackupEnabled", attrBool(mbs, domainConfig, "ConfigBackupEnabled", false));
			node.put("archiveConfigurationCount", attrInt(mbs, domainConfig, "ArchiveConfigurationCount", 0));
			node.put("configurationAuditType", attr(mbs, domainConfig, "ConfigurationAuditType", "none"));

			// Advisory for the Config Audit dropdown: the audit/logaudit values
			// need an Auditing provider in the security realm or the records
			// have no destination. If the walk fails, report true so the UI
			// does not nag over a lookup problem.
			boolean auditorConfigured = true;
			try {
				ObjectName secConfig = (ObjectName) mbs.getAttribute(domainConfig, "SecurityConfiguration");
				ObjectName realm = (ObjectName) mbs.getAttribute(secConfig, "DefaultRealm");
				ObjectName[] auditors = (ObjectName[]) mbs.getAttribute(realm, "Auditors");
				auditorConfigured = auditors != null && auditors.length > 0;
			} catch (Exception ignore) {
			}
			node.put("auditorConfigured", auditorConfigured);

			return Response.ok(mapper.writeValueAsString(node)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update domain-level settings (mode change takes effect after a full domain restart)")
	public Response update(String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);

			if (input.has("configurationAuditType") && !input.get("configurationAuditType").isNull()) {
				String audit = input.get("configurationAuditType").asText();
				switch (audit) {
				case "none":
				case "log":
				case "audit":
				case "logaudit":
					break;
				default:
					return Response.status(Response.Status.BAD_REQUEST)
							.entity("{\"error\":\"configurationAuditType must be none, log, audit, or logaudit.\"}").build();
				}
			}

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domainConfig = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");

				// Restart is only needed when the boot-time mode actually changes.
				boolean requiresRestart = false;
				if (input.has("productionMode") && !input.get("productionMode").isNull()) {
					boolean desired = input.get("productionMode").asBoolean();
					boolean current = attrBool(editMbs, domainConfig, "ProductionModeEnabled", desired);
					if (current != desired) {
						requiresRestart = true;
					}
					editMbs.setAttribute(domainConfig, new Attribute("ProductionModeEnabled", desired));
				}
				if (input.has("configBackupEnabled") && !input.get("configBackupEnabled").isNull()) {
					editMbs.setAttribute(domainConfig, new Attribute("ConfigBackupEnabled", input.get("configBackupEnabled").asBoolean()));
				}
				if (input.has("archiveConfigurationCount") && !input.get("archiveConfigurationCount").isNull()) {
					editMbs.setAttribute(domainConfig, new Attribute("ArchiveConfigurationCount", input.get("archiveConfigurationCount").asInt()));
				}
				if (input.has("configurationAuditType") && !input.get("configurationAuditType").isNull()) {
					editMbs.setAttribute(domainConfig, new Attribute("ConfigurationAuditType", input.get("configurationAuditType").asText()));
				}

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok("{\"success\":true,\"requiresRestart\":" + requiresRestart + "}").build();

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

	private int attrInt(MBeanServer mbs, ObjectName on, String name, int dflt) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? ((Number) val).intValue() : dflt;
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
