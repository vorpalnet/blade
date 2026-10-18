package org.vorpal.blade.services.options;

import java.lang.management.ManagementFactory;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/// Reads the container's `SipPerformanceRuntime` MBean for this server: the
/// counters behind [EngineLatency] and the lock-failure warning in [Strain].
/// The name is looked up once and kept; a failed read forgets it, so a
/// server that registers the MBean late is found on a later pass.
final class SipPerformance {

	private static volatile ObjectName name;

	private SipPerformance() {
	}

	/// The attribute's value, or null when the MBean or attribute cannot be read.
	static Object read(String attribute) {
		try {
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			ObjectName on = name;
			if (on == null) {
				Set<ObjectName> names = server.queryNames(new ObjectName("com.bea:Type=SipPerformanceRuntime,*"), null);
				if (names.isEmpty()) {
					return null;
				}
				on = names.iterator().next();
				name = on;
			}
			return server.getAttribute(on, attribute);
		} catch (Throwable t) {
			name = null;
			return null;
		}
	}
}
