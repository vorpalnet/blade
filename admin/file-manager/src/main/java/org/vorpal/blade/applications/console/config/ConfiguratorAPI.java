package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.util.Set;

import javax.naming.NamingException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.applications.console.config.test.ConfigHelper;

/// REST API for the BLADE Configurator.
///
/// Provides endpoints for discovering deployed BLADE applications and
/// managing their JSON configuration (schema, domain/cluster/server config,
/// and sample files). These endpoints back the configurator GUI which
/// is deployed separately in BLADE Connect.
@Path("/api")
public class ConfiguratorAPI {

	/// Returns the set of deployed BLADE application names discovered via JMX.
	@GET
	@Path("/apps")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getApps() {
		try {
			Set<String> apps = ConfigurationMonitor.queryApps();
			return Response.ok(apps).build();
		} catch (NamingException e) {
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Returns the JSON Schema for the specified application.
	@GET
	@Path("/schema/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSchema(@PathParam("app") String app) {
		return loadConfig(app, "SCHEMA");
	}

	/// Returns the sample configuration for the specified application.
	@GET
	@Path("/sample/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSample(@PathParam("app") String app) {
		return loadConfig(app, "SAMPLE");
	}

	/// Returns the configuration JSON for the specified application and config type.
	///
	/// @param app the application name
	/// @param configType one of Domain, Cluster, or Server (defaults to Domain)
	@GET
	@Path("/config/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getConfig(@PathParam("app") String app,
			@QueryParam("type") String configType) {
		if (configType == null || configType.isEmpty()) {
			configType = "Domain";
		}
		return loadConfig(app, configType);
	}

	/// Saves the configuration JSON for the specified application and config type.
	///
	/// @param app the application name
	/// @param configType one of Domain, Cluster, or Server (defaults to Domain)
	/// @param json the configuration JSON to save
	@POST
	@Path("/config/{app}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response saveConfig(@PathParam("app") String app,
			@QueryParam("type") String configType,
			String json) {
		if (configType == null || configType.isEmpty()) {
			configType = "Domain";
		}
		try {
			ConfigHelper cfgHelper = new ConfigHelper(app, configType);
			cfgHelper.saveFileLocally(configType, json);
			cfgHelper.closeSettings();
			return Response.ok("{\"status\":\"saved\"}").build();
		} catch (Exception e) {
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	private Response loadConfig(String app, String configType) {
		try {
			ConfigHelper cfgHelper = new ConfigHelper(app, configType);
			cfgHelper.getSettings();
			String data = cfgHelper.loadFile(configType);
			cfgHelper.closeSettings();

			if (data == null || data.isEmpty()) {
				return Response.status(Response.Status.NOT_FOUND)
						.entity("{\"error\":\"No data found for " + app + " (" + configType + ")\"}").build();
			}
			return Response.ok(data).build();
		} catch (IOException e) {
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

}
