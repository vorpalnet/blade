package org.vorpal.blade.applications.callflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A recorded call reads as a live trace source: same step shape, each step at
/// its own far end, and the verdict alongside.
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

	/// A proxy's caller and callee share one SIP session: one leg, two far ends.
	@Test
	void aProxiedCallKeepsBothFarEnds() throws Exception {
		JsonNode payload = new ObjectMapper().readTree("{\"fault\":\"downstream\",\"finding\":\"timeout\","
				+ "\"method\":\"INVITE\",\"status\":408,\"leg\":1,\"peer\":\"192.0.2.10:5091\",\"steps\":["
				+ "{\"t\":1000,\"dir\":\"in\",\"leg\":1,\"method\":\"INVITE\",\"cseq\":1,\"peer\":\"192.0.2.10:5071\"},"
				+ "{\"t\":1003,\"dir\":\"out\",\"leg\":1,\"method\":\"INVITE\",\"cseq\":1,\"peer\":\"192.0.2.10:5091\"},"
				+ "{\"t\":33000,\"dir\":\"in\",\"leg\":1,\"method\":\"INVITE\",\"status\":408,\"cseq\":1},"
				+ "{\"t\":33000,\"dir\":\"out\",\"leg\":1,\"method\":\"INVITE\",\"status\":408,\"cseq\":1,"
				+ "\"peer\":\"192.0.2.10:5071\"}]}");
		JsonNode steps = RecordedCalls.source("irouter", "D6E3ADDB", payload).path("steps");

		assertEquals("192.0.2.10:5071", steps.get(0).path("peer").asText(), "the caller");
		assertEquals("192.0.2.10:5091", steps.get(1).path("peer").asText(), "where the proxy sent it, not the caller");
		assertEquals("192.0.2.10:5091", steps.get(2).path("peer").asText(),
				"the container's own 408 sits with the INVITE it answers");
		assertEquals("192.0.2.10:5071", steps.get(3).path("peer").asText());
	}

	/// A request sent to a host name is answered from its IP address; the
	/// callee's later BYE comes from that address too. One lane.
	@Test
	void aHostAndItsAddressShareALane() throws Exception {
		JsonNode payload = new ObjectMapper().readTree("{\"fault\":\"application\",\"finding\":\"unrelayed\","
				+ "\"method\":\"BYE\",\"leg\":2,\"steps\":["
				+ "{\"t\":1,\"dir\":\"in\",\"leg\":1,\"method\":\"INVITE\",\"cseq\":1,\"peer\":\"198.51.100.7:5060\"},"
				+ "{\"t\":2,\"dir\":\"out\",\"leg\":2,\"method\":\"INVITE\",\"cseq\":1,\"peer\":\"pbx.example.net\"},"
				+ "{\"t\":3,\"dir\":\"in\",\"leg\":2,\"method\":\"INVITE\",\"status\":200,\"cseq\":1,"
				+ "\"peer\":\"203.0.113.9:5060\"},"
				+ "{\"t\":9,\"dir\":\"in\",\"leg\":2,\"method\":\"BYE\",\"cseq\":2,\"peer\":\"203.0.113.9:5060\"}]}");
		JsonNode steps = RecordedCalls.source("transfer", "0000ABCE", payload).path("steps");

		assertEquals("pbx.example.net", steps.get(2).path("peer").asText(), "the 200 stands with its INVITE");
		assertEquals("pbx.example.net", steps.get(3).path("peer").asText(), "the BYE from the address that answered");
	}
}
