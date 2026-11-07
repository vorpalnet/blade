package org.vorpal.blade.services.transfer;


import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
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

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v2.transfer.TransferSettings.TransferStyle;
import org.vorpal.blade.framework.v3.transfer.AttendedTransfer;
import org.vorpal.blade.framework.v3.transfer.TransferObserver;
import org.vorpal.blade.services.transfer.notify.TransferNotices;
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
public class TransferRestAPI extends TransferAPI implements TransferObserver {
	private static final long serialVersionUID = 1L;


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
	public void invokeTransfer( //
			@RequestBody(description = "transfer request", required = true) TransferRequest transferRequest, //
			@Context UriInfo uriInfo, //
			@Suspended AsyncResponse asyncResponse) {

		super.invokeTransfer(transferRequest, uriInfo, asyncResponse);

	}

	/// REST `attended` runs the attended transfer. It hangs up on no one and
	/// sends no NOTIFYs: Bob completes it by hanging up on Carol. Every other
	/// style runs as before.
	@Override
	protected Callflow createTransferCallflow(TransferStyle style) {
		if (style == TransferStyle.attended) {
			return new AttendedTransfer(this, settings.getCurrent(), false);
		}
		return super.createTransferCallflow(style);
	}

	// ---------------------------------------------------------------- events
	//
	// Every event goes to the transfer's webhook and the event sockets
	// (TransferNotices) exactly as the bus gets it. The waited REST request is
	// answered by the parent class on completion, refusal or abandonment.

	@Override
	public void transferRequested(SipServletRequest refer) throws ServletException, IOException {
		TransferNotices.publish("transferRequested", refer);
		super.transferRequested(refer);
	}

	@Override
	public void transferInitiated(SipServletRequest invite) throws ServletException, IOException {
		TransferNotices.publish("transferInitiated", invite);
		super.transferInitiated(invite);
	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		TransferNotices.publish("transferCompleted", response);
		super.transferCompleted(response);
	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		TransferNotices.publish("transferDeclined", response);
		super.transferDeclined(response);
	}

	@Override
	public void transferDeclined(SipServletRequest request) throws ServletException, IOException {
		TransferNotices.publish("transferDeclined", request);
	}

	@Override
	public void transferAbandoned(SipServletRequest request) throws ServletException, IOException {
		TransferNotices.publish("transferAbandoned", request);
		super.transferAbandoned(request);
	}

	/// An attended transfer's target answered. The webhook, the sockets and the
	/// bus are told just that (`consultationStarted`). A waited request is
	/// answered now, as completed: the consultation that follows can outlast
	/// any HTTP timeout. That is the one report that runs ahead of the truth;
	/// everything else says `transferCompleted` only when the transferee is
	/// connected to the target.
	@Override
	public void consultationStarted(SipServletResponse targetAnswer) throws ServletException, IOException {
		TransferNotices.publish("consultationStarted", targetAnswer);
		super.transferCompleted(targetAnswer);
	}

}
