package org.vorpal.blade.framework.v2.transfer.api;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.ws.rs.PathParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.framework.v2.transfer.BlindTransfer;
import org.vorpal.blade.framework.v2.transfer.ReferTransfer;
import org.vorpal.blade.framework.v2.transfer.Transfer;
import org.vorpal.blade.framework.v2.transfer.TransferListener;
import org.vorpal.blade.framework.v2.transfer.TransferSettings;
import org.vorpal.blade.framework.v2.transfer.TransferSettings.TransferStyle;

import io.swagger.v3.oas.annotations.parameters.RequestBody;

/**
 * REST API endpoint for initiating and managing call transfers.
 *
 * <p>
 * Provides endpoints for inspecting sessions and invoking transfer operations.
 * Implements TransferListener to handle transfer lifecycle events.
 */
public class TransferAPI extends ClientCallflow implements TransferListener {
	private static final long serialVersionUID = 1L;

	// Session attribute keys
	private static final String TXFER_REQUEST = "TXFER_REQUEST";
	private static final String INITIAL_REFER_ATTR = "INITIAL_REFER";
	private static final String EXPECT_ACK_ATTR = "EXPECT_ACK";
	private static final String SIP_ADDRESS_ATTR = "sipAddress";

	// SIP header names
	private static final String REFER_TO_HEADER = "Refer-To";
	private static final String REFERRED_BY_HEADER = "Referred-By";

	// Response description constants
	private static final String DESC_MISSING_JSON = "Missing JSON in request body.";
	private static final String DESC_REQUEST_PENDING = "Request Pending";
	private static final String DESC_TARGET_BUILD_FAILED = "Could not build target address";
	private static final String DESC_DIALOG_NOT_FOUND = "Dialog not found";
	private static final String DESC_SESSION_NOT_FOUND = "Session not found";
	private static final String DESC_FIRE_AND_FORGET = "Accepted, Fire and Forget";
	private static final String DESC_INTERNAL_ERROR = "Internal Server Error";

	// static because you cannot serialize AsyncResponse
	public static Map<String, AsyncResponse> responseMap = new ConcurrentHashMap<>();

	public static SettingsManager<TransferSettings> settings;

	/**
	 * Returns the map of pending asynchronous responses keyed by application
	 * session ID.
	 *
	 * @return the response map
	 */
	public static Map<String, AsyncResponse> getResponseMap() {
		return responseMap;
	}

	/**
	 * Sets the map of pending asynchronous responses.
	 *
	 * @param responseMap the response map to set
	 */
	public static void setResponseMap(Map<String, AsyncResponse> responseMap) {
		TransferAPI.responseMap = responseMap;
	}

	/**
	 * Returns the settings manager for transfer configuration.
	 *
	 * @return the settings manager
	 */
	public static SettingsManager<TransferSettings> getSettings() {
		return settings;
	}

	/**
	 * Sets the settings manager for transfer configuration.
	 *
	 * @param settings the settings manager to set
	 */
	public static void setSettings(SettingsManager<TransferSettings> settings) {
		TransferAPI.settings = settings;
	}

	/**
	 * Inspects session variables for a given session key. Returns session
	 * information if the session exists, or 404 if not found.
	 *
	 * @param key the session index key to look up
	 * @return a Response containing SessionResponse on success, or 404 if not found
	 */
//	@SuppressWarnings({ "unchecked" })
//	@GET
//	@Path("session/{key}")
//	@Produces({ MediaType.APPLICATION_JSON })
//	@Operation(summary = "Examine session variables")
//	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
//			@ApiResponse(responseCode = "404", description = "Not Found"),
//			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
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

