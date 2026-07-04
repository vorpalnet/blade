package org.vorpal.blade.framework.v3.diagnostics;

/// JMX interface for chain tracing — one per app, registered by
/// [TraceRegistrar] as `vorpal.blade:Name=<app>,Type=Trace[,Cluster=...]`.
///
/// The read side is the flight-recorder model: [TraceLog] buffers the
/// [org.vorpal.blade.framework.v3.CallStep]s of armed calls on each node, and
/// the Callflow Viewer pulls the buffers from EVERY node (traffic lands
/// anywhere) and merges them into per-call timelines by `X-Vorpal-Session`.
/// The write side is the arming control: the viewer invokes [#arm]/[#disarm]
/// on every app's MBean, which is what makes arming "framework-level" from
/// the operator's chair — one action arms the whole chain.
///
/// Registered via explicit `StandardMBean(..., TraceMXBean.class, true)` —
/// see memory `[[settingsmxbean-introspection-bug]]`.
public interface TraceMXBean {

	/// The buffered steps of armed calls on this node, oldest first:
	/// `{"app":"...","steps":[{"sessionId","epochMillis","order","kind","label","className","methodName","line"},...]}`.
	String getStepsJson();

	/// The live arming policy:
	/// `{"enabled":bool,"rules":[{"label","attribute","pattern","maxCaptures","captured","exhausted"},...]}`.
	String getRulesJson();

	/// Add an arming rule and enable tracing. `pattern` is a full-match regex
	/// against `attribute` (`From`, `To`, `Request-URI`, `Origin-IP`,
	/// `Content`, or any header name). `maxCaptures <= 0` = unlimited.
	void arm(String label, String attribute, String pattern, int maxCaptures);

	/// Drop all rules and disable tracing (already-armed calls finish
	/// recording).
	void disarm();

	/// Empty this node's step buffer.
	void clearSteps();
}
