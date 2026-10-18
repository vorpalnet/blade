package org.vorpal.blade.services.options;

/// This node's two SIP work-manager queues, over JMX, as [QueuePressure] reads
/// them for the health ping: work waiting for a thread, and the capacity the
/// container refuses requests at. The dashboard shows them per engine, so a
/// `503 Busy` from the options app, or the container's own `503` when a queue
/// fills, can be traced to the queue that filled.
///
/// Registered through an explicit [javax.management.StandardMBean] wrapper with
/// `isMXBean=true`, as every BLADE MBean is.
public interface SipQueuesMXBean {

	/// Messages waiting for a thread in `wlss.transport`; -1 when unreadable.
	int getTransportDepth();

	/// The `wlss.transport` depth the container refuses requests at; 0 when unreadable.
	int getTransportCapacity();

	/// Timers waiting for a thread in `wlss.timer`; -1 when unreadable.
	int getTimerDepth();

	/// The `wlss.timer` depth the container refuses requests at; 0 when unreadable.
	int getTimerCapacity();

	/// The percentage of either capacity at which the health ping answers
	/// `503 Busy`; 0 when the check is off.
	int getBusyPercent();

	/// True while [OverloadMonitor] says refuse new calls.
	boolean isRefusing();

	/// When the current overload episode began, in epoch milliseconds; 0 when
	/// there is none. An episode lasts until the engine has taken calls for 30
	/// seconds, so it can be set while [#isRefusing] is false.
	long getRefusingSince();

	/// How long the engine takes over its SIP messages: the 90th percentile
	/// over the last second, in milliseconds; -1 before the first reading.
	int getLatencyMillis();

	/// The latency at which the server refuses new calls; 0 when refusing is
	/// off. It takes them again below half.
	int getRefuseMillis();

	/// Whether the verdict reaches the container: `off`, `not installed`,
	/// `waiting` or `active`. See [OverloadMonitor#protection].
	String getProtection();

	/// Percent of time spent in collection pauses over the last 10 seconds; -1 unread.
	int getGcPercent();

	/// How full the old generation was after its last collection; -1 unread.
	int getHeapAfterGcPercent();

	/// Call-state lock failures in the last minute; -1 unread.
	long getLockFailuresPerMinute();

	/// The warnings raised and not yet cleared, in words.
	String[] getWarnings();

}
