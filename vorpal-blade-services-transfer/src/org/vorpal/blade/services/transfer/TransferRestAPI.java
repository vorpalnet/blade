package org.vorpal.blade.services.transfer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.ejb.Asynchronous;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.v2.transfer.TransferListener;
import org.vorpal.blade.framework.v2.transfer.api.TransferAPI;
import org.vorpal.blade.framework.v2.transfer.api.TransferRequest;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@OpenAPIDefinition(info = @Info( //
		title = "BLADE - Transfer", //
		version = "1", //
		description = "Performs transfer operations"))
@Path("v1")
public class TransferRestAPI extends TransferAPI implements TransferListener {
	private static final long serialVersionUID = 1L;

	private final static String TXFER_REQUEST = "TXFER_REQUEST";

	// static because you cannot serialize AsyncResponse
	public static Map<String, AsyncResponse> responseMap = new ConcurrentHashMap<>();

	@SuppressWarnings({ "unchecked" })
	@GET
	@Path("session/{key}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Examine session variables")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response inspect(@PathParam("key") String key) {

		return super.inspect(key);

	}

	@SuppressWarnings({ "unchecked" })
	@POST
	@Asynchronous
	@Path("transfer")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Transfer")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "202", description = "Accepted"),
			@ApiResponse(responseCode = "403", description = "Transfer Declined"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "406", description = "Not Acceptable"),
			@ApiResponse(responseCode = "410", description = "Transfer Abandoned"),
			@ApiResponse(responseCode = "406", description = "Not Acceptable"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public void blindTransfer( //
			@RequestBody(description = "transfer request", required = true) TransferRequest transferRequest, //
			@Context UriInfo uriInfo, //
			@Suspended AsyncResponse asyncResponse) {

		super.blindTransfer(transferRequest, uriInfo, asyncResponse);

	}

}
