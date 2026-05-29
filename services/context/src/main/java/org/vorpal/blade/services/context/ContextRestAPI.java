package org.vorpal.blade.services.context;

import java.util.Map;
import java.util.Set;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSessionsUtil;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.callflow.Callflow;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@OpenAPIDefinition(info = @Info( //
		title = "BLADE - Context", //
		version = "1", //
		description = "Lookup and mutate raw inbound SIP headers and per-call context for an in-progress call."))
@Path("v1")
public class ContextRestAPI {

	@GET
	@Path("{key}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Return the full header/context map for the call indexed by {key}")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	public Response getAll(@PathParam("key") String key) {
		Map<String, String> ctx = lookup(key);
		return (ctx != null) ? Response.ok(ctx).build() : Response.status(404).build();
	}

	@GET
	@Path("{key}/{name}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Return one header/context entry by name")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	public Response getOne(@PathParam("key") String key, @PathParam("name") String name) {
		Map<String, String> ctx = lookup(key);
		if (ctx == null) {
			return Response.status(404).build();
		}
		String value = ctx.get(name);
		return (value != null) ? Response.ok(value).build() : Response.status(404).build();
	}

	@PUT
	@Path("{key}/{name}")
	@Consumes(MediaType.TEXT_PLAIN)
	@Operation(summary = "Set or update a single header/context entry")
	@ApiResponses({ @ApiResponse(responseCode = "204", description = "No Content"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	public Response putOne(@PathParam("key") String key, @PathParam("name") String name, String value) {
		SipApplicationSession sas = lookupSession(key);
		if (sas == null) {
			return Response.status(404).build();
		}
		Map<String, String> ctx = getOrCreate(sas);
		ctx.put(name, value);
		sas.setAttribute(ContextServlet.RAW_HEADERS_ATTR, ctx);
		return Response.noContent().build();
	}

	@POST
	@Path("{key}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Operation(summary = "Bulk merge a JSON object of header/context entries")
	@ApiResponses({ @ApiResponse(responseCode = "204", description = "No Content"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	public Response merge(@PathParam("key") String key, Map<String, String> patch) {
		SipApplicationSession sas = lookupSession(key);
		if (sas == null) {
			return Response.status(404).build();
		}
		Map<String, String> ctx = getOrCreate(sas);
		if (patch != null) {
			ctx.putAll(patch);
		}
		sas.setAttribute(ContextServlet.RAW_HEADERS_ATTR, ctx);
		return Response.noContent().build();
	}

	@DELETE
	@Path("{key}/{name}")
	@Operation(summary = "Remove one header/context entry by name")
	@ApiResponses({ @ApiResponse(responseCode = "204", description = "No Content"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	public Response deleteOne(@PathParam("key") String key, @PathParam("name") String name) {
		SipApplicationSession sas = lookupSession(key);
		if (sas == null) {
			return Response.status(404).build();
		}
		Map<String, String> ctx = (Map<String, String>) sas.getAttribute(ContextServlet.RAW_HEADERS_ATTR);
		if (ctx == null || !ctx.containsKey(name)) {
			return Response.status(404).build();
		}
		ctx.remove(name);
		sas.setAttribute(ContextServlet.RAW_HEADERS_ATTR, ctx);
		return Response.noContent().build();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> lookup(String key) {
		SipApplicationSession sas = lookupSession(key);
		return (sas != null) ? (Map<String, String>) sas.getAttribute(ContextServlet.RAW_HEADERS_ATTR) : null;
	}

	private static SipApplicationSession lookupSession(String key) {
		if (key == null) {
			return null;
		}
		SipSessionsUtil sipUtil = Callflow.getSipUtil();
		Set<String> ids = sipUtil.getSipApplicationSessionIds(key);
		if (ids == null || ids.isEmpty()) {
			return null;
		}
		return sipUtil.getApplicationSessionById(ids.iterator().next());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> getOrCreate(SipApplicationSession sas) {
		Map<String, String> ctx = (Map<String, String>) sas.getAttribute(ContextServlet.RAW_HEADERS_ATTR);
		return (ctx != null) ? ctx : new java.util.LinkedHashMap<>();
	}

}
