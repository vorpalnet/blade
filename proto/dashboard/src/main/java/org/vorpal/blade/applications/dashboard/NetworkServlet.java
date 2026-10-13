package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The network map's data ([NetworkMap]): every site ([NetworkSite]) with what
/// is known about the equipment there.
///
/// This servlet only gathers: every engine's balancer health view from the
/// `EndpointHealth` MBeans and the gateways failing to register ([OpsFeed]),
/// read live on each request, and the calls per origin and the refused legs
/// from the analytics database, reused for [#ANALYTICS_TTL_MS].
///
/// Platform data: the viewer needs a grant on this domain's platform, and the
/// counts are this domain's calls whatever tenant they belong to, since they
/// say which equipment works, not who called. URL: `/blade/dashboard/network`.
@WebServlet("/network")
public class NetworkServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(NetworkServlet.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static final String BALANCER_HEALTH = "vorpal.blade:Name=proxy-balancer,Type=EndpointHealth,*";

	/// How long the database half is reused. The live half (balancer, trunks)
	/// is read on every request.
	static final long ANALYTICS_TTL_MS = 30_000L;

	private volatile long analyticsAt;
	private volatile AnalyticsReports.Table origins;
	private volatile AnalyticsReports.Table failures;
	private volatile String analyticsError;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		DashboardSettings settings = DashboardSettingsStartup.settings(getServletContext());
		List<NetworkSite> sites = settings == null ? new ArrayList<>() : settings.getSites();
		int window = settings == null ? 15 : settings.getNetworkWindowMinutes();
		refreshAnalytics(settings, sites, window);

		ObjectNode out;
		JsonNode bus = null;
		List<JsonNode> remotes = new ArrayList<>();
		Map<String, String> datasourceHosts = new java.util.LinkedHashMap<>();
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			bus = bus(dr);
			datasourceHosts = datasourceHosts(dr);
			remotes = remotes(dr);
		} catch (Exception e) {
			logger.log(Level.FINE, "bus or datasource read failed", e);
		}
		out = NetworkMap.assemble(sites, balancerViews(), OpsFeed.FEED.snapshot().path("trunksFailing"), origins,
				failures, window, settings == null ? 3 : settings.getNetworkFailingCalls(), datasourceHosts, bus, remotes);
		out.put("domain", SettingsManager.getDomainName());
		if (analyticsError != null) {
			out.put("analyticsError", analyticsError);
		}
		out.put("asOf", System.currentTimeMillis());
		resp.getWriter().write(out.toString());
	}

	/// The event bus as the broker sees it: each durable subscription (its
	/// name before the `@` WebLogic appends, the server hosting it, whether a
	/// consumer is attached, the messages waiting for it), the messages parked
	/// on the error queue, and the consumers on the topic. Read from the JMS
	/// runtime, so it is true whatever the subscribing application believes.
	static JsonNode bus(MBeanServer dr) throws Exception {
		ObjectNode bus = MAPPER.createObjectNode();
		com.fasterxml.jackson.databind.node.ArrayNode subs = bus.putArray("subscriptions");
		for (ObjectName on : dr.queryNames(new ObjectName("com.bea:Type=JMSDurableSubscriberRuntime,*"), null)) {
			String name = String.valueOf(HealthServlet.get(dr, on, "SubscriptionName"));
			int at = name.indexOf('@');
			ObjectNode sub = subs.addObject();
			sub.put("name", at > 0 ? name.substring(0, at) : name);
			sub.put("server", on.getKeyProperty("Location"));
			sub.put("active", Boolean.TRUE.equals(HealthServlet.get(dr, on, "Active")));
			Object waiting = HealthServlet.get(dr, on, "MessagesCurrentCount");
			sub.put("waiting", waiting instanceof Number ? ((Number) waiting).longValue() : 0L);
		}
		long parked = 0, consumers = 0;
		boolean topic = false;
		for (ObjectName on : dr.queryNames(new ObjectName("com.bea:Type=JMSDestinationRuntime,*"), null)) {
			String name = String.valueOf(on.getKeyProperty("Name"));
			if (name.contains("BladeEventBusErrorQueue")) {
				Object n = HealthServlet.get(dr, on, "MessagesCurrentCount");
				parked += n instanceof Number ? ((Number) n).longValue() : 0;
			} else if (name.contains("BladeEventBusTopic")) {
				topic = true;
				Object n = HealthServlet.get(dr, on, "ConsumersCurrentCount");
				consumers += n instanceof Number ? ((Number) n).longValue() : 0;
			}
		}
		bus.put("errorQueue", parked);
		if (topic) {
			bus.put("topicConsumers", consumers);
		}
		return bus;
	}

	/// Every application's view of the services it calls (the framework's
	/// `Remotes` MBean, one per application per server), each remote tagged
	/// with its `application` and `server`. The network agent serves the
	/// platform's connections (`"platform":true`) through every application's
	/// MBean on a server; they are kept once per server, as `platform`'s.
	static List<JsonNode> remotes(MBeanServer dr) throws Exception {
		List<JsonNode> out = new ArrayList<>();
		java.util.Set<String> platform = new java.util.HashSet<>();
		for (ObjectName on : dr.queryNames(new ObjectName("vorpal.blade:Type=Remotes,*"), null)) {
			Object json = HealthServlet.get(dr, on, "RemotesJson");
			if (!(json instanceof String)) {
				continue;
			}
			String server = on.getKeyProperty("Location");
			String app = ObjectName.unquote(on.getKeyProperty("Name"));
			for (JsonNode r : MAPPER.readTree((String) json)) {
				if (r.path("platform").asBoolean()
						&& !platform.add(server + "|" + r.path("kind").asText() + "|" + r.path("remote").asText())) {
					continue;
				}
				((ObjectNode) r).put("application", r.path("platform").asBoolean() ? "platform" : app).put("server",
						server);
				out.add(r);
			}
		}
		return out;
	}

	/// Each JDBC datasource the domain defines, by name, and the database host
	/// its URL names (null for a TNS alias).
	static Map<String, String> datasourceHosts(MBeanServer dr) throws Exception {
		Map<String, String> hosts = new java.util.LinkedHashMap<>();
		ObjectName config = HealthServlet.obj(dr, new ObjectName(HealthServlet.DRS), "DomainConfiguration");
		Object resources = config == null ? null : dr.getAttribute(config, "JDBCSystemResources");
		if (resources instanceof ObjectName[]) {
			for (ObjectName r : (ObjectName[]) resources) {
				ObjectName resource = HealthServlet.obj(dr, r, "JDBCResource");
				ObjectName params = resource == null ? null : HealthServlet.obj(dr, resource, "JDBCDriverParams");
				String url = params == null ? null : HealthServlet.str(dr, params, "Url");
				hosts.put(HealthServlet.str(dr, r, "Name"), SiteAddresses.jdbcHost(url));
			}
		}
		return hosts;
	}

	/// Each engine's balancer health view: `{"server", "health"}`.
	private static List<JsonNode> balancerViews() {
		List<JsonNode> views = new ArrayList<>();
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			for (ObjectName on : dr.queryNames(new ObjectName(BALANCER_HEALTH), null)) {
				Object json = HealthServlet.get(dr, on, "HealthJson");
				if (json instanceof String) {
					ObjectNode view = MAPPER.createObjectNode();
					String location = on.getKeyProperty("Location");
					view.put("server", location == null ? "local" : location);
					view.set("health", MAPPER.readTree((String) json));
					views.add(view);
				}
			}
		} catch (Exception e) {
			logger.log(Level.FINE, "balancer health read failed", e);
		}
		return views;
	}

	/// Calls per origin over the longest `quietMinutes` of any site (a day at
	/// least), and the legs refused in the last `window` minutes, this domain's
	/// only.
	private void refreshAnalytics(DashboardSettings settings, List<NetworkSite> sites, int window) {
		long now = System.currentTimeMillis();
		if (now - analyticsAt < ANALYTICS_TTL_MS) {
			return;
		}
		analyticsAt = now;
		AnalyticsStore store = (AnalyticsStore) getServletContext().getAttribute(AnalyticsStore.ATTR);
		if (store == null) {
			return;
		}
		try {
			AnalyticsReports reports = store.reports(
					settings == null ? "jdbc/BladeAnalytics" : settings.getAnalyticsDataSource());
			int quiet = sites.stream().filter(s -> s.getQuietMinutes() != null).mapToInt(NetworkSite::getQuietMinutes)
					.max().orElse(0);
			long lookBack = Math.max(24 * 60, quiet) * 60_000L;
			AnalyticsReports.Filter f = new AnalyticsReports.Filter(1, false, SettingsManager.getDomainName(),
					new Date(now), null);
			origins = reports.origins(f, new Date(now - lookBack));
			failures = reports.failures(f, new Date(now - window * 60_000L));
			analyticsError = null;
		} catch (RuntimeException e) {
			analyticsError = e.getClass().getSimpleName() + ": " + e.getMessage();
			logger.log(Level.FINE, "network analytics failed", e);
		}
	}
}
