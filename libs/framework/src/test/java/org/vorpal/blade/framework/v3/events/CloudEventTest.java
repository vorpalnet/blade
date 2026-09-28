package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The envelope's wire behavior — what round-trips, what stays off the wire,
/// and what a consumer must survive reading.
class CloudEventTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("dataversion round-trips through the wire form")
	void dataversionRoundTrips() throws Exception {
		ObjectNode data = MAPPER.createObjectNode();
		data.put("caller", "alice");

		CloudEvent published = CloudEvent.create("net.example.thing.happened", "/blade/events", "session-1", data, 3);
		CloudEvent received = CloudEvent.fromJson(published.toJson());

		assertEquals(Integer.valueOf(3), received.getDataversion());
		assertEquals(published.getType(), received.getType());
		assertEquals(published.getId(), received.getId());
	}

	/// An unversioned envelope must not grow a `dataversion` attribute on the
	/// wire — absence is the signal that the producer predates versioning, and
	/// consumers treat it as "no skew" rather than "version zero".
	@Test
	@DisplayName("an unversioned envelope stays unversioned on the wire")
	void absentVersionStaysAbsent() throws Exception {
		CloudEvent published = CloudEvent.create("net.example.thing.happened", "/blade/events", null, null);

		assertFalse(published.toJson().contains("dataversion"));
		assertNull(CloudEvent.fromJson(published.toJson()).getDataversion());
	}

	/// A consumer built before an extension attribute existed must keep reading
	/// envelopes that carry it — otherwise adding any attribute breaks every
	/// consumer built against an older framework jar. `dataversion` itself is
	/// exactly such an addition.
	@Test
	@DisplayName("unknown extension attributes are ignored, not fatal")
	void unknownAttributesAreIgnored() throws Exception {
		String json = "{\"specversion\":\"1.0\",\"type\":\"net.example.thing.happened\","
				+ "\"source\":\"/blade/events\",\"id\":\"abc\",\"someattrfromthefuture\":\"x\"}";

		CloudEvent received = CloudEvent.fromJson(json);
		assertEquals("net.example.thing.happened", received.getType());
	}

	/// A call event published by a framework jar from before the flat payload
	/// still reads through [CloudEvent#fields]: the name/value pairs become
	/// fields, and a numeric string answers `asLong` like a number would.
	@Test
	@DisplayName("fields() folds a legacy attributes array into the flat payload")
	void fieldsReadsTheLegacyShape() throws Exception {
		String json = "{\"specversion\":\"1.0\",\"type\":\"org.vorpal.blade.call.utterance\","
				+ "\"source\":\"/blade/app\",\"id\":\"abc\",\"data\":{\"vorpalId\":\"0000ABCD\","
				+ "\"attributes\":[{\"name\":\"text\",\"value\":\"hello\"},"
				+ "{\"name\":\"startMs\",\"value\":\"1500\"},{\"name\":\"vorpalId\",\"value\":\"spoof\"}]}}";

		ObjectNode fields = CloudEvent.fromJson(json).fields();

		assertEquals("hello", fields.path("text").asText());
		assertEquals(1500L, fields.path("startMs").asLong());
		assertEquals("0000ABCD", fields.path("vorpalId").asText(), "a real field is not overwritten by a pair");
		assertFalse(fields.has("attributes"));
	}

	@Test
	@DisplayName("fields() of the flat shape is the payload, and never null")
	void fieldsReadsTheFlatShape() {
		ObjectNode data = MAPPER.createObjectNode();
		data.put("text", "hello").put("startMs", 1500L);

		assertEquals(1500L, CloudEvent.create("t", "/s", null, data).fields().path("startMs").asLong());
		assertEquals(0, CloudEvent.create("t", "/s", null, null).fields().size());
	}
}
