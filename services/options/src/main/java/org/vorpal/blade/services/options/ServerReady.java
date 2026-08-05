package org.vorpal.blade.services.options;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/// The boot gate: has THIS server finished its deploy phase?
///
/// OCCAS accepts SIP traffic while applications are still deploying, and the
/// App Router would route those early calls through a partial chain (apps not
/// yet deployed bypass as virtual states) — not an error, a silently wrong
/// call path. The end of the deploy phase has a name in WebLogic: the server's
/// transition to `RUNNING`, which happens only after every deployment has been
/// processed. Until then the options app answers its health pings
/// `503 "Starting"`, so a SIP-aware load balancer keeps the node out of
/// rotation ([OptionsCallflow]; the options app deploys FIRST — see the
/// deployment-order note in the README — so it owns that answer for the whole
/// window).
///
/// **Latch, not a state mirror.** Once `RUNNING` has been observed the answer
/// is true forever (a volatile, no further reads): this is a boot gate, and a
/// later graceful suspend must not re-close it — drain is the tool for taking
/// a live node out of rotation, and WebLogic's own suspend already stops new
/// work.
///
/// Read locally from the platform MBean server
/// (`com.bea:Name=<server>,Type=ServerRuntime` → `State`), reflectively-loose
/// like `EngineOverload`: any failure reads as "not ready", which in a
/// container means "very early boot" (the runtime MBean isn't registered
/// yet). Outside WebLogic there is no such MBean at all, so the gate is
/// governed by the `unavailableUntilRunning` setting — off, and this class is
/// never consulted.
final class ServerReady {

	private static volatile boolean ready;

	private ServerReady() {
	}

	/// True once this server has been observed in the RUNNING state.
	static boolean isReady() {
		if (ready) {
			return true;
		}
		if ("RUNNING".equals(readState())) {
			ready = true;
			return true;
		}
		return false;
	}

	/// The server's lifecycle state, or null when unreadable (early boot, or
	/// not running inside WebLogic). Must never throw — health-check path.
	private static String readState() {
		try {
			String serverName = System.getProperty("weblogic.Name");
			if (serverName == null) {
				return null;
			}
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			ObjectName on = new ObjectName("com.bea:Name=" + serverName + ",Type=ServerRuntime");
			Object state = server.getAttribute(on, "State");
			return state != null ? state.toString() : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/// Clears the latch so tests can exercise the boot transition again.
	static void resetForTesting() {
		ready = false;
	}

}
