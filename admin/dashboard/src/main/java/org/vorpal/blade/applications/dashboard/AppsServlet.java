package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Deployment health, read live from the domain runtime: every application's
/// state on every running server (ACTIVE, or stuck PREPARED, or FAILED), the
/// framework each was built with, and each server's metaspace and classes.
///
/// URL: `/blade/dashboard/apps`.
///
/// **Why metaspace.** Every redeploy loads an application's classes again, and
/// a copy an old deployment still holds is freed only by a restart. Heap reads
/// high under G1 whether or not anything leaks; metaspace grows by the
/// redeploys and stays. The baseline is the server's first minute sample that
/// recorded metaspace after it started ([AnalyticsReports#firstMetaspaceMb]); without one (no database,
/// or a server started before samples carried metaspace), the size the
/// dashboard first saw, which can only under-state the growth.
///
/// **Why the ceiling.** A server started with `-XX:MaxMetaspaceSize` (the
/// installer sets one on engines with the SIP annotation scan off) stops loading
/// classes when it reaches it, and deploys and applications fail with
/// `OutOfMemoryError: Metaspace`. Past `metaspaceCeilingPercent` of it, a restart
/// is recommended whatever the growth says.
@WebServlet("/apps")
public class AppsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(AppsServlet.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// Metaspace bytes first seen per `server|activationTime`.
	static final Map<String, Long> BASELINE = new ConcurrentHashMap<>();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		DashboardSettings settings = DashboardSettingsStartup.settings(getServletContext());
		int growth = settings == null ? 25 : settings.getMetaspaceGrowthPercent();
		int ceiling = settings == null ? 75 : settings.getMetaspaceCeilingPercent();
		ObjectNode out = MAPPER.createObjectNode();
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			AnalyticsReports reports = null;
			try {
				Object store = getServletContext().getAttribute(AnalyticsStore.ATTR);
				reports = store == null ? null
						: ((AnalyticsStore) store).reports(settings == null ? "jdbc/BladeAnalytics"
								: settings.getAnalyticsDataSource());
			} catch (RuntimeException noDatabase) {
				logger.log(Level.FINE, "no analytics database for the metaspace baseline", noDatabase);
			}
			java.util.List<String> running = servers(dr, out.putArray("servers"), growth, ceiling, reports);
			apps(dr, running, out.putArray("apps"));
		} catch (Exception e) {
			logger.log(Level.FINE, "apps read failed", e);
			out.put("error", "the domain runtime could not be read: " + e);
		}
		if (Certificates.readAt == 0) {
			try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
				Certificates.refresh((MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime"),
						settings == null ? 30 : settings.getCertificateWarningDays(), System.currentTimeMillis());
			} catch (Exception e) {
				logger.log(Level.FINE, "certificates unread", e);
			}
		}
		out.set("certificates", MAPPER.valueToTree(Certificates.latest));
		out.put("certificatesReadAt", Certificates.readAt);
		out.put("certificateWarningDays", settings == null ? 30 : settings.getCertificateWarningDays());
		out.put("growthPercent", growth);
		out.put("ceilingPercent", ceiling);
		out.put("asOf", System.currentTimeMillis());
		MAPPER.writeValue(resp.getWriter(), out);
	}

	/// Each running server's metaspace and classes; returns their names.
	static java.util.List<String> servers(MBeanServer dr, ArrayNode out, int growth, int ceiling,
			AnalyticsReports reports) throws Exception {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (ObjectName sr : dr.queryNames(new ObjectName("com.bea:Type=ServerRuntime,*"), null)) {
			String name = HealthServlet.str(dr, sr, "Name");
			if (name == null) {
				continue;
			}
			names.add(name);
			ObjectNode s = out.addObject();
			s.put("server", name);
			Object started = HealthServlet.get(dr, sr, "ActivationTime");
			long at = started instanceof Number ? ((Number) started).longValue() : 0;
			if (at > 0) {
				s.put("startedAt", at);
			}
			Object usage = HealthServlet.get(dr,
					new ObjectName("java.lang:Location=" + name + ",name=Metaspace,type=MemoryPool"), "Usage");
			if (usage instanceof CompositeData) {
				long used = ((Number) ((CompositeData) usage).get("used")).longValue();
				Long sampled = null;
				if (reports != null && at > 0) {
					try {
						sampled = reports.firstMetaspaceMb(name,
								org.vorpal.blade.framework.v2.config.SettingsManager.getDomainName(),
								new java.util.Date(at));
					} catch (RuntimeException unreadable) {
						logger.log(Level.FINE, "metaspace baseline unreadable", unreadable);
					}
				}
				long base = sampled != null ? sampled * 1048576L : BASELINE.computeIfAbsent(name + "|" + at, k -> used);
				s.put("baselineFrom", sampled != null ? "sample" : "first seen");
				s.put("metaspace", used);
				s.put("metaspaceFirstSeen", base);
				// -1 (undefined) when the JVM has no -XX:MaxMetaspaceSize.
				long max = ((Number) ((CompositeData) usage).get("max")).longValue();
				boolean grown = used > base * (100L + growth) / 100L;
				boolean full = max > 0 && used >= max * ceiling / 100L;
				if (max > 0) {
					s.put("metaspaceMax", max);
				}
				s.put("nearCeiling", full);
				s.put("restart", grown || full);
			}
			ObjectName cl = new ObjectName("java.lang:Location=" + name + ",type=ClassLoading");
			Object loaded = HealthServlet.get(dr, cl, "LoadedClassCount");
			Object unloaded = HealthServlet.get(dr, cl, "UnloadedClassCount");
			if (loaded instanceof Number) {
				s.put("classesLoaded", ((Number) loaded).longValue());
			}
			if (unloaded instanceof Number) {
				s.put("classesUnloaded", ((Number) unloaded).longValue());
			}
		}
		java.util.Collections.sort(names);
		return names;
	}

	/// Each application's state on each running server it is deployed to,
	/// with the framework its `Metrics` MBean reports. Shared libraries are
	/// left out.
	static void apps(MBeanServer dr, java.util.List<String> running, ArrayNode out) throws Exception {
		ObjectName state = new ObjectName("com.bea:Name=AppRuntimeStateRuntime,Type=AppRuntimeStateRuntime");
		Map<String, String> framework = new java.util.HashMap<>();
		for (ObjectName m : dr.queryNames(new ObjectName("vorpal.blade:Type=Metrics,*"), null)) {
			Object v = HealthServlet.get(dr, m, "FrameworkVersion");
			framework.put(unquote(m.getKeyProperty("Name")) + "|" + m.getKeyProperty("Location"),
					v == null ? null : String.valueOf(v));
		}
		Map<String, String> config = configs(dr);
		String[] ids = (String[]) dr.getAttribute(state, "ApplicationIds");
		java.util.Arrays.sort(ids);
		for (String id : ids) {
			if (id.contains("#")) {
				continue;
			}
			for (String server : running) {
				Object st;
				try {
					st = dr.invoke(state, "getCurrentState", new Object[] { id, server },
							new String[] { String.class.getName(), String.class.getName() });
				} catch (Exception notThere) {
					continue;
				}
				if (st == null) {
					continue;
				}
				ObjectNode a = out.addObject();
				a.put("app", id);
				a.put("server", server);
				a.put("state", String.valueOf(st).replaceFirst("^STATE_", ""));
				String key = id + "|" + server;
				if (framework.containsKey(key)) {
					a.put("framework", framework.get(key));
				}
				if (config.containsKey(key)) {
					a.put("config", config.get(key));
				}
			}
		}
	}

	/// Each application's configuration on each server against the file the
	/// AdminServer holds ([ConfigDrift]), by `name|server`: `matches`,
	/// `differs at <path>`, `does not know <path>`, or `overrides, not compared` when a cluster or
	/// server file is merged over the domain's. No entry: no file, or the
	/// running configuration could not be read.
	static Map<String, String> configs(MBeanServer dr) throws Exception {
		Map<String, String> out = new java.util.HashMap<>();
		java.nio.file.Path base = java.nio.file.Paths.get("config/custom/vorpal");
		for (ObjectName on : dr.queryNames(new ObjectName("vorpal.blade:Type=Configuration,*"), null)) {
			String name = unquote(on.getKeyProperty("Name")), server = on.getKeyProperty("Location");
			String cluster = unquote(on.getKeyProperty("Cluster"));
			if (name == null || server == null) {
				continue;
			}
			java.nio.file.Path file = base.resolve(name + ".json");
			if (!java.nio.file.Files.isRegularFile(file)) {
				continue;
			}
			if (java.nio.file.Files.exists(base.resolve("_servers").resolve(server).resolve(name + ".json"))
					|| (cluster != null && java.nio.file.Files.exists(base.resolve("_clusters").resolve(cluster)
							.resolve(name + ".json")))) {
				out.put(name + "|" + server, "overrides, not compared");
				continue;
			}
			try {
				Object running = HealthServlet.get(dr, on, "CurrentJson");
				if (!(running instanceof String)) {
					continue;
				}
				String at = ConfigDrift.firstDifference(MAPPER.readTree(file.toFile()), MAPPER.readTree((String) running));
				out.put(name + "|" + server, at == null ? "matches" : at);
			} catch (Exception unreadable) {
				logger.log(Level.FINE, "configuration of " + name + " on " + server + " unreadable", unreadable);
			}
		}
		return out;
	}

	/// A key property as written: quoted only when its value needed it.
	static String unquote(String v) {
		return v != null && v.startsWith("\"") ? ObjectName.unquote(v) : v;
	}
}
