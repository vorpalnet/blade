package org.vorpal.blade.services.tpcc.v1;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.ejb.Asynchronous;
import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.services.tpcc.TpccServlet;
import org.vorpal.blade.services.tpcc.callflows.CreateDialog;
import org.vorpal.blade.services.tpcc.v1.DialogAPI.ResponseStuff;
import org.vorpal.blade.services.tpcc.v1.dialog.Dialog;
import org.vorpal.blade.services.tpcc.v1.dialog.DialogProperties;
import org.vorpal.blade.services.tpcc.v1.dialog.DialogPutAttributes;

import com.oracle.coherence.common.collections.ConcurrentHashMap;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/*
 * [x] POST   /dialog/{sessionId}                               -- creates a dialog
 * [x] GET    /dialog/{sessionId}/{dialogId}                    -- returns dialog properties 
 * [x] DELETE /dialog/{sessionId}/{dialogId}                    -- tears down dialog
 * [x] PUT    /dialog/{sessionId}/{dialogId}                    -- sets dialog properties 
 * [x] PUT    /dialog/{sessionId}/{dialog01}/connect/{dialog02} -- connects two dialogs together
 * [ ] PUT    /dialog/{sessionId}/{dialogId}/mute               -- mutes the party
 * [ ] DELETE /dialog/{sessionId}/{dialogId}/mute               -- unmutes the party
 * [ ] PUT    /dialog/{sessionId}/{dialogId}/hold               -- puts the party on hold
 * [ ] DELETE /dialog/{sessionId}/{dialogId}/hold               -- takes the party off hold
 */

@OpenAPIDefinition(info = @Info(title = "3PCC API", version = "1", description = "Allows one entity (which we call the controller) to set up and manage a communications relationship or telephone call between two or more other parties."))
@Path("api/v1")

public class DialogAPI extends Callflow implements Serializable {

	public class ResponseStuff {
		public UriInfo uriInfo;
		public AsyncResponse asyncResponse;

		public ResponseStuff(UriInfo uriInfo, AsyncResponse asyncResponse) {
			this.uriInfo = uriInfo;
			this.asyncResponse = asyncResponse;
		}

	}

	public static Map<String, ResponseStuff> responseMap = new ConcurrentHashMap<>();

	private static final long serialVersionUID = 1L;

	private transient AsyncResponse asyncResponse2;

	public DialogAPI() {
		// do nothing;
	}

	@POST
	@Asynchronous
	@Path("dialog/{sessionId}")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Create a new dialog.")
		@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "OK"), 
			@ApiResponse(responseCode = "404", description = "Not Found"), 
	        @ApiResponse(responseCode = "500", description = "Internal Server Error")
	})
	
	public void createDialog(
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@RequestBody(description = "optional session properties", required = true) Dialog dialogData,
			@Context UriInfo uriInfo, @Suspended AsyncResponse asyncResponse) {

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {

				SipServletRequest invite = sipFactory.createRequest(appSession, INVITE, dialogData.localParty,
						dialogData.remoteParty);

				if (dialogData.requestUri != null) {
					invite.setRequestURI(sipFactory.createURI(dialogData.requestUri));
				}

				invite.setContent(blackhole, "application/sdp");

				// Save this in static memory so it's not serialized
				responseMap.put(invite.getSession().getId(), new ResponseStuff(uriInfo, asyncResponse));

				CreateDialog cd = new CreateDialog();
				cd.invoke(invite);

			} else {
				asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
			}

		} catch (Exception e) {
			sipLogger.severe(e);
			asyncResponse.resume(Response.status(500, e.getMessage()).build());
		}

	}

