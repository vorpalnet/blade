/*
 * Single source of truth for the Tuning app's recommended values and the
 * JVM-configuration findings. Consumed by BOTH:
 *   - tuning.html   — the per-panel [Recommended] buttons fill from TUNING_REC,
 *   - report.html   — the printable scorecard compares live values to TUNING_REC
 *                     and reprints the same findings the dashboard shows.
 * Plain script (no jQuery), attached to window.
 */

var TUNING_REC = {
	serverTuning: {
		// The WLS default (10 MB) is too small to reliably push an updated
		// domain configuration to a managed node at startup.
		maxMessageSize: 40000000,
		// Measured benefit above this is inconclusive; a reasonable start.
		socketReaders: 4,
		// Quiets Notice-level subsystem noise (e.g. transport idle-socket
		// closes) in the server log.
		minimumSeverityToLog: 'Warning'
		// Plus: Thread Pool Min = Max (pinned pool) — a relationship, not a value.
	},
	jdbc: {
		// Initial = Min = Max, at least this, so the pool is fully allocated
		// at startup and never pays connection create/teardown under load.
		minimumPool: 300
	},
	workManagers: {
		// OCCAS 8.0 Debugging & Tuning doc values (re-verify against 8.3).
		'wlss.timer': { maxThreads: 200, capacity: 150000 },
		'wlss.transport': { capacity: 5000000 }
		// Plus: Min = Max wherever both constraints exist.
	},
	sip: {
		// The one universally defensible value on the SIP panel.
		engineCallStateCache: true
	},
	domain: {
		configBackupEnabled: true,
		archiveConfigurationCount: 20,   // 0 would mean keep-all (unbounded)
		configurationAuditType: 'log'
		// Production mode is deliberately NOT a recommendation — operator's call.
	},
	// Flags every profile should carry, independent of collector choice.
	jvmFlags: [
		'-XX:+AlwaysPreTouch',
		'-Djava.security.egd=file:/dev/./urandom',
		'-XX:+HeapDumpOnOutOfMemoryError',
		'-XX:+ExitOnOutOfMemoryError',
		'-XX:+ExplicitGCInvokesConcurrent',
		'-Dwlss.maddr.enable=true'
	]
};

/*
 * Static findings over the /api/v1/jvm payload (one entry per server:
 * rawArguments, heapInitial, heapMax, javaHome). Returns
 * [{server, sev, msg, fix}] — rendered by the dashboard's Health Check panel
 * and reprinted verbatim in the report's Findings section. sev is 'critical'
 * only for boot-blockers (the JVM will refuse to start on its next restart);
 * everything else is 'advisory'.
 */
function computeJvmFindings(jvmList) {
	var issues = [];
	(jvmList || []).forEach(function (s) {
		var raw = s.rawArguments || '';
		var name = s.server;
		// JDK major version, parsed up front — severity of the flag findings
		// depends on it (an -XX flag the JVM doesn't recognize is fatal).
		var vm = (s.javaHome || '').match(/(?:jdk-?|java-?|1\.)(\d{1,2})/i);
		var maj = vm ? parseInt(vm[1]) : 0;
		function advise(msg, fix) { issues.push({ server: name, sev: 'advisory', msg: msg, fix: fix }); }
		function critical(msg, fix) { issues.push({ server: name, sev: 'critical', msg: msg, fix: fix }); }
		if (s.heapMax && s.heapInitial !== s.heapMax) {
			advise('Heap not pinned: -Xms (' + (s.heapInitial || 'unset') + ') != -Xmx (' + s.heapMax + ')', 'Set initial = max to avoid heap-resize pauses.');
		}
		if (raw.indexOf('-XX:+UseConcMarkSweepGC') >= 0 || raw.indexOf('-XX:+UseParNewGC') >= 0) {
			// Removed in JDK 14; an unrecognized -XX:+ flag aborts JVM startup.
			if (maj >= 14) critical('CMS / ParNew GC flag present, but JavaHome appears to be JDK ' + maj + ' — the flag was removed in JDK 14, so the JVM will refuse to start on its next restart', 'Switch to G1, ZGC, or Shenandoah.');
			else advise('CMS / ParNew GC flag present — removed in JDK 14, fatal on JDK 21', 'Switch to G1, ZGC, or Shenandoah.');
		}
		if (raw.indexOf('-Xshare:off') >= 0) {
			advise('Class Data Sharing disabled (-Xshare:off) — slower warmup', 'Remove it; the default CDS archive is enabled for free since JDK 12.');
		}
		var mux = raw.match(/-Dweblogic\.MuxerClass=(\S+)/);
		if (mux && mux[1].indexOf('NIOSocketMuxer') < 0) {
			advise('Non-default socket muxer: ' + mux[1], 'WLS 14.1.2 recommends the default NIO muxer; remove the override unless intentional.');
		}
		if (raw.indexOf('-XX:+UseZGC') >= 0 && raw.indexOf('-XX:+ZGenerational') < 0) {
			advise('ZGC selected without -XX:+ZGenerational — running the legacy non-generational collector on JDK 21', 'Add -XX:+ZGenerational (JDK 21/22 only).');
		}
		if (raw.indexOf('-XX:+DisableExplicitGC') >= 0 && raw.indexOf('-XX:+ExplicitGCInvokesConcurrent') >= 0) {
			advise('Both -XX:+DisableExplicitGC and -XX:+ExplicitGCInvokesConcurrent present — they are mutually exclusive', 'Keep one: DisableExplicitGC ignores System.gc(); ExplicitGCInvokesConcurrent runs it concurrently.');
		}
		if (raw && raw.indexOf('-Xlog:gc') < 0) {
			advise('No GC logging configured (-Xlog:gc...) — no visibility into GC behavior after an incident', 'Add -Xlog:gc*:file=...:time,uptime,level,tags with rotation (see the shipped profiles).');
		}
		if (maj === 1) advise('JDK appears to be 1.x (pre-Java 9) per JavaHome: ' + s.javaHome, 'OCCAS 8.3 targets JDK 17 or 21.');
		else if (maj > 8 && maj < 21) advise('JDK appears to be ' + maj + ' per JavaHome — below the JDK 21 target', 'OCCAS 8.3 supports JDK 17 or 21; 21 unlocks Generational ZGC.');
		// Boot-blocker: -XX:+ZGenerational does not exist before JDK 21; an
		// unrecognized -XX flag makes the JVM refuse to start, so this server
		// will NOT come back from its next restart.
		if (maj > 1 && maj < 21 && raw.indexOf('-XX:+ZGenerational') >= 0) {
			critical('-XX:+ZGenerational requires JDK 21+, but JavaHome appears to be JDK ' + maj + ' — the JVM will refuse to start on its next restart', 'Remove the flag or move this server to JDK 21.');
		}
	});
	return issues;
}
