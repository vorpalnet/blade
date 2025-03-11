package org.vorpal.blade.services.transfer.api.v1;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.ejb.Asynchronous;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;
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
import org.vorpal.blade.services.transfer.TransferSettings.TransferStyle;
import org.vorpal.blade.services.transfer.callflows.BlindTransfer;
import org.vorpal.blade.services.transfer.callflows.TransferListener;

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
public class TransferAPI extends ClientCallflow implements TransferListener {
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

		Response response = null;
		SipApplicationSession appSession = null;
		SessionResponse sessionResponse = null;

		if (key != null) {
			Set<String> appSessionIds = sipUtil.getSipApplicationSessionIds(key);
			if (appSessionIds != null && appSessionIds.size() >= 1) {
				String appSessionId = (String) appSessionIds.toArray()[0];
				appSession = sipUtil.getApplicationSessionById(appSessionId);
			}
		}

		if (appSession != null) { // 200
			sessionResponse = new SessionResponse(appSession);
			response = Response.ok().entity(sessionResponse).build();
		} else { // 404
			response = Response.status(404).build();
		}

		return response;
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
	public void blindTransfer(
			@RequestBody(description = "transfer request", required = true) TransferRequest transferRequest,
			@Context UriInfo uriInfo, @Suspended AsyncResponse asyncResponse) {

		SipApplicationSession appSession = null;
		try {

			Callflow callflow = null;

			if (transferRequest.sessionKey != null) {
				Set<String> appSessionIds = sipUtil.getSipApplicationSessionIds(transferRequest.sessionKey);
				if (appSessionIds != null && appSessionIds.size() >= 1) {
					String appSessionId = (String) appSessionIds.toArray()[0];
					appSession = sipUtil.getApplicationSessionById(appSessionId);
				}
			}

//			sipLogger.warning("TransferAPI appSession=" + appSession);

			if (appSession != null) {

				appSession.setAttribute(TXFER_REQUEST, transferRequest);

				// Now find transferee
				SipSession transfereeSession = null;
				Address sipAddress = null;

//				sipLogger.finer(transfereeSession, "TransferAPI iterating through sessions... Looking for name="
//						+ transferRequest.dialogKey.name + ", value=" + transferRequest.dialogKey.value);

				for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet("SIP")) {

					String value = (String) sipSession.getAttribute(transferRequest.dialogKey.name);
					if (transferRequest.dialogKey.value.equalsIgnoreCase(value)) {
						transfereeSession = sipSession;
						sipAddress = (Address) sipSession.getAttribute("sipAddress");
						break;
					}

				}

//				sipLogger.warning("TransferAPI transfereeSession=" + transfereeSession);

				if (transfereeSession != null) {

					// can finally log request
					sipLogger.logObjectAsJson(transfereeSession, Level.FINER, transferRequest);

					Address transferee = (Address) transfereeSession.getAttribute("sipAddress");
					Address target = null;

					if (transferRequest.target.sipAddress != null) {
						target = sipFactory.createAddress(transferRequest.target.sipAddress);
//						copyParameters(transferor, target);
					} else if (transferRequest.target.sipUri != null) {
						target = sipFactory.createAddress("<" + transferRequest.target.sipUri + ">");
//						copyParameters(transferee, target);
					} else if (transferRequest.target.account != null) {
						SipURI tmpTarget = (SipURI) sipFactory.createAddress("sip:" + transferRequest.target.account)
								.getURI();
						target = sipFactory.createAddress(transferee.toString());
						((SipURI) target.getURI()).setUser(tmpTarget.getUser());
						((SipURI) target.getURI()).setHost(tmpTarget.getHost());
					} else if (transferRequest.target.user != null) {
						target = sipFactory.createAddress(transferee.toString());
						((SipURI) target.getURI()).setUser(transferRequest.target.user);
					}

//					sipLogger.warning("TransferAPI target=" + target);

					if (target != null) {
						SipSession transferorSession = getLinkedSession(transfereeSession);
						sipLogger.info(transferorSession,
								"TransferAPI REST transfer request; transferee=" + transferee + ", target=" + target);
						Address transferor = (Address) transferorSession.getAttribute("sipAddress");

						DummyRequest refer = new DummyRequest(INVITE, transferor, target);
						refer.setApplicationSession(appSession);
						refer.setSession(transferorSession);
						refer.setHeader("Refer-To", target.toString());

						if (transferRequest.target.inviteHeaders != null) {
							for (Header header : transferRequest.target.inviteHeaders) {
								refer.setHeader(header.name, header.value);
							}
						}

						TransferStyle style = transferRequest.style;
						if (null == style) {
							style = TransferServlet.settingsManager.getCurrent().getDefaultTransferStyle();
						}
						if (null == style) {
							style = TransferStyle.blind;
						}

						switch (style) {
						case attended:
//							callflow = new AttendedTransfer(this, false);
//							break;
						case conference:
//							callflow = new ConferenceTransfer(this, false);
//							break;
						case blind:
							callflow = new BlindTransfer(this, false);
//							callflow = new BlindTransfer(this, true);
							break;
						}

//						sipLogger.warning("TransferAPI callflow=" + callflow);

						callflow.process(refer);

						if (transferRequest.notification == null || //
								transferRequest.notification.style == null || //
								transferRequest.notification.style == TransferRequest.Notification.Style.async) {
							// Do not send response; Save response in static memory so it's not serialized
							responseMap.put(appSession.getId(), asyncResponse);
						} else {
							// must be none, callback or jms
							TransferResponse transferResponse = new TransferResponse();
							transferResponse.status = 202;
							transferResponse.description = "Accepted, Fire and Forget";
							transferResponse.request = transferRequest;
							asyncResponse.resume(Response.status(Status.ACCEPTED).entity(transferResponse).build());
						}
					} else {
						TransferResponse transferResponse = new TransferResponse();
						transferResponse.status = 500;
						transferResponse.description = "Could not build target address";
						transferResponse.request = transferRequest;
						asyncResponse.resume(Response.status(Status.NOT_ACCEPTABLE).entity(transferResponse).build());
					}

				} else {
					TransferResponse transferResponse = new TransferResponse();
					transferResponse.status = 404;
					transferResponse.description = "Dialog not found";
					transferResponse.request = transferRequest;
					asyncResponse.resume(Response.status(Status.NOT_FOUND).entity(transferResponse).build());
				}

			} else {
				TransferResponse transferResponse = new TransferResponse();
				transferResponse.status = 404;
				transferResponse.description = "Session not found";
				transferResponse.request = transferRequest;
				asyncResponse.resume(Response.status(Status.NOT_FOUND).entity(transferResponse).build());
			}

		} catch (Exception e) {
			sipLogger.severe(e);

			// no memory leaks!
			if (appSession != null) {
				responseMap.remove(appSession.getId());
			}

			TransferResponse transferResponse = new TransferResponse();
			transferResponse.status = 500;
			transferResponse.description = "Internal Server Error";
			transferResponse.request = transferRequest;
			asyncResponse.resume(Response.status(Status.INTERNAL_SERVER_ERROR).entity(transferResponse).build());
		}
	}

	@Override
	public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {
		sipLogger.finer(inboundRefer, "TransferAPI transferRequested " + inboundRefer.getMethod());

	}

	@Override
	public void transferInitiated(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.finer(outboundRequest, "TransferAPI transferInitiated " + outboundRequest.getMethod());
	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		sipLogger.finer(response, "TransferAPI transferCompleted " + response.getMethod() + " " + response.getStatus()
				+ " " + response.getReasonPhrase());

		AsyncResponse asyncResponse = responseMap.remove(response.getApplicationSession().getId());
		if (asyncResponse != null) {
			TransferResponse txferResp = new TransferResponse();
			txferResp.event = "transferCompleted";
			txferResp.method = response.getMethod();
			txferResp.status = response.getStatus();
			txferResp.description = response.getReasonPhrase();
			txferResp.request = (TransferRequest) response.getApplicationSession().getAttribute(TXFER_REQUEST);
			asyncResponse.resume(Response.status(Status.OK).entity(txferResp).build());
		}

	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		sipLogger.finer(response, "transferDeclined " + response.getMethod() + " " + response.getStatus() + " "
				+ response.getReasonPhrase());

		AsyncResponse asyncResponse = responseMap.remove(response.getApplicationSession().getId());
		if (asyncResponse != null) {
			TransferResponse txferResp = new TransferResponse();
			txferResp.event = "transferDeclined";
			txferResp.method = response.getMethod();
			txferResp.status = response.getStatus();
			txferResp.description = response.getReasonPhrase();
			txferResp.request = (TransferRequest) response.getApplicationSession().getAttribute(TXFER_REQUEST);
			asyncResponse.resume(Response.status(Status.FORBIDDEN).entity(txferResp).build());
		}
	}

	@Override
	public void transferAbandoned(SipServletRequest cancelRequest) throws ServletException, IOException {
		sipLogger.finer(cancelRequest, "TransferAPI transferAbandoned " + cancelRequest.getMethod());
		AsyncResponse asyncResponse = responseMap.remove(cancelRequest.getApplicationSession().getId());
		if (asyncResponse != null) {
			TransferResponse txferResp = new TransferResponse();
			txferResp.event = "transferAbandoned";
			txferResp.method = cancelRequest.getMethod();
			txferResp.request = (TransferRequest) cancelRequest.getApplicationSession().getAttribute(TXFER_REQUEST);
			asyncResponse.resume(Response.status(Status.GONE).entity(txferResp).build());
		}
	}

}
