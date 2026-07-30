package org.vorpal.blade.applications.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.io.VersionedFileStore;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.EventCatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Reads and publishes the domain-wide event catalog.
///
/// The catalog lives at `config/custom/vorpal/events.json`, relative to the
/// domain root — which is the AdminServer's working directory, so this app can
/// write it directly. The engine tier's `SettingsManager` picks the change up
/// from there with no redeploy. Exactly the mechanism the FSMAR editor uses for
/// `fsmar.json`; there is no second publish path to maintain.
///
/// Writes go through [VersionedFileStore], so every publish keeps a backup and
/// the console gets undo for free, and the body is re-serialized through Jackson
/// first: malformed JSON is rejected with its parse error rather than clobbering
/// a working catalog.
@Path("/catalog")
public class CatalogAPI {

	/// Relative to the domain root, which is the AdminServer's cwd.
	private static final java.nio.file.Path CATALOG = Paths.get("config/custom/vorpal/events.json");

	/// Tolerant of properties the model no longer has.
	///
	/// The read path hands the browser the raw file, stale fields and all, and
	/// the browser posts it straight back. A strict mapper here would therefore
	/// reject an operator's **first save on every existing domain** — an
	/// `events.json` published before `subscriptionName` and `durable` moved off
	/// `EventType` would come back as a 400 that reads "not a valid event
	/// catalog", for a catalog that is perfectly valid. The engine tier's
	/// `SettingsManager` has been lenient for exactly this reason since long
	/// before this console existed; being stricter than the thing that consumes
	/// the file is not rigour, it is an outage.
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	private static final VersionedFileStore STORE = new VersionedFileStore();

	/// The live catalog, or the framework defaults when none has been published
	/// yet — so a fresh domain opens the console on something meaningful rather
	/// than an empty page.
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response read() {
		try {
			if (Files.exists(CATALOG)) {
				String json = new String(Files.readAllBytes(CATALOG), java.nio.charset.StandardCharsets.UTF_8);
				ObjectNode body = (ObjectNode) MAPPER.readTree(json);
				body.put("published", true);
				return ok(body);
			}
			EventCatalog defaults = new EventCatalog();
			defaults.setTypes(BladeEventCatalog.analyticsTypes());
			defaults.setSubscriptions(
					java.util.Collections.singletonList(BladeEventCatalog.analyticsSubscription()));
			ObjectNode body = MAPPER.valueToTree(defaults);
			body.put("published", false);
			return ok(body);
		} catch (Exception e) {
			return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// The framework's own event types, for a console that wants to offer
	/// "restore what BLADE emits" without hand-typing six declarations.
	@GET
	@Path("/framework-types")
	@Produces(MediaType.APPLICATION_JSON)
	public Response frameworkTypes() {
		try {
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(BladeEventCatalog.analyticsTypes());
			return Response.ok(MAPPER.writeValueAsString(catalog.getTypes())).build();
		} catch (Exception e) {
			return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(e));
		}
	}

	/// Publish a catalog. Validated by round-tripping it through the model
	/// before anything touches disk.
	///
	/// **Findings are reported, not enforced.** A catalog mid-edit is legitimately
	/// incomplete — a subscription authored before the type it wants is the
	/// ordinary way round — and refusing the save would make the order of two
	/// edits matter. The same reasoning that makes `validation` default to WARN
	/// and `rejectUnknownTypes` default to off. The one finding that costs
	/// something quietly, a duplicate subscription name, is surfaced in the
	/// designer while it is being authored as well as here.
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response publish(String body) {
		EventCatalog catalog;
		try {
			catalog = MAPPER.readValue(body, EventCatalog.class);
		} catch (Exception e) {
			return error(Response.Status.BAD_REQUEST, "not a valid event catalog: " + e.getMessage());
		}
		try {
			Files.createDirectories(CATALOG.getParent());
			String canonical = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(catalog);
			STORE.write(CATALOG, canonical);

			ObjectNode result = MAPPER.createObjectNode();
			result.put("published", true);
			result.put("types", catalog.typesOrEmpty().size());
			result.put("subscriptions", catalog.subscriptionsOrEmpty().size());
			result.put("path", CATALOG.toAbsolutePath().toString());
			com.fasterxml.jackson.databind.node.ArrayNode findings = result.putArray("findings");
			for (String finding : catalog.validate()) {
				findings.add(finding);
			}
			return ok(result);
		} catch (IOException e) {
			return error(Response.Status.INTERNAL_SERVER_ERROR, "could not write the catalog: " + e.getMessage());
		}
	}

	private static Response ok(Object body) throws IOException {
		return Response.ok(MAPPER.writeValueAsString(body)).type(MediaType.APPLICATION_JSON).build();
	}

	/// Always JSON, built through Jackson: a quote inside an exception message
	/// must not produce a body the console's `response.json()` chokes on.
	private static Response error(Response.Status status, String message) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("error", message);
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body.toString()).build();
	}
}
