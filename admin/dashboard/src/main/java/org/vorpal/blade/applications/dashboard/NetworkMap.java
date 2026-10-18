package org.vorpal.blade.applications.dashboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The network map's data, put together from what [NetworkServlet] gathers:
/// the configured sites, every engine's balancer health view, the gateways
/// failing to register, and the two analytics tables. Free of JNDI, JMX and
/// JPA, so it is tested directly.
///
/// For each site:
/// - `servers`: this domain's servers there, by name. The board already reads
///   their state live from `/health`, so it is not repeated here.
/// - `datasources`: this domain's connection pools whose database is there,
///   named in the site or matched by their JDBC URL's host; their state is
///   also read live from `/health`.
/// - `remotes`: the services applications call that are there (a REST
///   lookup, a token endpoint, a directory, a database by its datasource),
///   as each application on each server has found them.
/// - `bus` (a cluster site only): the event bus as the broker sees it, each
///   durable subscription's consumer and backlog, and the error queue.
/// - `endpoints`: the proxy balancer's endpoints at the site, each as every
///   engine sees it (its OPTIONS pings and 503s). An endpoint is at the site
///   its balancer `site` names, or whose URI matches the site's addresses.
/// - `trunks`: gateways whose registration with a registrar at the site is
///   failing now.
/// - `inbound` (an inbound site only): calls whose first INVITE came from the
///   site's addresses, and when the last one did.
/// - `failures`: legs a server at the site refused (`branchFailed`), matched
///   by the Request-URI sent or the address that answered, each with its
///   [SipStatus] kind and meaning. Refusals that are part of a working call
///   (a challenge, a cancel) are left out.
///
/// `unplaced` lists the origins, the remotes that have failed, the refusals per server (`server`, the
/// address that answered or the host it was sent to), and the balancer endpoints that no site
/// claims, so an unknown peer shows up instead of vanishing. A balancer site with coordinates that
/// no configured site names is added as a downstream site, so a site is
/// entered once.
final class NetworkMap {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private NetworkMap() {
	}

	/// @param origins  [AnalyticsReports#origins], or null when unavailable
	/// @param failures [AnalyticsReports#failures], or null when unavailable
	static ObjectNode assemble(List<NetworkSite> configured, List<JsonNode> balancer, JsonNode trunksFailing,
			AnalyticsReports.Table origins, AnalyticsReports.Table failures, int windowMinutes, int failingCalls) {
		return assemble(configured, balancer, trunksFailing, origins, failures, windowMinutes, failingCalls,
				java.util.Collections.emptyMap(), null, java.util.Collections.emptyList());
	}

