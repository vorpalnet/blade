package org.vorpal.blade.applications.events;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventSourceGenerator;
import org.vorpal.blade.framework.v3.events.EventSubscription;
import org.vorpal.blade.framework.v3.events.EventType;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The designer's live preview and download.
///
/// Regenerates on every keystroke, which is why
/// [EventSourceGenerator] tolerates half-authored declarations rather than
/// throwing — a preview that explodes while you are still typing the event name
/// is useless.
///
/// Follows `admin/crud-editor`'s `PreviewServlet` discipline: catch `Throwable`
/// and always answer JSON, so an uncaught error cannot become a WebLogic HTML
/// error page that breaks the client's `response.json()`.
///
/// **Two previews, because an event and a subscriber are different things.** A
/// declaration yields the payload class, its schema, a sample envelope and the
/// producer snippet. A *subscription* yields the consumer — which needs the whole
/// catalog, not one declaration, because its selector and its dispatch come from
/// the types it names.
@Path("/designer")
public class DesignerAPI {

	/// Tolerant of properties the model no longer has.
	///
	/// A domain published its `events.json` before `subscriptionName` and
	/// `durable` moved off `EventType`. The browser round-trips whatever the
	/// server handed it, so a strict mapper here would reject an operator's first
	/// save on every existing domain — with a 400 that says nothing useful. The
	/// engine tier's `SettingsManager` has been lenient for exactly this reason
	/// since long before this console existed.
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/// Every generated artifact for one declaration, in one round trip — the
	/// preview tabs are a client-side switch, not five requests.
	@POST
	@javax.ws.rs.Path("/preview")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response preview(String body) {
		try {
			EventType declaration = MAPPER.readValue(body, EventType.class);
			ObjectNode result = MAPPER.createObjectNode();
			result.put("java", EventSourceGenerator.javaSource(declaration));
			result.put("producer", EventSourceGenerator.producerSnippet(declaration));
			result.set("schema", EventSourceGenerator.schema(declaration, MAPPER));
			result.put("sample", EventSourceGenerator.sampleEnvelope(declaration, MAPPER));
			result.put("selector", declaration.selector());
			return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Throwable t) {
			return error(Response.Status.BAD_REQUEST, String.valueOf(t));
		}
	}

	/// The consumer for one subscription.
	///
	/// Takes `{"subscription": {...}, "catalog": {...}}` rather than a bare
	/// subscription: the generated MDB binds to the destination its *types* live
	/// on, dispatches to a payload class per type, and derives its selector from
	/// them. None of that is knowable from the subscription alone.
	@POST
	@javax.ws.rs.Path("/preview-subscription")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response previewSubscription(String body) {
		try {
			ObjectNode request = (ObjectNode) MAPPER.readTree(body);
			EventSubscription subscription = MAPPER.treeToValue(request.path("subscription"), EventSubscription.class);
			EventCatalog catalog = MAPPER.treeToValue(request.path("catalog"), EventCatalog.class);

			ObjectNode result = MAPPER.createObjectNode();
			result.put("consumer", EventSourceGenerator.consumerSource(subscription, catalog));
			result.put("clientId", subscription == null ? null : subscription.clientId());
			result.put("subscriptionName", subscription == null ? null : subscription.subscriptionName());
			result.put("selector", subscription == null ? null : subscription.selector());
			result.put("selectorRationale", subscription == null ? null : subscription.selectorRationale());
			result.put("destination",
					catalog == null ? null : catalog.destinationForSubscription(subscription));

			// The findings that matter most here are the ones about THIS
			// subscription — a duplicate name, or a type the catalog does not
			// declare. Reported beside the preview rather than only on publish, so
			// a collision is visible while it is still being authored.
			ArrayNode findings = result.putArray("findings");
			if (catalog != null) {
				for (String finding : catalog.validate()) {
					findings.add(finding);
				}
			}
			return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Throwable t) {
			return error(Response.Status.BAD_REQUEST, String.valueOf(t));
		}
	}

	/// The payload contract as a Maven module the developer unzips into their own
	/// project.
	///
	/// The server generates source and stops there — it does not compile, and it
	/// does not publish a model jar into the shared library. The wire contract
	/// for this bus is the JSON Schema, not a shared Java class, which is what
	/// keeps a consumer written in another language a first-class citizen.
	@POST
	@javax.ws.rs.Path("/download")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces("application/zip")
	public Response download(String body) {
		try {
			EventType declaration = MAPPER.readValue(body, EventType.class);
			byte[] zip = EventSourceGenerator.moduleZip(declaration, MAPPER);
			String artifact = String.valueOf(declaration.getType()).replaceAll("[^A-Za-z0-9]", "-").toLowerCase();
			return Response.ok(zip)
					.header("Content-Disposition", "attachment; filename=\"" + artifact + ".zip\"")
					.build();
		} catch (Throwable t) {
			return error(Response.Status.BAD_REQUEST, String.valueOf(t));
		}
	}

	/// The consumer module for one subscription — the listener plus the payload
	/// classes it binds to.
	///
	/// This is the one that goes into the *consuming* application's source tree.
	/// Never into the framework: every BLADE WAR bundles the framework jar into
	/// its own `WEB-INF/lib` and EJB scanning covers `WEB-INF/lib`, so a consumer
	/// placed there would activate in every deployed application at once, all of
	/// them contending for one client id.
	@POST
	@javax.ws.rs.Path("/download-subscription")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces("application/zip")
	public Response downloadSubscription(String body) {
		try {
			ObjectNode request = (ObjectNode) MAPPER.readTree(body);
			EventSubscription subscription = MAPPER.treeToValue(request.path("subscription"), EventSubscription.class);
			EventCatalog catalog = MAPPER.treeToValue(request.path("catalog"), EventCatalog.class);

			byte[] zip = EventSourceGenerator.subscriptionModuleZip(subscription, catalog, MAPPER);
			String artifact = String.valueOf(subscription.getName()).replaceAll("[^A-Za-z0-9]", "-").toLowerCase();
			return Response.ok(zip)
					.header("Content-Disposition", "attachment; filename=\"" + artifact + "-consumer.zip\"")
					.build();
		} catch (Throwable t) {
			return error(Response.Status.BAD_REQUEST, String.valueOf(t));
		}
	}

	private static Response error(Response.Status status, String message) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("error", message);
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body.toString()).build();
	}
}
