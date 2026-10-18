package org.vorpal.blade.services.options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.naming.InitialContext;

import org.vorpal.blade.framework.v3.events.Problems;

/// Decides, on every engine, whether to refuse new calls, and says why.
///
/// Every [#SAMPLE_MILLIS] it reads how long the engine is taking over its SIP
/// messages ([EngineLatency]: the 90th percentile over the last second, as the
/// container measures it), feeds [OverloadVerdict], and hands the verdict to
/// OCCAS overload protection through [OverloadBridge]. While the verdict holds,
/// the container answers new requests from outside a dialog with `503 Server
/// busy`; calls already up carry on. Latency rather than queue depth, because it
/// is what a caller feels and it needs no tuning to the hardware: the wait in
/// the queue is part of it, and a faster engine works the same queue off
/// sooner. The point is to refuse new calls early enough that the queue never
/// fills, because a full queue makes the container refuse everything,
/// including the BYEs that would have drained it.
///
/// **A stall counts.** When messages are waiting and none finished in the last
/// second, the latency reads as the histogram's ceiling: an engine whose
/// threads are all blocked finishes nothing, and so reports nothing slow.
///
/// Once a second it also reads [Strain]: garbage collection, the old
/// generation after collection, call-state lock failures. Those are warnings,
/// raised for the dashboard and never a reason to refuse.
///
/// Each change is raised or cleared through [Problems], so it shows under the
/// dashboard's Issues, goes out through its notifications, and is stored by
/// analytics with the other operations events. The state is also on the
/// [SipQueuesMXBean], which the Servers panel reads.
///
/// Runs on the container's managed scheduler, rescheduling itself after each
/// pass.
final class OverloadMonitor implements Runnable {

	private static final Logger logger = Logger.getLogger(OverloadMonitor.class.getName());
	private static final String EXECUTOR = "java:comp/DefaultManagedScheduledExecutorService";

	/// Between readings. [EngineLatency#WINDOW] readings make its second.
	static final long SAMPLE_MILLIS = 250;

	/// How long one verdict holds without a newer one: a late pass must not let
	/// it lapse while the engine is still slow, and a stopped monitor must not
	/// leave the engine refusing for long.
	static final long VALID_MILLIS = 2_000;

	/// The window the garbage collection share is measured over.
	static final int GC_WINDOW_SECONDS = 10;

	static final String KEY = "overload";
	static final String GC = "overload.gc";
	static final String HEAP = "overload.heap";
	static final String LOCKS = "overload.locks";

	/// A warning clears once its reading falls below this share of its line,
	/// so a reading that hovers at the line raises it once, not every second.
	static final int CLEAR_SHARE = 80;

	/// How recently the container must have asked for the verdict to count as
	/// acting on it.
	static final long CONSULTED_MILLIS = 60_000L;

	private final Supplier<OptionsSettings> settings;
	private final String application;
	private final OverloadVerdict verdict = new OverloadVerdict();
	private final EngineLatency latency = new EngineLatency();
	private final Strain strain = new Strain();

	/// Raised and not yet cleared: key to summary.
	private final Map<String, String> raised = new LinkedHashMap<>();

	private ManagedScheduledExecutorService executor;
	private volatile ScheduledFuture<?> next;
	private volatile boolean stopped;
	private long lastStrain;

	// What the MBean reports.
	private volatile int latencyMillis = -1;
	private volatile long refusingSince;
	private volatile int gcPercent = -1;
	private volatile int heapAfterGcPercent = -1;
	private volatile long lockFailuresPerMinute = -1;

	OverloadMonitor(Supplier<OptionsSettings> settings, String application) {
		this.settings = settings;
		this.application = application;
	}

