package org.vorpal.blade.test.uac;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;

/// REST API for controlling the BLADE Test UAC load generator on this node.
///
/// Each node in the cluster receives commands independently.
/// The load generator instance is stored on the ServletContext so it
/// lives for the lifetime of this WAR deployment on this node.
@OpenAPIDefinition(info = @Info(title = "BLADE Test UAC - Load Generator", version = "1"))
@Path("api/v1/loadtest")
public class LoadTestAPI {

	private static final String GENERATOR_ATTR = "loadGenerator";

	@Context
	private ServletContext servletContext;

	/// Starts a load test with the given parameters on this node.
	///
	/// Returns 409 Conflict if a test is already running.
	@POST
	@Path("/start")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Start load generation on this node")
	public Response start(LoadTestRequest request) {
		try {
			LoadGenerator generator = getOrCreateGenerator();
			generator.start(request);
			return Response.ok(generator.getStatus()).build();
		} catch (IllegalStateException e) {
			return Response.status(Response.Status.CONFLICT)
					.entity(getOrCreateGenerator().getStatus()).build();
		} catch (IllegalArgumentException e) {
			return Response.status(Response.Status.BAD_REQUEST)
					.entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Stops the running load test on this node. Active calls drain naturally.
	@POST
	@Path("/stop")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Stop load generation on this node")
	public Response stop() {
		LoadGenerator generator = getOrCreateGenerator();
		generator.stop();
		return Response.ok(generator.getStatus()).build();
	}

	/// Returns the current load test status for this node.
	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get load test status on this node")
	public Response getStatus() {
		return Response.ok(getOrCreateGenerator().getStatus()).build();
	}

	private LoadGenerator getOrCreateGenerator() {
		LoadGenerator generator = (LoadGenerator) servletContext.getAttribute(GENERATOR_ATTR);
		if (generator == null) {
			generator = new LoadGenerator();
			servletContext.setAttribute(GENERATOR_ATTR, generator);
		}
		return generator;
	}

}