	/**
	 * Invokes a call transfer operation based on the provided transfer request.
	 * Supports blind, attended, conference, and REFER-based transfer styles.
	 *
	 * <p>
	 * Response codes:
	 * <ul>
	 * <li>200 OK - Transfer completed successfully</li>
	 * <li>202 Accepted - Transfer initiated (fire and forget mode)</li>
	 * <li>403 Forbidden - Transfer declined by remote party</li>
	 * <li>404 Not Found - Session or dialog not found</li>
	 * <li>406 Not Acceptable - Invalid request or target</li>
	 * <li>410 Gone - Transfer abandoned</li>
	 * <li>491 Request Pending - Glare condition detected</li>
	 * <li>500 Internal Server Error - Unexpected error</li>
	 * </ul>
	 *
	 * @param transferRequest the transfer request containing session key, dialog
	 *                        key, and target
	 * @param uriInfo         the URI context information
	 * @param asyncResponse   the JAX-RS async response for deferred completion
	 */
//	@SuppressWarnings({ "unchecked" })
//	@POST
//	@Asynchronous
//	@Path("transfer")
//	@Consumes({ MediaType.APPLICATION_JSON })
//	@Produces({ MediaType.APPLICATION_JSON })
//	@Operation(summary = "Transfer")
//	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
//			@ApiResponse(responseCode = "202", description = "Accepted"),
//			@ApiResponse(responseCode = "403", description = "Transfer Declined"),
//			@ApiResponse(responseCode = "404", description = "Not Found"),
//			@ApiResponse(responseCode = "406", description = "Not Acceptable"),
//			@ApiResponse(responseCode = "410", description = "Transfer Abandoned"),
//			@ApiResponse(responseCode = "406", description = "Not Acceptable"),
//			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public void invokeTransfer(
			@RequestBody(description = "transfer request", required = true) TransferRequest transferRequest,
			@Context UriInfo uriInfo, @Suspended AsyncResponse asyncResponse) {

//		AsyncSipServlet.getSipLogger().logObjectAsJson(Level.FINEST, transferRequest);

		SipApplicationSession appSession = null;
		try {

			Callflow callflow = null;

			if (transferRequest != null && transferRequest.sessionKey != null) {
				Set<String> appSessionIds = sipUtil.getSipApplicationSessionIds(transferRequest.sessionKey);

				sipLogger.finest("TransferAPI.invokeTransfer - invoking sipUtil.getSipApplicationSessionIds("
						+ transferRequest.sessionKey + ");");
				if (sipLogger.isLoggable(Level.FINEST)) {
					SipApplicationSession _appSession;
					for (String id : appSessionIds) {
						_appSession = sipUtil.getApplicationSessionById(id);
						if (_appSession != null) {
							sipLogger.finest(_appSession, "TransferAPI.invokeTransfer - appSessionId=" + id
									+ ", isNull=false, isValid=" + _appSession.isValid());
						} else {
							sipLogger.finer(
									"TransferAPI.invokeTransfer - appSessionId=" + id + "isNull=true, isValid=false");
						}
					}
				}

				if (appSessionIds != null && appSessionIds.size() >= 1) {
					String appSessionId = (String) appSessionIds.toArray()[0];
					appSession = sipUtil.getApplicationSessionById(appSessionId);

					if (appSession != null) {
						sipLogger.finer(appSession,
								"TransferAPI.invokeTransfer - TransferAPI appSession.id=" + appSession.getId());
					} else {
						sipLogger.warning(appSession, "TransferAPI.invokeTransfer - TransferAPI appSession.id=null");
					}

				}
			} else {

				TransferResponse transferResponse = new TransferResponse();
				transferResponse.status = 500;
				transferResponse.description = DESC_MISSING_JSON;
				transferResponse.request = transferRequest;

				if (sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine(appSession, "TransferAPI.invokeTransfer - transferResponse="
							+ Logger.serializeObjectWithoutNLCR(transferResponse));
				}

				asyncResponse.resume(Response.status(Status.NOT_ACCEPTABLE).entity(transferResponse).build());
				return;

			}

			if (appSession != null) {

				appSession.setAttribute(TXFER_REQUEST, transferRequest);

				// Now find transferee
				SipSession transfereeSession = null;
//				Address sipAddress = null;

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(appSession,
							"TransferAPI.invokeTransfer - TransferAPI iterating through sessions... Looking for name="
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
						sipLogger.finer(sipSession,
								"TransferAPI.invokeTransfer - sipSession.id=" + sipSession.getId() + ", match!");
						break;
					} else {
						sipLogger.finer(sipSession,
								"TransferAPI.invokeTransfer - sipSession.id=" + sipSession.getId() + ", no match.");
					}

				}

				if (transfereeSession != null) {

					// can finally log request
					if (sipLogger.isLoggable(Level.FINE)) {
						sipLogger.fine(appSession, "TransferAPI.invokeTransfer - transferRequest="
								+ Logger.serializeObjectWithoutNLCR(transferRequest));
					}

					// handle glare
					if (transfereeSession.getAttribute(EXPECT_ACK_ATTR) != null) {
						TransferResponse transferResponse = new TransferResponse();
						transferResponse.status = 491;
						transferResponse.description = DESC_REQUEST_PENDING;
						transferResponse.request = transferRequest;

						if (sipLogger.isLoggable(Level.FINE)) {
							sipLogger.fine(appSession, "TransferAPI.invokeTransfer - transferResponse="
									+ Logger.serializeObject(transferResponse));
						}

						asyncResponse.resume(Response.status(Status.NOT_ACCEPTABLE).entity(transferResponse).build());
						return;
					}

					Address transferee = (Address) transfereeSession.getAttribute(SIP_ADDRESS_ATTR);
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

					sipLogger.finer(transfereeSession, "TransferAPI.invokeTransfer -  target=" + target);

					if (target != null) {

						SipSession transferorSession = getLinkedSession(transfereeSession);

						Address transferor = (Address) transferorSession.getAttribute(SIP_ADDRESS_ATTR);

//						sipLogger.info(transferorSession, "TransferAPI REST transfer request; transferee=" + transferee
//								+ ", target=" + target + ", transferor=" + transferor);

						// bob (transferee) is doing the 'transfer', to alice
						DummyRequest refer = new DummyRequest(REFER, transferor, transferee);
						refer.setRequestURI(transferee.getURI());
						refer.setApplicationSession(appSession);
						refer.setSession(transferorSession);
						refer.setHeader(REFER_TO_HEADER, target.toString());
						refer.setHeader(REFERRED_BY_HEADER, transferor.toString());
						sipLogger.finer(transferorSession, "TransferAPI.invokeTransfer - Getting Referred-By: "
								+ refer.getHeader(REFERRED_BY_HEADER));

						if (transferRequest.target.inviteHeaders != null) {
							for (Header header : transferRequest.target.inviteHeaders) {
								refer.setHeader(header.name, header.value);
							}
						}

						TransferStyle style = transferRequest.style;
						if (style == null) {
							style = settings.getCurrent().getDefaultTransferStyle();
						}
						if (style == null) {
							style = TransferStyle.blind;
						}

						switch (style) {
						case attended:
						case conference:
						case blind:
							callflow = new BlindTransfer(this, settings.getCurrent(), false);
							break;
						case refer:

							callflow = new ReferTransfer(this, settings.getCurrent());
						}

						// Add any inviteHeaders
						((Transfer) callflow).setInviteHeaders(transferRequest.target.inviteHeaders);

						sipLogger.finest(transferorSession, "TransferAPI callflow=" + callflow);

						if (appSession.getAttribute(INITIAL_REFER_ATTR) == null) {
							appSession.setAttribute(INITIAL_REFER_ATTR, refer);
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
							transferResponse.description = DESC_FIRE_AND_FORGET;
							transferResponse.request = transferRequest;

							if (sipLogger.isLoggable(Level.FINE)) {
								sipLogger.fine(appSession, "TransferAPI.invokeTransfer - transferResponse="
										+ Logger.serializeObjectWithoutNLCR(transferResponse));
							}
							asyncResponse.resume(Response.status(Status.ACCEPTED).entity(transferResponse).build());
						}
					} else {
						TransferResponse transferResponse = new TransferResponse();
						transferResponse.status = 500;
						transferResponse.description = DESC_TARGET_BUILD_FAILED;
						transferResponse.request = transferRequest;

						if (sipLogger.isLoggable(Level.FINE)) {
							sipLogger.fine(appSession, "TransferAPI.invokeTransfer - transferResponse="
									+ Logger.serializeObjectWithoutNLCR(transferResponse));
						}

						asyncResponse.resume(Response.status(Status.NOT_ACCEPTABLE).entity(transferResponse).build());
					}

				} else {
					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(appSession, "TransferAPI.invokeTransfer - transferRequest="
								+ Logger.serializeObjectWithoutNLCR(transferRequest));
					}

					TransferResponse transferResponse = new TransferResponse();
					transferResponse.status = 404;
					transferResponse.description = DESC_DIALOG_NOT_FOUND;
					transferResponse.request = transferRequest;

					if (sipLogger.isLoggable(Level.FINE)) {
						sipLogger.fine(appSession, "TransferAPI.invokeTransfer - transferResponse="
								+ Logger.serializeObjectWithoutNLCR(transferResponse));
					}

					asyncResponse.resume(Response.status(Status.NOT_FOUND).entity(transferResponse).build());
				}

			} else {

				if (sipLogger.isLoggable(Level.WARNING)) {
					AsyncSipServlet.getSipLogger()
							.warning("No appSession found for sessionKey=" + transferRequest.sessionKey
									+ ", transferRequest=" + Logger.serializeObjectWithoutNLCR(transferRequest));
				}

				TransferResponse transferResponse = new TransferResponse();
				transferResponse.status = 404;
				transferResponse.description = DESC_SESSION_NOT_FOUND;
				transferResponse.request = transferRequest;

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(appSession, "TransferAPI.invokeTransfer - transferResponse="
							+ Logger.serializeObjectWithoutNLCR(transferResponse));
				}

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
			transferResponse.description = DESC_INTERNAL_ERROR;
			transferResponse.request = transferRequest;

			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(appSession,
						"TransferAPI.invokeTransfer - transferResponse=" + Logger.serializeObjectWithoutNLCR(transferResponse));
			}

			asyncResponse.resume(Response.status(Status.INTERNAL_SERVER_ERROR).entity(transferResponse).build());
		}
	}

	@Override
	public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {

		try {

			if (sipLogger.isLoggable(Level.INFO)) {
				URI ruri = inboundRefer.getRequestURI();
				String from = inboundRefer.getFrom().toString();
				String to = inboundRefer.getTo().toString();
				String referTo = inboundRefer.getHeader(REFER_TO_HEADER);
				String referredBy = inboundRefer.getHeader(REFERRED_BY_HEADER);
				sipLogger.info(inboundRefer, "TransferAPI.transferRequested - ruri=" + ruri + ", from=" + from + ", to="
						+ to + ", referTo=" + referTo + ", referredBy=" + referredBy);
			}

		} catch (Exception e) {
			sipLogger.severe(inboundRefer, e);
		}

	}

	@Override
	public void transferInitiated(SipServletRequest request) throws ServletException, IOException {
		// No action needed; transfer initiation is handled in the callflow
	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		sipLogger.finer(response, "TransferAPI.transferCompleted - " + response.getMethod() + " " + response.getStatus()
				+ " " + response.getReasonPhrase());

		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.info(response, "TransferAPI.transferCompleted - status=" + response.getStatus());
		}

		SipApplicationSession appSession = response.getApplicationSession();
		TransferRequest transferRequest = (TransferRequest) appSession.getAttribute(TXFER_REQUEST);

		if (transferRequest != null && transferRequest.notification != null
				&& Notification.Style.async.equals(transferRequest.notification.style)) {

			AsyncResponse asyncResponse = responseMap.remove(response.getApplicationSession().getId());
			if (asyncResponse != null) {

				TransferResponse txferResp = new TransferResponse();
				txferResp.event = "transferCompleted";
				txferResp.method = response.getMethod();
				txferResp.status = response.getStatus();
				txferResp.description = response.getReasonPhrase();
				txferResp.request = (TransferRequest) response.getApplicationSession().getAttribute(TXFER_REQUEST);

				if (sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine(response,
							"TransferAPI.transferCompleted - transferResponse=" + Logger.serializeObjectWithoutNLCR(txferResp));
				}

				asyncResponse.resume(Response.status(Status.OK).entity(txferResp).build());
			} else {

				sipLogger.severe(response,
						"TransferAPI.transferCompleted - Failed to remove asyncResponse from responseMap. Cannot 'resume'.");

			}

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

			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(response,
						"TransferAPI.transferDeclined - transferResponse=" + Logger.serializeObjectWithoutNLCR(txferResp));
			}

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

			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(cancelRequest,
						"TransferAPI.transferAbandoned - transferResponse=" + Logger.serializeObject(txferResp));
			}

			asyncResponse.resume(Response.status(Status.GONE).entity(txferResp).build());
		}
	}

}
