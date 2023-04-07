package org.vorpal.blade.framework.tpcc;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.test.client.MessageResponse;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

@OpenAPIDefinition(info = @Info(title = "ThirdPartyCallControl", version = "1", description = "APIs for third-party call-control"))
@Path("api/v1")
public class ThirdPartyCallControl {
	private static Map<String, AsyncResponse> asyncResponses = new HashMap<>();

	@POST
	@Path("/tpcc")
	@Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	@Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML }) // don't forget @XmlRootElement in the Java class
	@Operation(summary = "Open a connection.")
	public void connect( //
			@HeaderParam("Content-Type") String contentType, //
			@Suspended AsyncResponse asyncResponse, //
			@Context UriInfo uriInfo, //
			@RequestBody(content = @Content(schema = @Schema(implementation = org.vorpal.blade.test.client.MessageRequest.class)), //
					description = "Message content", //
					required = true) org.vorpal.blade.test.client.MessageRequest message)
			throws ServletException, IOException //
	{

		MessageResponse msgResponse = new MessageResponse();
		
		URI location = URI.create(uriInfo.getPath()); // add appSession hash number
		Response httpResponse = Response.created(location).type(contentType).entity(msgResponse).build();
		
//		// Save the 'transient' AsyncResponse for later HTTP Response
//		asyncResponses.put(appSession.getId(), asyncResponse);
//
//		// Remove the 'transient' asyncResponse from global memory
//		asyncResponses.remove(appSession.getId()).resume(httpResponse);

	}

}
