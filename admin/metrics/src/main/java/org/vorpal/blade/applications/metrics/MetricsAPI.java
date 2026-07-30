package org.vorpal.blade.applications.metrics;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.node.ObjectNode;

/// Every BLADE application's counters, aggregated across the cluster.
@Path("/metrics")
public class MetricsAPI {

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response all() {
		ObjectNode result = MetricsCollector.collect();
		return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
	}
}
