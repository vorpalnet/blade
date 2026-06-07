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
}
