package org.vorpal.blade.services.tpcc.v1;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.callflow.Callflow;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

@OpenAPIDefinition(info = @Info(title = "Third Party Call Control", version = "1", description = "Allows one entity (which we call the controller) to set up and manage a communications relationship or telephone call between two or more other parties."))
@Path("api/v1")

public class ThirdPartyCallControl extends Callflow {
	private static final long serialVersionUID = 1L;

	public class CreateRequest {
		public Integer inactivityTimer;
		public java.net.URL notificationURL;
//		public String notificationURL;
	}

	@GET
	@Path("create")
	@Operation(summary = "creates a new SipApplicationSession with a unique index key")
	public Response create(@Context UriInfo uriInfo) {

		SipApplicationSession appSession;
		String indexKey = null;
		do {
			indexKey = String.format("%08X", Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)))
					.toUpperCase();
		} while (null == (appSession = sipFactory.createApplicationSessionByKey(indexKey)));

		appSession.setAttribute("X-Vorpal-Session", indexKey);
		appSession.addIndexKey(indexKey);

		UriBuilder uriBuilder = uriInfo.getAbsolutePathBuilder().path(indexKey);
		return Response.created(uriBuilder.build()).build();
	}

	@POST
	@Path("create")
	@Operation(summary = "creates a new SipApplicationSession with a unique index key")
	public Response create( //

			@RequestBody(description = "optional inactivity timeout in minutes; optional callback URL for notifications", //
					required = false, //
					content = @Content(schema = @Schema(implementation = CreateRequest.class))) CreateRequest createRequest, //

			@Context UriInfo uriInfo) {

		SipApplicationSession appSession;
		String indexKey = null;
		do {
			indexKey = String.format("%08X", Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)))
					.toUpperCase();
		} while (null == (appSession = sipFactory.createApplicationSessionByKey(indexKey)));

		appSession.setAttribute("X-Vorpal-Session", indexKey);
		appSession.addIndexKey(indexKey);

		if (createRequest != null) {

			if (createRequest.inactivityTimer != null) {
				appSession.setExpires(createRequest.inactivityTimer);
			}

			if (createRequest.notificationURL != null) {
				appSession.setAttribute("NOTIFICATION_URL", createRequest.notificationURL);
			}

		}

		UriBuilder uriBuilder = uriInfo.getAbsolutePathBuilder().path(indexKey);
		return Response.created(uriBuilder.build()).build();
	}

	public class Header {
		public String name;
		public String value;
	}

	public class AttachRequest {
		public Address to;
		public Address from;
		public Header headers[];
		public Address media;
		public String context;
	}

	public class CallControlNotification {
		public String operation;
		public String context;
		public String sessionId;
		public String dialogId;
	}

	@POST
	@Path("{session}/attach")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "creates a call dialog and attaches it to the session")
	public Response attachMediaSession( //
			@Parameter(required = true, example = "ABCD6789", description = "the session id") @PathParam("session") String sessionId,
			@RequestBody(description = "endpoint; optional headers; optional media URIs", required = true, content = @Content(schema = @Schema(implementation = AttachRequest.class))) AttachRequest attachRequest,
			@Context UriInfo uriInfo) {

		Response response = null;
		String dialogId = null;

		SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
		String notificationUrl = (String) appSession.getAttribute("NOTIFICATION_URL");

		if (appSession != null) {
			SipServletRequest sipRequest = sipFactory.createRequest(appSession, INVITE, attachRequest.from,
					attachRequest.to);
			SipSession sipSession = sipRequest.getSession();
			dialogId = createVorpalDialogId(sipSession);

			UriBuilder uriBuilder = uriInfo.getAbsolutePathBuilder().path(dialogId);
			response = Response.created(uriBuilder.build()).build();

			if (attachRequest.headers != null) {
				for (Header header : attachRequest.headers) {
					sipRequest.addHeader(header.name, header.value);
				}
			}

			if (attachRequest.media != null) {

			} else {
				try {
					sipRequest.setContent(blackhole, "application/sdp");

					sipLogger.info(sipRequest, "sending INVITE");

					// send a sip INVITE with muted audio
					sendRequest(sipRequest, (sipResponse) -> {
						
						sipLogger.info(sipResponse, "received response "+sipResponse.getStatus()+" "+sipResponse.getReasonPhrase());

						
						CallControlNotification ccn = new CallControlNotification();
						
						// Reactive JAX-RS Client API
						Client client = ClientBuilder.newClient();
						WebTarget notificationService = client.target(notificationUrl);

						CompletionStage userIdStage = notificationService.request()
								// .accept(MediaType.APPLICATION_JSON)
								.rx()
								.post(Entity.entity( ccn, MediaType.APPLICATION_JSON_TYPE )    )
//								.post(Entity.entity( ccn )    )

								.exceptionally((throwable) -> {
									sipLogger.warning("An error has occurred");
									//throw new IOException(throwable);
									
									return null;
								});

						sendRequest(sipResponse.createAck());
					});

				} catch (Exception e) {
					sipLogger.severe(e);
					response = Response.status(Status.INTERNAL_SERVER_ERROR).build();
				}

			}

		} else {
			response = Response.status(Status.NOT_FOUND).build();
		}

		return response;

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	public final static String blackhole = "" + //
			"v=0\r\n" + //
			"o=CiscoSystemsCCM-SIP 3751 1 IN IP4 127.0.0.1\r\n" + //
			"s=SIP Call\r\n" + //
			"c=IN IP4 0.0.0.0\r\n" + //
			"b=TIAS:64000\r\n" + //
			"b=AS:64\r\n" + //
			"t=0 0\r\n" + //
			"m=audio 24580 RTP/AVP 0\r\n" + //
			"a=rtpmap:0 pcmu/8000\r\n" + //
			"a=ptime:20\r\n" + //
			"a=inactive\r\n";

}
