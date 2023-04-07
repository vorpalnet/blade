package org.vorpal.blade.test.client;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

@OpenAPIDefinition(info = @Info(title = "TestClient", version = "1", description = "a crude test client"))
@Path("api/v1")

public class TestClientAPI extends Callflow {

//	private Map<String, MessageSession> sessions = new HashMap<>();

//	private static transient AsyncResponse asyncResponse;

	private static Map<String, AsyncResponse> asyncResponses = new HashMap<>();

	@POST
	@Path("/connect")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Open a connection.")
//	public Response connect( //
	public void connect( //
			@Suspended AsyncResponse asyncResponse, //
			@Context UriInfo uriInfo, //
			@RequestBody(content = @Content(schema = @Schema(implementation = org.vorpal.blade.test.client.MessageRequest.class)), //
					description = "Message content", //
					required = true) org.vorpal.blade.test.client.MessageRequest message)
			throws ServletException, IOException //
	{

//		TestClientAPI.asyncResponse = asyncResponse;

		URI location = URI.create(uriInfo.getPath());

		// Create the SIP request
		SipFactory sipFactory = SettingsManager.getSipFactory();
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, message.fromAddress,
				message.toAddress);
		if (message.requestURI != null && message.requestURI.length() > 0) {
			bobRequest.setRequestURI(sipFactory.createURI(message.requestURI));
		}

		for (Header header : message.headers) {
			bobRequest.setHeader(header.name, header.value);
		}

//		bobRequest.setContent(message.body, "application/sdp");
//		bobRequest.setContent(message.body, "multipart/mixed");

		if (message.content != null && message.content.length() > 0) {
			bobRequest.setContent(message.content, message.contentType);
		}

		MessageSession msgSession = new MessageSession(bobRequest.getApplicationSession(), bobRequest.getSession());
		MessageResponse msgResponse = new MessageResponse();

		// Save the 'transient' AsyncResponse for later HTTP Response
		asyncResponses.put(appSession.getId(), asyncResponse);

		// Send the SIP request
		sendRequest(bobRequest, (bobResponse) -> {

			msgResponse.responses.add(bobResponse.toString());

			if (!provisional(bobResponse)) {
				sendRequest(bobResponse.createAck());

				msgResponse.finalStatus = bobResponse.getStatus();

				Response httpResponse = Response.created(location).entity(msgResponse).build();

				asyncResponses.remove(appSession.getId()).resume(httpResponse);

			}

		});

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
