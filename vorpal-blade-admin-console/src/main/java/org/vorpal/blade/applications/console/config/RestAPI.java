package org.vorpal.blade.applications.console.config;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(info = @Info(title = "MediaHub", version = "1", description = "Media Hub APIs"))

@Path("api/v1")
public class RestAPI {

	@GET
	@Path("{app}/reload")
//	@Produces({ "application/json" })
	@Operation(summary = "Reload configuration for all applications in the cluster with the given name.")
	public Response examineApplicationSession( //
			@Parameter(required = true, example = "transfer", description = "Reload the configuration") @PathParam("app") String app) {
		Response response;

		System.out.println("Invoking reload... app=" + app);

		response = Response.status(Response.Status.OK).build();
		return response;
	}

}
