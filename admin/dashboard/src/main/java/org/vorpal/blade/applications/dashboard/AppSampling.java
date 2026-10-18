package org.vorpal.blade.applications.dashboard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Once a minute, reads every application's `Type=Metrics` MBean across the
/// domain and publishes what each did since the last look as
/// [BladeEventTypes#APPLICATION_SAMPLED], and every running server's SIP
/// counters and health as [BladeEventTypes#SERVER_SAMPLED]. The analytics
/// service stores both.
///
/// **What it makes visible.** `events.publish.failures` counts events an
/// application tried to send and the bus refused: each one is a fact the
/// analytics database will never have. Until now that count lived only in the
/// MBean, reset on every deploy, so a dropped hour could not be seen afterwards.
/// Beside it go the events that did go out and the INVITEs the application
/// received, so a report can set what the container delivered against what
/// analytics recorded.
///
/// **Every counter and timing an application declares rides along.** An
/// application that counts its own failures (a speech service that did not
/// answer, a media server that refused) gets their history with no plumbing of
/// its own: `counters` carries each one that moved, `timings` each histogram's
/// count and summed milliseconds for the minute.
///
/// **On the AdminServer, once.** The dashboard is deployed there only, and the
/// domain runtime federates every node's MBeans, so one reader covers the
/// domain and no two nodes report the same counters.
///
/// **A sample that cannot be published is not retried.** The deltas are
/// computed from running totals, so the next sample that does go out still
/// carries no gap in the totals, only in when they were counted.
final class AppSampling implements Runnable {