	/// @param datasourceHosts each datasource's database host, from its JDBC
	///                        URL; a TNS alias has none
	/// @param bus             the event bus as the broker sees it
	///                        ([NetworkServlet]), attached to every cluster
	///                        site; null when unread
	/// @param remotes         every application's view of the services it calls
	///                        ([NetworkServlet#remotes])
	static ObjectNode assemble(List<NetworkSite> configured, List<JsonNode> balancer, JsonNode trunksFailing,
			AnalyticsReports.Table origins, AnalyticsReports.Table failures, int windowMinutes, int failingCalls,
			Map<String, String> datasourceHosts, JsonNode bus, List<JsonNode> remotes) {
		List<NetworkSite> sites = new ArrayList<>(configured);
		addBalancerSites(sites, balancer);

		ObjectNode out = MAPPER.createObjectNode();
		out.put("windowMinutes", windowMinutes);
		out.put("failingCalls", failingCalls);
		ArrayNode siteArray = out.putArray("sites");
		boolean[] originClaimed = new boolean[origins == null ? 0 : origins.rows.size()];
		boolean[] failureClaimed = new boolean[failures == null ? 0 : failures.rows.size()];
		boolean[] remoteClaimed = new boolean[remotes.size()];
		java.util.Set<String> endpointClaimed = new java.util.HashSet<>();
		for (NetworkSite site : sites) {
			ObjectNode s = siteArray.addObject();
			s.put("name", site.getName());
			s.put("city", site.getCity());
			if (site.getLat() != null && site.getLon() != null) {
				s.put("lat", site.getLat());
				s.put("lon", site.getLon());
			}
			s.set("roles", MAPPER.valueToTree(site.getRoles()));
			s.set("servers", MAPPER.valueToTree(site.getServers()));
			if (site.getQuietMinutes() != null) {
				s.put("quietMinutes", site.getQuietMinutes());
			}
			ArrayNode ds = s.putArray("datasources");
			for (String name : site.getDatasources()) {
				ds.add(name);
			}
			datasourceHosts.forEach((name, host) -> {
				if (host != null && !site.getDatasources().contains(name) && claims(site, host)) {
					ds.add(name);
				}
			});
			if (bus != null && site.has("cluster")) {
				s.set("bus", bus);
			}
			ArrayNode rs = s.putArray("remotes");
			for (int i = 0; i < remotes.size(); i++) {
				if (claimsRemote(site, remotes.get(i))) {
					remoteClaimed[i] = true;
					rs.add(remotes.get(i));
				}
			}
			endpoints(site, balancer, s.putArray("endpoints"), endpointClaimed);
			trunks(site, trunksFailing, s.putArray("trunks"));
			if (site.has("inbound") && origins != null) {
				inbound(site, origins, s.putObject("inbound"), originClaimed);
			}
			ArrayNode f = s.putArray("failures");
			if (failures != null) {
				for (int i = 0; i < failures.rows.size(); i++) {
					List<Object> r = failures.rows.get(i);
					if (claims(site, r.get(0)) || claims(site, r.get(1))) {
						failureClaimed[i] = true;
						if (!normal(r)) {
							f.add(failure(r));
						}
					}
				}
			}
		}
		ObjectNode unplaced = out.putObject("unplaced");
		unplacedEndpoints(balancer, endpointClaimed, unplaced.putArray("endpoints"));
		ArrayNode uo = unplaced.putArray("origins"), uf = unplaced.putArray("failures");
		for (int i = 0; i < originClaimed.length; i++) {
			if (!originClaimed[i]) {
				List<Object> r = origins.rows.get(i);
				uo.addObject().put("origin", String.valueOf(r.get(0))).put("calls", (Long) r.get(1)).put("last",
						time(r.get(2)));
			}
		}
		// Unclaimed refusals per server, not per call: one line says "192.0.2.1:
		// 3 x 408", however many numbers were dialled through it.
		Map<String, ObjectNode> byServer = new LinkedHashMap<>();
		for (int i = 0; i < failureClaimed.length; i++) {
			if (!failureClaimed[i] && !normal(failures.rows.get(i))) {
				ObjectNode f = failure(failures.rows.get(i));
				String server = f.hasNonNull("responder") ? f.path("responder").asText()
						: SiteAddresses.host(f.path("destination").asText(null));
				String key = server + "|" + f.path("status").asInt() + "|" + f.path("meaning").asText();
				ObjectNode g = byServer.get(key);
				if (g == null) {
					f.put("server", server);
					byServer.put(key, f);
				} else {
					g.put("calls", g.path("calls").asLong() + f.path("calls").asLong());
					g.put("last", Math.max(g.path("last").asLong(), f.path("last").asLong()));
				}
			}
		}
		byServer.values().forEach(uf::add);
		ArrayNode ur = unplaced.putArray("remotes");
		for (int i = 0; i < remotes.size(); i++) {
			if (!remoteClaimed[i] && remotes.get(i).path("failures").asLong() > 0) {
				ur.add(remotes.get(i));
			}
		}
		return out;
	}

	/// A database remote (`jdbc`) is a datasource's JNDI name, claimed by a
	/// site that names the datasource (`jdbc/BladeAnalytics` by
	/// `BladeAnalytics`); any other remote is a host, claimed by address.
	private static boolean claimsRemote(NetworkSite site, JsonNode remote) {
		String name = remote.path("remote").asText(null);
		if (name == null) {
			return false;
		}
		if ("jdbc".equals(remote.path("kind").asText())) {
			String tail = name.substring(name.lastIndexOf('/') + 1);
			return site.getDatasources().contains(name) || site.getDatasources().contains(tail);
		}
		return claims(site, name);
	}

	private static boolean claims(NetworkSite site, Object address) {
		return address != null && SiteAddresses.matches(site.getAddresses(), String.valueOf(address));
	}

	private static void addBalancerSites(List<NetworkSite> sites, List<JsonNode> balancer) {
		for (JsonNode view : balancer) {
			view.path("health").path("sites").fields().forEachRemaining(e -> {
				JsonNode b = e.getValue();
				boolean named = sites.stream().anyMatch(s -> e.getKey().equalsIgnoreCase(s.getName()));
				if (!named && b.hasNonNull("lat") && b.hasNonNull("lon")) {
					sites.add(new NetworkSite(e.getKey(), b.path("label").asText(e.getKey()), b.path("lat").asDouble(),
							b.path("lon").asDouble(), Arrays.asList("downstream")));
				}
			});
		}
	}

	private static void endpoints(NetworkSite site, List<JsonNode> balancer, ArrayNode out, java.util.Set<String> claimed) {
		for (JsonNode view : balancer) {
			Map<String, JsonNode> seen = new LinkedHashMap<>();
			eachEndpoint(view, ep -> {
				boolean here = site.getName() != null && site.getName().equalsIgnoreCase(ep.path("site").asText(null))
						|| claims(site, ep.path("uri").asText(null));
				if (here) {
					seen.putIfAbsent(ep.path("name").asText(), ep);
				}
			});
			seen.values().forEach(ep -> {
				claimed.add(ep.path("name").asText());
				endpoint(ep, view.path("server").asText(), out.addObject());
			});
		}
	}

