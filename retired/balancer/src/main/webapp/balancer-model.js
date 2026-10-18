/*
 * Single source of truth for the Balancer console's derived health state.
 * Consumed by BOTH:
 *   - health.js    — the live map/topology/grid views,
 *   - report.html  — the printable Site Health & Capacity report ranks and
 *                    rolls up endpoints with the same rules, so the two can
 *                    never disagree.
 * Plain script (no dependencies), attached to window. Pure functions over the
 * /api/health payload — no DOM, no fetch, no page state.
 *
 * Every node reports its OWN health view and nodes legitimately disagree, so
 * everything here aggregates across nodes[]: worst state wins the headline,
 * traffic counters sum.
 */

/* One endpoint's state as one node sees it. Ranked worst-first so a
 * site/card can aggregate with Math.min over STATE_RANK. `backoff` and
 * `drained` are derived here — the server only ever says up/down/unknown/
 * undefined. */
var STATES = ["down", "undefined", "backoff", "drained", "unknown", "up"];
var STATE_RANK = { down: 0, undefined: 1, backoff: 2, drained: 3, unknown: 4, up: 5 };

function stateOf(ep) {
	if (!ep || !ep.status || ep.status === "unknown") return "unknown";
	if (ep.status === "undefined") return "undefined";
	if (ep.enabled === false) return "drained";
	if (ep.downUntil && ep.downUntil > Date.now()) return "backoff";
	return ep.status === "up" ? "up" : "down";
}

/* Union of per-node config echoes (all nodes run the same published
 * config; the first node to carry a key wins). */
function mergedSites(nodes) {
	var sites = {};
	var clusterSite = null;
	nodes.forEach(function (n) {
		var h = n.health || {};
		Object.keys(h.sites || {}).forEach(function (k) {
			if (!sites[k]) sites[k] = h.sites[k];
		});
		if (!clusterSite && h.clusterSite) clusterSite = h.clusterSite;
	});
	return { sites: sites, clusterSite: clusterSite };
}

/* name -> aggregate: identity fields, per-node endpoint views, summed
 * counters, per-node sample rings. */
function buildIndex(nodes) {
	var index = {};
	nodes.forEach(function (n) {
		var h = n.health || {};
		var plans = h.plans || {};
		Object.keys(plans).forEach(function (pk) {
			(plans[pk] || []).forEach(function (tier) {
				(tier.endpoints || []).forEach(function (ep) {
					var a = index[ep.name];
					if (!a) {
						a = index[ep.name] = { name: ep.name, uri: null, weight: null, site: null,
							perNode: {}, samples: {}, attempts: 0, successes: 0, failovers: 0 };
					}
					if (ep.uri) a.uri = ep.uri;
					if (ep.weight != null) a.weight = ep.weight;
					if (ep.site != null) a.site = ep.site;
					if (!a.perNode[n.server]) {
						a.perNode[n.server] = ep;
						a.attempts += ep.attempts || 0;
						a.successes += ep.successes || 0;
						a.failovers += ep.failovers || 0;
						var det = (h.endpointDetails || {})[ep.name];
						a.samples[n.server] = (det && det.samples) || [];
					}
				});
			});
		});
	});
	return index;
}

/* Worst state across all nodes — the honest headline for a card/site.
 * Seeded from "up" (the BEST rank), not "unknown": seeding from unknown
 * (rank 4) meant an endpoint every node saw as up (rank 5) could never
 * report better than unknown, so the map's site markers read "0/N UP" even
 * when everything was healthy. No node views at all is still unknown. */
function worstState(agg) {
	var servers = Object.keys(agg.perNode);
	if (!servers.length) return "unknown";
	var worst = "up";
	servers.forEach(function (server) {
		var s = stateOf(agg.perNode[server]);
		if (STATE_RANK[s] < STATE_RANK[worst]) worst = s;
	});
	return worst;
}

/* siteKey -> rollup. Endpoints with no site (or a dangling site ref)
 * group under their literal key so nothing vanishes. `deltas` (per-endpoint
 * attempts since the previous poll) is a live-view concern — omit it and
 * every rollup's delta is 0. */
function siteRollup(index, siteInfo, deltas) {
	var roll = {};
	Object.keys(siteInfo.sites).forEach(function (k) {
		var s = siteInfo.sites[k];
		roll[k] = { key: k, label: s.label || k, lat: s.lat, lon: s.lon,
			total: 0, up: 0, worst: "up", delta: 0, endpoints: [] };
	});
	Object.keys(index).forEach(function (name) {
		var a = index[name];
		var key = a.site || "(no site)";
		if (!roll[key]) {
			roll[key] = { key: key, label: key, lat: null, lon: null,
				total: 0, up: 0, worst: "up", delta: 0, endpoints: [] };
		}
		var r = roll[key];
		var w = worstState(a);
		r.total++;
		if (w === "up") r.up++;
		if (STATE_RANK[w] < STATE_RANK[r.worst]) r.worst = w;
		r.delta += (deltas && deltas[name]) || 0;
		r.endpoints.push(name);
	});
	return roll;
}