	private static final Logger logger = Logger.getLogger(AppSampling.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final String EXECUTOR = "java:comp/DefaultManagedScheduledExecutorService";
	private static final String METRICS = "vorpal.blade:Type=Metrics,*";
	/// FSMAR's routing counters, one per node that runs the App Router.
	private static final String FSMAR = "org.vorpal.blade:type=Fsmar3,*";
	static final long PERIOD_SECONDS = 60;

	// Totals are keyed by kind: the four flattened facts by their payload name,
	// every declared counter under "c:", every histogram's count and summed
	// milliseconds under "t:" ... "#count" / "#sum".
	private static final String COUNTER = "c:";
	private static final String TIMING = "t:";
	private static final String COUNT = "#count";
	private static final String SUM = AppSampler.SUM;

	/// The counters the sample already carries as named fields, kept out of
	/// its `counters` list so they are not stored twice.
	private static final java.util.Set<String> FLATTENED = new java.util.HashSet<>(java.util.Arrays.asList(
			COUNTER + "events.published", COUNTER + "events.publish.failures", COUNTER + "sip.requests.INVITE",
			COUNTER + "sip.errors"));

	private final AppSampler applications = new AppSampler();
	/// The dashboard's current settings, read on each pass.
	private final java.util.function.Supplier<DashboardSettings> settings;

	AppSampling(java.util.function.Supplier<DashboardSettings> settings) {
		this.settings = settings;
	}

	private final AppSampler servers = new AppSampler();

	/// The held-session count each application last published, so the minute
	/// it falls to zero is published too; otherwise the last positive count
	/// would stand as the latest and read as a leak that has cleared.
	private final Map<String, Long> heldPublished = new HashMap<>();

	/// Schedule the sampling. Returns null, logged, when the container offers no
	/// managed scheduler; the dashboard runs without it.
	static ScheduledFuture<?> start(java.util.function.Supplier<DashboardSettings> settings) {
		try {
			ManagedScheduledExecutorService executor = (ManagedScheduledExecutorService) new InitialContext()
					.lookup(EXECUTOR);
			return executor.scheduleAtFixedRate(new AppSampling(settings), PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			logger.log(Level.WARNING, "application sampling not started: " + e);
			return null;
		}
	}

	@Override
	public void run() {
		// Never throws: an exception out of a periodic task cancels every later run.
		try {
			MBeanServer dr = (MBeanServer) new InitialContext().lookup(DOMAIN_RUNTIME);
			long now = System.currentTimeMillis();
			Map<String, Long> live = liveAppSessions(dr);
			for (ObjectName on : dr.queryNames(new ObjectName(METRICS), null)) {
				sample(dr, on, now, live);
			}
			sampleServers(dr, now);
			DashboardSettings settings = this.settings == null ? null : this.settings.get();
			Certificates.refresh(dr, settings == null ? 30 : settings.getCertificateWarningDays(), now);
			parked(dr);
			StuckThreads.check(dr);
		} catch (Throwable t) {
			logger.log(Level.WARNING, "application sampling pass failed", t);
		}
	}

	/// Whether this dashboard raised the parked-events problem and has not
	/// cleared it.
	private boolean parkedRaised;

	/// Events parked on the bus's error queue reached no report: raised as a
	/// problem while any are there, so Issues shows it and [Notifier] sends it,
	/// and cleared when the queue is empty (Servers › Bus deletes them).
	private void parked(MBeanServer dr) {
		long n = 0;
		try {
			for (ObjectName q : BusServlet.errorQueues(dr)) {
				Object c = HealthServlet.get(dr, q, "MessagesCurrentCount");
				n += c instanceof Number ? ((Number) c).longValue() : 0;
			}
		} catch (Exception e) {
			return;
		}
		if (n > 0 && !parkedRaised) {
			parkedRaised = true;
			org.vorpal.blade.framework.v3.events.Problems.raise("bus:parked", n + (n == 1 ? " event" : " events")
					+ " parked on the bus's error queue: a subscriber failed them, and they reached no report",
					"/blade/dashboard/");
		} else if (n == 0 && parkedRaised) {
			parkedRaised = false;
			org.vorpal.blade.framework.v3.events.Problems.clear("bus:parked");
		}
	}

	// ── applications ──────────────────────────────────────────────────────────

	private void sample(MBeanServer dr, ObjectName on, long now, Map<String, Long> live) {
		try {
			JsonNode report = MAPPER.readTree((String) dr.getAttribute(on, "ReportJson"));
			if (report == null || report.has("error")) {
				return;
			}
			if (!report.has("uptimeMillis")) {
				return; // a report without its uptime cannot tell a restart from a reset; skip the look
			}
			String app = report.path("app").asText(on.getKeyProperty("Name"));
			String node = report.path("node").asText(on.getKeyProperty("Location"));
			String cluster = report.path("cluster").isTextual() ? report.path("cluster").asText() : null;
			AppSampler.Delta d = applications.observe(app, node, cluster, now, report.path("uptimeMillis").asLong(),
					totals(report));
			// Sessions held through a quiet minute are the leak this is here to
			// see, so they make the sample worth publishing on their own.
			Long held = live.get(node + '/' + app);
			String key = node + '/' + app;
			long before = heldPublished.getOrDefault(key, 0L);
			boolean holding = held != null && (held > 0 || before > 0);
			if (d != null && (!d.quiet() || holding) && publish(d, held)) {
				heldPublished.put(key, held == null ? 0L : held);
			}
		} catch (Throwable t) {
			logger.log(Level.WARNING, "could not sample " + on, t);
		}
	}

	/// Every running total in one application's report, keyed as the class
	/// comment describes.
	static Map<String, Long> totals(JsonNode report) {
		Map<String, Long> totals = new LinkedHashMap<>();
		totals.put("published", counter(report, "events.published", null));
		totals.put("failures", counter(report, "events.publish.failures", null));
		totals.put("invites", counter(report, "sip.requests", "INVITE"));
		totals.put("sipErrors", counter(report, "sip.errors", null));
		for (Iterator<JsonNode> it = report.path("counters").elements(); it.hasNext();) {
			JsonNode c = it.next();
			String name = c.path("name").asText();
			JsonNode values = c.path("values");
			if (values.isObject() && values.size() > 0) {
				for (Iterator<Map.Entry<String, JsonNode>> v = values.fields(); v.hasNext();) {
					Map.Entry<String, JsonNode> e = v.next();
					totals.put(COUNTER + name + '.' + e.getKey(), e.getValue().asLong());
				}
			} else {
				totals.put(COUNTER + name, c.path("total").asLong());
			}
		}
		for (Iterator<JsonNode> it = report.path("histograms").elements(); it.hasNext();) {
			JsonNode h = it.next();
			long count = h.path("count").asLong();
			totals.put(TIMING + h.path("name").asText() + COUNT, count);
			// The exact sum where the application's framework reports one; an
			// older one reports only the whole-millisecond mean, and count times
			// mean is right to a millisecond per recording (see AppSampler#SUM).
			totals.put(TIMING + h.path("name").asText() + SUM, h.has("sumMillis") ? h.path("sumMillis").asLong()
					: count * h.path("meanMillis").asLong());
		}
		return totals;
	}

	/// A counter's total, or one labelled value of it; 0 when the application
	/// does not declare it (an admin application counts no SIP).
	static long counter(JsonNode report, String name, String value) {
		for (Iterator<JsonNode> it = report.path("counters").elements(); it.hasNext();) {
			JsonNode c = it.next();
			if (name.equals(c.path("name").asText())) {
				return (value == null) ? c.path("total").asLong() : c.path("values").path(value).asLong();
			}
		}
		return 0;
	}

	/// Application sessions each SIP application holds right now, by
	/// `node/application`, from the container's own `SipApplicationRuntime`.
	/// Its `Name` is `<server>_/<context root>`, and a SIP application is named
	/// for its context root.
	private static Map<String, Long> liveAppSessions(MBeanServer dr) {
		Map<String, Long> live = new HashMap<>();
		try {
			for (ObjectName on : dr.queryNames(new ObjectName("com.bea:Type=SipApplicationRuntime,*"), null)) {
				String name = on.getKeyProperty("Name");
				int slash = (name == null) ? -1 : name.indexOf("_/");
				if (slash > 0) {
					live.put(name.substring(0, slash) + '/' + name.substring(slash + 2),
							Math.max(0, HealthServlet.lng(dr, on, "ActiveAppSessionCount")));
				}
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "could not read SIP application runtimes", e);
		}
		return live;
	}

	private static boolean publish(AppSampler.Delta d, Long held) {
		return Events.publish((Long) null, null, BladeEventTypes.APPLICATION_SAMPLED, data -> fill(data, d, held));
	}

	/// The payload of one application sample. Package-private for the test.
	static void fill(ObjectNode data, AppSampler.Delta d, Long held) {
		data.put("app", d.app);
		data.put("node", d.node);
		if (d.cluster != null) {
			data.put("cluster", d.cluster);
		}
		data.put("seconds", d.seconds);
		data.put("published", d.count("published"));
		data.put("failures", d.count("failures"));
		data.put("invites", d.count("invites"));
		data.put("sipErrors", d.count("sipErrors"));
		data.put("restarted", d.restarted);
		if (held != null) {
			data.put("activeAppSessions", held);
		}
		ArrayNode counters = data.putArray("counters");
		ArrayNode timings = data.putArray("timings");
		for (Map.Entry<String, Long> e : d.counts.entrySet()) {
			String key = e.getKey();
			if (key.startsWith(COUNTER) && e.getValue() != 0 && !FLATTENED.contains(key)) {
				counters.addObject().put("name", key.substring(COUNTER.length())).put("delta", e.getValue());
			} else if (key.startsWith(TIMING) && key.endsWith(COUNT) && e.getValue() > 0) {
				String name = key.substring(TIMING.length(), key.length() - COUNT.length());
				timings.addObject().put("name", name).put("count", e.getValue()).put("sumMillis",
						Math.max(0, d.count(TIMING + name + SUM)));
			}
		}
		if (counters.size() == 0) {
			data.remove("counters");
		}
		if (timings.size() == 0) {
			data.remove("timings");
		}
	}

	// ── servers ───────────────────────────────────────────────────────────────

	/// One sample per running server: what its SIP container received and
	/// refused since the last look, what the App Router did with it, and how
	/// the server stood at this look.
	///
	/// **`rejected` is the count nothing else records.** A message the container
	/// refuses under overload never reaches an application, so no application
	/// event, no call row and no application counter knows it existed. The
	/// container's own `MessagesRejected` is the only witness.
	///
	/// Published every minute, quiet or not: there are few servers, and their
	/// heap and thread readings are what a later look at a bad hour needs.
	private void sampleServers(MBeanServer dr, long now) throws Exception {
		String admin = HealthServlet.adminName(dr);
		Map<String, ObjectName> sipByServer = new HashMap<>();
		for (ObjectName on : dr.queryNames(new ObjectName("com.bea:Type=SipServerRuntime,*"), null)) {
			sipByServer.put(HealthServlet.serverOf(on, admin), on);
		}
		Map<String, ObjectName> routerByServer = new HashMap<>();
		for (ObjectName on : dr.queryNames(new ObjectName(FSMAR), null)) {
			routerByServer.put(HealthServlet.serverOf(on, admin), on);
		}
		for (ObjectName sr : (ObjectName[]) dr.getAttribute(new ObjectName(HealthServlet.DRS), "ServerRuntimes")) {
			try {
				String name = HealthServlet.str(dr, sr, "Name");
				if (name == null) {
					continue;
				}
				ObjectName sip = sipByServer.get(name);
				ObjectName router = routerByServer.get(name);
				long started = HealthServlet.lng(dr, sr, "ActivationTime");
				if (started <= 0) {
					continue; // no start time, no way to tell a restart: skip the look
				}
				Map<String, Long> totals = new LinkedHashMap<>();
				totals.put("received", reading(dr, sip, "MessagesReceived"));
				totals.put("rejected", reading(dr, sip, "MessagesRejected"));
				totals.put("appSessionsCreated", reading(dr, sip, "ServerAppSessionCount"));
				totals.put("routed", reading(dr, router, "RequestsRouted"));
				totals.put("routingFallbacks", reading(dr, router, "DefaultApplicationFallbacks"));
				totals.put("undeployedBypasses", reading(dr, router, "UndeployedBypasses"));
				totals.put("routingCycles", reading(dr, router, "RoutingCyclesDetected"));
				if (totals.containsValue(-1L)) {
					// A read failed. Recorded as zero it would look like a restart, and the
					// next good read would publish the server's lifetime as one minute.
					logger.fine("server sample skipped, a read failed: " + name);
					continue;
				}
				Long cpu = cpuPct(dr, name, now);
				AppSampler.Delta d = servers.observe("", name, null, now, now - started, totals);
				if (d != null) {
					publishServer(dr, sr, sip, router != null, d, cpu);
				}
			} catch (Throwable t) {
				logger.log(Level.WARNING, "could not sample server " + sr, t);
			}
		}
	}

	/// The CPU this server's JVM used since the last look, as a percentage of
	/// all its processors: the change in `ProcessCpuTime` over the wall time
	/// between looks. An average over the minute, where the instant
	/// `ProcessCpuLoad` is one moment's reading and often 0 or 1 on the first
	/// call. Null on the first look, after a restart (the counter went back),
	/// or when the server's `java.lang` MBean is not reachable.
	///
	/// The JVM's platform MBeans are federated into the domain runtime as
	/// `java.lang:Location=<server>,type=OperatingSystem`.
	private Long cpuPct(MBeanServer dr, String server, long now) {
		try {
			ObjectName os = new ObjectName("java.lang:Location=" + server + ",type=OperatingSystem");
			long cpuNs = HealthServlet.lng(dr, os, "ProcessCpuTime");
			long processors = HealthServlet.lng(dr, os, "AvailableProcessors");
			if (cpuNs < 0 || processors <= 0) {
				cpuLast.remove(server);
				return null;
			}
			long[] last = cpuLast.put(server, new long[] { cpuNs, now });
			if (last == null || cpuNs < last[0] || now <= last[1]) {
				return null;
			}
			double usedMs = (cpuNs - last[0]) / 1_000_000.0;
			return Math.round(Math.min(100.0, 100.0 * usedMs / ((now - last[1]) * (double) processors)));
		} catch (Exception e) {
			return null;
		}
	}

	/// Server name to its last `ProcessCpuTime` (ns) and when it was read (ms).
	private final Map<String, long[]> cpuLast = new java.util.concurrent.ConcurrentHashMap<>();

	/// A counter's reading: 0 when the MBean is absent (the server has no such
	/// thing), -1 when it is there and the read failed.
	private static long reading(MBeanServer dr, ObjectName on, String attribute) {
		return (on == null) ? 0 : HealthServlet.lng(dr, on, attribute);
	}

	private static boolean publishServer(MBeanServer dr, ObjectName sr, ObjectName sip, boolean routes,
			AppSampler.Delta d, Long cpuPct) {
		ObjectName cr = HealthServlet.obj(dr, sr, "ClusterRuntime");
		ObjectName jvm = HealthServlet.obj(dr, sr, "JVMRuntime");
		ObjectName tp = HealthServlet.obj(dr, sr, "ThreadPoolRuntime");
		return Events.publish((Long) null, null, BladeEventTypes.SERVER_SAMPLED, data -> {
			data.put("node", d.node);
			if (cr != null) {
				data.put("cluster", HealthServlet.str(dr, cr, "Name"));
			}
			data.put("state", HealthServlet.str(dr, sr, "State"));
			data.put("seconds", d.seconds);
			data.put("restarted", d.restarted);
			data.put("received", d.count("received"));
			data.put("rejected", d.count("rejected"));
			data.put("appSessionsCreated", d.count("appSessionsCreated"));
			// The App Router's view of the same minute: initial requests it routed,
			// how many no rule matched (the default application took them), and how
			// many it routed PAST an application that was not deployed, which a
			// caller notices and nothing else logs as an error.
			if (routes) {
				data.put("routed", d.count("routed"));
				data.put("routingFallbacks", d.count("routingFallbacks"));
				data.put("undeployedBypasses", d.count("undeployedBypasses"));
				data.put("routingCycles", d.count("routingCycles"));
			}
			putReading(data, "activeAppSessions", reading(dr, sip, "ActiveServerAppSessionCount"), sip != null);
			putReading(data, "activeSipSessions", reading(dr, sip, "ActiveServerSipSessionCount"), sip != null);
			long free = (jvm == null) ? -1 : HealthServlet.lng(dr, jvm, "HeapFreePercent");
			putReading(data, "heapUsedPct", 100 - free, free >= 0);
			putReading(data, "cpuPct", cpuPct == null ? -1 : cpuPct, cpuPct != null);
			putReading(data, "threadsStuck", HealthServlet.lng(dr, tp, "StuckThreadCount"), tp != null);
			putReading(data, "threadsHogging", HealthServlet.lng(dr, tp, "HoggingThreadCount"), tp != null);
			putReading(data, "threadsPending", HealthServlet.lng(dr, tp, "PendingUserRequestCount"), tp != null);
			// What redeploys leave behind: classes loaded again, and old copies a
			// restart alone frees. Heap under G1 reads high either way.
			long metaspace = metaspaceBytes(dr, d.node);
			putReading(data, "metaspaceMb", metaspace < 0 ? -1 : metaspace / 1048576, metaspace >= 0);
			long classes = classesLoaded(dr, d.node);
			putReading(data, "classesLoaded", classes, classes >= 0);
		});
	}

	/// A server's metaspace in use, through the domain runtime's view of its
	/// JVM, or -1.
	static long metaspaceBytes(MBeanServer dr, String node) {
		try {
			Object usage = dr.getAttribute(
					new ObjectName("java.lang:Location=" + node + ",name=Metaspace,type=MemoryPool"), "Usage");
			return ((Number) ((javax.management.openmbean.CompositeData) usage).get("used")).longValue();
		} catch (Exception e) {
			return -1;
		}
	}

	/// The classes a server's JVM has loaded now, or -1.
	static long classesLoaded(MBeanServer dr, String node) {
		try {
			return ((Number) dr.getAttribute(new ObjectName("java.lang:Location=" + node + ",type=ClassLoading"),
					"LoadedClassCount")).longValue();
		} catch (Exception e) {
			return -1;
		}
	}

	/// Omits a reading the server does not have, rather than recording a zero
	/// it never reported (the AdminServer has no SIP container).
	private static void putReading(ObjectNode data, String name, long value, boolean present) {
		if (present && value >= 0) {
			data.put(name, value);
		}
	}
}
