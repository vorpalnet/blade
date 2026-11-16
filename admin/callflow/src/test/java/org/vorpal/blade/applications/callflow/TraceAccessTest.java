package org.vorpal.blade.applications.callflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.access.Access;
import org.vorpal.blade.framework.v3.access.Grant;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.AccessRule;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What a viewer's grants and the access policy let the Trace app send: whose
/// steps, by the grants, and whether their text, by the policy.
class TraceAccessTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// Two calls on one app: C1 for tenant tenant_001's radiology, with an HTTP step
	/// that carries no scope; C2 for tenant tenant_002. One recorded source with
	/// parties.
	private static ObjectNode traces() throws Exception {
		return (ObjectNode) MAPPER.readTree("{\"sources\":["
				+ "{\"app\":\"tpcc\",\"steps\":["
				+ "{\"sessionId\":\"C1\",\"kind\":\"http-request\",\"message\":\"POST /call\"},"
				+ "{\"sessionId\":\"C1\",\"kind\":\"request\",\"tenant\":\"tenant_001\",\"department\":\"radiology\",\"message\":\"INVITE sip:+1555@x\"},"
				+ "{\"sessionId\":\"C1\",\"kind\":\"request\",\"tenant\":\"tenant_001\",\"department\":\"sales\",\"message\":\"INVITE sip:+1666@x\"},"
				+ "{\"sessionId\":\"C2\",\"kind\":\"http-request\",\"message\":\"POST /call\"},"
				+ "{\"sessionId\":\"C2\",\"kind\":\"request\",\"tenant\":\"tenant_002\",\"message\":\"INVITE sip:+1777@x\"}]},"
				+ "{\"app\":\"gateway\",\"recorded\":true,\"parties\":{\"from\":\"<sip:+1555@x>\"},\"steps\":["
				+ "{\"sessionId\":\"C1\",\"kind\":\"request\",\"domain\":\"ashburn\",\"tenant\":\"tenant_001\",\"department\":\"radiology\"}]}"
				+ "]}");
	}

	private static Grant grant(String tenant, String department) {
		Grant g = new Grant();
		g.setTenants(Arrays.asList(tenant));
		g.setDepartments(Arrays.asList(department));
		return g;
	}

	/// A viewer in group `readers`, whose policy lets readers read the calls
	/// `match` names (every call, when empty).
	private static Access reading(Map<String, String> match, Access scope) {
		AccessPolicy policy = new AccessPolicy();
		policy.getRules().add(new AccessRule("readers", Arrays.asList("readers"), match, Arrays.asList("phi:transcript")));
		return scope.withContent(SubjectAttributes.of("viewer", new HashSet<>(Arrays.asList("readers"))),
				new AccessEvaluator(policy));
	}

	/// Scope from `grants`, and every call's text when `content`.
	private static TraceAccess viewer(boolean content, Grant... grants) {
		Access scope = Access.of(Arrays.asList(grants));
		return new TraceAccess(content ? reading(new HashMap<>(), scope) : scope, "ashburn");
	}

	private static TraceAccess admin() {
		return new TraceAccess(reading(new HashMap<>(), Access.all()), "ashburn");
	}

	private static List<String> messages(ObjectNode out) {
		List<String> m = new ArrayList<>();
		for (JsonNode src : out.path("sources")) {
			for (JsonNode step : src.path("steps")) {
				m.add(step.path("sessionId").asText() + ":" + step.path("message").asText(null));
			}
		}
		return m;
	}

	@Test
	void noLimitSendsEverything() throws Exception {
		ObjectNode out = traces();
		admin().apply(out);
		assertEquals(6, messages(out).size());
		assertFalse(out.has(TraceAccess.CONTENT_HIDDEN));
	}

	@Test
	void aDepartmentSeesItsOwnPartsAndTheCallsHttpSteps() throws Exception {
		ObjectNode out = traces();
		viewer(true, grant("tenant_001", "radiology")).apply(out);
		assertEquals(Arrays.asList("C1:POST /call", "C1:INVITE sip:+1555@x", "C1:null"), messages(out));
		assertTrue(out.path("sources").get(1).has("parties"), "content: the recorded call keeps its parties");
	}

	@Test
	void withoutContentTheShapeStaysAndTheTextGoes() throws Exception {
		ObjectNode out = traces();
		viewer(false, grant("*", "*")).apply(out);
		List<String> m = messages(out);
		assertEquals(6, m.size());
		for (String s : m) {
			assertTrue(s.endsWith(":null"), s);
		}
		assertFalse(out.path("sources").get(1).has("parties"));
		assertTrue(out.path(TraceAccess.CONTENT_HIDDEN).asBoolean());
	}

	@Test
	void noGrantSeesNothing() throws Exception {
		ObjectNode out = traces();
		new TraceAccess(Access.of(new ArrayList<Grant>()), "ashburn").apply(out);
		assertEquals(0, out.path("sources").size());
	}

	@Test
	void anotherDomainsStepIsNotShown() throws Exception {
		Grant g = grant("tenant_001", "*");
		g.setDomains(Arrays.asList("west"));
		ObjectNode out = traces();
		viewer(true, g).apply(out);
		assertEquals(0, out.path("sources").size());
	}

	@Test
	void onlyAViewerWhoSeesAllOfACallMayReplaceIt() throws Exception {
		JsonNode call = MAPPER.readTree("{\"sources\":[{\"steps\":["
				+ "{\"sessionId\":\"C1\",\"kind\":\"http-request\"},"
				+ "{\"sessionId\":\"C1\",\"kind\":\"request\",\"tenant\":\"tenant_001\",\"department\":\"radiology\"},"
				+ "{\"sessionId\":\"C1\",\"kind\":\"request\",\"tenant\":\"tenant_001\",\"department\":\"sales\"}]}]}");
		assertTrue(admin().seesAll(call));
		assertTrue(viewer(true, grant("tenant_001", "*")).seesAll(call));
		assertFalse(viewer(true, grant("tenant_001", "radiology")).seesAll(call), "sales' part is not theirs");
		assertFalse(viewer(false, grant("tenant_001", "*")).seesAll(call),
				"saving keeps text: needs the policy to let them read it");
	}

	@Test
	void savedCallsAreListedByTheirScopes() throws Exception {
		JsonNode scopes = TraceAccess.scopesOf(traces().path("sources"), "ashburn");
		assertEquals("[[\"ashburn\",\"tenant_001\",\"radiology\"],[\"ashburn\",\"tenant_001\",\"sales\"],[\"ashburn\",\"tenant_002\",null]]",
				scopes.toString(), "each distinct scope once, in the order seen");
		assertTrue(viewer(false, grant("tenant_002", "*")).seesAny(scopes));
		assertFalse(viewer(true, grant("other", "*")).seesAny(scopes));
		assertFalse(viewer(true, grant("tenant_001", "*")).seesAny(null), "an old summary is a call with no tenant");
		assertTrue(viewer(true, grant("*", "*")).seesAny(null));
	}

	@Test
	void recordedCallsAreNarrowedInSql() {
		List<Object> params = new ArrayList<>();
		assertEquals("", admin().recordedWhere(params));
		assertEquals(" AND 1 = 0", new TraceAccess(Access.of(new ArrayList<Grant>()), "ashburn").recordedWhere(params));
		String sql = viewer(false, grant("tenant_001", "radiology"), grant("tenant_002", "*")).recordedWhere(params);
		assertEquals(" AND ((1 = 1 AND tenant IN (?) AND department IN (?)) OR (1 = 1 AND tenant IN (?)))", sql);
		assertEquals(Arrays.asList("tenant_001", "radiology", "tenant_002"), params);
	}

	@Test
	void aRuleMayLetADepartmentReadOnlyItsOwnParts() throws Exception {
		Map<String, String> radiology = new HashMap<>();
		radiology.put("department", "radiology");
		ObjectNode out = traces();
		new TraceAccess(reading(radiology, Access.of(Arrays.asList(grant("tenant_001", "*")))), "ashburn").apply(out);
		assertEquals(Arrays.asList("C1:POST /call", "C1:INVITE sip:+1555@x", "C1:null", "C1:null"), messages(out),
				"radiology's text and the call's HTTP step; sales' part shown without its text; tenant_002 not at all");
		assertTrue(out.path("sources").get(1).has("parties"), "the recorded part is radiology's: parties kept");
		assertTrue(out.path(TraceAccess.CONTENT_HIDDEN).asBoolean(), "sales' text was taken out");
		assertFalse(new TraceAccess(reading(radiology, Access.all()), "ashburn").seesContent(),
				"not every call: no number search");
	}

	/// The page offers Save on a call only when no step of it is marked: a
	/// step's own flag, since a recorded step has no text either way.
	@Test
	void aWithheldStepIsMarkedAndAReadOneIsNot() throws Exception {
		Map<String, String> radiology = new HashMap<>();
		radiology.put("department", "radiology");
		ObjectNode out = traces();
		new TraceAccess(reading(radiology, Access.of(Arrays.asList(grant("tenant_001", "*")))), "ashburn").apply(out);
		List<String> marks = new ArrayList<>();
		for (JsonNode src : out.path("sources")) {
			for (JsonNode step : src.path("steps")) {
				marks.add(step.path("message").isNull() + "=" + step.path(TraceAccess.CONTENT_HIDDEN).asBoolean());
			}
		}
		assertEquals(Arrays.asList("false=false", "false=false", "true=true", "false=false"), marks,
				"only sales' step, whose text was taken out, carries the mark; the recorded radiology step has no text and no mark");
	}
}