//	public void createDialog(
//			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
//			@RequestBody(description = "optional session properties", required = true) Dialog dialogData,
//			@Context UriInfo uriInfo, @Suspended AsyncResponse asyncResponse) {
//
//		try {
//			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
//
//			if (appSession != null) {
//
//				SipServletRequest invite = sipFactory.createRequest(appSession, INVITE, dialogData.localParty,
//						dialogData.remoteParty);
//
//				if (dialogData.requestUri != null) {
//					invite.setRequestURI(sipFactory.createURI(dialogData.requestUri));
//				}
//
//				invite.setContent(blackhole, "application/sdp");
//
//				// Save this in static memory so it's not serialized
//				responseMap.put(invite.getSession().getId(), new ResponseStuff(uriInfo, asyncResponse));
//
//				sendRequest(invite, (inviteResponse) -> {
//
//					if (successful(inviteResponse)) {
//
//						sendRequest(inviteResponse.createAck());
//
//						ResponseStuff rstuff = DialogAPI.responseMap.remove(inviteResponse.getSession().getId());
//						Response response = Response.status(inviteResponse.getStatus(), inviteResponse.getReasonPhrase())
//								.build();
//						rstuff.asyncResponse.resume(response);
//
//					} else if (failure(inviteResponse)) {
//
//						ResponseStuff rstuff = DialogAPI.responseMap.get(inviteResponse.getSession().getId());
//						rstuff.asyncResponse
//								.resume(Response.status(inviteResponse.getStatus(), inviteResponse.getReasonPhrase()).build());
//
//					}
//				});
//			} else {
//				asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
//			}
//
//		} catch (Exception e) {
//			sipLogger.severe(e);
//			asyncResponse.resume(Response.status(500, e.getMessage()).build());
//		}
//
//	}
	
	@GET
	@Asynchronous
	@Path("dialog/{sessionId}/{dialogId}/connect/{dialogId2}")
	@Operation(summary = "Connect two dialogs together.")
	public void connectDialogs(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId,
			@Parameter(required = true, example = "BA11", description = "Dialog ID") @PathParam("dialogId2") String dialogId2
			// @RequestBody(description = "optional session properties", required = true)
			// DialogConnectRequest dcr,
			, @Suspended AsyncResponse asyncResponse

	) {
		SipServletRequest invite;
		SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

		// can't serialize AsyncResponse, so jam it in static memory
		String vorpalSession = (String) appSession.getAttribute("X-Vorpal-Session");
		TpccServlet.responseMap.put(vorpalSession, asyncResponse);

		try {

			if (appSession != null) {

				// This is the quickest way I can think of to find sessions
				Map<String, SipSession> sessionsMap = new HashMap<>();
				String dialog = null;
				for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet(SIP)) {
					dialog = (String) sipSession.getAttribute("X-Vorpal-Dialog");
					if (dialog != null) {
						sessionsMap.put(dialog, sipSession);
					}
				}

				SipServletRequest aliceRequest = sessionsMap.get(dialogId).createRequest(INVITE);
				SipServletRequest bobRequest = sessionsMap.get(dialogId2).createRequest(INVITE);

				// Send empty INVITE to Alice;
				sendRequest(aliceRequest, (aliceResponse) -> {

					if (failure(aliceResponse)) {

						TpccServlet.responseMap.get(vorpalSession).resume(
								Response.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase()).build());

//						asyncResponse.resume(
//								Response.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase()).build());						

					} else {
						if (successful(aliceResponse)) {

							// Send INVITE w/SDP to Bob
							sendRequest(copyContent(aliceResponse, bobRequest), (bobResponse) -> {

								if (failure(bobResponse)) {

									TpccServlet.responseMap.get(vorpalSession)
											.resume(Response
													.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase())
													.build());

//									asyncResponse.resume(
//											Response.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase())
//													.build());
								} else {

									if (successful(bobResponse)) {

										// Send ACK w/SDP to Alice;
										sendRequest(copyContent(bobResponse, aliceResponse.createAck()));

										// Send ACK to Bob;
										sendRequest(bobResponse.createAck());

										TpccServlet.responseMap.get(vorpalSession).resume(Response
												.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase())
												.build());

//										asyncResponse.resume(Response
//												.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase())
//												.build());
									}
								}

							});
						}
					}

				});

			}

		} catch (Exception e) {
//			Response response;
//			response = Response.status(500, e.getMessage()).build();
//			asyncResponse.resume(response);			

			TpccServlet.responseMap.get(vorpalSession).resume(Response.status(500, e.getMessage()).build());

			sipLogger.severe(e);
		}

	}

	@GET
	@Path("dialog/{sessionId}/{dialogId}")
	@Operation(summary = "Get dialog properties.")
	public Response getDialogAttributes(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId) {

		Response response;

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {

				// This is the quickest way I can think of to find sessions
				Map<String, SipSession> sessionsMap = new HashMap<>();
				for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet(SIP)) {
					dialogId = (String) sipSession.getAttribute("X-Vorpal-Dialog");
					if (dialogId != null) {
						sessionsMap.put(dialogId, sipSession);
					}
				}

				DialogProperties dp;
				dp = new DialogProperties(sessionsMap.get(dialogId));

				response = Response.accepted().entity(dp).build();
			} else {
				response = Response.status(Status.NOT_FOUND).build();

			}

		} catch (Exception e) {

			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

	@PUT
	@Path("dialog/{sessionId}/{dialogId}")
	@Operation(summary = "Get dialog properties.")
	public Response getDialogAttributes(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId,
			@RequestBody(description = "optional session properties", required = true) DialogPutAttributes dialogData) {

		Response response;

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {

				// This is the quickest way I can think of to find sessions
				Map<String, SipSession> sessionsMap = new HashMap<>();
				for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet(SIP)) {
					dialogId = (String) sipSession.getAttribute("X-Vorpal-Dialog");
					if (dialogId != null) {
						sessionsMap.put(dialogId, sipSession);
					}
				}

				SipSession sipSession = sessionsMap.get(dialogId);
				if (sipSession != null) {

					for (Entry<String, String> entry : dialogData.attributes.entrySet()) {
						sipSession.setAttribute("3pcc_" + entry.getKey(), entry.getValue());
					}

					response = Response.accepted().build();

				} else {
					response = Response.status(Status.NOT_FOUND).build();
				}

			} else {
				response = Response.status(Status.NOT_FOUND).build();
			}

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

	@DELETE
	@Asynchronous
	@Path("dialog/{sessionId}/{dialogId}")
	@Operation(summary = "Delete dialog (send BYE).")
	public void sendBye(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId,
			@Suspended AsyncResponse asyncResponse) {

//		Response response;

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {

				// This is the quickest way I can think of to find sessions
				Map<String, SipSession> sessionsMap = new HashMap<>();
				for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet(SIP)) {
					dialogId = (String) sipSession.getAttribute("X-Vorpal-Dialog");
					if (dialogId != null) {
						sessionsMap.put(dialogId, sipSession);
					}
				}

				SipSession sipSession = sessionsMap.get(dialogId);
				if (sipSession != null) {

					sendRequest(sipSession.createRequest(BYE), (byeResponse) -> {
						asyncResponse.resume(
								Response.status(byeResponse.getStatus(), byeResponse.getReasonPhrase()).build());
					});

				} else {
					asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
				}

			} else {
				asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
			}

		} catch (Exception e) {
			asyncResponse.resume(Response.status(500, e.getMessage()).build());
			sipLogger.severe(e);
		}
	}

