package org.vorpal.blade.library.fsmar3;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/// In-memory implementation behind [Fsmar3MetricsMBean]. Counters are
/// LongAdders (hot path, many engine threads); the report formats on demand.
public class Fsmar3Metrics implements Fsmar3MetricsMBean {

	private final LongAdder requestsRouted = new LongAdder();
	private final LongAdder defaultFallbacks = new LongAdder();
	private final LongAdder undeployedBypasses = new LongAdder();
	private final LongAdder routingCycles = new LongAdder();
	private final ConcurrentHashMap<String, LongAdder> transitionHits = new ConcurrentHashMap<>();

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
}
