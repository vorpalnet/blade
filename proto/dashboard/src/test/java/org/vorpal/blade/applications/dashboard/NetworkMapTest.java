package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What each site on the network map claims: endpoints, trunks, calls
/// arriving, and refused legs, and what no site claims.
class NetworkMapTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Date T = new Date(1_791_800_000_000L);

	private static NetworkSite site(String name, List<String> roles, String... addresses) {
		NetworkSite s = new NetworkSite(name, "somewhere", 39.0, -77.0, roles);
		s.setAddresses(Arrays.asList(addresses));
		return s;
	}

	private static final NetworkSite CLUSTER = site("dc", Arrays.asList("cluster"), "10.1.1.124");
	private static final NetworkSite CARRIER = site("carrier", Arrays.asList("inbound", "downstream"),
			"sip.carrier.example.net", "192.0.2.0/28");
	private static final NetworkSite PBX = site("pbx", Arrays.asList("downstream"), "pbx.example.com");

	private static AnalyticsReports.Table origins(Object[]... rows) {
		AnalyticsReports.Table t = new AnalyticsReports.Table("origin", "calls", "last call");
		for (Object[] r : rows) {
			t.row(r);
		}
		return t;
	}

	private static AnalyticsReports.Table failures(Object[]... rows) {
		AnalyticsReports.Table t = new AnalyticsReports.Table("destination", "responder", "status", "not sent", "calls",
				"last", "retry after");
		for (Object[] r : rows) {
			t.row(r);
		}
		return t;
	}

	private static ObjectNode map(List<JsonNode> balancer, JsonNode trunks, AnalyticsReports.Table origins,
			AnalyticsReports.Table failures) {
		return NetworkMap.assemble(Arrays.asList(CLUSTER, CARRIER, PBX), balancer, trunks, origins, failures, 15, 3);
	}

	private static JsonNode site(ObjectNode map, String name) {
		for (JsonNode s : map.path("sites")) {
			if (name.equals(s.path("name").asText())) {
				return s;
			}
		}
		throw new AssertionError("no site " + name);
	}

	@Test
	void callsArriveAtTheInboundSiteThatClaimsTheirOrigin() {
		ObjectNode m = map(Collections.emptyList(), null,
				origins(new Object[] { "192.0.2.5", 4L, T }, new Object[] { "203.0.113.9", 2L, T },
						new Object[] { "10.1.1.124", 1L, T }),
				null);
		assertEquals(4, site(m, "carrier").path("inbound").path("calls").asInt(), "192.0.2.5 is inside its /28");
		assertEquals(T.getTime(), site(m, "carrier").path("inbound").path("last").asLong());
		assertFalse(site(m, "dc").has("inbound"), "the cluster is not an inbound site, so claims no origin");
		List<String> unclaimed = new ArrayList<>();
		m.path("unplaced").path("origins").forEach(o -> unclaimed.add(o.path("origin").asText()));
		assertEquals(Arrays.asList("203.0.113.9", "10.1.1.124"), unclaimed,
				"an unknown peer, and a call from our own address that no inbound site claims, are listed");
	}

	@Test
	void aRefusedLegBelongsToTheSiteItWasSentToOrThatAnswered() {
		ObjectNode m = map(Collections.emptyList(), null, null,
				failures(new Object[] { "sip:+15550100@pbx.example.com", "198.51.100.20", 503L, false, 2L, T, 30L },
						new Object[] { "sip:bob@203.0.113.50", "192.0.2.3", 404L, false, 1L, T, null },
						new Object[] { "sip:bob@198.51.100.99", null, 408L, false, 3L, T, null }));
		JsonNode pbx = site(m, "pbx").path("failures");
		assertEquals(1, pbx.size(), "by the Request-URI's host");
		assertEquals("failing", pbx.get(0).path("kind").asText());
		assertEquals("server unavailable", pbx.get(0).path("meaning").asText());
		assertEquals(30, pbx.get(0).path("retryAfter").asInt());
		JsonNode carrier = site(m, "carrier").path("failures");
		assertEquals(1, carrier.size(), "by the address that answered");
		assertEquals("misconfigured", carrier.get(0).path("kind").asText());
		JsonNode lost = m.path("unplaced").path("failures");
		assertEquals(1, lost.size(), "a 408 from a server no site names is listed, not dropped");
		assertEquals(408, lost.get(0).path("status").asInt());
		assertEquals("198.51.100.99", lost.get(0).path("server").asText(), "nobody answered: the host it was sent to");
	}

	@Test
	void unclaimedRefusalsAreCountedPerServerNotPerNumber() {
		ObjectNode m = map(Collections.emptyList(), null, null,
				failures(new Object[] { "sip:a@203.0.113.1", null, 408L, false, 1L, T, null },
						new Object[] { "sip:b@203.0.113.1", null, 408L, false, 1L, T, null },
						new Object[] { "sip:c@203.0.113.1:5060", null, 408L, false, 2L, T, null }));
		JsonNode lost = m.path("unplaced").path("failures");
		assertEquals(1, lost.size());
		assertEquals("203.0.113.1", lost.get(0).path("server").asText());
		assertEquals(4, lost.get(0).path("calls").asInt());
	}

	@Test
	void anInviteThatCouldNotBeSentIsTheServerUnreachable() {
		ObjectNode m = map(Collections.emptyList(), null, null,
				failures(new Object[] { "sip:bob@pbx.example.com:5999;transport=tcp", null, 500L, true, 1L, T, null }));
		JsonNode f = site(m, "pbx").path("failures").get(0);
		assertEquals("failing", f.path("kind").asText());
		assertEquals("could not reach the server", f.path("meaning").asText());
	}

	@Test
	void refusalsThatArePartOfAWorkingCallAreLeftOut() {
		ObjectNode m = map(Collections.emptyList(), null, null,
				failures(new Object[] { "sip:bob@pbx.example.com", "198.51.100.20", 407L, false, 5L, T, null },
						new Object[] { "sip:bob@pbx.example.com", "198.51.100.20", 487L, false, 2L, T, null },
						new Object[] { "sip:bob@nowhere.example.org", null, 401L, false, 1L, T, null },
						new Object[] { "sip:bob@pbx.example.com", "198.51.100.20", 486L, false, 1L, T, null }));
		JsonNode pbx = site(m, "pbx").path("failures");
		assertEquals(1, pbx.size(), "the challenge and the cancel are left out; the busy stays, as a callee's");
		assertEquals("callee", pbx.get(0).path("kind").asText());
		assertEquals(0, m.path("unplaced").path("failures").size(), "a challenge is not an unknown server failing");
	}

	@Test
	void aSiteHasTheBalancersEndpointsThereAndItsFailingTrunks() throws Exception {
		JsonNode view = MAPPER.readTree("{\"server\":\"engine0\",\"health\":{\"sites\":{"
				+ "\"pbx\":{\"label\":\"PBX\",\"lat\":32.8,\"lon\":-96.8},"
				+ "\"lab\":{\"label\":\"Lab\",\"lat\":40.0,\"lon\":-100.0},"
				+ "\"nowhere\":{\"label\":\"No coordinates\"}},"
				+ "\"plans\":{\"default\":[{\"endpoints\":["
				+ "{\"name\":\"pbx1\",\"uri\":\"sip:pbx1.example.com\",\"site\":\"pbx\",\"status\":\"down\",\"routable\":false,\"enabled\":true,\"note\":\"timeout\"},"
				+ "{\"name\":\"pbx2\",\"uri\":\"sip:pbx.example.com:5060\",\"status\":\"up\",\"routable\":true,\"enabled\":true,\"lastRttMs\":12}"
				+ "]}]}}}");
		JsonNode trunks = MAPPER.readTree("[{\"gateway\":\"carrier-trunk\",\"registrar\":\"sip.carrier.example.net:5060\","
				+ "\"status\":403,\"reason\":\"Forbidden\",\"node\":\"engine0\"}]");
		ObjectNode m = map(Arrays.asList(view), trunks, null, null);

		JsonNode endpoints = site(m, "pbx").path("endpoints");
		assertEquals(2, endpoints.size(), "pbx1 by its balancer site, pbx2 by its URI's host");
		assertEquals("down", endpoints.get(0).path("status").asText());
		assertEquals("engine0", endpoints.get(0).path("node").asText());
		assertEquals(12, endpoints.get(1).path("rttMs").asInt());
		assertEquals(1, site(m, "carrier").path("trunks").size(), "the registrar's host is the carrier's");

		List<String> names = new ArrayList<>();
		m.path("sites").forEach(s -> names.add(s.path("name").asText()));
		assertEquals(Arrays.asList("dc", "carrier", "pbx", "lab"), names,
				"the balancer's lab joins; pbx is not entered twice; a site without coordinates is not added");
		assertTrue(site(m, "lab").path("roles").toString().contains("downstream"));
	}

	@Test
	void aSiteHasTheDatasourcesItNamesOrWhoseHostItClaims() throws Exception {
		NetworkSite db = site("db", Arrays.asList("downstream"), "db.example.com");
		db.setDatasources(Arrays.asList("BladeAnalytics"));
		java.util.Map<String, String> hosts = new java.util.LinkedHashMap<>();
		hosts.put("BladeAnalytics", null);
		hosts.put("BladeCatalog", "db.example.com");
		hosts.put("Other", "elsewhere.example.net");
		JsonNode bus = MAPPER.readTree("{\"subscriptions\":[{\"name\":\"analytics-db\",\"server\":\"engine0\",\"active\":false,\"waiting\":12}],\"errorQueue\":0}");
		ObjectNode m = NetworkMap.assemble(Arrays.asList(CLUSTER, db), Collections.emptyList(), null, null, null, 15, 3,
				hosts, bus, Collections.emptyList());
		assertEquals("[\"BladeAnalytics\",\"BladeCatalog\"]", site(m, "db").path("datasources").toString(),
				"named, then claimed by its URL's host; a TNS alias only by name");
		assertEquals("analytics-db", site(m, "dc").path("bus").path("subscriptions").get(0).path("name").asText(),
				"the bus belongs to the cluster");
		assertFalse(site(m, "db").has("bus"));
	}

	@Test
	void aRemoteIsAtTheSiteOfItsHostOrItsDatasource() throws Exception {
		NetworkSite db = site("db", Arrays.asList("downstream"), "db.example.com");
		db.setDatasources(Arrays.asList("Lookup"));
		List<JsonNode> remotes = new ArrayList<>();
		remotes.add(MAPPER.readTree("{\"kind\":\"rest\",\"remote\":\"pbx.example.com:8443\",\"calls\":5,\"failures\":2,\"application\":\"irouter\",\"server\":\"engine0\"}"));
		remotes.add(MAPPER.readTree("{\"kind\":\"jdbc\",\"remote\":\"jdbc/Lookup\",\"calls\":3,\"failures\":0,\"application\":\"irouter\",\"server\":\"engine0\"}"));
		remotes.add(MAPPER.readTree("{\"kind\":\"oauth\",\"remote\":\"login.example.org\",\"calls\":1,\"failures\":1,\"application\":\"irouter\",\"server\":\"engine0\"}"));
		remotes.add(MAPPER.readTree("{\"kind\":\"rest\",\"remote\":\"quiet.example.org\",\"calls\":4,\"failures\":0,\"application\":\"irouter\",\"server\":\"engine0\"}"));
		ObjectNode m = NetworkMap.assemble(Arrays.asList(CLUSTER, PBX, db), Collections.emptyList(), null, null, null, 15,
				3, Collections.emptyMap(), null, remotes);
		assertEquals("pbx.example.com:8443", site(m, "pbx").path("remotes").get(0).path("remote").asText(), "by host, port aside");
		assertEquals("jdbc/Lookup", site(m, "db").path("remotes").get(0).path("remote").asText(), "by datasource name");
		JsonNode lost = m.path("unplaced").path("remotes");
		assertEquals(1, lost.size(), "an unclaimed remote is listed only when it has failed");
		assertEquals("login.example.org", lost.get(0).path("remote").asText());
	}
}
