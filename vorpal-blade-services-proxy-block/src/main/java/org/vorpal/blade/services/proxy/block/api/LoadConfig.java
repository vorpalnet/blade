package org.vorpal.blade.services.proxy.block.api;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.vorpal.blade.services.proxy.block.optimized.OptimizedBlockConfig;
import org.vorpal.blade.services.proxy.block.optimized.OptimizedBlockConfigSample;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * This class is meant to be an example of how the proxy-block application will
 * load a configuration at runtime. This is a dummy service. Proxy-block will
 * implement a client and pull the config from elsewhere.
 */

@OpenAPIDefinition(info = @Info( //
		title = "Proxy-Block", //
		version = "1", //
		description = "Manages configurations."))
@Path("v1")
public class LoadConfig {

	@SuppressWarnings({ "unchecked" })
	@GET
	@Path("config/load/{id}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Returns a configuration based on an identifier.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response load(@PathParam("id") String id) {
		OptimizedBlockConfig confg = new OptimizedBlockConfigSample();
		Response response = Response.ok().entity(confg).build();
		return response;
	}

}
