package org.vorpal.blade.framework.v3.diagnostics;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v3.CallStep;

/// Smoke-test driver for the chain-aware call tracing core — the "which app in
/// the string misbehaved" assembly and the capture-cap safety. Run via `main`,
/// like the other v3 smoke tests. Pure; no SIP container.
///
/// Proves: (1) steps captured by DIFFERENT apps that share an `X-Vorpal-Session`
/// id are merged into one call and ordered into a single cross-app timeline;
/// (2) distinct calls stay separate; (3) `appsInOrder` reports the chain as it
/// actually executed (so a misbehaving app is located by name); (4) per-hop and
/// total timing fall out of the timestamps; (5) a [TraceRule] captures at most
/// its cap and then self-disarms.
public final class CallTraceSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testChainAggregation();
		testSeparateCalls();
		testTimingAndOutOfOrderInput();
		testCaptureCap();
		summary();
	}

	private static CallStep step(String sessionId, long ts, int order, String kind, String label, String appClass) {
		return new CallStep(sessionId, ts, order, CallStep.OUT, kind, label, "org.vorpal.blade.services." + appClass, "process", 42, null);
	}

	/// Three apps in one chain (analytics → proxy → hold), each recording on its
	/// own session but sharing X-Vorpal-Session "callA". Fed in mixed order.
	private static void testChainAggregation() {
		List<CallStep> steps = Arrays.asList(
				step("callA", 1000, 1, "request", "INVITE", "Hold"),        // hold acted at t=1000
				step("callA", 100, 1, "request", "INVITE", "Analytics"),    // analytics first, t=100
				step("callA", 500, 1, "request", "INVITE", "Proxy"),        // proxy middle, t=500
				step("callA", 1200, 2, "request", "BYE", "Hold"));          // hold tore it down

		Map<String, CallTrace> byCall = CallTraceAggregator.byCall(steps);
		check("one logical call", byCall.size() == 1);
		CallTrace t = byCall.get("callA");
		check("all steps present", t != null && t.size() == 4);
		check("chain order = analytics, proxy, hold",
				t.appsInOrder().equals(Arrays.asList("Analytics", "Proxy", "Hold")));
		// The culprit is locatable by name: the BYE came from Hold.
		CallStep last = t.getSteps().get(t.getSteps().size() - 1);
		check("last message is the BYE from Hold",
				"BYE".equals(last.getLabel()) && "Hold".equals(last.getSimpleClassName()));
	}

	private static void testSeparateCalls() {
		List<CallStep> steps = Arrays.asList(
				step("callA", 100, 1, "request", "INVITE", "Analytics"),
				step("callB", 110, 1, "request", "INVITE", "Analytics"),
				step("callA", 200, 2, "response", "200", "Analytics"));
		Map<String, CallTrace> byCall = CallTraceAggregator.byCall(steps);
		check("two distinct calls", byCall.size() == 2);
		check("callA has 2 steps", byCall.get("callA").size() == 2);
		check("callB has 1 step", byCall.get("callB").size() == 1);
	}

	private static void testTimingAndOutOfOrderInput() {
		CallTrace t = CallTraceAggregator.merge("callA", Arrays.asList(
				step("callA", 1200, 2, "request", "BYE", "Hold"),
				step("callA", 100, 1, "request", "INVITE", "Analytics")));
		check("merge orders by timestamp", "INVITE".equals(t.getSteps().get(0).getLabel()));
		check("duration = last - first", t.durationMillis() == 1100);
	}

	private static void testCaptureCap() {
		TraceRule rule = new TraceRule("debug", null, 3);
		check("consume 1", rule.tryConsume());
		check("consume 2", rule.tryConsume());
		check("consume 3", rule.tryConsume());
		check("4th is refused (cap hit)", !rule.tryConsume());
		check("rule now exhausted", rule.isExhausted());

		TraceRule unlimited = new TraceRule("forever", null, 0);
		check("unlimited never refuses", unlimited.tryConsume() && unlimited.tryConsume());
		check("unlimited never exhausted", !unlimited.isExhausted());
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

	private static void summary() {
		System.out.println("CallTraceSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
