package org.vorpal.blade.services.options;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/// Early warning ahead of the container's transport throttle.
///
/// Before OCCAS schedules an inbound SIP message it compares two work-manager
/// queues against their capacity constraints: `wlss.transport` (SIP message
/// processing) and `wlss.timer` (SIP timers). When either queue is full, the
/// transport stops handing messages to applications and answers every request,
/// OPTIONS included, with `503 Server Busy` for anywhere from half a second to
/// 32 seconds. By then the node is already rejecting calls, and the health
/// ping is one of the requests refused: an application cannot report a state
/// in which it no longer receives the ping. The same holds for OCCAS overload
/// protection, which rejects new requests before any application sees them.
///
/// This class reads the same two queues while the node is still answering, so
/// [OptionsCallflow] can answer `503 "Busy"` once either queue passes a
/// configured percentage of its capacity (`queuePressurePercent`, default
/// [#DEFAULT_PERCENT]). The load balancer backs off before the throttle trips,
/// and the node rejoins as soon as a ping finds the queues below the line.
///
/// **Queue depth** is work waiting for a thread, read through WebLogic's
/// `weblogic.work.WorkManagerFactory`, the same lookup the transport uses.
/// It is reached by reflection so this WAR compiles against no container JAR.
///
/// **Capacity** is the `Count` of each work manager's capacity constraint in
/// the domain configuration. The container reads it once at startup and keeps
/// it, so this class does too. A work manager with no capacity constraint is
/// throttled at the container's built-in defaults, [#TRANSPORT_DEFAULT_CAPACITY]
/// and [#TIMER_DEFAULT_CAPACITY].
///
/// Health-check path: nothing here throws. Anything unreadable reads as "no
/// pressure", and the ping answers 200 exactly as it did before this check.
final class QueuePressure {

	/// Percent of capacity at which the ping turns 503 when `options.json`
	/// does not say.
	static final int DEFAULT_PERCENT = 80;

	static final String TRANSPORT = "wlss.transport";
	static final String TIMER = "wlss.timer";

	/// The container's queue limits when a work manager has no capacity constraint.
	static final int TRANSPORT_DEFAULT_CAPACITY = 400;
	static final int TIMER_DEFAULT_CAPACITY = 256;

	private static final Queue transport = new Queue(TRANSPORT, TRANSPORT_DEFAULT_CAPACITY);
	private static final Queue timer = new Queue(TIMER, TIMER_DEFAULT_CAPACITY);

	private QueuePressure() {
	}

	/// True when either SIP work-manager queue is at or above `percent` of its
	/// capacity. `percent` of 0 or less turns the check off.
	static boolean isPressured(int percent) {
		if (percent <= 0) {
			return false;
		}
		return transport.over(percent) || timer.over(percent);
	}

	/// `depth` is at or above `percent` of `capacity`. Integer arithmetic in
	/// longs, so a 5,000,000 capacity times 100 cannot overflow.
	static boolean over(int depth, int capacity, int percent) {
		if (depth < 0 || capacity <= 0 || percent <= 0) {
			return false;
		}
		return (long) depth * 100L >= (long) capacity * percent;
	}

	/// One work manager: its queue-depth reader and its cached capacity.
	/// Lookups are retried on every call until they succeed (early boot), then
	/// kept. Races between pings only repeat a lookup.
	static final class Queue {
		private final String name;
		private final int defaultCapacity;
		private volatile Object workManager;
		private volatile Method queueDepth;
		private volatile int capacity; // 0 = not read yet

		Queue(String name, int defaultCapacity) {
			this.name = name;
			this.defaultCapacity = defaultCapacity;
		}

		boolean over(int percent) {
			int cap = capacity();
			if (cap <= 0) {
				return false;
			}
			return QueuePressure.over(depth(), cap, percent);
		}

		/// Work waiting for a thread, or -1 when unreadable.
		int depth() {
			try {
				if (workManager == null) {
					Class<?> factory = Class.forName("weblogic.work.WorkManagerFactory");
					Object instance = factory.getMethod("getInstance").invoke(null);
					Object wm = factory.getMethod("find", String.class).invoke(instance, name);
					if (wm == null) {
						return -1;
					}
					queueDepth = Class.forName("weblogic.work.WorkManager").getMethod("getQueueDepth");
					workManager = wm;
				}
				return (Integer) queueDepth.invoke(workManager);
			} catch (Throwable t) {
				return -1;
			}
		}

		/// The capacity the container throttles at, or 0 when unreadable.
		int capacity() {
			if (capacity > 0) {
				return capacity;
			}
			int read = readCapacity(ManagementFactory.getPlatformMBeanServer(), name, defaultCapacity);
			if (read > 0) {
				capacity = read;
			}
			return read;
		}
	}

	/// Walks the domain configuration: RuntimeService → DomainConfiguration →
	/// SelfTuning → the work manager named `name` → its Capacity → Count.
	/// Returns `defaultCapacity` when that work manager exists with no capacity
	/// constraint, and 0 when the configuration cannot be read or has no such
	/// work manager (guessing a limit there could turn a healthy node away).
	static int readCapacity(MBeanServer server, String name, int defaultCapacity) {
		try {
			ObjectName runtimeService = new ObjectName(
					"com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean");
			ObjectName domain = (ObjectName) server.getAttribute(runtimeService, "DomainConfiguration");
			ObjectName selfTuning = (ObjectName) server.getAttribute(domain, "SelfTuning");
			ObjectName[] workManagers = (ObjectName[]) server.getAttribute(selfTuning, "WorkManagers");
			if (workManagers == null) {
				return 0;
			}
			for (ObjectName wm : workManagers) {
				if (name.equals(server.getAttribute(wm, "Name"))) {
					ObjectName constraint = (ObjectName) server.getAttribute(wm, "Capacity");
					if (constraint == null) {
						return defaultCapacity;
					}
					Object count = server.getAttribute(constraint, "Count");
					return (count instanceof Integer) ? (Integer) count : 0;
				}
			}
			return 0;
		} catch (Throwable t) {
			return 0;
		}
	}

}
