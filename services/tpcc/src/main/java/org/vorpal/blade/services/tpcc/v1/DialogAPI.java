package org.vorpal.blade.services.tpcc.v1;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.services.tpcc.TpccServlet;
import org.vorpal.blade.services.tpcc.callflows.CreateDialog;
import org.vorpal.blade.services.tpcc.v1.dialog.Dialog;
import org.vorpal.blade.services.tpcc.v1.dialog.DialogProperties;
import org.vorpal.blade.services.tpcc.v1.dialog.DialogPutAttributes;
import org.vorpal.blade.services.tpcc.v1.session.SessionCreateRequest;
import org.vorpal.blade.services.tpcc.v1.session.SessionGetResponse;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/// 3PCC (Third-Party Call Control) REST API — the atomic command set a
/// controller chains to build and manage a call between two or more parties:
///
/// ```
/// POST   /session                                          -- create a session, returns {sessionId}
/// POST   /dialog/{sessionId}                               -- create a leg, returns {sessionId, dialogId}
/// GET    /dialog/{sessionId}                               -- list the session's legs
/// GET    /dialog/{sessionId}/{dialogId}                    -- one leg's properties
/// PUT    /dialog/{sessionId}/{dialogId}                    -- set a leg's attributes
/// PUT    /dialog/{sessionId}/{dialogA}/connect/{dialogB}   -- bridge two legs
/// DELETE /dialog/{sessionId}/{dialogId}                    -- tear a leg down (BYE)
/// ```
///
/// A `sessionId` is the SIP application-session key; a `dialogId` is one leg's
/// Vorpal dialog id (the `X-Vorpal-Dialog` attribute). Create returns the
/// dialogId so the caller can chain connect/delete; `GET /dialog/{sessionId}`
/// lets a UI discover and poll every leg.
///
/// Not yet implemented (roadmap): per-leg mute / hold (the framework ships
/// `CallflowMute` / `CallflowHold` / `CallflowResume`), and a friendlier v2
/// `POST /call {from,to}` that runs create+create+connect in one shot.
@OpenAPIDefinition(info = @Info(title = "3PCC API", version = "1", description = "Allows one entity (the controller) to set up and manage a call between two or more other parties."))
@Path("api/v1")
public class DialogAPI extends Callflow implements Serializable {

	private static final long serialVersionUID = 1L;

	/// Parks the JAX-RS async continuation (which is not serializable) in static
	/// memory so the SIP callback in `CreateDialog` can resume it, keyed by the
	/// leg's SipSession id. Carries the `sessionId` so the resume can echo it back.
	public static class ResponseStuff {
		public UriInfo uriInfo;
		public AsyncResponse asyncResponse;
		public String sessionId;

		public ResponseStuff(UriInfo uriInfo, AsyncResponse asyncResponse, String sessionId) {
			this.uriInfo = uriInfo;
			this.asyncResponse = asyncResponse;
			this.sessionId = sessionId;
		}
	}

	public static Map<String, ResponseStuff> responseMap = new ConcurrentHashMap<>();

	public DialogAPI() {
		// no-arg ctor for JAX-RS per-request instantiation
	}

	/// Find one leg (SipSession) in a session by its Vorpal dialog id. Returns
	/// null if the session or the requested leg is absent. Replaces three
	/// copy-pasted loops that overwrote the requested id with the last one
	/// enumerated (so they always resolved the wrong leg).
	@SuppressWarnings("unchecked")
	private SipSession findLeg(SipApplicationSession appSession, String dialogId) {
		if (appSession == null || dialogId == null) {
			return null;
		}
		for (SipSession sipSession : (Set<SipSession>) appSession.getSessionSet(SIP)) {
			if (dialogId.equals(this.getVorpalDialogId(sipSession))) {
				return sipSession;
			}
		}
		return null;
	}