	/// The endpoints no site claims, each as every engine sees it, the same
	/// shape as a site's.
	private static void unplacedEndpoints(List<JsonNode> balancer, java.util.Set<String> claimed, ArrayNode out) {
		for (JsonNode view : balancer) {
			Map<String, JsonNode> seen = new LinkedHashMap<>();
			eachEndpoint(view, ep -> {
				if (!claimed.contains(ep.path("name").asText())) {
					seen.putIfAbsent(ep.path("name").asText(), ep);
				}
			});
			seen.values().forEach(ep -> endpoint(ep, view.path("server").asText(), out.addObject()));
		}
	}

	private static void eachEndpoint(JsonNode view, java.util.function.Consumer<JsonNode> each) {
		view.path("health").path("plans").forEach(tiers -> tiers.forEach(tier -> tier.path("endpoints").forEach(each::accept)));
	}

	/// One engine's view of one endpoint: its pings, a refusal (`refused`, the
	/// 503 and its reason phrase) and its back-off (`downUntil`, epoch ms), and
	/// the calls it was offered on that engine.
	private static void endpoint(JsonNode ep, String node, ObjectNode e) {
		e.put("name", ep.path("name").asText());
		e.put("uri", ep.path("uri").asText(null));
		e.put("node", node);
		e.put("status", ep.path("status").asText("unknown"));
		e.put("routable", ep.path("routable").asBoolean(true));
		e.put("enabled", ep.path("enabled").asBoolean(true));
		if (ep.hasNonNull("note")) {
			e.put("note", ep.path("note").asText());
		}
		if (ep.hasNonNull("lastRttMs")) {
			e.put("rttMs", ep.path("lastRttMs").asLong());
		}
		if (ep.hasNonNull("refused")) {
			e.put("refused", ep.path("refused").asText());
		}
		if (ep.hasNonNull("downUntil")) {
			e.put("downUntil", ep.path("downUntil").asLong());
		}
		for (String counter : new String[] { "attempts", "successes", "failovers" }) {
			if (ep.hasNonNull(counter)) {
				e.put(counter, ep.path(counter).asLong());
			}
		}
	}

	private static void trunks(NetworkSite site, JsonNode trunksFailing, ArrayNode out) {
		if (trunksFailing == null) {
			return;
		}
		for (JsonNode t : trunksFailing) {
			if (claims(site, t.path("registrar").asText(null))) {
				out.add(t);
			}
		}
	}

	private static void inbound(NetworkSite site, AnalyticsReports.Table origins, ObjectNode in, boolean[] claimed) {
		long calls = 0, last = 0;
		for (int i = 0; i < origins.rows.size(); i++) {
			List<Object> r = origins.rows.get(i);
			if (claims(site, r.get(0))) {
				claimed[i] = true;
				calls += (Long) r.get(1);
				last = Math.max(last, time(r.get(2)));
			}
		}
		in.put("calls", calls);
		if (last > 0) {
			in.put("last", last);
		}
	}

	/// A refusal that is part of a call working as designed (a challenge, a
	/// cancel), which the map leaves out.
	private static boolean normal(List<Object> r) {
		return r.get(2) == null
				|| !Boolean.TRUE.equals(r.get(3)) && SipStatus.kind(((Long) r.get(2)).intValue()) == SipStatus.Kind.NORMAL;
	}

	private static ObjectNode failure(List<Object> r) {
		ObjectNode o = MAPPER.createObjectNode();
		o.put("destination", r.get(0) == null ? null : String.valueOf(r.get(0)));
		o.put("responder", r.get(1) == null ? null : String.valueOf(r.get(1)));
		int status = ((Long) r.get(2)).intValue();
		boolean notSent = Boolean.TRUE.equals(r.get(3));
		o.put("status", status);
		o.put("kind", notSent ? "failing" : SipStatus.kind(status).name().toLowerCase(Locale.ROOT));
		o.put("meaning", notSent ? "could not reach the server" : SipStatus.meaning(status));
		o.put("calls", (Long) r.get(4));
		o.put("last", time(r.get(5)));
		if (r.get(6) != null) {
			o.put("retryAfter", (Long) r.get(6));
		}
		if (r.size() > 7 && r.get(7) != null) {
			o.put("reason", String.valueOf(r.get(7)));
		}
		return o;
	}

	private static long time(Object o) {
		return o instanceof Date ? ((Date) o).getTime() : 0L;
	}
}
