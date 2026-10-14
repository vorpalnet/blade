package org.vorpal.blade.applications.callflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A recorded call reads as a live trace source: same step shape, each leg
/// named once, and the verdict alongside.
class RecordedCallsTest {

	@Test
	void aRecordedCallIsATraceSource() throws Exception {
		JsonNode payload = new ObjectMapper().readTree("{\"server\":\"engine0\",\"fault\":\"downstream\","
				+ "\"finding\":\"timeout\",\"method\":\"INVITE\",\"status\":408,\"peer\":\"pbx.example.net\",\"leg\":2,"
				+ "\"dropped\":false,\"omitted\":0,\"steps\":["
				+ "{\"t\":1000,\"dir\":\"in\",\"leg\":1,\"method\":\"INVITE\",\"cseq\":1,\"peer\":\"198.51.100.7:5060\"},"
				+ "{\"t\":1001,\"dir\":\"out\",\"leg\":2,\"method\":\"INVITE\",\"cseq\":1,\"peer\":\"pbx.example.net\"},"
				+ "{\"t\":33001,\"dir\":\"in\",\"leg\":2,\"method\":\"INVITE\",\"status\":408,\"cseq\":1},"
				+ "{\"t\":33002,\"dir\":\"out\",\"leg\":1,\"method\":\"INVITE\",\"status\":408,\"cseq\":1,\"peer\":\"198.51.100.7:5060\"}]}");
		ObjectNode src = RecordedCalls.source("listener", "0000ABCD", payload);

		assertEquals("listener", src.path("app").asText());
		assertEquals("engine0", src.path("server").asText());
		assertTrue(src.path("recorded").asBoolean());
		assertEquals("timeout", src.path("verdict").path("finding").asText());
		assertEquals(408, src.path("verdict").path("status").asInt());

		JsonNode steps = src.path("steps");
		assertEquals(4, steps.size());
		assertEquals("0000ABCD", steps.get(0).path("sessionId").asText(), "joined with other apps' records by Vorpal-ID");
		assertEquals("request", steps.get(1).path("kind").asText());
		assertEquals("INVITE", steps.get(1).path("label").asText());
		assertEquals("response", steps.get(2).path("kind").asText());
		assertEquals("408 INVITE", steps.get(2).path("label").asText());
		assertEquals("pbx.example.net", steps.get(2).path("peer").asText(),
				"the container's own 408 has no address; its leg's name keeps it on the callee's lane");
		assertEquals(3, steps.get(3).path("order").asInt());
	}
}
