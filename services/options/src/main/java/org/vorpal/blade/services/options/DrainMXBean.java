package org.vorpal.blade.services.options;

/// Administrative drain control for one engine node, over JMX.
///
/// Registered through an explicit [javax.management.StandardMBean] wrapper with
/// `isMXBean=true`, for the same reason every BLADE MBean is: WLS 14.1.1's JMX
/// auto-introspection silently drops no-arg getters from the computed MBeanInfo
/// otherwise (see the SettingsManager precedent).
///
/// Setting `Drained=true` makes THIS node's options app answer its OPTIONS
/// health pings `503 "Draining"`, so a SIP-aware load balancer takes the node
/// out of rotation for NEW calls. Runtime state, not configuration: the flag is
/// per-JVM, volatile, and resets to false on restart — a rebooted engine
/// rejoins the pool as soon as the app is up, and a forgotten drain can never
/// outlive the JVM it was set on.
public interface DrainMXBean {

	/// Whether this node is administratively drained (OPTIONS answers 503).
	boolean isDrained();

	/// Drain (`true`) or resume (`false`) this node.
	void setDrained(boolean drained);

	/// Epoch millis when the current drain began; 0 when not drained.
	long getDrainedSinceMillis();

}
