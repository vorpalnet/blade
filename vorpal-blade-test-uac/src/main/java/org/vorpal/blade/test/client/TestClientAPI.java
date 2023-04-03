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

	@GET
	@Path("/insert")
	public Response insert( //
			@QueryParam("sessionKey") String sessionKey, //
			@QueryParam("endpoint") String endpoint, //
			@QueryParam("agentId") String agentId, //
			@QueryParam("uuid") String uuid) {
		Response insertResponse;
		SettingsManager.sipLogger.info("insert sessionKey: " + sessionKey //
				+ ", endpoint: " + endpoint //
				+ ", agentId: " + agentId //
				+ ", uuid: " + uuid);

		insertResponse = Response.status(Response.Status.ACCEPTED).build();
		return insertResponse;
	}

	@POST
	@Path("/connect")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Open a connection.")
	public Response connect( //
//	public void connect( //
//			@Suspended AsyncResponse asyncResponse, //
			@Context UriInfo uriInfo, //
			@RequestBody(content = @Content(schema = @Schema(implementation = org.vorpal.blade.test.client.MessageRequest.class)), //
					description = "Message content", //
					required = true) org.vorpal.blade.test.client.MessageRequest message)
			throws ServletException, IOException //
	{

		URI location = URI.create(uriInfo.getPath());

		// Create the SIP request
		SipFactory sipFactory = SettingsManager.getSipFactory();
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, message.from, message.to);

		sipLogger.fine(bobRequest.getSession(), "message.to: = " + message.to);
		sipLogger.fine(bobRequest.getSession(), "To:         = " + bobRequest.getTo().toString());
		sipLogger.fine(bobRequest.getSession(), "requestURI: = " + bobRequest.getRequestURI().toString());
		bobRequest.setRequestURI(bobRequest.getTo().getURI());
		sipLogger.fine(bobRequest.getSession(), "requestURI: = " + bobRequest.getRequestURI().toString());

		for (Header header : message.headers) {
			bobRequest.setHeader(header.name, header.value);
		}
//		bobRequest.setContent(message.body, "application/sdp");
		bobRequest.setContent(message.body, "multipart/mixed");

		MessageSession msgSession = new MessageSession(bobRequest.getApplicationSession(), bobRequest.getSession());
//		msgResponse.id = msgSession.getId();
//		msgSession.setAsyncResponse(asyncResponse);
//		sessions.put(msgSession.getId(), msgSession);

		// Send the SIP request

		MessageResponse msgResponse = new MessageResponse();

		
		sendRequest(bobRequest, (bobResponse) -> {
			if (!provisional(bobResponse)) {
				sendRequest(bobResponse.createAck());

				msgResponse.status = bobResponse.getStatus();

				for (String headerName : bobResponse.getHeaderNameList()) {
					Header h = new Header();
					ListIterator<String> itr = bobResponse.getHeaders(headerName);

					StringBuilder headerValue = new StringBuilder();
					int count = 0;
					while (itr.hasNext()) {
						if (count > 0) {
							headerValue.append(", ");
						}
						headerValue.append(itr.next());
						count++;
					}

					h.name = headerName;
					h.value = headerValue.toString();

					msgResponse.headers.add(h);
				}

				if (bobResponse.getContent() != null) {
					msgResponse.body = new String(bobResponse.getRawContent());
				}

				// Build the async response
				// Response httpResponse;

//				Response httpResponse = Response.created(location).entity(msgResponse).build();

				// asyncResponse.resume(httpResponse);

//				sessions.get(msgSession.getId()).getAsyncResponse().resume(httpResponse);

			}

		});

//		Response httpResponse = Response.created(location).entity(msgResponse).build();
		Response httpResponse = Response.created(location).build();
		return httpResponse;

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
