package org.vorpal.blade.services.events;

import java.util.function.Supplier;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/// HTTP → JMS ingress for the event bus, at `POST /events/api/v1/events`.
///
/// This is the seam that lets a non-Java producer publish to the bus. The
/// Gumball attendant (a Python sidecar) already emits CloudEvents 1.0 over HTTP;
/// pointing its `ATTENDANT_SINK` at this URL requires **no change to the
/// producer** — the same bytes it POSTs become a JSON `TextMessage` on the
/// topic, and every subscribing BLADE app receives its own copy.
///
/// The endpoint is deliberately thin: parse, minimally validate, fill in a
/// `source`/`id` if absent, publish, ack. It does no last-mile integration —
/// that is the consumers' job.
@Path("/events")
public class EventIngestResource {

	@Context
	private ServletContext servletContext;

	/// Accept a CloudEvents 1.0 envelope and publish it to the bus.
	///
	/// Returns 202 Accepted with the event id once it is on the topic, 400 if
	/// the body is not a valid CloudEvent (missing `type` or malformed JSON), or
	/// 503 if the bus publisher is not yet installed on this node (JMS resources
	/// not provisioned).
	@POST
	@Consumes({ "application/cloudevents+json", MediaType.APPLICATION_JSON })
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Publish a CloudEvent to the BLADE event bus")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Accepted and published to the topic"),
			@ApiResponse(responseCode = "400", description = "Body is not a valid CloudEvent"),
			@ApiResponse(responseCode = "503", description = "Event bus publisher not available on this node") })
	public Response ingest(String body) {
		if (!EventBus.isReady()) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"event bus not available\"}").build();
		}

		CloudEvent event;
		try {
			event = CloudEvent.fromJson(body);
		} catch (Exception e) {
			return Response.status(Response.Status.BAD_REQUEST)
					.entity("{\"error\":\"malformed CloudEvent JSON\"}").build();
		}

		if (event.getType() == null || event.getType().isEmpty()) {
			return Response.status(Response.Status.BAD_REQUEST)
					.entity("{\"error\":\"CloudEvent 'type' is required\"}").build();
		}

		// Fill in the attributes a well-formed CloudEvent needs but a terse
		// producer may omit: a source (from config) and a fresh id/time.
		if (event.getSource() == null || event.getSource().isEmpty()) {
			event.setSource(defaultSource());
		}
		if (event.getId() == null || event.getId().isEmpty()) {
			CloudEvent stamped = CloudEvent.create(event.getType(), event.getSource(), event.getSubject(),
					event.getData());
			stamped.setDatacontenttype(event.getDatacontenttype());
			event = stamped;
		}

		try {
			EventBus.publish(event);
		} catch (Exception e) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"publish failed\"}").build();
		}

		return Response.status(Response.Status.ACCEPTED)
				.entity("{\"id\":\"" + event.getId() + "\"}").build();
	}

	@SuppressWarnings("unchecked")
	private String defaultSource() {
		Object attr = (servletContext == null) ? null
				: servletContext.getAttribute(EventBusStartup.SETTINGS_SUPPLIER_ATTR);
		if (attr instanceof Supplier) {
			Object cfg = ((Supplier<Object>) attr).get();
			if (cfg instanceof EventBusSettings) {
				String source = ((EventBusSettings) cfg).getSource();
				if (source != null && !source.isEmpty()) {
					return source;
				}
			}
		}
		return "/blade/events";
	}
}
