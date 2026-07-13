/*
 * Endpoint-health console. Three views over one JSON feed (api/health):
 *
 *   Map      — sites placed geographically (Albers USA when every placed
 *              site projects inside the US, else a world projection), health
 *              rolled up per site, traffic arcs pulsing from the cluster
 *              site when calls flowed since the last poll.
 *   Topology — per-plan pipeline: engines -> tier -> tier with failover
 *              arrows; endpoints as cards with per-node status, RTT
 *              sparkline, heartbeat strip, and traffic share.
 *   Grid     — the dense table: rows tier/endpoint, one column per engine
 *              node (each node's INDEPENDENT view).
 *
 * Endpoints are identified by their registry NAME. Status is conveyed by
 * shape + text label first — never by color alone. Rendering is
 * string-built markup (the Trace-ladder idiom); d3-geo/topojson-client are
 * used ONLY for projection math over the vendored atlases in map/.
 */
(function () {
	"use strict";

	var REFRESH_MS = 10000;
	var AGGREGATE_OVER = 12; // tiers larger than this render as a summary
	var root = document.getElementById("health-root");
	var updated = document.getElementById("last-updated");
	var auto = document.getElementById("autorefresh");

	// ---- view state (survives the innerHTML rebuilds) ----
	var view = (location.hash || "#map").slice(1);
	if (["map", "topology", "grid"].indexOf(view) < 0) view = "map";
	var siteFilter = null;      // topology filtered to one site (set by map click)
	var selectedEndpoint = null; // detail panel target
	var expanded = {};           // "planKey#tier" -> true (aggregated tier opened)
	var lastData = null;
	var deltas = {};             // endpoint name -> attempts since previous poll
	var prevAttempts = {};       // endpoint name -> last poll's attempt total
	var atlases = { us: null, world: null, loading: false };

	// ---------------------------------------------------------- utilities

	function esc(s) {
		return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) {
			return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
		});
	}

	function age(ms) {
		if (!ms) return "never checked";
		var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
		if (s < 60) return s + "s ago";
		if (s < 3600) return Math.round(s / 60) + "m ago";
		return Math.round(s / 3600) + "h ago";
	}

	function clock(ms) {
		return new Date(ms).toLocaleTimeString();
	}

	/* stateOf/STATE_RANK and the aggregation helpers live in balancer-model.js
	 * (shared with the printable report). */

	/* Shape + label carry the state; the CSS class only tints. */
	function chipFor(state, ep) {
		switch (state) {
		case "up":        return '<span class="bal-chip bal-up">&#9679; UP</span>';
		case "down":      return '<span class="bal-chip bal-down">&#9632; DOWN</span>';
		case "undefined": return '<span class="bal-chip bal-down">! UNDEFINED</span>';
		case "drained":   return '<span class="bal-chip bal-unknown">&#9634; DRAINED</span>';
		case "backoff":
			var left = ep && ep.downUntil ? Math.max(0, Math.round((ep.downUntil - Date.now()) / 1000)) : 0;
			return '<span class="bal-chip bal-backoff">&#9670; BACKOFF ' + left + "s</span>";
		default:          return '<span class="bal-chip bal-unknown">? UNKNOWN</span>';
		}
	}

	function chip(ep) {
		return chipFor(stateOf(ep), ep);
	}

	/* Tiny per-node glyph (topology cards): shape only, name in the tooltip. */
	function miniGlyph(state, server) {
		var sym = { up: "&#9679;", down: "&#9632;", backoff: "&#9670;", drained: "&#9634;",
			undefined: "!", unknown: "?" }[state] || "?";
		var cls = { up: "bal-up", down: "bal-down", backoff: "bal-backoff" }[state] || "bal-unknown";
		return '<span class="bal-mini ' + cls + '" title="' + esc(server) + ": " + state + '">' + sym + "</span>";
	}

	// --------------------------------------------------- data aggregation
	// mergedSites/buildIndex/worstState/siteRollup come from balancer-model.js.

	function computeDeltas(index) {
		var next = {};
		deltas = {};
		Object.keys(index).forEach(function (name) {
			var total = index[name].attempts;
			if (prevAttempts.hasOwnProperty(name)) {
				deltas[name] = Math.max(0, total - prevAttempts[name]);
			} else {
				deltas[name] = 0;
			}
			next[name] = total;
		});
		prevAttempts = next;
	}

	// -------------------------------------------------------- sparkline etc.

	/* Overlaid per-node RTT polylines from the ping samples. */
	function sparkline(agg, width, height, big) {
		var servers = Object.keys(agg.samples);
		var series = [];
		var maxRtt = 1;
		servers.forEach(function (server) {
			var pts = agg.samples[server].filter(function (s) {
				return s[3] === "ping" && s[2] >= 0;
			}).slice(-30);
			if (pts.length > 1) {
				series.push({ server: server, pts: pts });
				pts.forEach(function (s) { if (s[2] > maxRtt) maxRtt = s[2]; });
			}
		});
		if (!series.length) {
			return '<div class="bal-nortt">no RTT samples yet</div>';
		}
		var svg = '<svg class="bal-spark" viewBox="0 0 ' + width + " " + height +
			'" width="' + width + '" height="' + height + '" role="img" aria-label="OPTIONS round-trip trend">';
		series.forEach(function (s) {
			var n = s.pts.length;
			var line = s.pts.map(function (p, i) {
				var x = (i / (n - 1)) * (width - 4) + 2;
				var y = height - 3 - (p[2] / maxRtt) * (height - 8);
				return x.toFixed(1) + "," + y.toFixed(1);
			}).join(" ");
			svg += '<polyline points="' + line + '" fill="none"' +
				(big ? ' data-node="' + esc(s.server) + '"' : "") + "/>";
		});
		svg += "</svg>";
		var latest = agg.perNode[servers[0]] || {};
		var label = big ? "" : '<span class="bal-spark-label">' +
			(latest.lastRttMs >= 0 ? esc(latest.lastRttMs) + " ms" : "&mdash;") +
			' <em>max ' + maxRtt + "ms</em></span>";
		return '<div class="bal-sparkrow">' + svg + label + "</div>";
	}

	/* Heartbeat strip: every observation from every node, merged by time.
	 * Up = short bar at the baseline, down = full-height bar (shape first;
	 * color reinforces). */
	function heartbeat(agg, width, height) {
		var all = [];
		Object.keys(agg.samples).forEach(function (server) {
			agg.samples[server].forEach(function (s) { all.push(s); });
		});
		all.sort(function (a, b) { return a[0] - b[0]; });
		var per = 4; // 3px bar + 1px gap
		var n = Math.min(all.length, Math.floor(width / per));
		if (!n) return '<div class="bal-nortt">no observations yet</div>';
		var recent = all.slice(-n);
		var svg = '<svg class="bal-beat" viewBox="0 0 ' + width + " " + height +
			'" width="' + width + '" height="' + height + '" role="img" aria-label="Recent up/down observations">';
		recent.forEach(function (s, i) {
			var upSeg = s[1] === 1;
			var h = upSeg ? Math.round(height * 0.4) : height;
			svg += '<rect class="' + (upSeg ? "bal-beat-up" : "bal-beat-down") + '" x="' + (i * per) +
				'" y="' + (height - h) + '" width="3" height="' + h + '"><title>' +
				esc(clock(s[0]) + " · " + s[3] + " · " + s[4] + (s[2] >= 0 ? " · " + s[2] + "ms" : "")) +
				"</title></rect>";
		});
		svg += "</svg>";
		return svg;
	}

	// ------------------------------------------------------------ map view

	function loadAtlases(then) {
		if (atlases.us && atlases.world) { then(); return; }
		if (atlases.loading) return; // a render will re-run when loaded
		atlases.loading = true;
		Promise.all([
			fetch("map/states-albers-10m.json").then(function (r) { return r.json(); }),
			fetch("map/land-110m.json").then(function (r) { return r.json(); })
		]).then(function (loaded) {
			atlases.us = loaded[0];
			atlases.world = loaded[1];
			atlases.loading = false;
			then();
		}).catch(function (e) {
			atlases.loading = false;
			root.innerHTML = '<p class="bal-empty">Failed to load map atlas: ' + esc(e.message) + "</p>";
		});
	}

	function siteGlyphSvg(x, y, r, pulse) {
		var shape;
		switch (r.worst) {
		case "up": // all up: solid circle
			shape = '<circle class="bal-site-up" cx="0" cy="0" r="7"/>';
			break;
		case "down":
		case "undefined": // trouble: solid square
			shape = '<rect class="bal-site-down" x="-7" y="-7" width="14" height="14"/>';
			break;
		default: // degraded/backoff/drained/unknown: diamond
			shape = '<rect class="bal-site-warn" x="-6" y="-6" width="12" height="12" transform="rotate(45)"/>';
		}
		return '<g class="bal-site" data-site="' + esc(r.key) + '" transform="translate(' +
			x.toFixed(1) + "," + y.toFixed(1) + ')">' +
			(pulse ? '<circle class="bal-site-pulse" cx="0" cy="0" r="8"/>' : "") +
			shape +
			'<text class="bal-site-name" x="0" y="-13">' + esc(r.label) + "</text>" +
			'<text class="bal-site-count" x="0" y="24">' + r.up + "/" + r.total + " UP</text>" +
			"<title>" + esc(r.label + ": " + r.up + " of " + r.total + " endpoints up (worst: " + r.worst + ")") +
			"</title></g>";
	}

	/* Quadratic arc, bowed perpendicular to the chord. */
	function arcPath(x1, y1, x2, y2) {
		var mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
		var dx = x2 - x1, dy = y2 - y1;
		var len = Math.sqrt(dx * dx + dy * dy) || 1;
		var bow = Math.min(60, len * 0.18);
		var cx = mx - (dy / len) * bow, cy = my + (dx / len) * bow;
		return "M" + x1.toFixed(1) + "," + y1.toFixed(1) +
			" Q" + cx.toFixed(1) + "," + cy.toFixed(1) +
			" " + x2.toFixed(1) + "," + y2.toFixed(1);
	}

	function renderMap(data) {
		var nodes = data.nodes;
		var siteInfo = mergedSites(nodes);
		var index = buildIndex(nodes);
		var roll = siteRollup(index, siteInfo, deltas);
		var keys = Object.keys(roll);

		if (!keys.length) {
			root.innerHTML = '<p class="bal-empty">No endpoints configured.</p>';
			return;
		}
		if (!window.d3 || !d3.geoPath || !window.topojson) {
			root.innerHTML = '<p class="bal-empty">Map libraries missing (map/*.min.js not served?). ' +
				"The Topology and Grid tabs work without them.</p>";
			return;
		}
		if (!atlases.us || !atlases.world) {
			root.innerHTML = '<p class="bal-empty">Loading map&hellip;</p>';
			loadAtlases(function () { if (view === "map") render(lastData); });
			return;
		}

		var placed = keys.filter(function (k) { return roll[k].lat != null && roll[k].lon != null; });
		var unplaced = keys.filter(function (k) { return roll[k].lat == null || roll[k].lon == null; });

		// projection choice: Albers USA composites AK/HI insets and returns
		// null for points outside the US — which makes it the US test too
		var usProj = d3.geoAlbersUsa();
		var isUS = placed.length > 0 && placed.every(function (k) {
			return usProj([roll[k].lon, roll[k].lat]) != null;
		});

		var W = 975, H = isUS ? 610 : 520;
		var svg = '<svg class="bal-map" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="Site map">';

		var project;
		if (isUS) {
			var usPath = d3.geoPath(); // states-albers-10m is pre-projected to 975x610
			svg += '<path class="bal-map-land" d="' +
				usPath(topojson.feature(atlases.us, atlases.us.objects.nation)) + '"/>';
			svg += '<path class="bal-map-borders" d="' +
				usPath(topojson.mesh(atlases.us, atlases.us.objects.states, function (a, b) { return a !== b; })) + '"/>';
			project = function (lon, lat) { return usProj([lon, lat]); };
		} else {
			var worldProj = d3.geoNaturalEarth1().fitExtent([[8, 8], [W - 8, H - 8]], { type: "Sphere" });
			var worldPath = d3.geoPath(worldProj);
			svg += '<path class="bal-map-sphere" d="' + worldPath({ type: "Sphere" }) + '"/>';
			svg += '<path class="bal-map-land" d="' +
				worldPath(topojson.feature(atlases.world, atlases.world.objects.land)) + '"/>';
			project = function (lon, lat) { return worldProj([lon, lat]); };
		}

		// traffic arcs first (under the glyphs)
		var cluster = siteInfo.clusterSite && roll[siteInfo.clusterSite];
		var clusterXY = (cluster && cluster.lat != null && cluster.lon != null)
			? project(cluster.lon, cluster.lat) : null;
		if (clusterXY) {
			placed.forEach(function (k) {
				if (k === siteInfo.clusterSite) return;
				var xy = project(roll[k].lon, roll[k].lat);
				if (!xy) return;
				var pulse = roll[k].delta > 0;
				svg += '<path class="bal-arc' + (pulse ? " bal-arc-pulse" : "") + '" d="' +
					arcPath(clusterXY[0], clusterXY[1], xy[0], xy[1]) + '"/>';
			});
		}

		placed.forEach(function (k) {
			var xy = project(roll[k].lon, roll[k].lat);
			if (xy) svg += siteGlyphSvg(xy[0], xy[1], roll[k], roll[k].delta > 0);
		});
		if (clusterXY) {
			svg += '<g class="bal-cluster" transform="translate(' + clusterXY[0].toFixed(1) + "," +
				clusterXY[1].toFixed(1) + ')"><circle r="11" class="bal-cluster-ring"/>' +
				'<text class="bal-site-name" x="0" y="-16">OCCAS</text><title>OCCAS cluster (' +
				esc(siteInfo.clusterSite) + ")</title></g>";
		}
		svg += "</svg>";

		var tray = "";
		if (unplaced.length) {
			tray = '<aside class="bal-tray"><h3>Unplaced</h3>' + unplaced.map(function (k) {
				var r = roll[k];
				return '<button type="button" class="bal-tray-site" data-site="' + esc(r.key) + '">' +
					chipFor(r.worst === "up" ? "up" : r.worst, null) +
					" <strong>" + esc(r.label) + "</strong> <span>" + r.up + "/" + r.total + " UP</span></button>";
			}).join("") + "<p>Sites without coordinates, and endpoints with no site.</p></aside>";
		}

		var legend = '<footer class="bal-legend">' +
			'<span><span class="bal-key bal-key-circle"></span> all up</span>' +
			'<span><span class="bal-key bal-key-diamond"></span> degraded</span>' +
			'<span><span class="bal-key bal-key-square"></span> down</span>' +
			'<span><span class="bal-key bal-key-arc"></span> traffic since last poll</span>' +
			"<span>click a site to inspect it</span></footer>";

		root.innerHTML = '<section class="bal-plan bal-map-panel"><h2>Sites' +
			(isUS ? "" : " (world)") + "</h2>" +
			'<div class="bal-map-wrap">' + svg + tray + "</div>" + legend + "</section>";
	}

	// ------------------------------------------------------- topology view

	function tierTotals(tier, index) {
		var total = 0;
		(tier.endpoints || []).forEach(function (ep) {
			var a = index[ep.name];
			if (a) total += a.attempts;
		});
		return total;
	}

	function endpointCard(agg, tierAttempts) {
		var worst = worstState(agg);
		var share = tierAttempts > 0 ? Math.round((agg.attempts / tierAttempts) * 100) : 0;
		var pulse = (deltas[agg.name] || 0) > 0;
		var dim = siteFilter && (agg.site || "(no site)") !== siteFilter;

		var glyphs = Object.keys(agg.perNode).map(function (server) {
			return miniGlyph(stateOf(agg.perNode[server]), server);
		}).join("");

		return '<div class="bal-card' + (pulse ? " bal-card-pulse" : "") + (dim ? " bal-card-dim" : "") +
			'" data-ep="' + esc(agg.name) + '">' +
			'<div class="bal-card-head"><strong>' + esc(agg.name) + "</strong>" +
			(agg.weight != null && agg.weight !== 1 ? '<span class="bal-badge">w' + esc(agg.weight) + "</span>" : "") +
			(agg.site ? '<span class="bal-badge">' + esc(agg.site) + "</span>" : "") +
			'<span class="bal-card-chip">' + chipFor(worst, firstNodeView(agg)) + "</span></div>" +
			'<div class="bal-card-uri">' + esc(agg.uri || "") + "</div>" +
			'<div class="bal-nodes">' + glyphs + "</div>" +
			sparkline(agg, 150, 26, false) +
			heartbeat(agg, 150, 14) +
			'<div class="bal-share" title="' + share + '% of this tier\'s attempts">' +
			'<div style="width:' + share + '%"></div></div>' +
			'<div class="bal-counts">' + agg.attempts + " att &middot; " + agg.successes +
			" ok &middot; " + agg.failovers + " fail</div>" +
			"</div>";
	}

	function firstNodeView(agg) {
		var servers = Object.keys(agg.perNode);
		return servers.length ? agg.perNode[servers[0]] : null;
	}

	function aggregateCard(planKey, tier, index) {
		var counts = {};
		var movers = [];
		(tier.endpoints || []).forEach(function (ep) {
			var a = index[ep.name];
			var w = a ? worstState(a) : "undefined";
			counts[w] = (counts[w] || 0) + 1;
			if (a && (deltas[ep.name] || 0) > 0) movers.push(ep.name);
		});
		var parts = STATES.filter(function (s) { return counts[s]; }).map(function (s) {
			return counts[s] + " " + s.toUpperCase();
		});
		return '<div class="bal-card bal-card-agg">' +
			"<strong>" + tier.endpoints.length + " endpoints</strong>" +
			'<div class="bal-counts">' + parts.join(" &middot; ") + "</div>" +
			(movers.length ? '<div class="bal-counts">active: ' + esc(movers.slice(0, 5).join(", ")) +
				(movers.length > 5 ? "&hellip;" : "") + "</div>" : "") +
			'<button type="button" class="bal-expand" data-expand="' + esc(planKey + "#" + tier.tier) +
			'">show all</button></div>';
	}

	function renderTopology(data) {
		var nodes = data.nodes;
		var index = buildIndex(nodes);

		var planKeys = {};
		nodes.forEach(function (n) {
			Object.keys((n.health && n.health.plans) || {}).forEach(function (k) { planKeys[k] = true; });
		});
		var keys = Object.keys(planKeys).sort();
		if (!keys.length) {
			root.innerHTML = '<p class="bal-empty">No plans configured.</p>';
			return;
		}

		var engines = '<div class="bal-engines"><h4>Engines</h4>' + nodes.map(function (n) {
			return '<div class="bal-engine">' + esc(n.server) + "</div>";
		}).join("") + "</div>";

		var filterChip = siteFilter
			? '<span class="bal-filter">site: ' + esc(siteFilter) +
				' <button type="button" data-clearfilter="1" title="Clear the site filter">&#10005;</button></span>'
			: "";

		var html = "";
		keys.forEach(function (planKey) {
			var skeleton = null;
			nodes.some(function (n) {
				var p = n.health && n.health.plans && n.health.plans[planKey];
				if (p) { skeleton = p; return true; }
				return false;
			});
			if (!skeleton) return;

			html += '<section class="bal-plan"><h2>Plan: ' + esc(planKey) + " " + filterChip + "</h2>";
			html += '<div class="bal-pipe">' + engines;

			skeleton.forEach(function (tier, t) {
				html += '<div class="bal-arrow' + (t > 0 ? " bal-arrow-failover" : "") + '">' +
					'<span>&#8594;</span><em>' + (t > 0 ? "failover" : "route") + "</em></div>";

				var attempts = tierTotals(tier, index);
				var big = (tier.endpoints || []).length > AGGREGATE_OVER &&
					!expanded[planKey + "#" + tier.tier];

				html += '<div class="bal-tierbox"><header>#' + tier.tier +
					(tier.name ? " " + esc(tier.name) : "") +
					'<span>' + esc(tier.strategy) + " &middot; " + esc(tier.timeout) + "s</span></header>";
				if (big) {
					html += aggregateCard(planKey, tier, index);
				} else {
					(tier.endpoints || []).forEach(function (ep) {
						var a = index[ep.name];
						if (a) {
							html += endpointCard(a, attempts);
						} else {
							html += '<div class="bal-card"><strong>' + esc(ep.name) +
								"</strong> " + chipFor("undefined", null) + "</div>";
						}
					});
				}
				html += "</div>";
			});

			html += "</div></section>";
		});

		root.innerHTML = html;
	}

	// ----------------------------------------------------------- grid view

	function cell(ep) {
		if (!ep) {
			return '<td class="bal-cell bal-missing">&mdash;</td>';
		}
		var rtt = (ep.lastRttMs != null && ep.lastRttMs >= 0) ? ep.lastRttMs + " ms &middot; " : "";
		return '<td class="bal-cell">' + chip(ep) +
			'<span class="bal-note">' + esc(ep.note || "") + "</span>" +
			'<span class="bal-age">' + rtt + age(ep.lastChecked) + "</span></td>";
	}

	function renderGrid(data) {
		var nodes = data.nodes;
		var planKeys = {};
		nodes.forEach(function (n) {
			var plans = (n.health && n.health.plans) || {};
			Object.keys(plans).forEach(function (k) { planKeys[k] = true; });
		});

		var html = "";
		Object.keys(planKeys).sort().forEach(function (planKey) {
			var skeleton = null;
			nodes.some(function (n) {
				var p = n.health && n.health.plans && n.health.plans[planKey];
				if (p) { skeleton = p; return true; }
				return false;
			});
			if (!skeleton) return;

			html += '<section class="bal-plan"><h2>Plan: ' + esc(planKey) + "</h2>";
			html += '<div class="bal-scroll"><table class="bal-table"><thead><tr>' +
				"<th>Tier</th><th>Endpoint</th>" +
				nodes.map(function (n) { return "<th>" + esc(n.server) + "</th>"; }).join("") +
				"</tr></thead><tbody>";

			skeleton.forEach(function (tier) {
				(tier.endpoints || []).forEach(function (ep, i) {
					html += '<tr data-ep="' + esc(ep.name) + '">';
					if (i === 0) {
						html += '<td class="bal-tier" rowspan="' + tier.endpoints.length + '">#' +
							tier.tier + (tier.name ? " " + esc(tier.name) : "") +
							'<span class="bal-age">' + esc(tier.strategy) +
							" &middot; timeout " + esc(tier.timeout) + "s</span></td>";
					}
					html += '<td class="bal-uri"><strong>' + esc(ep.name) + "</strong>" +
						(ep.weight != null && ep.weight !== 1 ? " (w" + esc(ep.weight) + ")" : "") +
						'<span class="bal-age">' + esc(ep.uri || "") + "</span></td>";
					nodes.forEach(function (n) {
						html += cell(findEndpoint(n, planKey, tier.tier, ep.name));
					});
					html += "</tr>";
				});
			});

			html += "</tbody></table></div></section>";
		});

		root.innerHTML = html || '<p class="bal-empty">No plans configured.</p>';
	}

	function findEndpoint(node, planKey, tierIndex, name) {
		var plan = node.health && node.health.plans && node.health.plans[planKey];
		if (!plan) return null;
		for (var t = 0; t < plan.length; t++) {
			if (plan[t].tier !== tierIndex) continue;
			var eps = plan[t].endpoints || [];
			for (var e = 0; e < eps.length; e++) {
				if (eps[e].name === name) return eps[e];
			}
		}
		return null;
	}

	// --------------------------------------------------------- detail panel

	function renderDetail(data) {
		var existing = document.getElementById("bal-detail");
		if (existing) existing.remove();
		if (!selectedEndpoint || !data) return;

		var index = buildIndex(data.nodes);
		var agg = index[selectedEndpoint];
		if (!agg) { selectedEndpoint = null; return; }

		var html = '<aside class="bal-detail" id="bal-detail"><div class="bal-detail-head">' +
			"<strong>" + esc(agg.name) + "</strong>" +
			(agg.site ? '<span class="bal-badge">' + esc(agg.site) + "</span>" : "") +
			(agg.weight != null && agg.weight !== 1 ? '<span class="bal-badge">w' + esc(agg.weight) + "</span>" : "") +
			'<button type="button" class="bal-close" data-close="1" title="Close">&#10005;</button></div>' +
			'<div class="bal-card-uri">' + esc(agg.uri || "") + "</div>" +
			'<div class="bal-counts">' + agg.attempts + " attempts &middot; " + agg.successes +
			" ok &middot; " + agg.failovers + " failovers (all nodes)</div>" +
			'<h4>RTT trend (all nodes overlaid)</h4>' + sparkline(agg, 300, 60, true) +
			'<h4>Observations</h4>' + heartbeat(agg, 300, 18);

		Object.keys(agg.perNode).forEach(function (server) {
			var ep = agg.perNode[server];
			html += '<div class="bal-detail-node"><h4>' + esc(server) + "</h4>" + chip(ep) +
				'<span class="bal-age">' +
				(ep.lastRttMs != null && ep.lastRttMs >= 0 ? ep.lastRttMs + " ms &middot; " : "") +
				age(ep.lastChecked) + " &middot; " + esc(ep.note || "") + "</span></div>";
		});

		// note log: most recent observations across all nodes, newest first
		var all = [];
		Object.keys(agg.samples).forEach(function (server) {
			agg.samples[server].forEach(function (s) { all.push({ server: server, s: s }); });
		});
		all.sort(function (a, b) { return b.s[0] - a.s[0]; });
		html += "<h4>Log</h4><ul class='bal-log'>";
		all.slice(0, 14).forEach(function (row) {
			var s = row.s;
			html += "<li><time>" + esc(clock(s[0])) + "</time> " +
				(s[1] === 1 ? "&#9679;" : "&#9632;") + " " + esc(s[4]) +
				(s[2] >= 0 ? " &middot; " + s[2] + "ms" : "") +
				' <em>' + esc(s[3]) + " @ " + esc(row.server) + "</em></li>";
		});
		html += "</ul></aside>";

		document.body.insertAdjacentHTML("beforeend", html);
	}

	// ----------------------------------------------------------- dispatch

	function syncTabs() {
		["map", "topology", "grid"].forEach(function (v) {
			document.getElementById("tab-" + v).classList.toggle("bal-tab-active", v === view);
		});
	}

	function render(data) {
		if (!data) return;
		var nodes = data.nodes || [];
		if (!nodes.length) {
			root.innerHTML = '<p class="bal-empty">No EndpointHealth MBeans found. ' +
				"Is the proxy-balancer service deployed to the engine cluster?</p>";
			return;
		}
		syncTabs();
		if (view === "map") renderMap(data);
		else if (view === "topology") renderTopology(data);
		else renderGrid(data);
		renderDetail(data);
	}

	function refresh() {
		fetch("api/health", { credentials: "same-origin" })
			.then(function (r) {
				if (!r.ok) throw new Error("HTTP " + r.status);
				return r.json();
			})
			.then(function (data) {
				lastData = data;
				computeDeltas(buildIndex(data.nodes || []));
				render(data);
				updated.textContent = "updated " + new Date().toLocaleTimeString();
			})
			.catch(function (e) {
				root.innerHTML = '<p class="bal-empty">Failed to load health: ' + esc(e.message) + "</p>";
				updated.textContent = "error";
			});
	}

	// one delegated listener survives every innerHTML rebuild
	document.addEventListener("click", function (e) {
		var el = e.target.closest("[data-view],[data-site],[data-ep],[data-expand],[data-close],[data-clearfilter]");
		if (!el) return;
		if (el.dataset.view) {
			view = el.dataset.view;
			location.hash = view;
			render(lastData);
		} else if (el.dataset.site) {
			siteFilter = el.dataset.site;
			view = "topology";
			location.hash = view;
			render(lastData);
		} else if (el.dataset.expand) {
			expanded[el.dataset.expand] = true;
			render(lastData);
		} else if (el.dataset.clearfilter) {
			siteFilter = null;
			render(lastData);
		} else if (el.dataset.close) {
			selectedEndpoint = null;
			renderDetail(lastData);
		} else if (el.dataset.ep) {
			selectedEndpoint = el.dataset.ep;
			renderDetail(lastData);
		}
	});
	document.addEventListener("keydown", function (e) {
		if (e.key === "Escape" && selectedEndpoint) {
			selectedEndpoint = null;
			renderDetail(lastData);
		}
	});

	window.addEventListener("hashchange", function () {
		var v = (location.hash || "#map").slice(1);
		if (["map", "topology", "grid"].indexOf(v) >= 0 && v !== view) {
			view = v;
			render(lastData);
		}
	});

	syncTabs();
	refresh();
	setInterval(function () {
		if (auto.checked) refresh();
	}, REFRESH_MS);
})();