	/// Starts sampling. Returns null, logged, when the container offers no
	/// managed scheduler; the options service runs without it.
	static OverloadMonitor start(Supplier<OptionsSettings> settings, String application) {
		try {
			OverloadMonitor monitor = new OverloadMonitor(settings, application);
			monitor.executor = (ManagedScheduledExecutorService) new InitialContext().lookup(EXECUTOR);
			monitor.next = monitor.executor.schedule(monitor, SAMPLE_MILLIS, TimeUnit.MILLISECONDS);
			if (!OverloadBridge.installed()) {
				logger.info("Options - " + OverloadBridge.HOLDER + " is not in the domain lib folder; the overload"
						+ " verdict is reported to the dashboard only, and nothing refuses new calls early.");
			}
			return monitor;
		} catch (Exception e) {
			logger.log(Level.WARNING, "Options - overload monitor not started: " + e);
			return null;
		}
	}

	/// Stops sampling, withdraws the verdict, and clears what it raised, so an
	/// undeployed options service leaves neither a refusing engine nor a stale
	/// issue behind.
	void stop() {
		stopped = true;
		ScheduledFuture<?> f = next;
		if (f != null) {
			f.cancel(false);
		}
		OverloadBridge.report(false, 0);
		synchronized (raised) {
			for (String key : raised.keySet()) {
				Problems.clear(key);
			}
			raised.clear();
		}
	}

	@Override
	public void run() {
		try {
			sample(System.currentTimeMillis(), overload());
		} catch (Throwable t) {
			// Never let one bad pass stop the sampling.
			logger.log(Level.WARNING, "Options - overload sample failed", t);
		} finally {
			if (!stopped) {
				next = executor.schedule(this, SAMPLE_MILLIS, TimeUnit.MILLISECONDS);
			}
		}
	}

	OverloadSettings overload() {
		OptionsSettings s = settings.get();
		OverloadSettings o = s != null ? s.getOverload() : null;
		return o != null ? o : new OverloadSettings();
	}

	private void sample(long now, OverloadSettings o) {
		int ms = latency.sample();
		if (stalled(ms, latency.readable(), QueuePressure.transport().depth())) {
			ms = EngineLatency.CEILING_MILLIS;
		}
		latencyMillis = ms;
		boolean was = verdict.refusing();
		boolean refuse = verdict.sample(ms, o.getRefuseMillis());
		OverloadBridge.report(refuse, VALID_MILLIS);

		if (refuse && !was) {
			refusingSince = now;
			String summary = refusingSummary(o, now);
			logger.warning("Options - " + summary);
			raise(KEY, summary, false);
		} else if (!refuse && was) {
			refusingSince = 0;
			logger.info("Options - taking new calls again: SIP messages taking " + Math.max(0, ms) + " ms");
			clear(KEY);
		}

		if (now - lastStrain >= 1_000L) {
			lastStrain = now;
			strain(now, o);
		}
	}

	/// Messages are waiting and none finished in the last second.
	static boolean stalled(int latencyMillis, boolean readable, int queued) {
		return latencyMillis < 0 && readable && queued > 0;
	}

	private String refusingSummary(OverloadSettings o, long now) {
		String reading = "SIP messages taking " + latencyMillis + " ms or more (90th percentile; refuses at "
				+ o.getRefuseMillis() + " ms, takes calls again below " + (o.getRefuseMillis() / 2) + " ms)";
		switch (protection(o, now)) {
		case "active":
			return "Refusing new calls: " + reading;
		case "not installed":
			return "Overloaded, " + reading + ", but blade-overload.jar is not in the domain lib folder,"
					+ " so nothing refuses new calls before the queue fills";
		default:
			return "Overloaded, " + reading + ", but the container is not asking for the verdict:"
					+ " sipserver.xml does not name the BLADE overload collector (./install.sh <env> overload)";
		}
	}