//	@POST
//	@Path("session")
//	@Consumes({ MediaType.APPLICATION_JSON })
//	@Operation(summary = "Create a new session with properties defined in JSON body.")
//	public Response createSession(
//			@RequestBody(description = "optional session properties", required = true) Session sessionData,
//			@Context UriInfo uriInfo) {
//
//		Response response = null;
//
//		sipLogger.info("createSession with JSON...");
//		System.out.println("createSession...");
//
//		try {
//
//			SipApplicationSession appSession;
//			String indexKey = null;
//			do {
//				indexKey = String.format("%08X", Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)))
//						.toUpperCase();
//			} while (null == (appSession = sipFactory.createApplicationSessionByKey(indexKey)));
//			appSession.setAttribute("X-Vorpal-Session", indexKey);
//			appSession.addIndexKey(indexKey);
//
//			if (sessionData != null && sessionData.expires != null) {
//				appSession.setExpires(sessionData.expires);
//			} else {
//				Integer expiration = TpccServlet.settingsManager.getCurrent().getSession().getExpiration();
//				if (expiration != null) {
//					appSession.setExpires(expiration);
//				}
//			}
//
//			if (sessionData != null) {
//				if (sessionData.attributes != null && sessionData.attributes.size() > 0) {
//					for (Entry<String, String> entry : sessionData.attributes.entrySet()) {
//						appSession.setAttribute("3pcc_" + entry.getKey(), entry.getValue());
//					}
//				}
//
//				if (sessionData.groups != null && sessionData.groups.size() > 0) {
//					for (String group : sessionData.groups) {
//						appSession.addIndexKey(group);
//					}
//				}
//			}
//
//			UriBuilder uriBuilder = uriInfo.getAbsolutePathBuilder().path(indexKey);
//			response = Response.created(uriBuilder.build()).build();
//
//		} catch (Exception e) {
//			sipLogger.severe(e);
//			e.printStackTrace();
//		}
//
//		if (response == null) {
//			response = Response.serverError().build();
//		}
//
//		return response;
//
//	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	static final String blackhole = "" + //
			"v=0\r\n" + //
			"o=- 15474517 1 IN IP4 127.0.0.1\r\n" + //
			"s=cpc_med\r\n" + //
			"c=IN IP4 0.0.0.0\r\n" + //
			"t=0 0\r\n" + //
			"m=audio 23348 RTP/AVP 0\r\n" + //
			"a=rtpmap:0 pcmu/8000\r\n" + //
			"a=inactive\r\n";

}
