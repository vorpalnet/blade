package org.vorpal.blade.applications.console.tuning;

import java.util.Set;
import java.util.TreeSet;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for managing WebLogic Single Sign-On settings.
 *
 * WebLogic SSO is configured per-server on the WebAppContainerMBean.
 * When enabled, WebLogic sets a shared authentication cookie across
 * all web applications on the server, so users only log in once.
 */
@Path("/api/v1/sso")
@Tag(name = "SSO", description = "Single Sign-On configuration")
public class SsoSettings {

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get SSO status for all servers")
	public Response getSsoStatus() {
		try {
			InitialContext ctx = new InitialContext();
			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			// Query all ServerRuntimeMBeans to find server names
			ObjectName domainRuntime = new ObjectName("com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName[] serverRuntimes = (ObjectName[]) mbeanServer.getAttribute(domainRuntime, "ServerRuntimes");

			StringBuilder json = new StringBuilder("[");
			boolean first = true;

			for (ObjectName serverRuntime : serverRuntimes) {
				String serverName = (String) mbeanServer.getAttribute(serverRuntime, "Name");

				// Look up the server's WebAppContainerMBean
				ObjectName serverConfig = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
				ObjectName webAppContainer = (ObjectName) mbeanServer.getAttribute(serverConfig, "WebAppContainer");

				boolean ssoEnabled = false;
				if (webAppContainer != null) {
					ssoEnabled = (Boolean) mbeanServer.getAttribute(webAppContainer, "AuthCookieEnabled");
				}

				if (!first) json.append(",");
				first = false;
				json.append("{\"server\":\"").append(serverName).append("\",\"ssoEnabled\":").append(ssoEnabled).append("}");
			}

			json.append("]");
			return Response.ok(json.toString()).build();

		} catch (Exception e) {
			return Response.serverError()
					.entity("{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
					.build();
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Enable or disable SSO on all servers")
	public Response setSsoStatus(String body) {
		try {
			// Parse the simple {"enabled": true/false} body
			boolean enabled = body.contains("true");

			InitialContext ctx = new InitialContext();
			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			// Start an edit session
			ObjectName configManager = new ObjectName("com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");
			ObjectName editService = new ObjectName("com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");

			// Get domain configuration
			ObjectName domainRuntime = new ObjectName("com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName[] serverRuntimes = (ObjectName[]) mbeanServer.getAttribute(domainRuntime, "ServerRuntimes");

			Set<String> serverNames = new TreeSet<>();
			for (ObjectName sr : serverRuntimes) {
				serverNames.add((String) mbeanServer.getAttribute(sr, "Name"));
			}

			// Use the edit MBean server to make configuration changes
			MBeanServer editMBeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");

			ObjectName editConfigManager = new ObjectName("com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");
			editMBeanServer.invoke(editConfigManager, "startEdit", new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				for (String serverName : serverNames) {
					ObjectName serverConfig = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
					ObjectName webAppContainer = (ObjectName) editMBeanServer.getAttribute(serverConfig, "WebAppContainer");

					if (webAppContainer != null) {
						editMBeanServer.setAttribute(webAppContainer,
								new javax.management.Attribute("AuthCookieEnabled", enabled));
					}
				}

				editMBeanServer.invoke(editConfigManager, "save", null, null);
				editMBeanServer.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok("{\"success\":true,\"ssoEnabled\":" + enabled + "}").build();

			} catch (Exception e) {
				// Undo on failure
				editMBeanServer.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMBeanServer.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return Response.serverError()
					.entity("{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
					.build();
		}
	}
}
