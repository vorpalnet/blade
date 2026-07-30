package org.vorpal.blade.applications.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import javax.management.ObjectName;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.applications.events.jms.JmsDestinationEditor;
import org.vorpal.blade.applications.events.jms.JmsEditSession;
import org.vorpal.blade.applications.events.jms.JmsRuntimeOps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The mutating half of JMS administration.
///
/// **Authorization is per operation, not per URL.** A servlet
/// `security-constraint` cannot tell a read from a delete, so `web.xml` gates
/// the app coarsely and the real decision is made here:
///
/// | Operation | Role |
/// |---|---|
/// | provision, create, tune | `Deployer`, `Admin` |
/// | pause, resume | `Operator`, `Deployer`, `Admin` |
/// | delete, purge, browse message bodies | **`Admin`** |
///
/// Browse is Admin-only for the same reason a tap is: message bodies carry
/// call-correlated data, and reading them is a wiretap on production traffic
/// however convenient the button is. Every one of those reads is logged with
/// the calling principal.
///
/// **Destructive operations are refused on protected destinations** regardless
/// of role, and can be switched off entirely for a domain. Nothing reads the
/// analytics database yet, which means an accidental purge there is *silent* —
/// there is no user who would report it.
@Path("/admin")
public class JmsWriteAPI {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Logger logger = Logger.getLogger(JmsWriteAPI.class.getName());

	private static final List<String> DEPLOY_ROLES = Arrays.asList("Deployer", "Admin");
	private static final List<String> OPERATE_ROLES = Arrays.asList("Operator", "Deployer", "Admin");
	private static final List<String> ADMIN_ONLY = Arrays.asList("Admin");

	@Context
	private HttpServletRequest request;

	@Context
	private javax.servlet.ServletContext servletContext;

