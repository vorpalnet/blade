package org.vorpal.blade.library.fsmar3;


/// JMX surface for FSMAR 3 routing metrics — registered explicitly via
/// `StandardMBean` (never auto-introspection; see the SettingsManager MBean
/// precedent) under `org.vorpal.blade:type=Fsmar3,name=metrics`.
///
/// The counters are in-memory and per-JVM (each engine node routes its own
/// traffic); they reset on restart or via [#resetCounters].
public interface Fsmar3MetricsMBean {

	/// Total getNextApplication invocations.
	long getRequestsRouted();

	/// Times the defaultApplication fallback fired.
	long getDefaultApplicationFallbacks();

	/// Times an undeployed application was bypassed.
	long getUndeployedBypasses();

	/// Times a routing cycle was detected (config loops through undeployed apps).
	long getRoutingCyclesDetected();

	/// One line per transition that has ever fired, formatted
	/// `state/METHOD/transitionId = count`, sorted by key. Transitions that
	/// never fired don't appear.
	String[] getTransitionHits();

	/// Zero every counter.
	void resetCounters();

	/// Arms routing-trace capture: the next `count` calls (by Call-ID) record
	/// a full [RouteTrace] — every hop, every transition evaluated, the values
	/// extracted, and the final decision. Clamped to 0–100; 0 disarms. The
	/// Flow editor replays captured traces on the state-machine diagram.
	void captureNextCalls(int count);

	/// Calls left to capture; 0 means disarmed.
	int getCaptureRemaining();

	/// The captured traces, one JSON document per call in arrival order —
	/// the shared trace format documented on [RouteTrace].
	String[] getCapturedTraces();

	/// Discards all captured traces.
	void clearCapturedTraces();
}
