/* BLADE Test Console — discovers tester nodes over the console's federated-JMX
 * REST facade, renders per-node status and aggregated scenario metrics, and
 * fans run commands out to every matching node. Vanilla JS, no dependencies. */
(function () {
	"use strict";

	var API = "api";
	var REFRESH_MS = 2000;

	var elNodes = document.getElementById("nodes");
	var elScenarios = document.getElementById("scenarios");
	var elApp = document.getElementById("ctl-app");
	var elFeedback = document.getElementById("ctl-feedback");

	var knownApps = [];

	function esc(value) {
		return String(value).replace(/[&<>"]/g, function (c) {
			return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
		});
	}

	function feedback(message) {
		elFeedback.textContent = message;
		if (message) {
			setTimeout(function () {
				if (elFeedback.textContent === message) elFeedback.textContent = "";
			}, 6000);
		}
	}

	// ---- rendering ----

	function renderNodes(cluster) {
		if (!cluster.length) {
			elNodes.innerHTML = '<p class="tc-empty vorpal-empty">No tester nodes found. Deploy test-uac / test-uas to the ' +
				'engine tier; each node registers a TesterControl MBean at startup.</p>';
			return;
		}
		var html = cluster.map(function (node) {
			var s = node.status || {};
			var state = s.state || "UNKNOWN";
			return '<div class="tc-node vorpal-panel">' +
				'<div class="tc-node-head vorpal-panel-head">' +
				'<span><span class="tc-node-title vorpal-panel-title">' + esc(node.app) + '</span> ' +
				'<span class="tc-node-server vorpal-panel-sub">@ ' + esc(node.server) + '</span></span>' +
				'<span class="tc-state tc-state-' + esc(state) + ' vorpal-badge' + stateBadge(state) + '">' + esc(state) + '</span>' +
				'</div>' +
				'<dl class="tc-counters">' +
				'<dt>active</dt><dd>' + (s.activeCalls || 0) + '</dd>' +
				'<dt>started</dt><dd>' + (s.totalStarted || 0) + '</dd>' +
				'<dt>completed</dt><dd>' + (s.totalCompleted || 0) + '</dd>' +
				'<dt>failed</dt><dd>' + (s.totalFailed || 0) + '</dd>' +
				'<dt>scenario</dt><dd>' + esc(s.scenario || "—") + '</dd>' +
				'<dt>elapsed</dt><dd>' + formatElapsed(s.elapsedMilliseconds) + '</dd>' +
				'</dl>' +
				'</div>';
		}).join("");
		elNodes.innerHTML = html;
	}

	// The state's word is the badge; its outline only repeats it.
	function stateBadge(state) {
		switch (state) {
			case "RUNNING": return " vorpal-badge-ok";
			case "STOPPING": return " vorpal-badge-warn";
			default: return "";
		}
	}

	function formatElapsed(ms) {
		if (!ms) return "—";
		var sec = Math.floor(ms / 1000);
		var min = Math.floor(sec / 60);
		return min > 0 ? (min + "m " + (sec % 60) + "s") : (sec + "s");
	}

	/* Sum counters across nodes per scenario; percentiles can't be merged, so
	 * show the worst (max) node — labeled as such in the section header. */
	function aggregate(cluster) {
		var byScenario = {};
		cluster.forEach(function (node) {
			(Array.isArray(node.report) ? node.report : []).forEach(function (r) {
				var key = r.scenario || "(none)";
				var agg = byScenario[key];
				if (!agg) {
					agg = byScenario[key] = {
						scenario: key, started: 0, completed: 0, failed: 0, answered: 0,
						forwarded: 0, expectMismatched: 0, assertionsPassed: 0,
						assertionsFailed: 0, assertionsWarned: 0, latencyCount: 0,
						latencyAvgMs: 0, latencyMaxMs: 0, latencyP50Ms: 0,
						latencyP90Ms: 0, latencyP99Ms: 0, statusCounts: {}
					};
				}
				["started", "completed", "failed", "answered", "forwarded", "expectMismatched",
					"assertionsPassed", "assertionsFailed", "assertionsWarned", "latencyCount"
				].forEach(function (f) { agg[f] += (r[f] || 0); });
				["latencyAvgMs", "latencyMaxMs", "latencyP50Ms", "latencyP90Ms", "latencyP99Ms"
				].forEach(function (f) { agg[f] = Math.max(agg[f], r[f] || 0); });
				var counts = r.finalStatusCounts || {};
				Object.keys(counts).forEach(function (code) {
					agg.statusCounts[code] = (agg.statusCounts[code] || 0) + counts[code];
				});
			});
		});
		return Object.keys(byScenario).sort().map(function (k) { return byScenario[k]; });
	}

	function renderScenarios(cluster) {
		var rows = aggregate(cluster);
		if (!rows.length) {
			elScenarios.innerHTML = '<p class="tc-empty vorpal-empty">No metrics yet — start a run, or place test calls ' +
				'through a tester node.</p>';
			return;
		}
		var html = '<table class="tc-table vorpal-table"><thead><tr>' +
			'<th>scenario</th><th class="num">started</th><th class="num">completed</th><th class="num">failed</th>' +
			'<th class="num">answered</th><th class="num">forwarded</th><th>statuses</th>' +
			'<th class="num">p50</th><th class="num">p90</th><th class="num">p99</th><th class="num">max</th>' +
			'<th class="num">expect ✗</th><th class="num">assert ✓</th><th class="num">assert ✗</th><th class="num">assert ⚠</th>' +
			'</tr></thead><tbody>';
		rows.forEach(function (r) {
			var statuses = Object.keys(r.statusCounts).sort().map(function (code) {
				return code + "×" + r.statusCounts[code];
			}).join(", ") || "—";
			html += "<tr>" +
				"<td>" + esc(r.scenario) + "</td>" +
				"<td class=\"num\">" + r.started + "</td>" +
				"<td class=\"num\">" + r.completed + "</td>" +
				"<td class=\"num\">" + r.failed + "</td>" +
				"<td class=\"num\">" + r.answered + "</td>" +
				"<td class=\"num\">" + r.forwarded + "</td>" +
				"<td>" + esc(statuses) + "</td>" +
				"<td class=\"num\">" + r.latencyP50Ms + "ms</td>" +
				"<td class=\"num\">" + r.latencyP90Ms + "ms</td>" +
				"<td class=\"num\">" + r.latencyP99Ms + "ms</td>" +
				"<td class=\"num\">" + r.latencyMaxMs + "ms</td>" +
				"<td class=\"num\">" + r.expectMismatched + "</td>" +
				"<td class=\"num\">" + r.assertionsPassed + "</td>" +
				"<td class=\"num\">" + r.assertionsFailed + "</td>" +
				"<td class=\"num\">" + r.assertionsWarned + "</td>" +
				"</tr>";
		});
		html += "</tbody></table>";
		elScenarios.innerHTML = html;
	}

	function updateAppChoices(cluster) {
		var apps = [];
		cluster.forEach(function (node) {
			if (apps.indexOf(node.app) < 0) apps.push(node.app);
		});
		apps.sort();
		if (apps.join("|") === knownApps.join("|")) return;
		knownApps = apps;
		var current = elApp.value;
		elApp.innerHTML = '<option value="">(all tester apps)</option>' + apps.map(function (a) {
			return '<option value="' + esc(a) + '">' + esc(a) + "</option>";
		}).join("");
		elApp.value = current;
	}

	// ---- polling ----

	function refresh() {
		fetch(API + "/cluster", { headers: { "Accept": "application/json" } })
			.then(function (r) { return r.json(); })
			.then(function (cluster) {
				if (!Array.isArray(cluster)) throw new Error(cluster.error || "unexpected response");
				updateAppChoices(cluster);
				renderNodes(cluster);
				renderScenarios(cluster);
			})
			.catch(function (e) {
				elNodes.innerHTML = '<p class="tc-empty vorpal-error">Cluster query failed: ' + esc(e.message) + "</p>";
			});
	}

	// ---- controls ----

	function buildLoadRequest() {
		var req = {};
		var scenario = document.getElementById("ctl-scenario").value.trim();
		var mode = document.getElementById("ctl-mode").value;
		var cps = document.getElementById("ctl-cps").value;
		var concurrent = document.getElementById("ctl-concurrent").value;
		var maxCalls = document.getElementById("ctl-maxcalls").value;
		var duration = document.getElementById("ctl-duration").value.trim();
		if (scenario) req.scenario = scenario;
		if (mode) req.mode = mode;
		if (cps) req.targetCps = parseFloat(cps);
		if (concurrent) req.targetConcurrent = parseInt(concurrent, 10);
		if (maxCalls) req.maxCalls = parseInt(maxCalls, 10);
		if (duration) req.duration = duration;
		return req;
	}

	function command(path, body) {
		var app = elApp.value;
		var url = API + "/" + path + (app ? "?app=" + encodeURIComponent(app) : "");
		return fetch(url, {
			method: "POST",
			headers: { "Content-Type": "application/json", "Accept": "application/json" },
			body: body ? JSON.stringify(body) : ""
		}).then(function (r) {
			return r.json().then(function (data) { return { ok: r.ok, data: data }; });
		}).then(function (result) {
			if (!result.ok) throw new Error((result.data && result.data.error) || "request failed");
			var errors = (Array.isArray(result.data) ? result.data : []).filter(function (n) { return n.error; });
			feedback(errors.length
				? path + ": " + errors.length + " node(s) reported errors — " + errors[0].error
				: path + " sent to " + (Array.isArray(result.data) ? result.data.length : "?") + " node(s)");
			refresh();
		}).catch(function (e) {
			feedback(path + " failed: " + e.message);
		});
	}

	document.getElementById("btn-start").addEventListener("click", function () {
		command("start", buildLoadRequest());
	});
	document.getElementById("btn-stop").addEventListener("click", function () {
		command("stop", null);
	});
	document.getElementById("btn-reset").addEventListener("click", function () {
		command("reset", null);
	});

	refresh();
	setInterval(refresh, REFRESH_MS);
})();
