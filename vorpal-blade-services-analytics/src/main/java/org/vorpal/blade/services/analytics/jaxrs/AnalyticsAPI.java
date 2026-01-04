package org.vorpal.blade.services.analytics.jaxrs;

import javax.jms.TextMessage;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@OpenAPIDefinition(info = @Info( //
		title = "BLADE - Analytics", //
		version = "1", //
		description = "Performs analytics operations"))
@Path("v1")
public class AnalyticsAPI {
	private static final long serialVersionUID = 1L;

//	private QueueConnectionFactory qconFactory;
//	private QueueConnection qcon;
//	private QueueSession qsession;
//	private QueueSender qsender;
//	private Queue queue;
	private TextMessage msg;

	@SuppressWarnings({ "unchecked" })
	@GET
	@Path("test")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Examine session variables")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK"),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response test() {

		System.out.println("Cool Beans!");

		Response response = Response.ok().entity("Cool Beans!").build();
		try {

//			InitialContext ctx = new InitialContext();
//
//			// init begin
//			qconFactory = (QueueConnectionFactory) ctx.lookup(JMS_FACTORY);
//			qcon = qconFactory.createQueueConnection();
//			qsession = qcon.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
//			queue = (Queue) ctx.lookup(QUEUE);
//			qsender = qsession.createSender(queue);
			TextMessage msg = AnalyticsSipServlet.qsession.createTextMessage();
//			qcon.start();
			// init end

			String message = "Booyah!";
			System.out.println("Sending message: " + message);
			msg.setText("Booyah!");
			AnalyticsSipServlet.qsender.send(msg);

//			qsender.close();
//			qsession.close();
//			qcon.close();

		} catch (Exception ex) {
			ex.printStackTrace();
		}

		return response;
	}

}
