package org.vorpal.blade.services.tpcc.v1;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.services.tpcc.v1.session.SessionCreateRequest;
import org.vorpal.blade.services.tpcc.v1.session.SessionGetResponse;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

/*
 * [ ] GET    /session                             -- creates a session, returns: /session/{sessionId}
 * [ ] POST   /session                             -- accepts json, creates a session, returns: /session/{sessionId}
 * [ ] GET    /session/{sessionId}                 -- describes a json session object
 * [ ] DELETE /session/{sessionId}                 -- destroys a session
 * [ ] PUT    /session/{sessionId}                 -- adds, update or modify json attributes
 *
 * [ ] PUT    /group/{groupId}/{sessionId}         -- adds session to a group, returns: /group/{groupId}
 * [ ] DELETE /group/{groupId}/{sessionId}         -- removes session from a group
 * [ ] GET    /group/{groupId}                     -- returns a list of sessions identified by group
 * [ ] DELETE /group/{groupId}                     -- removes all sessions from a group
 * 
 */

@OpenAPIDefinition(info = @Info( //
		title = "BLADE - 3rd Party Call Control", //
		version = "1", //
		description = "Manages communications relationships between two or more other parties."))
@Path("api/v1")
public class SessionAPI extends Callflow {
	private static final long serialVersionUID = 1L;