	private void strain(long now, OverloadSettings o) {
		gcPercent = strain.gcPercent(now, GC_WINDOW_SECONDS);
		heapAfterGcPercent = Strain.heapAfterGcPercent();
		lockFailuresPerMinute = strain.lockFailuresPerMinute(now);

		warn(GC, gcPercent, o.getGcWarnPercent(),
				"Garbage collection took " + gcPercent + "% of the last " + GC_WINDOW_SECONDS + " s");
		warn(HEAP, heapAfterGcPercent, o.getHeapWarnPercent(),
				"Old generation still " + heapAfterGcPercent + "% full after its last collection");
		warn(LOCKS, lockFailuresPerMinute, o.getLockFailuresWarnPerMinute(),
				lockFailuresPerMinute + " call-state lock failures in the last minute");
	}

	/// Raises the warning when `reading` reaches `line`, clears it once the
	/// reading falls below [#CLEAR_SHARE] percent of the line. A line of 0
	/// turns it off; an unreadable reading (-1) changes nothing.
	private void warn(String key, long reading, int line, String summary) {
		boolean up;
		synchronized (raised) {
			up = raised.containsKey(key);
		}
		switch (warningChange(up, reading, line)) {
		case 1:
			raise(key, summary, true);
			break;
		case -1:
			clear(key);
			break;
		default:
			break;
		}
	}

	/// 1 to raise, -1 to clear, 0 to leave as it is.
	static int warningChange(boolean up, long reading, int line) {
		if (line <= 0) {
			return up ? -1 : 0;
		}
		if (reading < 0) {
			return 0;
		}
		if (!up && reading >= line) {
			return 1;
		}
		if (up && reading * 100L < (long) line * CLEAR_SHARE) {
			return -1;
		}
		return 0;
	}

	private void raise(String key, String summary, boolean warning) {
		synchronized (raised) {
			if (stopped) {
				return;
			}
			raised.put(key, summary);
		}
		String open = "/blade/configurator/configurator.html?schema=" + application;
		if (warning) {
			Problems.warn(key, summary, open);
		} else {
			Problems.raise(key, summary, open);
		}
	}

	private void clear(String key) {
		synchronized (raised) {
			if (raised.remove(key) == null) {
				return;
			}
		}
		Problems.clear(key);
	}

	/// Whether the verdict reaches the container: `off` (refuseMillis 0),
	/// `not installed` (no blade-overload.jar in the domain lib folder),
	/// `waiting` (installed, but the container has not asked in the last
	/// minute: no new calls, or sipserver.xml does not name the collector), or
	/// `active`.
	static String protection(OverloadSettings o, long now) {
		if (o.getRefuseMillis() <= 0) {
			return "off";
		}
		if (!OverloadBridge.installed()) {
			return "not installed";
		}
		long asked = OverloadBridge.consulted();
		return asked > 0 && now - asked < CONSULTED_MILLIS ? "active" : "waiting";
	}

	/// What a health ping says when the engine is past half of `refuseMillis`:
	/// `latency 60 ms`, or null while it is under. Load balancers that ping
	/// stop sending new calls here before the container has to refuse them.
	String pingBusy() {
		return pingBusy(latencyMillis, overload().getRefuseMillis());
	}

	static String pingBusy(int latencyMillis, int refuseMillis) {
		if (refuseMillis <= 0 || latencyMillis < 0 || latencyMillis * 2 < refuseMillis) {
			return null;
		}
		return "latency " + latencyMillis + " ms";
	}

	// ── what the MBean reads ──

	boolean isRefusing() {
		return verdict.refusing();
	}

	long getRefusingSince() {
		return refusingSince;
	}

	int getLatencyMillis() {
		return latencyMillis;
	}

	String getProtection() {
		return protection(overload(), System.currentTimeMillis());
	}

	int getGcPercent() {
		return gcPercent;
	}

	int getHeapAfterGcPercent() {
		return heapAfterGcPercent;
	}

	long getLockFailuresPerMinute() {
		return lockFailuresPerMinute;
	}

	/// The warnings raised and not cleared, in words.
	List<String> getWarnings() {
		synchronized (raised) {
			List<String> out = new ArrayList<>();
			raised.forEach((k, v) -> {
				if (!KEY.equals(k)) {
					out.add(v);
				}
			});
			return out;
		}
	}
}
