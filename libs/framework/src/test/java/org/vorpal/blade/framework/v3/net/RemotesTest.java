package org.vorpal.blade.framework.v3.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventPublisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// What an application has found of the services it calls.
class RemotesTest {

	static final class Capture extends EventPublisher {
		final List<CloudEvent> sent = new ArrayList<>();

		Capture() {
			super(EventBus.CONNECTION_FACTORY_JNDI, EventBus.TOPIC_JNDI);
		}

		@Override
		public void publish(CloudEvent event) {
			sent.add(event);
		}

		@Override
		public void close() {
		}
	}

	private Capture bus;

	@BeforeEach
	void setUp() {
		Remotes.clear();
		bus = new Capture();
		EventBus.register(bus);
	}

	@AfterEach
	void tearDown() {
		EventBus.unregisterAll();
		Remotes.clear();
	}

	private static JsonNode only() throws Exception {
		JsonNode all = new ObjectMapper().readTree(Remotes.json());
		assertEquals(1, all.size());
		return all.get(0);
	}

	@Test
	void callsFailuresAndTheRunOfFailuresSinceTheLastSuccess() throws Exception {
		Remotes.succeeded("rest", "api.example.com", 40);
		Remotes.failed("rest", "api.example.com", "HTTP 503", 10);
		Remotes.failed("rest", "api.example.com", "HttpConnectTimeoutException: HTTP connect timed out", 5000);
		JsonNode r = only();
		assertEquals(3, r.path("calls").asInt());
		assertEquals(2, r.path("failures").asInt());
		assertEquals(2, r.path("consecutive").asInt());
		assertEquals("HttpConnectTimeoutException: HTTP connect timed out", r.path("lastFailure").asText());
		assertEquals(1683, r.path("avgMs").asInt());
		assertTrue(r.path("lastFailureAt").asLong() > 0);

		Remotes.succeeded("rest", "api.example.com", 40);
		assertEquals(0, only().path("consecutive").asInt(), "a success ends the run");
	}

	@Test
	void aBreakerEdgeMarksTheRemoteAndPublishesOnce() throws Exception {
		Remotes.breaker("jdbc", "jdbc/Lookup", true, "IO Error: Connection refused");
		assertTrue(only().path("open").asBoolean());
		Remotes.breaker("jdbc", "jdbc/Lookup", false, null);
		assertEquals(false, only().path("open").asBoolean());

		assertEquals(2, bus.sent.size());
		assertEquals(BladeEventTypes.REMOTE_DOWN, bus.sent.get(0).getType());
		assertEquals("jdbc/Lookup", bus.sent.get(0).getData().path("remote").asText());
		assertEquals("IO Error: Connection refused", bus.sent.get(0).getData().path("reason").asText());
		assertEquals(BladeEventTypes.REMOTE_UP, bus.sent.get(1).getType());
	}

	@Test
	void aUrlTemplatedPerCallCannotGrowTheMapWithoutEnd() throws Exception {
		for (int i = 0; i < Remotes.MAX_REMOTES + 50; i++) {
			Remotes.failed("rest", "host" + i + ".example.com", "HTTP 500", 1);
		}
		assertEquals(Remotes.MAX_REMOTES, new ObjectMapper().readTree(Remotes.json()).size());
	}

	@Test
	void theRemoteIsTheHostAndPortOrTheNameItself() {
		assertEquals("api.example.com", Remotes.hostOf("https://api.example.com/v1/lookup?x=1"));
		assertEquals("api.example.com:8443", Remotes.hostOf("https://api.example.com:8443/v1"));
		assertEquals("ldap.example.com:389", Remotes.hostOf("ldap://ldap.example.com:389/dc=example"));
		assertEquals("jdbc/Lookup", Remotes.hostOf("jdbc/Lookup"));
		assertEquals(null, Remotes.hostOf(null));
	}

	@Test
	void aReasonIsTheInnermostCause() {
		Exception e = new RuntimeException("request failed",
				new java.io.IOException("wrapped", new java.net.ConnectException("Connection refused")));
		assertEquals("ConnectException: Connection refused", Remotes.reason(e));
	}
}
