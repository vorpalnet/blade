package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What is wrong now follows the latest event per thing per node; the feed
/// keeps everything, newest first, and stays bounded.
class OpsFeedTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static CloudEvent event(String type, String key, String value, String node) {
		ObjectNode data = MAPPER.createObjectNode();
		data.put(key, value).put("node", node);
		return CloudEvent.create(type, "/blade/test", value, data);
	}

	@Test
	void anEndpointIsDownUntilItComesBackOnThatNode() {
		OpsFeed feed = new OpsFeed();
		feed.apply(event(BladeEventTypes.ENDPOINT_DOWN, "endpoint", "sbc-east", "engine1"));
		feed.apply(event(BladeEventTypes.ENDPOINT_DOWN, "endpoint", "sbc-east", "engine2"));
		assertEquals(2, feed.snapshot().path("endpointsDown").size(), "each node's view is its own");

		feed.apply(event(BladeEventTypes.ENDPOINT_UP, "endpoint", "sbc-east", "engine1"));
		assertEquals(1, feed.snapshot().path("endpointsDown").size());
		assertEquals("engine2", feed.snapshot().path("endpointsDown").get(0).path("node").asText());
	}

	@Test
	void aTrunkIsFailingUntilItRegisters() {
		OpsFeed feed = new OpsFeed();
		feed.apply(event(BladeEventTypes.TRUNK_FAILED, "gateway", "carrier-a", "engine0"));
		assertEquals(1, feed.snapshot().path("trunksFailing").size());
		feed.apply(event(BladeEventTypes.TRUNK_REGISTERED, "gateway", "carrier-a", "engine0"));
		assertEquals(0, feed.snapshot().path("trunksFailing").size());
	}

	@Test
	void theFeedIsNewestFirstAndBounded() {
		OpsFeed feed = new OpsFeed();
		for (int i = 0; i < OpsFeed.RECENT + 5; i++) {
			feed.apply(event(BladeEventTypes.SIP_DENIED, "sourceAddress", "10.0.0." + i, "engine0"));
		}
		ObjectNode snapshot = feed.snapshot();
		assertEquals(OpsFeed.RECENT, snapshot.path("recent").size());
		assertEquals("10.0.0." + (OpsFeed.RECENT + 4),
				snapshot.path("recent").get(0).path("sourceAddress").asText());
		assertEquals(BladeEventTypes.SIP_DENIED, snapshot.path("recent").get(0).path("type").asText());
	}
}
