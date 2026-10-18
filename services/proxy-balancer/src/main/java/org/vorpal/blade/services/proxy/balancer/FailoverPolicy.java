package org.vorpal.blade.services.proxy.balancer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.vorpal.blade.services.proxy.balancer.config.HealthParameters;

/// Which INVITE responses move a call on, and which take the endpoint out of
/// service, from the balancer's `health` settings.
///
/// **Moving on** (`failoverOn`) applies alike to the hunt inside a tier and to
/// escalation to the next tier. Only route and server failures are worth
/// another endpoint: a 404, a 486 or a 401 would be answered the same way by
/// every endpoint, so asking each one multiplies the traffic of a call that
/// was never going to complete, at the moment traffic is the problem.
///
/// **Out of service** (`markDownOn`) for a 503 means the endpoint refused;
/// its Retry-After is honored. For a 408 it means only the 408 the container
/// makes itself when nothing at all came back, which has no sender address.
/// A 408 that arrived from the endpoint proves it alive: its own next hop
/// timed out, and the call moves on while the endpoint stays in service.
final class FailoverPolicy {

	static final List<Integer> DEFAULT_FAILOVER = Collections.unmodifiableList(Arrays.asList(408, 502, 503, 504));
	static final List<Integer> DEFAULT_MARK_DOWN = Collections.unmodifiableList(Arrays.asList(408, 503));

	private FailoverPolicy() {
	}

	/// Should this final response try the next endpoint, or the next tier?
	static boolean failover(int status, HealthParameters health) {
		List<Integer> on = health != null ? health.getFailoverOn() : null;
		return status >= 300 && (on != null ? on : DEFAULT_FAILOVER).contains(status);
	}

	/// Should this final response take the endpoint out of service?
	/// `fromWire` is false for a response the container made itself.
	static boolean markDown(int status, boolean fromWire, HealthParameters health) {
		List<Integer> on = health != null ? health.getMarkDownOn() : null;
		if (!(on != null ? on : DEFAULT_MARK_DOWN).contains(status)) {
			return false;
		}
		return status != 408 || !fromWire;
	}
}
