package org.vorpal.blade.services.events;

import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventType;
import org.vorpal.blade.framework.v3.events.ValidationPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/// HTTP → JMS ingress for the event bus, at `POST /events/api/v1/events`.
///
/// This is the seam that lets a non-Java producer publish. A voice-attendant
/// sidecar (Python) already emits CloudEvents 1.0 over HTTP; pointing its
/// event sink at this URL requires **no change to the producer** — the same
/// bytes it POSTs become a JSON `TextMessage` on the destination, and every
/// subscribing BLADE app receives its own copy.
///
/// The endpoint stays thin: parse, fill in what a terse producer omitted, check
/// the payload against its declared schema, publish, acknowledge. It does no
/// last-mile integration — that is the consumers' job.
///
/// **What the catalog changes here.** The ingress now knows what an event is
/// supposed to look like, so it can answer three questions it previously could
/// not: is this type declared, does its payload match, and which destination
/// does it belong on. Each is governed by catalog policy rather than hard-coded
/// — see [EventCatalog#isRejectUnknownTypes] and [EventCatalog#getValidation].
@Path("/events")
public class EventIngestResource {

	private static final Logger logger = Logger.getLogger(EventIngestResource.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// Holds the compiled schemas. JAX-RS creates the resource per request by
	/// default, but the compiled set is keyed on the catalog instance, so a
	/// longer-lived resource simply reuses it. See [EventValidator].
	private final EventValidator validator = new EventValidator();

	@Context
	private ServletContext servletContext;

	/// Accept a CloudEvents 1.0 envelope and publish it to the bus.
	///
	/// @param body a CloudEvents 1.0 JSON envelope
	/// @return 202 with the event id once it is on the destination; 400 when the
	///         body is not a valid CloudEvent, names an undeclared type while the
	///         catalog is authoritative, or fails its schema under
	///         [ValidationPolicy#REJECT]; 503 when no publisher is installed for
	///         the destination on this node
	@POST
	@Consumes({ "application/cloudevents+json", MediaType.APPLICATION_JSON })
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Publish a CloudEvent to the BLADE event bus")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Accepted and published"),
			@ApiResponse(responseCode = "400", description = "Not a valid CloudEvent, undeclared type, or payload failed its schema"),
			@ApiResponse(responseCode = "503", description = "Event bus publisher not available on this node") })
	public Response ingest(String body) {
		EventCatalog catalog = currentCatalog();

		CloudEvent event;
		try {
			event = CloudEvent.fromJson(body);
		} catch (Exception e) {
			return error(Response.Status.BAD_REQUEST, "malformed CloudEvent JSON");
		}

		if (event.getType() == null || event.getType().isEmpty()) {
			return error(Response.Status.BAD_REQUEST, "CloudEvent 'type' is required");
		}

		EventType declaration = (catalog == null) ? null : catalog.findType(event.getType());
		if (declaration == null && catalog != null && catalog.isRejectUnknownTypes()) {
			return error(Response.Status.BAD_REQUEST,
					"event type '" + event.getType() + "' is not declared in the catalog");
		}

		// Fill in the attributes a well-formed CloudEvent needs but a terse
		// producer may omit: a source (from the catalog) and a fresh id/time.
		if (event.getSource() == null || event.getSource().isEmpty()) {
			event.setSource(defaultSource(catalog));
		}
		if (event.getId() == null || event.getId().isEmpty()) {
			CloudEvent stamped = CloudEvent.create(event.getType(), event.getSource(), event.getSubject(),
					event.getData());
			stamped.setDatacontenttype(event.getDatacontenttype());
			event = stamped;
		}

		ValidationPolicy policy = (catalog == null) ? ValidationPolicy.OFF : catalog.getValidation();
		if (policy != ValidationPolicy.OFF && declaration != null) {
			EventValidator.Result result = validator.validate(catalog, event);
			if (!result.isValid()) {
				if (policy == ValidationPolicy.REJECT) {
					return error(Response.Status.BAD_REQUEST,
							"payload does not match the schema for '" + event.getType() + "': " + result.summary());
				}
				// WARN: publish anyway, but say exactly what was wrong. This is
				// the setting to run in while a new schema is proven against
				// live traffic.
				logger.warning("event " + event.getId() + " of type " + event.getType()
						+ " does not match its declared schema: " + result.summary());
			}
		}

		String destination = (catalog == null) ? null : catalog.destinationFor(declaration);
		if (!EventBus.isReady(destination)) {
			return error(Response.Status.SERVICE_UNAVAILABLE, "event bus not available on this node");
		}

		try {
			EventBus.publish(event, destination);
		} catch (Exception e) {
			return error(Response.Status.SERVICE_UNAVAILABLE, "publish failed");
		}

		ObjectNode accepted = MAPPER.createObjectNode();
		accepted.put("id", event.getId());
		return Response.status(Response.Status.ACCEPTED).entity(accepted.toString()).build();
	}

	/// Always answer JSON, built through Jackson rather than string
	/// concatenation so a quote inside a schema message cannot produce a body
	/// the caller's `response.json()` chokes on.
	private static Response error(Response.Status status, String message) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("error", message);
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body.toString()).build();
	}

	/// The live catalog, re-read per request so a console or Configurator edit
	/// takes effect without a redeploy. Null when the app failed to start its
	/// SettingsManager, in which case the ingress degrades to its pre-catalog
	/// behavior rather than refusing traffic outright.
	@SuppressWarnings("unchecked")
	private EventCatalog currentCatalog() {
		Object attr = (servletContext == null) ? null
				: servletContext.getAttribute(EventBusStartup.CATALOG_SUPPLIER_ATTR);
		if (attr instanceof Supplier) {
			Object candidate = ((Supplier<Object>) attr).get();
			if (candidate instanceof EventCatalog) {
				return (EventCatalog) candidate;
			}
		}
		return null;
	}

	private static String defaultSource(EventCatalog catalog) {
		if (catalog != null && catalog.getDefaultSource() != null && !catalog.getDefaultSource().isEmpty()) {
			return catalog.getDefaultSource();
		}
		return "/blade/events";
	}
}
