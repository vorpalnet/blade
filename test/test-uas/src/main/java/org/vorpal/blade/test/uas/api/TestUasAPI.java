package org.vorpal.blade.test.uas.api;

import java.util.HashMap;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;

import org.vorpal.blade.test.uas.UasServlet;
import org.vorpal.blade.test.uas.config.TestUasConfig;

/// REST API for runtime configuration of the BLADE Test UAS.
///
/// All PUT endpoints modify the live configuration immediately.
/// Changes take effect on the next incoming call.
@OpenAPIDefinition(info = @Info(title = "BLADE Test UAS", version = "1"))
@Path("api/v1/config")
public class TestUasAPI {

	/// Returns the current UAS configuration.
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get current UAS configuration")
	public Response getConfig() {
		return Response.ok(UasServlet.settingsManager.getCurrent()).build();
	}

	/// Replaces the entire UAS configuration.
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Replace entire UAS configuration")
	public Response setConfig(TestUasConfig config) {
		TestUasConfig current = UasServlet.settingsManager.getCurrent();
		current.setDefaultStatus(config.getDefaultStatus());
		current.setDefaultDelay(config.getDefaultDelay());
		current.setDefaultDuration(config.getDefaultDuration());
		current.setSdpContent(config.getSdpContent());
		current.setErrorMap(config.getErrorMap());
		return Response.ok(current).build();
	}

	/// Updates the default response status code.
	@PUT
	@Path("/status")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update default response status code")
	public Response setStatus(TestUasConfig partial) {
		TestUasConfig current = UasServlet.settingsManager.getCurrent();
		current.setDefaultStatus(partial.getDefaultStatus());
		return Response.ok(current).build();
	}

	/// Updates the default response delay.
	@PUT
	@Path("/delay")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update default response delay (e.g. 5s, 100ms)")
	public Response setDelay(TestUasConfig partial) {
		TestUasConfig current = UasServlet.settingsManager.getCurrent();
		current.setDefaultDelay(partial.getDefaultDelay());
		return Response.ok(current).build();
	}

	/// Updates the default call duration before auto-BYE.
	@PUT
	@Path("/duration")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update default call duration before auto-BYE (e.g. 30s, 5m)")
	public Response setDuration(TestUasConfig partial) {
		TestUasConfig current = UasServlet.settingsManager.getCurrent();
		current.setDefaultDuration(partial.getDefaultDuration());
		return Response.ok(current).build();
	}

	/// Replaces the error map (phone number to SIP error code mappings).
	@PUT
	@Path("/errormap")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Replace error map (phone number -> SIP error code)")
	public Response setErrorMap(HashMap<String, Integer> errorMap) {
		TestUasConfig current = UasServlet.settingsManager.getCurrent();
		current.setErrorMap(errorMap);
		return Response.ok(current).build();
	}

}
