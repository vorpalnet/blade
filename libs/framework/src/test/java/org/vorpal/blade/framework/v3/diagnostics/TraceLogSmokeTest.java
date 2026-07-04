package org.vorpal.blade.framework.v3.diagnostics;

import java.util.List;

import org.vorpal.blade.framework.v3.CallStep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Container-free smoke test for the node-side trace buffer + its JMX face:
/// append/snapshot ordering, ring eviction at capacity, arm/disarm policy
/// flips, and the hand-built JSON parsing back cleanly (incl. escaping).
/// Run: `java -cp <classes:test-classes:deps> org.vorpal.blade.framework.v3.diagnostics.TraceLogSmokeTest`
public class TraceLogSmokeTest {

	private static int passed = 0;
	private static int failed = 0;

	public static void main(String[] args) throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		// ---- append + snapshot ordering ----
		TraceLog.clear();
		TraceLog.append(step("callA", 100, 1, "request", "INVITE"));
		TraceLog.append(step("callA", 105, 2, "response", "200"));
		List<CallStep> snap = TraceLog.snapshot();
		check("append keeps arrival order", snap.size() == 2
				&& snap.get(0).getLabel().equals("INVITE") && snap.get(1).getLabel().equals("200"));
		check("null append ignored", appendNullOk());

		// ---- ring eviction ----
		TraceLog.clear();
		for (int i = 0; i < TraceLog.CAPACITY + 100; i++) {
			TraceLog.append(step("call" + i, i, 1, "request", "INVITE"));
		}
		snap = TraceLog.snapshot();
		check("capacity bounds the buffer", snap.size() == TraceLog.CAPACITY);
		check("oldest evicted first", snap.get(0).getEpochMillis() == 100
				&& snap.get(snap.size() - 1).getEpochMillis() == TraceLog.CAPACITY + 99);

		// ---- arm / disarm policy ----
		TraceLog.disarm();
		check("disarmed by default here", !TraceLog.diagnostics().isEnabled());
		TraceLog.arm("rule one", "From", ".*alice.*", 5);
		check("arm enables + adds rule", TraceLog.diagnostics().isEnabled()
				&& TraceLog.diagnostics().getRules().size() == 1);
		TraceLog.arm("rule two", "To", ".*bob.*", 0);
		check("second rule appends", TraceLog.diagnostics().getRules().size() == 2);
		TraceLog.disarm();
		check("disarm clears + disables", !TraceLog.diagnostics().isEnabled()
				&& TraceLog.diagnostics().getRules().isEmpty());

		// ---- Trace MBean JSON ----
		Trace mbean = new Trace("proxy");
		TraceLog.clear();
		TraceLog.append(step("call\"quoted\"\nnewline", 42, 7, "response", "487"));
		JsonNode steps = mapper.readTree(mbean.getStepsJson());
		check("steps json parses", steps.path("app").asText().equals("proxy")
				&& steps.path("steps").size() == 1);
		JsonNode s0 = steps.path("steps").get(0);
		check("step fields survive round-trip", s0.path("sessionId").asText().equals("call\"quoted\"\nnewline")
				&& s0.path("epochMillis").asLong() == 42
				&& s0.path("order").asInt() == 7
				&& s0.path("kind").asText().equals("response")
				&& s0.path("label").asText().equals("487")
				&& s0.path("className").asText().equals("org.example.ProxyInvite")
				&& s0.path("methodName").asText().equals("process")
				&& s0.path("line").asInt() == 44);
		check("direction survives round-trip", s0.path("direction").asText().equals(CallStep.OUT));
		check("raw SIP message survives round-trip (CRLF + escaped quotes)",
				s0.path("message").asText().equals(
						"INVITE sip:x SIP/2.0\r\nFrom: \"quo\\ted\" <sip:a@b>\r\n\r\nv=0\r\n"));

		mbean.arm("watch alice", "From", ".*alice.*", 3);
		JsonNode rules = mapper.readTree(mbean.getRulesJson());
		check("rules json parses", rules.path("enabled").asBoolean()
				&& rules.path("rules").size() == 1);
		JsonNode r0 = rules.path("rules").get(0);
		check("rule fields survive round-trip", r0.path("label").asText().equals("watch alice")
				&& r0.path("attribute").asText().equals("From")
				&& r0.path("pattern").asText().equals(".*alice.*")
				&& r0.path("maxCaptures").asInt() == 3
				&& r0.path("captured").asInt() == 0
				&& !r0.path("exhausted").asBoolean());

		mbean.clearSteps();
		mbean.disarm();
		check("clear + disarm via mbean", TraceLog.snapshot().isEmpty()
				&& !TraceLog.diagnostics().isEnabled());

		System.out.println("TraceLogSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	private static boolean appendNullOk() {
		try {
			TraceLog.append(null);
			return TraceLog.snapshot().size() == 2;
		} catch (Exception e) {
			return false;
		}
	}

	private static CallStep step(String sessionId, long millis, int order, String kind, String label) {
		return new CallStep(sessionId, millis, order, CallStep.OUT, kind, label, "org.example.ProxyInvite", "process", 44,
				"INVITE sip:x SIP/2.0\r\nFrom: \"quo\\ted\" <sip:a@b>\r\n\r\nv=0\r\n");
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}
}
