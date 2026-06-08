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

import org.vorpal.blade.framework.v3.tester.LoadEngine;
import org.vorpal.blade.framework.v3.tester.LoadRequest;
import org.vorpal.blade.framework.v3.tester.TesterMetrics;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;

/// REST API for controlling this node's [LoadEngine].
///
/// Each node in the cluster receives commands independently; the engine and
/// metrics live on the ServletContext for the lifetime of this WAR
/// deployment on this node. The same controls are available cluster-wide
/// through the `TesterControl` JMX MBean (the BLADE Test Console uses that).
@OpenAPIDefinition(info = @Info(title = "BLADE Test UAC - Load Generator", version = "1"))
@Path("api/v1/loadtest")
public class LoadTestAPI {

	@Context
	private ServletContext servletContext;

	/// Starts a load test with the given parameters on this node. The
	/// optional `scenario` field selects a configured scenario; null fields
	/// fall back to the configuration's `originate` defaults.
	///
	/// Returns 409 Conflict if a test is already running.
	@POST
	@Path("/start")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Start load generation on this node")
	public Response start(LoadRequest request) {
		LoadEngine engine = engine();
		if (engine == null) {
			return notReady();
		}
		try {
			engine.start(request);
			return Response.ok(engine.getStatus()).build();
		} catch (IllegalStateException e) {
			return Response.status(Response.Status.CONFLICT).entity(engine.getStatus()).build();
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
		LoadEngine engine = engine();
		if (engine == null) {
			return notReady();
		}
		engine.stop();
		return Response.ok(engine.getStatus()).build();
	}

	/// Returns the current load test status for this node.
	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get load test status on this node")
	public Response getStatus() {
		LoadEngine engine = engine();
		if (engine == null) {
			return notReady();
		}
		return Response.ok(engine.getStatus()).build();
	}

	/// Returns the per-scenario metrics report for this node: counters,
	/// final-status distribution, latency percentiles, assertion tallies.
	@GET
	@Path("/report")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get the per-scenario metrics report for this node")
	public Response getReport() {
		return Response.ok(TesterMetrics.from(servletContext).report()).build();
	}

	/// Clears all per-scenario metrics on this node.
	@POST
	@Path("/reset")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Clear the per-scenario metrics on this node")
	public Response reset() {
		TesterMetrics.from(servletContext).reset();
		return Response.noContent().build();
	}

	private LoadEngine engine() {
		return (LoadEngine) servletContext.getAttribute(LoadEngine.ATTR);
	}

	private Response notReady() {
		return Response.status(Response.Status.SERVICE_UNAVAILABLE)
				.entity("{\"error\":\"Load engine not initialized; servlet still starting\"}").build();
	}
}
