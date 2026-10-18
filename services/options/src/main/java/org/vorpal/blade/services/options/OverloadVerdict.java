package org.vorpal.blade.services.options;

/// Whether to refuse new calls, from the engine's latency. It starts refusing
/// when the latency reaches `refuseMillis` and stops once it falls below half
/// of that. The gap keeps a latency that hovers at the line from switching
/// refusing on and off with every reading, which OCCAS's own thresholds cannot
/// do: each has a single value.
///
/// Not thread-safe; [OverloadMonitor] feeds it from one task at a time.
final class OverloadVerdict {

	private boolean refusing;

	/// Takes one latency reading in milliseconds and returns the verdict after
	/// it. No reading (-1: an idle engine, or a histogram that cannot be read)
	/// takes calls: refusing must never outlast the evidence for it.
	/// `refuseMillis` of 0 or less turns refusing off.
	boolean sample(int latencyMillis, int refuseMillis) {
		if (refuseMillis <= 0 || latencyMillis < 0) {
			refusing = false;
		} else {
			refusing = refusing ? latencyMillis * 2 >= refuseMillis : latencyMillis >= refuseMillis;
		}
		return refusing;
	}

	boolean refusing() {
		return refusing;
	}
}
