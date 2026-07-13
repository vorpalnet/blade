package org.vorpal.blade.applications.console.tuning;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/tuning.json` on first deployment when no
/// operator-supplied file is present.
///
/// Ships two OCCAS-tuned JVM profiles as starting points: G1GC (the safe
/// JDK 11+ default) and ZGC (low-latency, JDK 21 / OCCAS 8.3 only). Both assume
/// a 4-vCPU / 16 GB engine node; an operator can clone, edit, and assign them.
/// Each template must be a fixed point of its "Apply template" button in the
/// Tuning console (recommendJvm / recommendJvmZgc) — clicking the button on the
/// shipped profile is a no-op.
public class TuningSettingsSample extends TuningSettings {
	private static final long serialVersionUID = 1L;

	public TuningSettingsSample() {
		this.jvmProfiles = new java.util.ArrayList<>(java.util.Arrays.asList(
				new JvmProfile("G1GC - Java 11+",
					"OCCAS-tuned G1 baseline for a 4-vCPU / 16 GB engine node, JDK 11+ (OCCAS 8.1 and 8.3) — the safe default. "
					+ "Heap pinned at 8 GB (Xms=Xmx, pre-touched) so there are no resize pauses and page-fault jitter is paid once at startup, "
					+ "leaving ~8 GB for the OS, OCCAS/Coherence off-heap call state, and NIO buffers. "
					+ "50 ms pause goal; IHOP 35 seeds G1's adaptive marking trigger so concurrent marking starts early until the JVM learns the real allocation rate. "
					+ "String dedup for repeated SIP headers; System.gc() runs concurrently (ExplicitGCInvokesConcurrent) so WebLogic RMI DGC cannot force a full pause. "
					+ "On OutOfMemoryError the JVM writes a heap dump and exits so Node Manager restarts it and calls fail over. "
					+ "PerfDisableSharedMem avoids page-cache stalls writing hsperfdata — jps/jstat won't see the process; jcmd and JMX still work. "
					+ "GC thread counts are left at their defaults, which size to the vCPUs automatically.",
					"-Xms8g\n-Xmx8g\n-XX:+UseG1GC\n-XX:+AlwaysPreTouch\n-XX:MaxGCPauseMillis=50\n-XX:InitiatingHeapOccupancyPercent=35\n-XX:+ParallelRefProcEnabled\n-XX:+UseStringDeduplication\n-XX:+ExplicitGCInvokesConcurrent\n-Djava.security.egd=file:/dev/./urandom\n-Dwlss.maddr.enable=true\n-XX:+PerfDisableSharedMem\n-XX:MetaspaceSize=256m\n-XX:MaxMetaspaceSize=512m\n-XX:+HeapDumpOnOutOfMemoryError\n-XX:+ExitOnOutOfMemoryError\n-XX:HeapDumpPath=./servers/${server}/logs\n-Xlog:gc*:file=./servers/${server}/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=20M"),
				new JvmProfile("ZGC - Java 21+",
					"OCCAS-tuned low-latency profile for JDK 21 (OCCAS 8.3) — generational ZGC with typically sub-millisecond pauses (a design goal, not a guarantee). "
					+ "Heap pinned at 8 GB (Xms=Xmx, pre-touched); SoftMaxHeapSize 7 GB keeps 1 GB of headroom so ZGC cycles start before allocation stalls. "
					+ "String dedup for repeated SIP headers; System.gc() runs concurrently (ExplicitGCInvokesConcurrent). "
					+ "On OutOfMemoryError the JVM writes a heap dump and exits so Node Manager restarts it and calls fail over. "
					+ "PerfDisableSharedMem avoids page-cache stalls writing hsperfdata — jps/jstat won't see the process; jcmd and JMX still work. "
					+ "ZGenerational is JDK 21/22 only — default in 23, removed in 24; drop the flag when moving past 22. "
					+ "Expect somewhat higher CPU use than G1 (concurrent GC threads compete with SIP workers on small nodes).",
					"-Xms8g\n-Xmx8g\n-XX:+UseZGC\n-XX:+ZGenerational\n-XX:+AlwaysPreTouch\n-XX:SoftMaxHeapSize=7g\n-XX:+UseStringDeduplication\n-XX:+ExplicitGCInvokesConcurrent\n-Djava.security.egd=file:/dev/./urandom\n-Dwlss.maddr.enable=true\n-XX:+PerfDisableSharedMem\n-XX:MetaspaceSize=256m\n-XX:MaxMetaspaceSize=512m\n-XX:+HeapDumpOnOutOfMemoryError\n-XX:+ExitOnOutOfMemoryError\n-XX:HeapDumpPath=./servers/${server}/logs\n-Xlog:gc*:file=./servers/${server}/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=20M")));
	}
}
