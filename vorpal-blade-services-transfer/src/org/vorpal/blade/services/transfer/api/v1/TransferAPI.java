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

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.DummyRequest;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.services.transfer.TransferServlet;
import org.vorpal.blade.services.transfer.TransferSettings.TransferStyle;
import org.vorpal.blade.services.transfer.callflows.BlindTransfer;
import org.vorpal.blade.services.transfer.callflows.ReferTransfer;
import org.vorpal.blade.services.transfer.callflows.Transfer;
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

//		AsyncSipServlet.getSipLogger().logObjectAsJson(Level.FINEST, transferRequest);

		SipApplicationSession appSession = null;
		try {

			Callflow callflow = null;

			if (transferRequest != null && transferRequest.sessionKey != null) {
				Set<String> appSessionIds = sipUtil.getSipApplicationSessionIds(transferRequest.sessionKey);

				sipLogger.finest("invoking sipUtil.getSipApplicationSessionIds(" + transferRequest.sessionKey + ");");
				if (sipLogger.isLoggable(Level.FINEST)) {
					SipApplicationSession _appSession;
					for (String id : appSessionIds) {
						_appSession = sipUtil.getApplicationSessionById(id);
						if (_appSession != null) {
							sipLogger.finer(_appSession,
									"appSessionId=" + id + ", isNull=false, isValid=" + _appSession.isValid());
						} else {
							sipLogger.finer("appSessionId=" + id + "isNull=true, isValid=false");
						}
					}
				}

				if (appSessionIds != null && appSessionIds.size() >= 1) {
					String appSessionId = (String) appSessionIds.toArray()[0];
					appSession = sipUtil.getApplicationSessionById(appSessionId);

					if (appSession != null) {
						sipLogger.finer(appSession, "TransferAPI appSession.id=" + appSession.getId());
					} else {
						sipLogger.warning(appSession, "TransferAPI appSession.id=null");
					}

				}
			} else {

				TransferResponse transferResponse = new TransferResponse();
				transferResponse.status = 500;
				transferResponse.description = "Missing JSON in request body.";
				transferResponse.request = transferRequest;
				asyncResponse.resume(Response.status(Status.NOT_ACCEPTABLE).entity(transferResponse).build());
				return;

			}

			if (appSession == null) {

				if (sipLogger.isLoggable(Level.FINEST)) {
					AsyncSipServlet.getSipLogger().logObjectAsJson(Level.FINEST, transferRequest);
				}

				if (sipLogger.isLoggable(Level.WARNING)) {
					AsyncSipServlet.getSipLogger()
							.warning("No appSession found for sessionKey=" + transferRequest.sessionKey);
				}

			}

			if (appSession != null) {

				appSession.setAttribute(TXFER_REQUEST, transferRequest);

				// Now find transferee
				SipSession transfereeSession = null;
//				Address sipAddress = null;

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(appSession, "TransferAPI iterating through sessions... Looking for name="
							+ transferRequest.dialogKey.name + ", value=" + transferRequest.dialogKey.value);
				}

				for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet("SIP")) {

					if (sipLogger.isLoggable(Level.FINEST)) {
						sipLogger.finest(sipSession, "sipSession.id=" + sipSession.getId() + " attributes include:");
						String value;
						Object obj;
						for (String name : sipSession.getAttributeNameSet()) {
							obj = sipSession.getAttribute(name);
							if (obj instanceof String) {
								value = (String) obj;
								sipLogger.finest(sipSession, "\t name=" + name + ", value=" + value);
							}
						}
					}

					String value = (String) sipSession.getAttribute(transferRequest.dialogKey.name);

					if (value != null && transferRequest.dialogKey.value.equalsIgnoreCase(value)) {
						transfereeSession = sipSession;
//						sipAddress = (Address) sipSession.getAttribute("sipAddress");
						sipLogger.finer(sipSession, "sipSession.id=" + sipSession.getId() + ", match!");
						break;
					} else {
						sipLogger.finer(sipSession, "sipSession.id=" + sipSession.getId() + ", no match.");
					}

				}

				if (transfereeSession != null) {

					// can finally log request
					sipLogger.logObjectAsJson(transfereeSession, Level.FINER, transferRequest);

					// handle glare
					if (null != transfereeSession.getAttribute("EXPECT_ACK")) {
						TransferResponse transferResponse = new TransferResponse();
						transferResponse.status = 491;
						transferResponse.description = "Request Pending";
						transferResponse.request = transferRequest;
						asyncResponse.resume(Response.status(Status.NOT_ACCEPTABLE).entity(transferResponse).build());
						return;
					}

					Address transferee = (Address) transfereeSession.getAttribute("sipAddress");
					Address target = null;

					if (transferRequest.target.sipAddress != null) {
						target = sipFactory.createAddress(transferRequest.target.sipAddress);
					} else if (transferRequest.target.sipUri != null) {
						target = sipFactory.createAddress("<" + transferRequest.target.sipUri + ">");
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

					sipLogger.finer(transfereeSession, "TransferAPI target=" + target);

					if (target != null) {
						SipSession transferorSession = getLinkedSession(transfereeSession);

						Address transferor = (Address) transferorSession.getAttribute("sipAddress");

						sipLogger.info(transferorSession, "TransferAPI REST transfer request; transferee=" + transferee
								+ ", target=" + target + ", transferor=" + transferor);

						// bob (transferee) is doing the 'transfer', to alice
						DummyRequest refer = new DummyRequest(REFER, transferor, transferee);
						refer.setRequestURI(transferee.getURI());
						refer.setApplicationSession(appSession);
						refer.setSession(transferorSession);
						refer.setHeader("Refer-To", target.toString());

						refer.setHeader("Referred-By", transferor.toString());
						sipLogger.finer(transferorSession, "Getting Referred-By: " + refer.getHeader("Referred-By"));

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
						case conference:
						case blind:
							callflow = new BlindTransfer(this, false);
							break;
						case refer:

							callflow = new ReferTransfer(this);
						}

						// Add any inviteHeaders
						((Transfer) callflow).setInviteHeaders(transferRequest.target.inviteHeaders);

						sipLogger.finest(transferorSession, "TransferAPI callflow=" + callflow);

						if (appSession.getAttribute("INITIAL_REFER") == null) {
							appSession.setAttribute("INITIAL_REFER", refer);
						}

						callflow.process(refer);

						if (transferRequest.notification == null || //
								transferRequest.notification.style == null || //
								transferRequest.notification.style == Notification.Style.async) {
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

		try {

			SipApplicationSession appSession = inboundRefer.getApplicationSession();

			// save X-Previous-DN-Tmp for use later
			URI referTo = inboundRefer.getAddressHeader("Refer-To").getURI();
			appSession.setAttribute("Refer-To", referTo);

			if (sipLogger.isLoggable(Level.INFO)) {
				URI ruri = inboundRefer.getRequestURI();
				String from = inboundRefer.getFrom().toString();
				String to = inboundRefer.getTo().toString();
				String referredBy = inboundRefer.getHeader("Referred-By");
				sipLogger.info(inboundRefer, "TransferAPI.transferRequested - ruri=" + ruri + ", from=" + from + ", to="
						+ to + ", referTo=" + referTo + ", referredBy=" + referredBy);
			}

		} catch (Exception e) {
			sipLogger.severe(inboundRefer, e);
		}

	}

	@Override
	public void transferInitiated(SipServletRequest outboundInvite) throws ServletException, IOException {

		SipApplicationSession appSession = outboundInvite.getApplicationSession();

		// Set Header X-Original-DN
		URI xOriginalDN = (URI) appSession.getAttribute("X-Original-DN");
		outboundInvite.setHeader("X-Original-DN", xOriginalDN.toString());
		sipLogger.finer(outboundInvite, Color.YELLOW_BOLD_BRIGHT(
				"TransferServlet.transferInitiated - setting INVITE header X-Original-DN: " + xOriginalDN.toString()));

		// Set Header X-Previous-DN
		URI xPreviousDN = (URI) appSession.getAttribute("X-Previous-DN");
		outboundInvite.setHeader("X-Previous-DN", xPreviousDN.toString());
		sipLogger.finer(outboundInvite, Color.YELLOW_BOLD_BRIGHT(
				"TransferServlet.transferInitiated - setting INVITE header X-Previous-DN: " + xPreviousDN.toString()));

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(outboundInvite, "TransferAPI.transferInitiated - method=" + outboundInvite.getMethod());
		}
	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		sipLogger.finer(response, "TransferAPI.transferCompleted - " + response.getMethod() + " " + response.getStatus()
				+ " " + response.getReasonPhrase());

		SipApplicationSession appSession = response.getApplicationSession();

		SipSession callee = response.getSession();
		callee.setAttribute("userAgent", "callee");
		SipSession caller = Callflow.getLinkedSession(callee);
		caller.setAttribute("userAgent", "caller");

		// now update X-Previous-DN for future use after success transfer
		URI referTo = (URI) appSession.getAttribute("Refer-To");
		appSession.setAttribute("X-Previous-DN", referTo);
		sipLogger.finer(response,
				Color.YELLOW_BOLD_BRIGHT("TransferAPI.transferComplete - saving X-Previous-DN: " + referTo.toString()));

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(response, "TransferAPI.transferCompleted - status=" + response.getStatus());
		}

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
		sipLogger.finer(response, "TransferAPI.transferDeclined - " + response.getMethod() + " " + response.getStatus()
				+ " " + response.getReasonPhrase());

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
		sipLogger.finer(cancelRequest, "TransferAPI.transferAbandoned - " + cancelRequest.getMethod());
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
