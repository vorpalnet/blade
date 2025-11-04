package org.vorpal.blade.services.proxy.block.api;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.services.proxy.block.optimized.OptimizedDialed;
import org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * This class is meant to be an example of how the proxy-block application will
 * load a configuration at runtime. This is a dummy service. Proxy-block will
 * implement a client and pull the config from elsewhere.
 */

@OpenAPIDefinition(info = @Info( //
		title = "Proxy-Block", //
		version = "1", //
		description = "Manages configurations."))
@Path("v1")
public class LoadConfig {

	@SuppressWarnings({ "unchecked" })
	@GET
	@Path("config/load/{id}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Returns a configuration based on an identifier.")
	@ApiResponses(value = { @ApiResponse(responseCode = "200", description = "OK", content = {
			@Content(mediaType = "application/json", schema = @Schema(implementation = CallingNumbers.class)) }),
			@ApiResponse(responseCode = "404", description = "Not Found"),
			@ApiResponse(responseCode = "500", description = "Internal Server Error") })
	public Response load(@PathParam("id") String id) {
		CallingNumbers config = new CallingNumbers();

//		OptimizedTranslation ot1 = new OptimizedTranslation();
//		
//		OptimizedDialed od1 = new OptimizedDialed();
//		od1.forwardTo.add("sip:voicemail-101@10.1.1.101:5060");
//		ot1.dialedNumbers.put("19135556789", od1);
//		config.put("18165551234", ot1);

		OptimizedTranslation ot1 = config.addCallingNumber("18165551234")
				.forwardTo("sip:voicemail-101@10.1.1.101:5060");
		ot1.addDialedNumber("19135556789").forwardTo("sip:voicemail-102@10.1.1.102:5060");
		ot1.addDialedNumber("19135556789").forwardTo("sip:voicemail-103@10.1.1.103:5060");

		OptimizedTranslation ot2 = config.addCallingNumber("14155551234")
				.forwardTo("sip:voicemail-101@10.1.1.101:5060");

//		OptimizedBlockConfig config = new OptimizedBlockConfigSample();

		Response response = Response.ok().entity(config).build();
		return response;
	}

}
