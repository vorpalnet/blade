package org.vorpal.blade.framework.v3.configuration.connectors;

import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.snmp.Snmp;

/// Node-local circuit breaker shared by the IO-bound connectors
/// ([RestConnector], [LdapConnector], [JdbcConnector]). When the remote
/// endpoint fails, the breaker opens: the connector skips its call for a
/// cooldown window and the iRouter falls to its default route (the breaker is
/// policy-neutral — allow vs. block is the routing config's call). After the
/// window a live call retries; success closes the breaker.
///
/// The whole state machine is a single `AtomicLong`:
///  - `0` — healthy/closed.
///  - a positive value — the epoch-millis until which calls are suppressed (open).
///
/// `getAndSet` gives lock-free edge detection: under a flood of concurrent
/// failures during an outage, exactly one caller observes the healthy→open
/// transition (logs a WARNING and, if enabled, emits ONE SNMP trap), and
/// exactly one observes open→healthy on recovery. So an outage produces one
/// "down" notification and one "up" — never a trap per failed call.
///
/// Transient and node-local: each cluster node tracks the endpoint's health as
/// IT sees it. No cross-node coordination, no singleton, no background timer
/// thread — just a timestamp compared on the call path.
final class CircuitBreaker {

	private final AtomicLong state = new AtomicLong(0L);

	/// True when calls should be suppressed right now (open and still inside the
	/// cooldown window). Once the window elapses this returns false so the next
	/// call goes through as a live retry — even though the breaker stays
	/// "degraded" until that retry actually succeeds.
	boolean isOpen() {
		long until = state.get();
		return until > 0L && System.currentTimeMillis() < until;
	}

	/// Record a failed call (transport error, timeout, non-2xx, query/search
	/// failure). Arms or re-arms suppression for `cooldownSeconds`. Only on the
	/// healthy→open EDGE does it log a WARNING and (if `trap`) emit one SNMP
	/// trap — so concurrent failures collapse to a single notice. Never throws.
	void recordFailure(int cooldownSeconds, boolean trap, SipServletRequest req, String tag, String reason) {
		long prev = state.getAndSet(System.currentTimeMillis() + cooldownSeconds * 1000L);
		if (prev == 0L) {
			Logger log = SettingsManager.getSipLogger();
			if (log != null) {
				log.warning(req, tag + " circuit OPEN — " + reason
						+ "; suppressing calls for " + cooldownSeconds + "s, routing to default");
			}
			if (trap) {
				Snmp.trap(Snmp.Severity.WARNING, tag + " unreachable — " + reason
						+ "; calls routing to default for " + cooldownSeconds + "s");
			}
		}
	}

	/// Record a successful call. Closes the breaker. Only on the open→healthy
	/// EDGE does it log INFO and (if `trap`) emit one SNMP trap. A cheap no-op
	/// on every normal healthy call. Never throws.
	void recordSuccess(boolean trap, SipServletRequest req, String tag) {
		long prev = state.getAndSet(0L);
		if (prev != 0L) {
			Logger log = SettingsManager.getSipLogger();
			if (log != null) {
				log.info(req, tag + " circuit CLOSED — endpoint recovered");
			}
			if (trap) {
				Snmp.trap(Snmp.Severity.INFO, tag + " recovered");
			}
		}
	}
}
