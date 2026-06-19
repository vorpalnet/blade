package org.vorpal.blade.framework.v3.probe;

/// JMX interface every BLADE JVM exposes so the Tuning app on the AdminServer can
/// read this node's kernel tunables (sysctl values, THP, CPU governor, process
/// limits) over the standard DomainRuntime channel — no agents, no SSH, no sudo,
/// no shelling out. Tier 1 of the kernel-params probe: read-only `/proc/sys`,
/// `/sys`, and `/proc/self/limits` via plain file I/O.
///
/// The single operation returns JSON (like the LogReader / Tester MBeans) so no
/// custom types have to cross the JMX boundary.
public interface KernelProbeMXBean {

	/// Returns `{"server":..,"os":..,"readings":{key:value|"n/a"},"limits":{..}}`.
	String readJson();
}
