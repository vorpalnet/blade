package org.vorpal.blade.overload;

/// The overload verdict for this server, set by the options service and read
/// by OCCAS overload protection through [OverloadCollector].
///
/// This class lives in the domain's `lib/` folder, on the server classpath, so
/// it is loaded once per server and the same class is seen by the container
/// and by every application. The options service decides; it calls
/// [#report] by reflection on each sample, so no WAR carries a copy of this
/// class that would hide the server's.
///
/// **A verdict expires.** Each report is good for a stated time. When no new
/// report arrives in that time, because the options service was undeployed,
/// stopped, or wedged, the verdict reads as "not overloaded". A missing
/// decider never leaves a server refusing calls; the container's own transport
/// throttle still guards it.
public final class Overload {

	private static volatile boolean refusing;
	private static volatile long validUntil;
	private static volatile long consulted;

	private Overload() {
	}

	/// Sets the verdict: `refuse` true while new calls should be refused. It
	/// holds for `validMillis`, after which [#refusing] reads false until the
	/// next report.
	public static void report(boolean refuse, long validMillis) {
		validUntil = System.currentTimeMillis() + Math.max(0, validMillis);
		refusing = refuse;
	}

	/// True while the latest verdict says refuse and has not expired.
	public static boolean refusing() {
		return refusing(System.currentTimeMillis());
	}

	static boolean refusing(long now) {
		return refusing && now < validUntil;
	}

	/// When the container last asked for the verdict, in epoch milliseconds,
	/// or 0 if never. Real-time overload protection asks once for each new
	/// request from outside a dialog, so a recent time means the container is
	/// configured to act on the verdict.
	public static long consulted() {
		return consulted;
	}

	static void markConsulted(long now) {
		consulted = now;
	}
}
