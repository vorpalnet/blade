package org.vorpal.blade.services.options;

import java.lang.reflect.Method;

/// Reads OCCAS's own overload state, without a compile-time dependency on the
/// engine internals.
///
/// OCCAS's overload-protection feature flips a reject flag on its singleton
/// `com.bea.wcp.sip.engine.server.olp.OverloadProtection` when a configured
/// threshold trips (`isBusy() == true`); the SIP engine already rejects new
/// transactions while that flag is set. The Options service mirrors that flag
/// into the OPTIONS health check so a SIP-aware load balancer drains this node
/// gracefully (stops sending NEW calls) instead of letting the engine reject
/// calls one at a time after they arrive.
///
/// Resolved reflectively on purpose: if the class is absent (running outside
/// OCCAS, or a future engine renames it) the lookups fail closed and
/// `isOverloaded()` simply returns false, so OPTIONS keeps answering 200 exactly
/// as before. This is a health-check path — it must never throw.
final class EngineOverload {

	private static final Method GET_INSTANCE;
	private static final Method IS_BUSY;

	static {
		Method getInstance = null;
		Method isBusy = null;
		try {
			Class<?> olp = Class.forName("com.bea.wcp.sip.engine.server.olp.OverloadProtection");
			getInstance = olp.getMethod("getInstance");
			isBusy = olp.getMethod("isBusy");
		} catch (Throwable t) {
			// Overload-protection class not present / changed — treat as never
			// overloaded; OPTIONS behaves exactly as it did before this feature.
		}
		GET_INSTANCE = getInstance;
		IS_BUSY = isBusy;
	}

	private EngineOverload() {
	}

	/// True only when OCCAS overload protection is actively rejecting traffic.
	/// Any failure (class missing, null singleton, reflection error) returns
	/// false so the health check never fails on account of this check.
	static boolean isOverloaded() {
		if (GET_INSTANCE == null || IS_BUSY == null) {
			return false;
		}
		try {
			Object instance = GET_INSTANCE.invoke(null);
			return instance != null && Boolean.TRUE.equals(IS_BUSY.invoke(instance));
		} catch (Throwable t) {
			return false;
		}
	}
}
