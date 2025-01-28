package org.vorpal.blade.services.transfer.api.v1;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.ejb.Asynchronous;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;
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
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.services.transfer.TransferServlet;
import org.vorpal.blade.services.transfer.callflows.BlindTransfer;
import org.vorpal.blade.services.transfer.callflows.TransferListener;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@OpenAPIDefinition(info = @Info( //
		title = "BLADE Transfer Session API", //
		version = "2", //
		description = "Performs transfer operations"))
@Path("api/v1")
public class TransferAPI extends ClientCallflow implements TransferListener {

//	public class ResponseStuff {
//		public UriInfo uriInfo;
//		public AsyncResponse asyncResponse;
//		public ResponseStuff(UriInfo uriInfo, AsyncResponse asyncResponse) {
//			this.uriInfo = uriInfo;
//			this.asyncResponse = asyncResponse;
//		}
//	}

	// static because you cannot serialize AsyncResponse
	public static Map<String, AsyncResponse> responseMap = new ConcurrentHashMap<>();

	@GET
	@Path("session/{key}")
	@Operation(summary = "Get a session.")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response getSession(
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("key") String sessionKey) {
		Response response;

		try {

			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionKey, false);
			if (appSession != null) {
				SessionResponse session = new SessionResponse(appSession);
				response = Response.accepted().entity(session).build();
			} else {
				response = Response.status(Status.NOT_FOUND).build();
			}

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;
	}

	@SuppressWarnings({ "unchecked", "unchecked" })
	@POST
	@Asynchronous
	@Path("transfer/blind/{sessionKey}/{dialogId}")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Performs a blind call transfer")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "403", description = "Transfer Declined"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "410", description = "Transfer Abandoned"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public void blindTransfer(
			@Parameter(required = true, example = "ABCD6789", description = "session key") @PathParam("sessionKey") String sessionKey,
			@Parameter(required = true, example = "BA5E", description = "dialog id") @PathParam("dialogId") String dialogId,
			@RequestBody(description = "transfer request", required = true) TransferRequest transferRequest,
			@Context UriInfo uriInfo, @Suspended AsyncResponse asyncResponse) {

		try {

			Callflow callflow = null;

			String appSessionId = null;
			Set<String> appSessionIds = sipUtil.getSipApplicationSessionIds(sessionKey);
			appSessionId = (String) appSessionIds.toArray()[0];

			SipApplicationSession appSession = sipUtil.getApplicationSessionById(appSessionId);

			String transferee = null;
			SipSession transfereeSession = null;

			for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet("SIP")) {
				if (dialogId.equals(Callflow.getVorpalDialogId(sipSession))) {
					transfereeSession = sipSession;
					break;
				}

			}

			sipLogger.finer(transfereeSession,
					"REST API blindTransfer invoked. destination=" + transferRequest.destination);

			transferee = ((Address) transfereeSession.getAttribute("identityAddress")).toString();

			String target = transferRequest.destination;
			DummyRequest refer = new DummyRequest(INVITE, transferee, target);
			refer.setApplicationSession(appSession);
			refer.setSession(transfereeSession);
			refer.setHeader("Refer-To", transferRequest.destination);

			if (appSession != null) {
				callflow = new BlindTransfer(this);
				callflow.process(refer);

				// Save this in static memory so it's not serialized
				responseMap.put(appSession.getId(), asyncResponse);
			} else {
				asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
			}

		} catch (Exception e) {
			sipLogger.severe(e);
			asyncResponse.resume(Response.status(500, e.getMessage()).build());
		}
	}

	@Override
	public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {
		sipLogger.finer(inboundRefer, "transferRequested " + inboundRefer.getMethod());

		SipApplicationSession appSession = inboundRefer.getApplicationSession();

		// save X-Previous-DN-Tmp for use later
		URI referTo = inboundRefer.getAddressHeader("Refer-To").getURI();
		appSession.setAttribute("Refer-To", referTo);
	}

	@Override
	public void transferInitiated(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.finer(outboundRequest, "transferInitiated " + outboundRequest.getMethod());

		SipApplicationSession appSession = outboundRequest.getApplicationSession();

		// Set Header X-Original-DN
		URI xOriginalDN = (URI) appSession.getAttribute("X-Original-DN");
		outboundRequest.setHeader("X-Original-DN", xOriginalDN.toString());

		// Set Header X-Previous-DN
		URI xPreviousDN = (URI) appSession.getAttribute("X-Previous-DN");
		outboundRequest.setHeader("X-Previous-DN", xPreviousDN.toString());

		// now update X-Previous-DN for future use
		URI referTo = (URI) appSession.getAttribute("Refer-To");
		appSession.setAttribute("X-Previous-DN", referTo);
	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		sipLogger.finer(response, "transferCompleted " + response.getMethod() + " " + response.getStatus() + " "
				+ response.getReasonPhrase());

		AsyncResponse asyncResponse = responseMap.remove(response.getApplicationSession().getId());
		asyncResponse.resume(Response.status(Status.OK).build());
	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		sipLogger.finer(response, "transferDeclined " + response.getMethod() + " " + response.getStatus() + " "
				+ response.getReasonPhrase());

		AsyncResponse asyncResponse = responseMap.remove(response.getApplicationSession().getId());
		asyncResponse.resume(Response.status(Status.FORBIDDEN).build());
	}

	@Override
	public void transferAbandoned(SipServletRequest cancelRequest) throws ServletException, IOException {
		sipLogger.finer(cancelRequest, "transferAbandoned " + cancelRequest.getMethod());

		AsyncResponse asyncResponse = responseMap.remove(cancelRequest.getApplicationSession().getId());
		asyncResponse.resume(Response.status(Status.GONE).build());
	}

}
