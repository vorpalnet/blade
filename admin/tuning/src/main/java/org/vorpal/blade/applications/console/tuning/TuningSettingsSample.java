package org.vorpal.blade.applications.console.tuning;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/tuning.json` on first deployment when no
/// operator-supplied file is present.
///
/// Ships two OCCAS-tuned JVM profiles as starting points: G1GC (the safe
/// JDK 11+ default) and ZGC (low-latency, JDK 21 / OCCAS 8.3 only). Both assume
/// a 4-vCPU / 16 GB engine node; an operator can clone, edit, and assign them.
public class TuningSettingsSample extends TuningSettings {
	private static final long serialVersionUID = 1L;

	public TuningSettingsSample() {
		this.jvmProfiles = new java.util.ArrayList<>(java.util.Arrays.asList(
				new JvmProfile("G1GC - Java 8+",
					"OCCAS-tuned baseline for deterministic garbage colletion.",
					"-Xms8g\n-Xmx8g\n-XX:+UseG1GC\n-XX:+AlwaysPreTouch\n-XX:MaxGCPauseMillis=50\n-XX:InitiatingHeapOccupancyPercent=35\n-XX:+ParallelRefProcEnabled\n-XX:+UseStringDeduplication\n-XX:+ExplicitGCInvokesConcurrent\n-Djava.security.egd=file:/dev/./urandom\n-Dwlss.maddr.enable=true\n-XX:+PerfDisableSharedMem\n-XX:MetaspaceSize=256m\n-XX:MaxMetaspaceSize=512m\n-XX:+HeapDumpOnOutOfMemoryError\n-XX:HeapDumpPath=./servers/${server}/logs\n-Xlog:gc*:file=./servers/${server}/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=20M"),
				new JvmProfile("ZGC - Java 21+",
					"OCCAS-tuned baseline for low-latency, sub-millisecond pauses.",
					"-Xms8g\n-Xmx8g\n-XX:+UseZGC\n-XX:+ZGenerational\n-XX:+AlwaysPreTouch\n-XX:SoftMaxHeapSize=7g\n-XX:+UseStringDeduplication\n-XX:+ExplicitGCInvokesConcurrent\n-Djava.security.egd=file:/dev/./urandom\n-Dwlss.maddr.enable=true\n-XX:+PerfDisableSharedMem\n-XX:MetaspaceSize=256m\n-XX:MaxMetaspaceSize=512m\n-XX:+HeapDumpOnOutOfMemoryError\n-XX:HeapDumpPath=./servers/${server}/logs\n-Xlog:gc*:file=./servers/${server}/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=20M")));
	}
}
