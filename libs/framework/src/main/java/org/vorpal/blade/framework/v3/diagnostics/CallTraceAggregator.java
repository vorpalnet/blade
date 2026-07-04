package org.vorpal.blade.framework.v3.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v3.CallStep;

/// Merges the per-app [CallStep]s captured across a routed chain into per-call
/// [CallTrace]s — the "which app misbehaved" assembly.
///
/// Each app in the chain records its own steps (on its own
/// `SipApplicationSession`), all tagged with the same stable `X-Vorpal-Session`
/// id. This groups by that id and orders each call's steps into one timeline:
/// primarily by timestamp (across apps), and by per-app `order` as a stable
/// tie-breaker (two sends in the same millisecond keep their emit order).
///
/// Pure and dependency-free on purpose — it's the correctness core, unit-tested
/// without a SIP container ([CallTraceSmokeTest]).
public final class CallTraceAggregator {

	private CallTraceAggregator() {
	}

	private static final Comparator<CallStep> TIMELINE =
			Comparator.comparingLong(CallStep::getEpochMillis).thenComparingInt(CallStep::getOrder);

	/// Group steps by `X-Vorpal-Session` id and order each group into a
	/// [CallTrace]. Insertion order of the returned map follows first appearance
	/// of each call in the input.
	public static Map<String, CallTrace> byCall(Iterable<CallStep> steps) {
		Map<String, List<CallStep>> grouped = new LinkedHashMap<>();
		if (steps != null) {
			for (CallStep s : steps) {
				String id = s.getSessionId() == null ? "" : s.getSessionId();
				grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
			}
		}
		Map<String, CallTrace> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<CallStep>> e : grouped.entrySet()) {
			List<CallStep> ordered = new ArrayList<>(e.getValue());
			ordered.sort(TIMELINE);
			out.put(e.getKey(), new CallTrace(e.getKey(), ordered));
		}
		return out;
	}

	/// Assemble a single call's [CallTrace] from steps already known to belong to
	/// it (e.g. the merged per-app traces for one `X-Vorpal-Session`).
	public static CallTrace merge(String sessionId, Iterable<CallStep> steps) {
		List<CallStep> ordered = new ArrayList<>();
		if (steps != null) {
			for (CallStep s : steps) {
				ordered.add(s);
			}
		}
		ordered.sort(TIMELINE);
		return new CallTrace(sessionId, ordered);
	}
}
