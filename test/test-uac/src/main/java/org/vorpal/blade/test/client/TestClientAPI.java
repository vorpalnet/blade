package org.vorpal.blade.test.client;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.crud.RuleSet;
import org.vorpal.blade.framework.v3.tester.Scenario;
import org.vorpal.blade.framework.v3.tester.TemplateLoader;
import org.vorpal.blade.framework.v3.tester.TesterConfiguration;
import org.vorpal.blade.test.uac.UserAgentClientServlet;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

/// Single-call REST test client: `POST /api/v1/connect` sends one INVITE
/// and returns every SIP response (provisional and final) in the HTTP
/// reply. The INVITE's body comes from a named scenario's template, a
/// template file, or inline content; a scenario also applies its
/// transformation rules to the request and its responses.
@OpenAPIDefinition(info = @Info(title = "TestClient", version = "1", description = "Single-call SIP test client"))
@Path("api/v1")
public class TestClientAPI extends Callflow {
	private static final long serialVersionUID = 1L;

	private static final Map<String, AsyncResponse> asyncResponses = new HashMap<>();

	private final TemplateLoader templates = new TemplateLoader();

	@POST
	@Path("/connect")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Send a single INVITE and collect its responses.")
	public void connect( //
			@Suspended AsyncResponse asyncResponse, //
			@Context UriInfo uriInfo, //
			@RequestBody(content = @Content(schema = @Schema(implementation = MessageRequest.class)), //
					description = "Message content", //
					required = true) MessageRequest message)
			throws ServletException, IOException //
	{
		URI location = URI.create(uriInfo.getPath());

		// Create the SIP request
		SipFactory sipFactory = SettingsManager.getSipFactory();
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		SipServletRequest request = sipFactory.createRequest(appSession, INVITE, message.fromAddress,
				message.toAddress);
		if (message.requestURI != null && message.requestURI.length() > 0) {
			request.setRequestURI(sipFactory.createURI(message.requestURI));
		}

		if (message.headers != null) {
			for (Header header : message.headers) {
				request.setHeader(header.name, header.value);
			}
		}

		// Resolve the optional scenario: template + transformation rules.
		TesterConfiguration config = UserAgentClientServlet.settingsManager.getCurrent();
		Scenario scenario = null;
		if (message.scenario != null) {
			scenario = config.getScenarios().get(message.scenario);
			if (scenario == null) {
				asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
						.entity("{\"error\":\"Unknown scenario: " + message.scenario + "\"}").build());
				return;
			}
			appSession.setAttribute("scenario", message.scenario);
		}
		final RuleSet ruleSet = (scenario != null) ? scenario.effectiveRules(config) : null;

		// Body: scenario template > request template > inline content.
		try {
			if (scenario != null && scenario.getTemplate() != null) {
				templates.get(scenario.getTemplate()).apply(request);
			} else if (message.template != null) {
				templates.get(message.template).apply(request);
			} else if (message.content != null) {
				request.setContent(message.content,
						(message.contentType != null) ? message.contentType : "application/sdp");
			}
		} catch (IOException e) {
			asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
					.entity("{\"error\":\"" + e.getMessage() + "\"}").build());
			return;
		}

		if (ruleSet != null) {
			ruleSet.applyRules(request, "callStarted");
		}

		MessageResponse msgResponse = new MessageResponse();

		// Save the 'transient' AsyncResponse for later HTTP Response
		asyncResponses.put(appSession.getId(), asyncResponse);

		sipLogger.finer(request, "Sending this...\n" + request.toString());

		// Send the SIP request
		sendRequest(request, (response) -> {
			sipLogger.finer(response, "Received this...\n" + response.toString());

			if (ruleSet != null) {
				ruleSet.applyRules(response, "responseEvent");
			}

			msgResponse.responses.add(response.toString());

			if (successful(response)) {
				sendRequest(response.createAck());
			}

			if (!provisional(response)) {
				sipLogger.finer(response, "Final response: " + response.getStatus());
				msgResponse.finalStatus = response.getStatus();
				Response httpResponse = Response.created(location).entity(msgResponse).build();
				asyncResponses.remove(appSession.getId()).resume(httpResponse);
			}
		});
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// REST-driven; no inbound SIP requests reach this callflow.
	}
}
