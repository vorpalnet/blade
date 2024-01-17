package org.vorpal.blade.services.transfer.api.v1;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipSessionsUtil;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.Logger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(info = @Info(title = "Transfer", version = "1", description = "Transfer APIs"))
@Path("api/v1")
public class TransferService {

	private static Logger sipLogger = SettingsManager.getSipLogger();
	private static SipSessionsUtil sipUtil = Callflow.getSipUtil();
	private static SipFactory sipFactory = Callflow.getSipFactory();

	@GET
	@Path("/session/{indexKey}")
	@Produces({ "application/json", "application/xml" })
	@Operation(summary = "Examine session and dialog information.")
	public Response getSessionInformation( //
			@Parameter(required = true, example = "0000000000", description = "The SipApplicationSession key") @PathParam("indexKey") String indexKey) {
		Response response;

		Session session;

		sipLogger.fine("getSessionInformation key=" + indexKey);

		SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(indexKey, false);

		if (appSession == null) {
			sipLogger.fine("SipApplicationSession not found for key " + indexKey);
			response = Response.status(Response.Status.NOT_FOUND).build();
		} else {
			session = new Session(appSession);
			response = Response.status(Response.Status.ACCEPTED).entity(session).build();
		}

		return response;
	}

}
