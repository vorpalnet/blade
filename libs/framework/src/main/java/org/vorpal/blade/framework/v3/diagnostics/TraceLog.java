package org.vorpal.blade.framework.v3.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v3.CallStep;

/// The per-app trace buffer and arming policy — the node-side half of chain
/// tracing.
///
/// State is held in statics, which in a WAR means PER-APP: the framework JAR is
/// bundled in every WAR, so each app's classloader gets its own copy (the same
/// idiom as `SettingsManager.sipLogger`). Nothing is shared across apps or
/// nodes, per the no-singletons rule — each node's buffer is independent, and
/// the viewer merges them by `X-Vorpal-Session`.
///
/// Two things live here:
///
/// - **The step buffer.** `v3.Callflow.record()` appends every [CallStep] of an
///   ARMED call (unarmed calls never reach this class). Bounded ring — beyond
///   [#CAPACITY] steps the oldest fall off — so an armed rule left overnight
///   can't grow memory. Read and cleared over JMX by [Trace].
/// - **The arming policy.** A [Diagnostics] holding the live [TraceRule]s,
///   edited over JMX ([Trace#arm]/[Trace#disarm]). `v3.Callflow` consults it
///   once per `SipApplicationSession` (the first outbound send) and arms the
///   session on a match. In-memory only, deliberately: a trace session is
///   "arm, reproduce, disarm" — rules vanish on redeploy/restart, which is the
///   safe default for a debugging tool.
public final class TraceLog {

	/// Max buffered steps per app per node. Armed calls only, so this is many
	/// complete call traces; the viewer reads long before it wraps.
	public static final int CAPACITY = 2000;

	private static final Diagnostics diagnostics = new Diagnostics();
	private static final ConcurrentLinkedDeque<CallStep> steps = new ConcurrentLinkedDeque<>();
	private static final AtomicInteger size = new AtomicInteger(0);

	private TraceLog() {
	}

	/// The live arming policy this app consults. Never null.
	public static Diagnostics diagnostics() {
		return diagnostics;
	}

	/// Append one step of an armed call, evicting the oldest beyond capacity.
	public static void append(CallStep step) {
		if (step == null) {
			return;
		}
		steps.addLast(step);
		if (size.incrementAndGet() > CAPACITY) {
			if (steps.pollFirst() != null) {
				size.decrementAndGet();
			}
		}
	}

	/// Snapshot of the buffered steps, oldest first.
	public static List<CallStep> snapshot() {
		return new ArrayList<>(steps);
	}

	public static void clear() {
		steps.clear();
		size.set(0);
	}

	/// Add an arming rule and enable tracing. `pattern` is a full-match regex
	/// against the chosen attribute (`From`, `To`, `Request-URI`, `Origin-IP`,
	/// `Content`, or any header name), e.g. `.*5551234.*`.
	public static void arm(String label, String attribute, String pattern, int maxCaptures) {
		Selector selector = new Selector(label, attribute, pattern, "$0");
		synchronized (diagnostics) {
			diagnostics.getRules().add(new TraceRule(label, selector, maxCaptures));
			diagnostics.setEnabled(true);
		}
	}

	/// Drop every rule and switch tracing off. Calls already armed keep
	/// recording until they end (the per-session flag is already set).
	public static void disarm() {
		synchronized (diagnostics) {
			diagnostics.getRules().clear();
			diagnostics.setEnabled(false);
		}
	}
}
