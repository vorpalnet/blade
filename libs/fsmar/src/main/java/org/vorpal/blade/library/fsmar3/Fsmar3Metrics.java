package org.vorpal.blade.library.fsmar3;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.fasterxml.jackson.databind.ObjectMapper;

/// In-memory implementation behind [Fsmar3MetricsMBean]. Counters are
/// LongAdders (hot path, many engine threads); the report formats on demand.
///
/// Also hosts the opt-in **routing trace capture**: arm with
/// [#captureNextCalls], and the next N calls record a full [RouteTrace]
/// (every hop, every transition evaluated) for the Flow editor's replay
/// feature. Disarmed cost is a single atomic read per routed request.
public class Fsmar3Metrics implements Fsmar3MetricsMBean {

	/// Hard ceiling on retained traces — capture is a diagnostic spotlight,
	/// not a flight recorder.
	static final int MAX_TRACES = 100;

	private final LongAdder requestsRouted = new LongAdder();
	private final LongAdder defaultFallbacks = new LongAdder();
	private final LongAdder undeployedBypasses = new LongAdder();
	private final LongAdder routingCycles = new LongAdder();
	private final ConcurrentHashMap<String, LongAdder> transitionHits = new ConcurrentHashMap<>();

	/// Calls left to capture. Zero = disarmed (the common case).
	private final AtomicInteger captureRemaining = new AtomicInteger();
	/// Captured traces keyed by Call-ID — a call's later hops (subsequent
	/// `getNextApplication` invocations) find and extend its existing trace.
	private final ConcurrentHashMap<String, RouteTrace> captured = new ConcurrentHashMap<>();
	/// Arrival stamp so [#getCapturedTraces] can restore call order.
	private final AtomicLong captureSeq = new AtomicLong();

	private static final ObjectMapper traceMapper = new ObjectMapper();

	void countRequest() {
		requestsRouted.increment();
	}

	void countDefaultFallback() {
		defaultFallbacks.increment();
	}

	void countBypass() {
		undeployedBypasses.increment();
	}

	void countCycle() {
		routingCycles.increment();
	}

	/// Count a fired transition, keyed `state/METHOD/transitionId` (a null
	/// transition id renders as `-`).
	void countTransition(String state, String method, String transitionId) {
		String key = state + "/" + method + "/" + (transitionId != null ? transitionId : "-");
		transitionHits.computeIfAbsent(key, k -> new LongAdder()).increment();
	}

	/// Returns the [RouteTrace] to record this `getNextApplication` invocation
	/// into, or null when capture doesn't apply (the overwhelmingly common
	/// case — one atomic read, no allocation).
	///
	/// A Call-ID already being captured keeps recording even after the armed
	/// count hits zero, so a multi-hop call's trace completes. A new Call-ID
	/// claims one armed slot, subject to [#MAX_TRACES].
	RouteTrace recorder(String callId, String method, String requestUri) {
		if (captureRemaining.get() == 0 && captured.isEmpty()) {
			return null;
		}
		if (callId == null) {
			return null;
		}
		RouteTrace trace = captured.get(callId);
		if (trace != null) {
			return trace;
		}
		// New call: claim one armed slot.
		while (true) {
			int remaining = captureRemaining.get();
			if (remaining <= 0 || captured.size() >= MAX_TRACES) {
				return null;
			}
			if (captureRemaining.compareAndSet(remaining, remaining - 1)) {
				break;
			}
		}
		trace = new RouteTrace(callId, method, requestUri, captureSeq.incrementAndGet());
		RouteTrace race = captured.putIfAbsent(callId, trace);
		return (race != null) ? race : trace;
	}

	@Override
	public long getRequestsRouted() {
		return requestsRouted.sum();
	}

	@Override
	public long getDefaultApplicationFallbacks() {
		return defaultFallbacks.sum();
	}

	@Override
	public long getUndeployedBypasses() {
		return undeployedBypasses.sum();
	}

	@Override
	public long getRoutingCyclesDetected() {
		return routingCycles.sum();
	}

	@Override
	public String[] getTransitionHits() {
		TreeMap<String, LongAdder> sorted = new TreeMap<>(transitionHits);
		String[] out = new String[sorted.size()];
		int i = 0;
		for (Map.Entry<String, LongAdder> e : sorted.entrySet()) {
			out[i++] = e.getKey() + " = " + e.getValue().sum();
		}
		return out;
	}

	@Override
	public void resetCounters() {
		requestsRouted.reset();
		defaultFallbacks.reset();
		undeployedBypasses.reset();
		routingCycles.reset();
		transitionHits.clear();
	}

	@Override
	public void captureNextCalls(int count) {
		captureRemaining.set(Math.max(0, Math.min(count, MAX_TRACES)));
	}

	@Override
	public int getCaptureRemaining() {
		return captureRemaining.get();
	}

	@Override
	public String[] getCapturedTraces() {
		List<RouteTrace> traces = new ArrayList<>(captured.values());
		traces.sort((a, b) -> Long.compare(a.seq, b.seq));
		List<String> out = new ArrayList<>(traces.size());
		for (RouteTrace t : traces) {
			try {
				out.add(t.toJson(traceMapper));
			} catch (Exception e) {
				// One unserializable trace shouldn't hide the rest.
			}
		}
		return out.toArray(new String[0]);
	}

	@Override
	public void clearCapturedTraces() {
		captured.clear();
	}
}
