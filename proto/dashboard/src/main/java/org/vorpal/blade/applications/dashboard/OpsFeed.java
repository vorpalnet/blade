package org.vorpal.blade.applications.dashboard;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The platform's operations events as the dashboard shows them: what is wrong
/// now, and what just happened.
///
/// **What is wrong now** is kept as state, keyed so the latest event wins: an
/// endpoint is down until its `endpoint.up`, a trunk is failing until its
/// `trunk.registered`, and a queue's depth is its last minute's report, per node
/// because every node keeps its own view. **What just happened** is the last
/// [#RECENT] events of every operations type, newest first.
///
/// Held in memory on this server only, fed by a subscription
/// `DashboardSettingsStartup` opens once the settings exist. Live, not durable:
/// a dashboard opened after an outage shows the outage from its next event, and
/// the history belongs to whatever stores events, not to a screen.
final class OpsFeed implements EventSubscriber.Handler {

	/// This subscriber's name on the broker, and its metric key.
	static final String SUBSCRIPTION = "blade-dashboard-ops";

	/// The one feed this application keeps, read by [OpsServlet].
	static final OpsFeed FEED = new OpsFeed();

	/// How many recent events the feed keeps.
	static final int RECENT = 100;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// `endpoint|node` → the `endpoint.down` still in force.
	private final Map<String, ObjectNode> endpointsDown = new ConcurrentHashMap<>();

	/// `gateway|node` → the `trunk.failed` still in force.
	private final Map<String, ObjectNode> trunksFailing = new ConcurrentHashMap<>();

	/// `queue|node` → the latest `queue.depth`.
	private final Map<String, ObjectNode> queues = new ConcurrentHashMap<>();

	private final ArrayDeque<ObjectNode> recent = new ArrayDeque<>();

	static final List<String> TYPES = java.util.Arrays.asList(BladeEventTypes.QUEUE_DEPTH,
			BladeEventTypes.ENDPOINT_DOWN, BladeEventTypes.ENDPOINT_UP, BladeEventTypes.REGISTRATION_ADDED,
			BladeEventTypes.REGISTRATION_REMOVED, BladeEventTypes.TRUNK_REGISTERED, BladeEventTypes.TRUNK_FAILED,
			BladeEventTypes.TRUNK_UNREGISTERED, BladeEventTypes.PRESENCE_PUBLISHED, BladeEventTypes.SIP_DENIED,
			BladeEventTypes.CONFIG_SAVED, BladeEventTypes.CONFIG_PUBLISHED);

	@Override
	public void handle(List<CloudEvent> batch) {
		for (CloudEvent event : batch) {
			apply(event);
		}
	}

	/// Fold one event into the state and the feed. Package-private and free of
	/// JMS, so it is tested directly.
	void apply(CloudEvent event) {
		if (event == null || event.getType() == null) {
			return;
		}
		ObjectNode data = event.fields();
		String node = data.path("node").asText("");
		switch (event.getType()) {
		case BladeEventTypes.ENDPOINT_DOWN:
			endpointsDown.put(data.path("endpoint").asText() + "|" + node, stamped(event, data));
			break;
		case BladeEventTypes.ENDPOINT_UP:
			endpointsDown.remove(data.path("endpoint").asText() + "|" + node);
			break;
		case BladeEventTypes.TRUNK_FAILED:
			trunksFailing.put(data.path("gateway").asText() + "|" + node, stamped(event, data));
			break;
		case BladeEventTypes.TRUNK_REGISTERED:
		case BladeEventTypes.TRUNK_UNREGISTERED:
			trunksFailing.remove(data.path("gateway").asText() + "|" + node);
			break;
		case BladeEventTypes.QUEUE_DEPTH:
			queues.put(data.path("queue").asText() + "|" + node, stamped(event, data));
			break;
		default:
			break;
		}
		ObjectNode item = stamped(event, data);
		item.put("type", event.getType());
		synchronized (recent) {
			recent.addFirst(item);
			while (recent.size() > RECENT) {
				recent.removeLast();
			}
		}
	}

	/// The snapshot `/ops` serves.
	ObjectNode snapshot() {
		ObjectNode out = MAPPER.createObjectNode();
		out.set("endpointsDown", array(endpointsDown));
		out.set("trunksFailing", array(trunksFailing));
		out.set("queues", array(queues));
		ArrayNode feed = out.putArray("recent");
		synchronized (recent) {
			recent.forEach(feed::add);
		}
		return out;
	}

	private static ObjectNode stamped(CloudEvent event, ObjectNode data) {
		ObjectNode copy = data.deepCopy();
		copy.put("time", event.getTime());
		return copy;
	}

	private static ArrayNode array(Map<String, ObjectNode> state) {
		ArrayNode out = MAPPER.createArrayNode();
		state.values().forEach(out::add);
		return out;
	}
}