	@POST
	@Path("session")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(operationId = "createSession", summary = "Create a 3PCC session; returns the sessionId to use for its dialogs.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response createSession(
			@RequestBody(description = "optional session properties", required = false) SessionCreateRequest sessionData) {

		try {
			// Generate a unique 8-hex application-session key. This IS the
			// sessionId the caller uses on every later request.
			String sessionId;
			do {
				sessionId = String.format("%08X", ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL));
			} while (sipUtil.getApplicationSessionByKey(sessionId, false) != null);

			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, true);

			if (sessionData != null) {
				if (sessionData.expires != null) {
					appSession.setExpires(sessionData.expires);
				}
				if (sessionData.attributes != null) {
					for (Entry<String, String> entry : sessionData.attributes.entrySet()) {
						appSession.setAttribute("3pcc_" + entry.getKey(), entry.getValue());
					}
				}
			}

			sipLogger.info(appSession, "3PCC createSession: sessionId=" + sessionId);
			return Response.ok(Collections.singletonMap("sessionId", sessionId)).build();

		} catch (Exception e) {
			sipLogger.severe(e);
			return Response.status(500, e.getMessage()).build();
		}
	}

	@POST
	@Asynchronous
	@Path("dialog/{sessionId}")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(operationId = "createDialog", summary = "Create a new dialog (leg); returns its dialogId.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Session Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public void createDialog(
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@RequestBody(description = "the party to call", required = true) Dialog dialogData,
			@Context UriInfo uriInfo, @Suspended AsyncResponse asyncResponse) {

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {

				SipServletRequest invite = sipFactory.createRequest(appSession, INVITE, dialogData.localParty,
						dialogData.remoteParty);

				if (dialogData.requestUri != null) {
					invite.setRequestURI(sipFactory.createURI(dialogData.requestUri));
				}

				// OFFERLESS initial INVITE (RFC 3725 Flow I): the party answers
				// with its real offer and CreateDialog answers in the ACK with an
				// RFC 3264 inactive SDP — media parked until connectDialogs
				// re-INVITEs to bridge the call.

				// Park the async continuation (not serializable) in static memory,
				// keyed by the leg's SipSession id, for CreateDialog to resume.
				responseMap.put(invite.getSession().getId(), new ResponseStuff(uriInfo, asyncResponse, sessionId));

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

	@GET
	@Path("dialog/{sessionId}")
	@Operation(operationId = "listDialogs", summary = "List every leg in a session, keyed by dialogId.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Session Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response listDialogs(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId) {

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			if (appSession == null) {
				return Response.status(Status.NOT_FOUND).build();
			}
			return Response.ok(new SessionGetResponse(appSession)).build();

		} catch (Exception e) {
			sipLogger.severe(e);
			return Response.status(500, e.getMessage()).build();
		}
	}

	@GET
	@Path("dialog/{sessionId}/{dialogId}")
	@Operation(operationId = "getDialog", summary = "Get one leg's properties.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response getDialog(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId) {

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			SipSession leg = findLeg(appSession, dialogId);

			if (leg == null) {
				return Response.status(Status.NOT_FOUND).build();
			}
			return Response.ok(new DialogProperties(leg)).build();

		} catch (Exception e) {
			sipLogger.severe(e);
			return Response.status(500, e.getMessage()).build();
		}
	}

	@PUT
	@Path("dialog/{sessionId}/{dialogId}")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(operationId = "setDialogAttributes", summary = "Set a leg's attributes.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response setDialogAttributes(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId,
			@RequestBody(description = "attributes to set", required = true) DialogPutAttributes dialogData) {

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			SipSession leg = findLeg(appSession, dialogId);

			if (leg == null) {
				return Response.status(Status.NOT_FOUND).build();
			}

			if (dialogData != null && dialogData.attributes != null) {
				for (Entry<String, String> entry : dialogData.attributes.entrySet()) {
					leg.setAttribute("3pcc_" + entry.getKey(), entry.getValue());
				}
			}
			return Response.ok().build();

		} catch (Exception e) {
			sipLogger.severe(e);
			return Response.status(500, e.getMessage()).build();
		}
	}

	@PUT
	@Asynchronous
	@Path("dialog/{sessionId}/{dialogId}/connect/{dialogId2}")
	@Operation(operationId = "connectDialogs", summary = "Bridge two legs together.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Session or Dialog Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public void connectDialogs(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId,
			@Parameter(required = true, example = "BA11", description = "Dialog ID") @PathParam("dialogId2") String dialogId2,
			@Suspended AsyncResponse asyncResponse) {

		// Park the async continuation (not serializable) keyed by the sessionId
		// (a String path param — non-null). The former code keyed on
		// getVorpalSessionId(appSession), which is null for a REST-created
		// session, throwing a null-key NPE into ConcurrentHashMap.
		TpccServlet.responseMap.put(sessionId, asyncResponse);

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			SipSession aliceLeg = findLeg(appSession, dialogId);
			SipSession bobLeg = findLeg(appSession, dialogId2);

			if (aliceLeg == null || bobLeg == null) {
				resumeConnect(sessionId, Response.status(Status.NOT_FOUND).build());
				return;
			}

			SipServletRequest aliceRequest = aliceLeg.createRequest(INVITE);
			SipServletRequest bobRequest = bobLeg.createRequest(INVITE);

			// Offerless re-INVITE Alice; take her SDP offer to Bob; feed Bob's
			// answer back to Alice in her ACK; ACK Bob. Bridge complete.
			sendRequest(aliceRequest, (aliceResponse) -> {
				if (!successful(aliceResponse)) {
					// provisional responses simply keep waiting; only a final
					// non-2xx ends the attempt.
					if (failure(aliceResponse)) {
						resumeConnect(sessionId, Response
								.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase()).build());
					}
					return;
				}

				sendRequest(copyContent(aliceResponse, bobRequest), (bobResponse) -> {
					if (!successful(bobResponse)) {
						if (failure(bobResponse)) {
							// resume with BOB's status, not Alice's success.
							resumeConnect(sessionId, Response
									.status(bobResponse.getStatus(), bobResponse.getReasonPhrase()).build());
						}
						return;
					}

					// ACK Alice with Bob's answer; ACK Bob.
					sendRequest(copyContent(bobResponse, aliceResponse.createAck()));
					sendRequest(bobResponse.createAck());

					resumeConnect(sessionId,
							Response.status(aliceResponse.getStatus(), aliceResponse.getReasonPhrase()).build());
				});
			});

		} catch (Exception e) {
			sipLogger.severe(e);
			resumeConnect(sessionId, Response.status(500, e.getMessage()).build());
		}
	}

	/// Resume-and-remove the parked continuation for a connect, so the static
	/// map never leaks an entry after the response is sent.
	private void resumeConnect(String sessionId, Response response) {
		AsyncResponse parked = TpccServlet.responseMap.remove(sessionId);
		if (parked != null) {
			parked.resume(response);
		}
	}

	@DELETE
	@Asynchronous
	@Path("dialog/{sessionId}/{dialogId}")
	@Operation(operationId = "deleteDialog", summary = "Tear a leg down (send BYE).")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public void deleteDialog(@Context UriInfo uriInfo,
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId,
			@Parameter(required = true, example = "BA5E", description = "Dialog ID") @PathParam("dialogId") String dialogId,
			@Suspended AsyncResponse asyncResponse) {

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			SipSession leg = findLeg(appSession, dialogId);

			if (leg == null) {
				asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
				return;
			}

			sendRequest(leg.createRequest(BYE), (byeResponse) -> {
				asyncResponse.resume(
						Response.status(byeResponse.getStatus(), byeResponse.getReasonPhrase()).build());
			});

		} catch (Exception e) {
			sipLogger.severe(e);
			asyncResponse.resume(Response.status(500, e.getMessage()).build());
		}
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		throw new ServletException("DialogAPI is REST-initiated; it never handles an inbound SIP request.");
	}

}
