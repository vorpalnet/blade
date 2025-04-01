package org.vorpal.blade.services.tpcc.v1;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;

//@OpenAPIDefinition(info = @Info( //
//		title = "Cloud Routing Solution - Dummy Server", //
//		version = "1", //
//		description = "Simulates the CRS model"))
@Path("api/v1/test")
public class CRSTest extends ClientCallflow {
	private static final long serialVersionUID = 1L;

	@POST
	@Path("token")
	@Operation(summary = "Create a new session.")
	@Produces({ "application/json" })
	public Response token(@Context UriInfo uriInfo) {
		System.out.println("invoking token test...");
		String accessToken = "{\"access_token\":\"eyJraWQiOiJHakJ2c2NVZlZ5dlF4dWhuazVRNllwUnZLSkxBS05BYUJjREtTSVlwd3hVIiwidHlwIjoiSldUIiwiYWxnIjoiUlMyNTYifQ.eyJhdWQiOiJodHRwczovL2FwaS51aGcuY29tIiwic3ViIjoiNDk1ODQ5MWUtY2I5MC00ZDk5LTljM2YtZDNiYmUyY2RjZTc4IiwiYXpwIjoiNDk1ODQ5MWUtY2I5MC00ZDk5LTljM2YtZDNiYmUyY2RjZTc4Iiwic2NvcGUiOiJodHRwczovL2FwaS51aGcuY29tLy5kZWZhdWx0IiwiaXNzIjoiaHR0cHM6Ly9ub25wcm9kLmlkZW50aXR5LnVoZy5jb20iLCJ0eXAiOiJCZWFyZXIiLCJvaWQiOiI0OTU4NDkxZS1jYjkwLTRkOTktOWMzZi1kM2JiZTJjZGNlNzgiLCJleHAiOjE3MzA5ODU2NzQsImlhdCI6MTczMDk4MjA3NCwianRpIjoiM2M3ZDFhMzMtNDc0Ni00ZGYyLWFlZTAtZTE1Mzc3NWU4YWMwIn0.F7DImVRMsrq2PM6z0XctX8GaTLUE92sOcx-U75fYNvOycdBhZp3m7rrfIMiTPt8BZPVcO6RkopG39U3jPLHNfn73iL33YypRSNbjKFpcJxJRqiv38hDFIZ_KlixVVwsN6pJZXADsrNOlDIUle55n70LW8HvARJALJkp9L7DkHC6USj4T-qg6WSxTGAtjqvM9QDUHd26NCeUXpJbAOvivhQlpthKpoim0tDmD3opbViuH4o446V4S4lom8LHoEoqJpghCahCvUrillJGrQRpsZolMIBX1r0li7lOoLs_dVa4GDXzMJPCx4RiZw0nVnXs7hVNIITqvr3ASZRwmuAhSxA\",\"expires_in\":3594,\"refresh_expires_in\":0,\"token_type\":\"Bearer\",\"not-before-policy\":0,\"scope\":\"https://api.uhg.com/.default\"}";

		Response response = null;

		try {
			response = Response.ok().entity(accessToken).build();

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

	@POST
	@Path("crs")
	@Operation(summary = "invoke a thing")
	@Produces({ "application/json" })
	public Response routeRequest(@Context UriInfo uriInfo) {
		System.out.println("invoking routeRequest test...");

		String status = "" + //
				"{\"status\" : 201.0," + //
				"\"X-attach-headers\" : {" + //
				"  \"ANI\" : \"763406095\"," + //
				"  \"user\" : \"csp\"" + //
				"}," + //
				"\"attachedData\" : {" + //
				"  \"treatmentArray\" : [ \"musicOnHold1.wav\", \"musicOnHold2.wav\" ]," + //
				"  \"cacheANI\" : false," + //
				"  \"defaultDestination\" : \"1969939702\"," + //
				"  \"emergencyMessage\" : \"emergency.wav\"" + //
				"}," + //
				"\"destination\" : \"1969939702\"," + //
				"\"source\" : \"8558293911\"," + //
				"\"statusMessage\" : \"Tfn classification completed...\"," + //
				"\"useCacheasPrimary\" : false," + //
				"\"ucid\" : \"1111\"," + //
				"\"executionArn\" : \"arn:aws:states:us-east-1:211125693230:express:cd-sf-crs:84117329-f035-4054-a1bd-1dfe0c8d99fd:5e30c4ca-86c6-4c0e-b2f3-bc055bf99cbf\"}";

		Response response = null;

		try {
			response = Response.status(201).entity(status).build();

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

}
