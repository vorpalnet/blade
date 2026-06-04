/**
 *  MIT License
 *
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.framework.v2.snmp;

import java.lang.reflect.Method;
import java.util.logging.Level;

/// Sends user-defined SNMP traps from BLADE application code, without a
/// compile-time dependency on the OCCAS SIP engine.
///
/// OCCAS already ships the trap-sending machinery this wraps:
/// `com.bea.wcp.sip.management.snmp.SNMPAgent.sendSipAppTrap(trapType, message)`
/// emits one of seven severity-keyed SIP-application notifications defined in
/// the shipped `WLSS-MIB.asn1` (`sipAppInfoTrap` .. `sipAppEmergencyTrap`,
/// OIDs `1.3.6.1.4.1.140.626.200.14` .. `.20`). The engine fills in the server
/// name, deployed application name, current servlet, and timestamp; the caller
/// supplies the severity (which OID fires) and a free-text message.
///
/// Two ways to fire a trap:
///  - explicitly, for a business event: `Snmp.trap(Severity.CRITICAL, "registrar pool exhausted")`
///  - automatically, by logging: set `LogParameters.snmpTrapLevel` and any log
///    statement at or above that level also emits a trap (see the bridge in
///    `org.vorpal.blade.framework.v2.logging.Logger`).
///
/// Resolved reflectively on purpose, mirroring `EngineOverload` in the Options
/// service: if `SNMPAgent` is absent (running outside OCCAS, unit tests, or a
/// future engine that renames it) every lookup fails closed and `trap(...)`
/// becomes a silent no-op. Whether a trap actually leaves the box is still
/// governed by the WebLogic SNMP agent — the domain agent must be enabled with
/// at least one trap destination (configured in the Tuning admin app). With no
/// agent configured, `sendSipAppTrap` itself does nothing.
///
/// Threading note: OCCAS's `sendSipAppTrap` reads the deployed application name
/// from the thread's context classloader, so traps resolve their app name
/// correctly when fired from inside a SIP callflow (the intended use). Fired
/// from an unrelated pool thread whose context classloader is not a WLS
/// application loader, the underlying call may throw; this wrapper swallows it
/// and the trap is simply not sent. A trap must never break its caller.
public final class Snmp {

	/// The seven SIP-application trap severities OCCAS defines, each mapped to
	/// the MIB notification type `SNMPAgent.sendSipAppTrap` expects. Mirrors
	/// `com.bea.wcp.sip.management.snmp.TrapHelper.TRAP_SIP_APP_SEVERITY` without
	/// depending on it at compile time.
	public enum Severity {
		INFO("sipAppInfoTrap"), //
		WARNING("sipAppWarningTrap"), //
		ERROR("sipAppErrorTrap"), //
		NOTICE("sipAppNoticeTrap"), //
		CRITICAL("sipAppCriticalTrap"), //
		ALERT("sipAppAlertTrap"), //
		EMERGENCY("sipAppEmergencyTrap");

		private final String trapType;

		Severity(String trapType) {
			this.trapType = trapType;
		}

		/// The OCCAS/WLSS MIB notification name for this severity.
		public String getTrapType() {
			return trapType;
		}

		/// Maps a `java.util.logging.Level` to a trap severity for the
		/// logging→trap bridge. JUL has fewer levels than the MIB, so this
		/// collapses to the three reachable from logging: SEVERE→ERROR,
		/// WARNING→WARNING, everything else (INFO/CONFIG/FINE…)→INFO. The
		/// remaining severities (NOTICE/CRITICAL/ALERT/EMERGENCY) are only
		/// reachable by calling `trap(...)` directly.
		public static Severity fromLevel(Level level) {
			if (level == null) {
				return INFO;
			}
			int value = level.intValue();
			if (value >= Level.SEVERE.intValue()) {
				return ERROR;
			}
			if (value >= Level.WARNING.intValue()) {
				return WARNING;
			}
			return INFO;
		}
	}

	private static final Method GET_AGENT;
	private static final Method SEND_SIP_APP_TRAP;

	static {
		Method getAgent = null;
		Method sendSipAppTrap = null;
		try {
			Class<?> agentClass = Class.forName("com.bea.wcp.sip.management.snmp.SNMPAgent");
			getAgent = agentClass.getMethod("getAgent");
			sendSipAppTrap = agentClass.getMethod("sendSipAppTrap", String.class, String.class);
		} catch (Throwable t) {
			// SNMPAgent not present / changed — traps become a no-op and BLADE
			// behaves exactly as it did before this feature.
		}
		GET_AGENT = getAgent;
		SEND_SIP_APP_TRAP = sendSipAppTrap;
	}

	private Snmp() {
	}

	/// True when the OCCAS trap-sending class resolved — i.e. we are running on
	/// an OCCAS server and `trap(...)` can reach the engine. This does NOT mean
	/// the WebLogic SNMP agent is enabled or has a trap destination; that is
	/// separate domain configuration. Useful for an admin/health display.
	public static boolean isAvailable() {
		return GET_AGENT != null && SEND_SIP_APP_TRAP != null;
	}

	/// Sends a SIP-application SNMP trap at the given severity with a free-text
	/// message. Never throws: if SNMP is unavailable, the engine is not present,
	/// or the send fails, the call is a silent no-op.
	///
	/// @param severity which of the seven SIP-application traps to emit
	/// @param message  free-text detail carried in the `wlssTrapErrorMsg` binding
	public static void trap(Severity severity, String message) {
		if (severity == null || !isAvailable()) {
			return;
		}
		try {
			Object agent = GET_AGENT.invoke(null);
			if (agent != null) {
				SEND_SIP_APP_TRAP.invoke(agent, severity.getTrapType(), message);
			}
		} catch (Throwable t) {
			// Trap delivery is best-effort and must never break the caller.
		}
	}
}