	@POST
	@Path("test")
	@Operation(summary = "Create a new session.")
	@Produces({ "application/json" })
	public Response test(@Context UriInfo uriInfo) {
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
	@Path("test/routeRequest")
	@Operation(summary = "invoke a thing")
	@Produces({ "application/json" })
	public Response test2(@Context UriInfo uriInfo) {
		System.out.println("invoking test/routeRequest...");
		

		String gibberish = "{\n"
			    + "    \"responseCode\": 201,"
				+ "    \"data\": {\n"
				+ "        \"attachedData\": {\n"
				+ "            \"treatmentArray\": [\n"
				+ "                \"musicOnHold1.wav\",\n"
				+ "                \"musicOnHold2.wav\",\n"
				+ "                \"disconnect\"\n"
				+ "            ],\n"
				+ "            \"cacheANI\": false,\n"
				+ "            \"defaultDestination\": \"199223322\",\n"
				+ "            \"emergencyMessage\": \"emergency.wav\",\n"
				+ "            \"useCacheasPrimary\": true\n"
				+ "        },\n"
				+ "        \"ContextAttributes\": {\n"
				+ "            \"ENT_TFN_Name\": \"RegressionTest1\",\n"
				+ "            \"ENT_Action\": \"Continue\",\n"
				+ "            \"ENT_Unit\": \"OPT\",\n"
				+ "            \"ENT_Segment\": \"RegressionTest\",\n"
				+ "            \"ENT_Function\": \"Demo\",\n"
				+ "            \"ENT_Language\": \"English\",\n"
				+ "            \"ENT_CallType\": \"CT_RegressionTest_Initial_IVR\"\n"
				+ "        }\n"
				+ "    }\n"
				+ "}\n"
				+ "";
		
		
		Response response = null;

		try {
			response = Response.status(201).entity(gibberish).build();

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}
	

	@GET
	@Path("session")
	@Operation(summary = "Create a new session.")
	public Response createSession(@Context UriInfo uriInfo) {

		Response response = null;

		try {

			SipApplicationSession appSession;
			String indexKey = null;
			do {
				indexKey = String.format("%08X", Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)))
						.toUpperCase();
			} while (null == (appSession = sipFactory.createApplicationSessionByKey(indexKey)));
			appSession.setAttribute("X-Vorpal-Timestamp", Long.toHexString(System.currentTimeMillis()).toUpperCase());
			appSession.setAttribute("X-Vorpal-Session", indexKey);
			appSession.addIndexKey(indexKey);

			UriBuilder uriBuilder = uriInfo.getAbsolutePathBuilder().path(indexKey);
			response = Response.created(uriBuilder.build()).build();

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

	@POST
	@Path("session")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Create a new session.")
	public Response createSessionWithAttributes( //
			@RequestBody( //
					content = @Content(schema = @Schema( //
							name = "Session", //
							description = "Session properties", //
							implementation = SessionCreateRequest.class)), //
					description = "session properties", //
					required = false) SessionCreateRequest sessionData, //
			//
			@Context UriInfo uriInfo) {

		Response response = null;

		try {

			SipApplicationSession appSession;
			String indexKey = null;
			do {
				indexKey = String.format("%08X", Math.abs(ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)))
						.toUpperCase();
			} while (null == (appSession = sipFactory.createApplicationSessionByKey(indexKey)));
			appSession.setAttribute("X-Vorpal-Timestamp", Long.toHexString(System.currentTimeMillis()).toUpperCase());
			appSession.setAttribute("X-Vorpal-Session", indexKey);
			appSession.addIndexKey(indexKey);

			UriBuilder uriBuilder = uriInfo.getAbsolutePathBuilder().path(indexKey);
			response = Response.created(uriBuilder.build()).build();

			if (sessionData.expires != null) {
				appSession.setExpires(sessionData.expires);
			}

			if (sessionData.invalidateWhenReady != null) {
				appSession.setInvalidateWhenReady(sessionData.invalidateWhenReady);
			}

			if (sessionData.attributes != null && sessionData.attributes.size() > 0) {
				for (Entry<String, String> entry : sessionData.attributes.entrySet()) {
					appSession.setAttribute("3pcc_" + entry.getKey(), entry.getValue());
				}
			}

			if (sessionData.groups != null && sessionData.groups.size() > 0) {
				for (String group : sessionData.groups) {
					appSession.addIndexKey(group);
				}
			}

			sipLogger.info(appSession, "Session created...");

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;
	}

	@GET
	@Path("session/{sessionId}")
	@Operation(summary = "Get a session.")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response getSession(
			@Parameter(required = true, example = "ABCD6789", description = "Session ID") @PathParam("sessionId") String sessionId) {
		Response response;

		try {

			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			if (appSession != null) {
				SessionGetResponse session = new SessionGetResponse(appSession);
				response = Response.accepted().entity(session).build();
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
	@Path("session/{sessionId}")
	@Consumes({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Set session properties")
	public Response updateSession(
			@Parameter(required = true, example = "ABCD6789", description = "the unique session") @PathParam("sessionId") String sessionId,
			@RequestBody(description = "optional session properties", required = true) SessionCreateRequest sessionData,
			@Context UriInfo uriInfo) {
		Response response;

		try {
			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {

				if (sessionData != null) {

					if (sessionData.expires != null) {
						appSession.setExpires(sessionData.expires);
					}

					if (sessionData.attributes != null && sessionData.attributes.size() > 0) {
						for (Entry<String, String> entry : sessionData.attributes.entrySet()) {
							if (entry.getValue() != null && entry.getValue().length() > 0) {
								appSession.setAttribute("3pcc_" + entry.getKey(), entry.getValue());
							} else {
								appSession.removeAttribute("3pcc_" + entry.getKey());
							}
						}
					}

					if (sessionData.groups != null && sessionData.groups.size() > 0) {
						for (String group : sessionData.groups) {
							appSession.addIndexKey(group);
						}
					}
				}

				response = Response.accepted().build();

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
	@Path("session/{sessionId}")
	@Operation(summary = "Destroy session.")
	public Response removeSession(
			@Parameter(required = true, example = "ABCD6789", description = "the unique session") @PathParam("sessionId") String sessionId) {

		Response response;

		try {

			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);

			if (appSession != null) {
				appSession.invalidate();
				response = Response.accepted().build();
			} else {
				response = Response.status(Status.NOT_FOUND).build();
			}

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;
	}

	@GET
	@Path("group/{groupId}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "List sessions by group.")
	public Response listSessionsInGroup(
			@Parameter(required = true, example = "myGroup", description = "group name") @PathParam("groupId") String groupId,
			@Context UriInfo uriInfo) {

		Response response;
		try {

			List<String> sessions = new LinkedList<>();

			SipApplicationSession appSession;
			String sessionId;
			for (String appSessionId : sipUtil.getSipApplicationSessionIds(groupId)) {
				appSession = sipUtil.getApplicationSessionById(appSessionId);

				sessionId = (String) appSession.getAttribute("X-Vorpal-Session");
				if (sessionId != null) {
					sessions.add(sessionId);
				}
			}

			response = Response.accepted().entity(sessions).build();

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

	@DELETE
	@Path("group/{groupId}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "List sessions by group.")
	public Response deleteGroup(
			@Parameter(required = true, example = "myGroup", description = "group name") @PathParam("groupId") String groupId,
			@Context UriInfo uriInfo) {

		Response response;
		try {

			List<String> sessions = new LinkedList<>();
			SipApplicationSession appSession;
			for (String appSessionId : sipUtil.getSipApplicationSessionIds(groupId)) {
				appSession = sipUtil.getApplicationSessionById(appSessionId);
				appSession.removeIndexKey(groupId);
			}

			response = Response.accepted().build();

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;
	}

	@PUT
	@Path("group/{groupId}/{sessionId}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Assigns a session to a group.")
	public Response assignGroup(
			@Parameter(required = true, example = "myGroup", description = "group name") @PathParam("groupId") String groupId,
			@Parameter(required = true, example = "ABCD6789", description = "session") @PathParam("sessionId") String sessionId,
			@Context UriInfo uriInfo) {

		Response response;

		try {

			List<String> sessions = new LinkedList<>();

			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			if (appSession != null) {
				appSession.addIndexKey(groupId);
				response = Response.accepted().build();
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
	@Path("group/{groupId}/{sessionId}")
	@Produces({ MediaType.APPLICATION_JSON })
	@Operation(summary = "Assigns a session to a group.")
	public Response removeFromGroup(
			@Parameter(required = true, example = "myGroup", description = "group name") @PathParam("groupId") String groupId,
			@Parameter(required = true, example = "ABCD6789", description = "session") @PathParam("sessionId") String sessionId,
			@Context UriInfo uriInfo) {

		Response response;

		try {

			List<String> sessions = new LinkedList<>();

			SipApplicationSession appSession = sipUtil.getApplicationSessionByKey(sessionId, false);
			if (appSession != null) {
				appSession.removeIndexKey(groupId);
				response = Response.accepted().build();
			} else {
				response = Response.status(Status.NOT_FOUND).build();
			}

		} catch (Exception e) {
			response = Response.status(500, e.getMessage()).build();
			sipLogger.severe(e);
		}

		return response;

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub
	}

}