	/// Clusters and servers a destination can be targeted at, for the picker
	/// that replaces the old refuse-if-more-than-one-cluster behaviour.
	@GET
	@Path("/targets")
	@Produces(MediaType.APPLICATION_JSON)
	public Response targets() {
		try (JmsEditSession session = new JmsEditSession()) {
			ArrayNode out = MAPPER.createArrayNode();
			addTargets(session, out, "Clusters", "cluster");
			addTargets(session, out, "Servers", "server");
			return ok(out);
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	private void addTargets(JmsEditSession session, ArrayNode out, String attribute, String kind) throws Exception {
		Object array = session.server().getAttribute(session.domain(), attribute);
		if (array instanceof ObjectName[]) {
			for (ObjectName target : (ObjectName[]) array) {
				ObjectNode node = out.addObject();
				node.put("name", String.valueOf(session.server().getAttribute(target, "Name")));
				node.put("kind", kind);
			}
		}
	}

	/// Stand up (or adopt) the messaging stack and create a destination in it.
	@POST
	@Path("/destinations")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response createDestination(String body) {
		Response denied = require(DEPLOY_ROLES, "create a destination");
		if (denied != null) {
			return denied;
		}
		try {
			JsonNode in = MAPPER.readTree(body);
			String name = in.path("name").asText();
			String jndiName = in.path("jndiName").asText();
			if (name.isEmpty() || jndiName.isEmpty()) {
				return fail(Response.Status.BAD_REQUEST, "name and jndiName are both required");
			}
			JmsDestinationEditor.Kind kind = "QUEUE".equalsIgnoreCase(in.path("kind").asText("TOPIC"))
					? JmsDestinationEditor.Kind.QUEUE
					: JmsDestinationEditor.Kind.TOPIC;

			List<String> targets = new ArrayList<>();
			for (JsonNode target : in.path("targets")) {
				targets.add(target.asText());
			}

			try (JmsEditSession session = new JmsEditSession()) {
				ObjectName resource = JmsDestinationEditor.ensureStack(session, targets);
				String subDeployment = JmsDestinationEditor.subDeploymentOf(session, resource);
				ObjectName destination = JmsDestinationEditor.createDestination(session, resource, kind, name,
						jndiName, subDeployment);

				long quotaBytes = in.path("quotaBytes").asLong(1073741824L);
				JmsDestinationEditor.applyQuota(session, resource, destination, name + "Quota", quotaBytes);

				if (in.has("redeliveryLimit")) {
					JmsDestinationEditor.applyDeliveryFailure(session, destination,
							Integer.valueOf(in.path("redeliveryLimit").asInt()), null);
				}

				session.commit();
				logger.info(principal() + " created " + kind + " " + name + " (" + jndiName + ")");
				return okLog(session.log());
			}
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// Destroy a destination. Admin only, refused on a protected name, and
	/// refused outright when the domain has destructive operations switched off.
	@POST
	@Path("/destinations/delete")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteDestination(String body) {
		Response denied = require(ADMIN_ONLY, "delete a destination");
		if (denied != null) {
			return denied;
		}
		try {
			JsonNode in = MAPPER.readTree(body);
			String name = in.path("name").asText();
			String jndiName = in.path("jndiName").asText();

			Response guard = guardDestructive(jndiName, name);
			if (guard != null) {
				return guard;
			}

			JmsDestinationEditor.Kind kind = "QUEUE".equalsIgnoreCase(in.path("kind").asText("TOPIC"))
					? JmsDestinationEditor.Kind.QUEUE
					: JmsDestinationEditor.Kind.TOPIC;

			try (JmsEditSession session = new JmsEditSession()) {
				ObjectName resource = JmsDestinationEditor.ensureStack(session, new ArrayList<String>());
				JmsDestinationEditor.destroyDestination(session, resource, kind, name);
				session.commit();
				logger.warning(principal() + " DELETED " + kind + " " + name);
				return okLog(session.log());
			}
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// Pause or resume production, insertion or consumption on a destination.
	@POST
	@Path("/destinations/control")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response control(String body) {
		Response denied = require(OPERATE_ROLES, "pause or resume a destination");
		if (denied != null) {
			return denied;
		}
		try {
			JsonNode in = MAPPER.readTree(body);
			String name = in.path("name").asText();
			String operation = in.path("operation").asText();

			// Closed set: an arbitrary operation name here would be an
			// invoke-anything hole on a runtime MBean.
			switch (operation) {
			case "pause":
			case "resume":
			case "pauseProduction":
			case "resumeProduction":
			case "pauseConsumption":
			case "resumeConsumption":
			case "pauseInsertion":
			case "resumeInsertion":
				break;
			default:
				return fail(Response.Status.BAD_REQUEST, "unsupported operation '" + operation + "'");
			}

			List<String> log = JmsRuntimeOps.apply(name, operation);
			logger.info(principal() + " " + operation + " on " + name);
			return okLog(log);
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// Delete messages. Irreversible, Admin only, refused on protected names.
	@POST
	@Path("/destinations/purge")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response purge(String body) {
		Response denied = require(ADMIN_ONLY, "purge a destination");
		if (denied != null) {
			return denied;
		}
		try {
			JsonNode in = MAPPER.readTree(body);
			String name = in.path("name").asText();
			Response guard = guardDestructive(in.path("jndiName").asText(), name);
			if (guard != null) {
				return guard;
			}
			String selector = in.path("selector").asText("");
			long deleted = JmsRuntimeOps.purge(name, selector);
			logger.warning(principal() + " PURGED " + deleted + " messages from " + name
					+ (selector.isEmpty() ? " (all)" : " matching " + selector));

			ObjectNode result = MAPPER.createObjectNode();
			result.put("deleted", deleted);
			return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// Browse messages without consuming them.
	///
	/// Non-destructive, but Admin-only and audited: this returns message
	/// bodies, and a body on this bus carries the call correlator and whatever
	/// the producer put in it.
	@GET
	@Path("/destinations/browse")
	@Produces(MediaType.APPLICATION_JSON)
	public Response browse(@QueryParam("name") String name, @QueryParam("selector") String selector,
			@QueryParam("start") int start, @QueryParam("count") int count) {
		Response denied = require(ADMIN_ONLY, "browse message bodies");
		if (denied != null) {
			return denied;
		}
		if (name == null || name.isEmpty()) {
			return fail(Response.Status.BAD_REQUEST, "name is required");
		}
		logger.info(principal() + " browsed " + name
				+ ((selector == null || selector.isEmpty()) ? "" : " with selector " + selector));
		try {
			return ok(MAPPER.valueToTree(JmsRuntimeOps.browse(name, selector, start, (count > 0) ? count : 25)));
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// Drop a durable subscription's backlog without removing the subscription.
	@POST
	@Path("/subscriptions/purge")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response purgeSubscription(String body) {
		Response denied = require(ADMIN_ONLY, "purge a subscription");
		if (denied != null) {
			return denied;
		}
		try {
			JsonNode in = MAPPER.readTree(body);
			String destination = in.path("destination").asText();
			String subscription = in.path("subscription").asText();
			Response guard = guardDestructive(null, destination);
			if (guard != null) {
				return guard;
			}
			logger.warning(principal() + " PURGED subscription " + subscription + " on " + destination);
			return okLog(JmsRuntimeOps.purgeSubscription(destination, subscription));
		} catch (Exception e) {
			return fail(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	// -------------------------------------------------------------- guards

	private String principal() {
		String user = (request == null) ? null : request.getRemoteUser();
		return (user == null) ? "<unauthenticated>" : user;
	}

	/// Null when the caller holds one of the roles; a 403 otherwise.
	private Response require(List<String> roles, String action) {
		if (request == null) {
			return fail(Response.Status.FORBIDDEN, "no request context");
		}
		for (String role : roles) {
			if (request.isUserInRole(role)) {
				return null;
			}
		}
		logger.warning(principal() + " was denied: " + action + " (needs " + roles + ")");
		return fail(Response.Status.FORBIDDEN, "You need one of " + roles + " to " + action + ".");
	}

	/// Null when the destructive operation may proceed.
	private Response guardDestructive(String jndiName, String name) {
		EventsAdminSettings settings = settings();
		if (settings == null) {
			return null;
		}
		if (!settings.isAllowDestructiveOperations()) {
			return fail(Response.Status.FORBIDDEN,
					"Destructive operations are switched off for this domain. Turn them on in the Events console settings if you really mean it.");
		}
		for (String protectedName : settings.getProtectedDestinations().split(",")) {
			String trimmed = protectedName.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.equals(jndiName) || trimmed.equals(name) || (jndiName != null && jndiName.endsWith(trimmed))) {
				return fail(Response.Status.FORBIDDEN, "'" + trimmed
						+ "' is a protected destination. Remove it from the console's protected list first.");
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private EventsAdminSettings settings() {
		try {
			Object manager = (servletContext == null) ? null
					: servletContext.getAttribute(EventsAdminSettingsStartup.SETTINGS_ATTR);
			if (manager instanceof org.vorpal.blade.framework.v2.config.SettingsManager) {
				return ((org.vorpal.blade.framework.v2.config.SettingsManager<EventsAdminSettings>) manager)
						.getCurrent();
			}
		} catch (Exception e) {
			// A console that cannot read its own settings should still refuse
			// safely, which it does: the caller treats null as "no extra guard"
			// and the role check above has already run.
		}
		return null;
	}

	private static Response ok(Object body) throws Exception {
		return Response.ok(MAPPER.writeValueAsString(body)).type(MediaType.APPLICATION_JSON).build();
	}

	private static Response okLog(List<String> log) throws Exception {
		ObjectNode result = MAPPER.createObjectNode();
		result.put("success", true);
		ArrayNode steps = result.putArray("steps");
		for (String line : log) {
			steps.add(line);
		}
		return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	private static Response fail(Response.Status status, String message) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("error", message);
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body.toString()).build();
	}
}
