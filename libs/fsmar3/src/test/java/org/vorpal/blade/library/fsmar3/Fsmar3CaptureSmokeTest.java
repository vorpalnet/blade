package org.vorpal.blade.library.fsmar3;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;
import javax.servlet.sip.ar.SipRouteModifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke test for the opt-in routing-trace capture ([Fsmar3Metrics] +
/// [RouteTrace]) — driven through the same recorder API AppRouter uses:
/// arm via the MBean op, claim a trace per Call-ID, record hops/evaluations/
/// outcomes, serialize, and verify the shared trace format the Flow editor
/// replays.
///
/// Run via `main`, like the other v3 smoke tests.
public final class Fsmar3CaptureSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		testDisarmedReturnsNull();
		testArmClaimAndExhaust();
		testSameCallExtendsAtZeroRemaining();
		testTraceJsonShape();
		testBypassCycleFallbackFlags();
		testExtractedDiffPerHop();
		testClampAndClear();
		testMaxTracesBound();
		summary();
	}

	private static void testDisarmedReturnsNull() {
		Fsmar3Metrics m = new Fsmar3Metrics();
		check("disarmed: recorder null", m.recorder("call-1", "INVITE", "sip:bob@example.com") == null);
		check("disarmed: remaining 0", m.getCaptureRemaining() == 0);
		check("disarmed: no traces", m.getCapturedTraces().length == 0);
	}

	private static void testArmClaimAndExhaust() {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(2);
		check("armed: remaining 2", m.getCaptureRemaining() == 2);

		RouteTrace a = m.recorder("call-a", "INVITE", "sip:a@x");
		RouteTrace b = m.recorder("call-b", "INVITE", "sip:b@x");
		check("armed: two calls claimed", a != null && b != null && a != b);
		check("armed: remaining exhausted", m.getCaptureRemaining() == 0);
		check("armed: third call not captured", m.recorder("call-c", "INVITE", "sip:c@x") == null);
		check("armed: null Call-ID not captured", m.recorder(null, "INVITE", "sip:n@x") == null);
		check("armed: two traces retained", m.getCapturedTraces().length == 2);
	}

	private static void testSameCallExtendsAtZeroRemaining() {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(1);
		RouteTrace hop1 = m.recorder("call-a", "INVITE", "sip:a@x");
		check("extend: first hop claims", hop1 != null);
		check("extend: remaining 0", m.getCaptureRemaining() == 0);
		// The same call's next getNextApplication invocation keeps recording.
		RouteTrace hop2 = m.recorder("call-a", "INVITE", "sip:a@x");
		check("extend: second hop reuses trace", hop2 == hop1);
		check("extend: still one trace", m.getCapturedTraces().length == 1);
	}

	/// Records the way AppRouter does for a two-state path and verifies the
	/// serialized shared trace format field by field.
	private static void testTraceJsonShape() throws Exception {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(1);
		RouteTrace t = m.recorder("call-shape", "INVITE", "sip:bob@example.com");

		// Invocation 1: state "null", two transitions evaluated, second fires.
		Map<String, String> ctx = new HashMap<>();
		t.beginHop("null", ctx);
		ctx.put("From.user", "alice");
		ctx.put("tier", "gold");
		t.evaluated("INV-bob", "${To.user} == 'bob'", false);
		t.evaluated("INV-gold", "${tier} == 'gold'", true);
		t.matched("INV-gold", "b2bua");
		t.routed(new SipApplicationRouterInfo("b2bua", SipApplicationRoutingRegion.NEUTRAL_REGION,
				"sip:alice@example.com", new String[] { "sip:alice@gold-trunk" }, SipRouteModifier.ROUTE, null));
		t.endInvocation(ctx);

		// Invocation 2: state "b2bua", unconditional deliver.
		t.beginHop("b2bua", ctx);
		t.evaluated("B2B-deliver", null, true);
		t.matched("B2B-deliver", "proxy-registrar");
		t.routed(new SipApplicationRouterInfo("proxy-registrar", SipApplicationRoutingRegion.NEUTRAL_REGION,
				null, null, SipRouteModifier.NO_ROUTE, null));
		t.endInvocation(ctx);

		String[] traces = m.getCapturedTraces();
		check("shape: one trace", traces.length == 1);

		JsonNode root = new ObjectMapper().readTree(traces[0]);
		check("shape: callId", "call-shape".equals(root.path("callId").asText()));
		check("shape: method", "INVITE".equals(root.path("method").asText()));
		check("shape: requestURI", "sip:bob@example.com".equals(root.path("requestURI").asText()));
		check("shape: two hops", root.path("hops").size() == 2);

		JsonNode hop1 = root.path("hops").get(0);
		check("shape: hop1 state", "null".equals(hop1.path("state").asText()));
		check("shape: hop1 extracted diff", "gold".equals(hop1.path("extracted").path("tier").asText())
				&& "alice".equals(hop1.path("extracted").path("From.user").asText()));
		check("shape: hop1 two evaluations", hop1.path("evaluated").size() == 2);
		check("shape: hop1 eval order + outcome",
				!hop1.path("evaluated").get(0).path("fired").asBoolean()
						&& hop1.path("evaluated").get(1).path("fired").asBoolean()
						&& "INV-gold".equals(hop1.path("evaluated").get(1).path("id").asText()));
		check("shape: hop1 matched/next", "INV-gold".equals(hop1.path("matched").asText())
				&& "b2bua".equals(hop1.path("next").asText()));
		check("shape: hop1 resolved route", "sip:alice@gold-trunk".equals(hop1.path("routes").get(0).asText()));
		check("shape: hop1 subscriberURI", "sip:alice@example.com".equals(hop1.path("subscriberURI").asText()));
		check("shape: hop1 routeModifier", "ROUTE".equals(hop1.path("routeModifier").asText()));
		check("shape: hop1 not bypassed", !hop1.path("bypassed").asBoolean());

		JsonNode hop2 = root.path("hops").get(1);
		check("shape: hop2 state", "b2bua".equals(hop2.path("state").asText()));
		check("shape: hop2 unconditional when omitted", !hop2.path("evaluated").get(0).has("when"));

		check("shape: finalApp is last decision", "proxy-registrar".equals(root.path("finalApp").asText()));
		check("shape: no fallback/cycle", !root.path("defaultFallback").asBoolean()
				&& !root.path("cycleDetected").asBoolean());
		check("shape: final context snapshot", "gold".equals(root.path("context").path("tier").asText()));
	}

	private static void testBypassCycleFallbackFlags() throws Exception {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(1);
		RouteTrace t = m.recorder("call-flags", "INVITE", "sip:x@y");

		Map<String, String> ctx = new HashMap<>();
		t.beginHop("null", ctx);
		t.evaluated("INV-1", "${a} == 'b'", true);
		t.matched("INV-1", "screening");
		t.bypassed(); // screening undeployed
		t.beginHop("screening", ctx); // bypass iteration = new hop
		t.evaluated("SCR-1", null, true);
		t.matched("SCR-1", "null");
		t.cycle();
		t.defaultFallback("b2bua");
		t.endInvocation(ctx);

		JsonNode root = new ObjectMapper().readTree(m.getCapturedTraces()[0]);
		check("flags: hop1 bypassed", root.path("hops").get(0).path("bypassed").asBoolean());
		check("flags: bypass produced second hop", root.path("hops").size() == 2);
		check("flags: cycleDetected", root.path("cycleDetected").asBoolean());
		check("flags: defaultFallback", root.path("defaultFallback").asBoolean());
		check("flags: finalApp from fallback", "b2bua".equals(root.path("finalApp").asText()));
	}

	private static void testExtractedDiffPerHop() throws Exception {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(1);
		RouteTrace t = m.recorder("call-diff", "INVITE", "sip:x@y");

		Map<String, String> ctx = new HashMap<>();
		ctx.put("From.user", "alice"); // pre-existing (carried from earlier)
		t.beginHop("null", ctx);
		ctx.put("To.user", "bob"); // added this hop
		ctx.put("From.user", "alice"); // unchanged — must NOT appear in diff
		t.endInvocation(ctx);

		t.beginHop("screening", ctx);
		ctx.put("From.user", "anonymous"); // changed this hop — must appear
		t.endInvocation(ctx);

		JsonNode root = new ObjectMapper().readTree(m.getCapturedTraces()[0]);
		JsonNode d1 = root.path("hops").get(0).path("extracted");
		check("diff: hop1 has added value", "bob".equals(d1.path("To.user").asText()));
		check("diff: hop1 omits unchanged value", !d1.has("From.user"));
		JsonNode d2 = root.path("hops").get(1).path("extracted");
		check("diff: hop2 has changed value", "anonymous".equals(d2.path("From.user").asText()));
		check("diff: hop2 omits untouched value", !d2.has("To.user"));
	}

	private static void testClampAndClear() {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(1000);
		check("clamp: capped at MAX_TRACES", m.getCaptureRemaining() == Fsmar3Metrics.MAX_TRACES);
		m.captureNextCalls(-5);
		check("clamp: negative disarms", m.getCaptureRemaining() == 0);

		m.captureNextCalls(1);
		m.recorder("call-x", "INVITE", "sip:x@y");
		check("clear: trace present", m.getCapturedTraces().length == 1);
		m.clearCapturedTraces();
		check("clear: traces gone", m.getCapturedTraces().length == 0);
		check("clear: counters untouched by capture ops", m.getRequestsRouted() == 0);
	}

	private static void testMaxTracesBound() {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.captureNextCalls(Fsmar3Metrics.MAX_TRACES);
		for (int i = 0; i < Fsmar3Metrics.MAX_TRACES; i++) {
			check2(m.recorder("call-" + i, "INVITE", "sip:x@y") != null);
		}
		check("bound: filled to MAX_TRACES", m.getCapturedTraces().length == Fsmar3Metrics.MAX_TRACES);
		// Re-arm while full: new calls must not exceed the ceiling.
		m.captureNextCalls(10);
		check("bound: full buffer rejects new call", m.recorder("call-extra", "INVITE", "sip:x@y") == null);
		check("bound: still MAX_TRACES retained", m.getCapturedTraces().length == Fsmar3Metrics.MAX_TRACES);
		// Existing calls still extend.
		check("bound: existing call still extends", m.recorder("call-0", "INVITE", "sip:x@y") != null);
		// Order: arrival order preserved in the report.
		String first = m.getCapturedTraces()[0];
		check("bound: arrival order preserved", first.contains("\"callId\":\"call-0\""));
	}

	/// Aggregated check for loop bodies — one FAIL line instead of a hundred PASSes.
	private static boolean loopOk = true;

	private static void check2(boolean ok) {
		if (!ok) loopOk = false;
	}

	private static void check(String name, boolean ok) {
		if (ok) { passed++; System.out.println("  PASS  " + name); }
		else { failed++; System.out.println("  FAIL  " + name); }
	}

	private static void summary() {
		check("loop assertions all passed", loopOk);
		System.out.println("Fsmar3CaptureSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
