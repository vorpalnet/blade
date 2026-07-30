package org.vorpal.blade.applications.events;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.applications.events.jms.JmsInventory;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventSubscription;
import org.vorpal.blade.framework.v3.events.EventType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The JMS administration surface: what exists, what it is doing, and where the
/// catalog and the running domain disagree.
@Path("/jms")
public class JmsAdminAPI {

	/// Tolerant of properties the model no longer has.
	///
	/// [#loadCatalog] swallows a parse failure and falls through to an empty
	/// catalog so the page still renders its destinations — which means a strict
	/// mapper would not error here, it would **silently blank the drift page** on
	/// every domain whose `events.json` predates the current model. Silent is the
	/// one failure mode this whole subsystem exists to eliminate.
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	private static final java.nio.file.Path CATALOG = Paths.get("config/custom/vorpal/events.json");

	/// The full JMS picture — servers, destinations, depths, consumers, durable
	/// subscriptions.
	@GET
	@Path("/inventory")
	@Produces(MediaType.APPLICATION_JSON)
	public Response inventory() {
		try {
			return Response.ok(MAPPER.writeValueAsString(JmsInventory.read())).build();
		} catch (Exception e) {
			return error(String.valueOf(e));
		}
	}

	/// Where the catalog and the running domain disagree.
	///
	/// Five findings. The last two are about *subscribers* and neither existed
	/// while the catalog thought an event had exactly one consumer:
	///
	/// 1. **Missing destination** — the catalog declares a type whose
	///    destination does not exist. Its events go nowhere.
	/// 2. **Selector drift** — a live durable subscription's selector is not the
	///    one its declaration derives. That consumer receives the wrong events, or
	///    none, and the symptom is indistinguishable from no events being
	///    published. It is the failure this whole design exists to make visible: a
	///    selector is a string literal no compiler ever checked.
	/// 3. **Undeclared subscription** — a live durable subscription that the
	///    catalog does not name. Usually the *orphan* of a rename: changing a
	///    subscription's name or its selector does not move the old subscription,
	///    it abandons it, and an abandoned durable subscription accumulates
	///    against the destination's quota forever. Nothing else in the domain will
	///    ever mention it again.
	/// 4. **Not yet deployed** — the catalog declares a subscription with no live
	///    subscriber. Expected right after a catalog edit; a standing complaint
	///    means the consuming app was never deployed, and the events it wants are
	///    not being held for it.
	///
	/// Only durable subscriptions are checked. A non-durable subscriber — the
	/// event tap, for instance — leaves nothing behind and cannot be orphaned.
	@GET
	@Path("/drift")
	@Produces(MediaType.APPLICATION_JSON)
	public Response drift() {
		try {
			JmsInventory.Inventory inventory = JmsInventory.read();
			EventCatalog catalog = loadCatalog();

			Map<String, JmsInventory.Destination> byJndi = new HashMap<>();
			for (JmsInventory.Destination destination : inventory.destinations) {
				if (destination.jndiName != null) {
					byJndi.put(destination.jndiName, destination);
				}
			}

			ArrayNode findings = MAPPER.createArrayNode();

			for (String finding : catalog.validate()) {
				findings.add(finding("catalog", "events.json", finding));
			}

			for (EventType type : catalog.typesOrEmpty()) {
				String jndi = catalog.destinationFor(type);
				if (jndi != null && !byJndi.containsKey(jndi)) {
					findings.add(finding("missing-destination", type.getType(), "declares destination " + jndi
							+ ", which does not exist in this domain — its events go nowhere"));
				}
			}

			// Live subscriptions, by the name the domain knows them by. That name
			// is the subscriber's identity, so it is the only thing that can be
			// matched back to a declaration — a selector cannot, since two
			// subscribers may legitimately share one.
			Map<String, JmsInventory.DurableSubscriber> live = new HashMap<>();
			for (JmsInventory.Destination destination : inventory.destinations) {
				for (JmsInventory.DurableSubscriber subscriber : destination.durableSubscribers) {
					if (subscriber.subscriptionName != null) {
						live.put(subscriber.subscriptionName, subscriber);
					}
				}
			}

			List<String> declaredNames = new ArrayList<>();
			for (EventSubscription subscription : catalog.subscriptionsOrEmpty()) {
				String name = subscription.getName();
				if (name == null || name.isEmpty()) {
					continue;
				}
				declaredNames.add(name);

				JmsInventory.DurableSubscriber subscriber = live.get(name);
				if (subscriber == null) {
					findings.add(finding("not-deployed", name,
							"is declared but has no live durable subscription. Until the consuming application is "
									+ "deployed, nothing is being held for it and its events are being missed."));
					continue;
				}

				String declared = subscription.selector();
				String actual = (subscriber.selector == null) ? "" : subscriber.selector.trim();
				String expected = (declared == null) ? "" : declared.trim();
				if (!expected.equals(actual)) {
					findings.add(finding("selector-drift", name,
							"subscribes with " + (actual.isEmpty() ? "no selector" : actual) + ", but the catalog "
									+ "derives " + (expected.isEmpty() ? "no selector" : expected)
									+ ". Regenerate and redeploy the consumer — editing the selector on a live "
									+ "subscription abandons its backlog. Pending: " + subscriber.messagesPending));
				}
			}

			for (Map.Entry<String, JmsInventory.DurableSubscriber> entry : live.entrySet()) {
				if (declaredNames.contains(entry.getKey())) {
					continue;
				}
				findings.add(finding("undeclared-subscription", entry.getKey(),
						"is live but the catalog does not declare it. Most often the orphan of a rename: the old "
								+ "subscription was abandoned rather than moved, and it will accumulate against the "
								+ "destination's quota forever. Purge it to stop it consuming quota — removing it "
								+ "outright needs WLSession.unsubscribe, because the MDB container creates these "
								+ "with an unrestricted client id. Pending: " + entry.getValue().messagesPending));
			}

			ObjectNode result = MAPPER.createObjectNode();
			result.set("findings", findings);
			result.put("declaredTypes", catalog.typesOrEmpty().size());
			result.put("declaredSubscriptions", catalog.subscriptionsOrEmpty().size());
			result.put("liveSubscriptions", live.size());
			result.put("destinations", inventory.destinations.size());
			if (inventory.error != null) {
				result.put("error", inventory.error);
			}
			return Response.ok(result.toString()).build();
		} catch (Exception e) {
			return error(String.valueOf(e));
		}
	}

	private static ObjectNode finding(String kind, String subject, String detail) {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("kind", kind);
		node.put("subject", subject);
		node.put("detail", detail);
		return node;
	}

	/// The published catalog, or an empty one. A domain with no catalog is not
	/// an error — it has simply not published yet.
	private static EventCatalog loadCatalog() {
		try {
			if (Files.exists(CATALOG)) {
				return MAPPER.readValue(CATALOG.toFile(), EventCatalog.class);
			}
		} catch (Exception e) {
			// Fall through to an empty catalog: the drift page should still
			// render the destinations it found.
		}
		return new EventCatalog();
	}

	private static Response error(String message) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("error", message);
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(MediaType.APPLICATION_JSON)
				.entity(body.toString()).build();
	}
}
