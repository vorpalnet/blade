/* BLADE Dashboard — no dependencies, no CDN. Fetches the analytics servlet and
   draws hand-rolled SVG charts, refreshing on a timer. */
(function () {
	'use strict';
	var NS = 'http://www.w3.org/2000/svg';
	var REFRESH_MS = 30000;      /* analytics (DB) */
	var HEALTH_MS = 5000;        /* cluster health (live MBeans) */
	var statusEl = document.getElementById('status');
	var statusText = document.getElementById('statusText');
	var updatedEl = document.getElementById('updated');

	function setStatus(kind, text) { statusEl.className = 'status' + (kind ? ' ' + kind : ''); statusText.textContent = text; }

	/* fetch that copes with FORM auth: an expired session returns the login
	   HTML (not JSON), so on non-JSON we reload into the form. */
	function getJson(url) {
		return fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
			.then(function (r) {
				var ct = r.headers.get('content-type') || '';
				if (r.status === 401 || r.status === 403 || ct.indexOf('text/html') >= 0) {
					window.location.reload(); throw new Error('auth');
				}
				return r.text().then(function (t) {
					try { return JSON.parse(t); }
					catch (e) { window.location.reload(); throw new Error('auth'); }
				});
			});
	}

	function el(name, attrs, text) {
		var e = document.createElementNS(NS, name);
		if (attrs) for (var k in attrs) e.setAttribute(k, attrs[k]);
		if (text != null) e.textContent = text;
		return e;
	}
	function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
	function msg(container, cls, text) { clear(container); var d = document.createElement('div'); d.className = cls; d.textContent = text; container.appendChild(d); }

	function defs(svg) {
		var d = el('defs');
		var bg = el('linearGradient', { id: 'barGrad', x1: '0', y1: '0', x2: '0', y2: '1' });
		bg.appendChild(el('stop', { offset: '0', 'stop-color': '#b97cd0' }));
		bg.appendChild(el('stop', { offset: '1', 'stop-color': '#7b3690' }));
		var ag = el('linearGradient', { id: 'areaGrad', x1: '0', y1: '0', x2: '0', y2: '1' });
		ag.appendChild(el('stop', { offset: '0', 'stop-color': '#b97cd0', 'stop-opacity': '.28' }));
		ag.appendChild(el('stop', { offset: '1', 'stop-color': '#b97cd0', 'stop-opacity': '0' }));
		d.appendChild(bg); d.appendChild(ag); svg.appendChild(d);
	}

	function frame(container) {
		clear(container);
		var W = container.clientWidth || 600, H = container.clientHeight || 220;
		var svg = el('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none' });
		defs(svg); container.appendChild(svg);
		return { svg: svg, W: W, H: H };
	}

	function niceMax(v) { if (v <= 0) return 1; var p = Math.pow(10, Math.floor(Math.log(v) / Math.LN10)); var f = v / p; var n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10; return n * p; }
	function yAxis(svg, W, padL, padR, padT, ph, max) {
		var steps = 4;
		for (var i = 0; i <= steps; i++) {
			var y = padT + ph - (ph * i / steps), v = Math.round(max * i / steps);
			svg.appendChild(el('line', { 'class': 'gridline', x1: padL, y1: y, x2: W - padR, y2: y }));
			svg.appendChild(el('text', { 'class': 'tick', x: padL - 6, y: y + 3, 'text-anchor': 'end' }, String(v)));
		}
	}
	function shortLabel(s) { s = String(s); var m = /^\d{4}-(\d{2})-(\d{2})$/.exec(s); return m ? m[1] + '/' + m[2] : (s.length > 11 ? s.slice(0, 10) + '…' : s); }
	function labelX(svg, data, padL, pw, y, n) {
		var idxs = n <= 8 ? data.map(function (_, i) { return i; }) : [0, Math.floor(n / 2), n - 1];
		idxs.forEach(function (i) {
			var x = n <= 1 ? padL + pw / 2 : padL + (pw * i / (n - 1));
			var anchor = i === 0 ? 'start' : (i === n - 1 ? 'end' : 'middle');
			svg.appendChild(el('text', { 'class': 'tick', x: x, y: y, 'text-anchor': anchor }, shortLabel(data[i][0])));
		});
	}

	function barChart(container, data) {
		if (!data || !data.length) { msg(container, 'empty', 'No calls in this window.'); return; }
		var f = frame(container), W = f.W, H = f.H, svg = f.svg;
		var padL = 42, padR = 12, padT = 12, padB = 26, pw = W - padL - padR, ph = H - padT - padB;
		var max = niceMax(Math.max.apply(null, data.map(function (d) { return +d[1] || 0; })));
		yAxis(svg, W, padL, padR, padT, ph, max);
		var n = data.length, gap = n > 40 ? 1 : 3, slot = pw / n, bw = Math.max(1, slot - gap);
		data.forEach(function (d, idx) {
			var v = +d[1] || 0, h = max > 0 ? ph * v / max : 0, x = padL + slot * idx + gap / 2, y = padT + ph - h;
			var r = el('rect', { 'class': 'bar', x: x, y: y, width: bw, height: Math.max(0, h), rx: Math.min(3, bw / 2) });
			r.appendChild(el('title', null, d[0] + ': ' + v)); svg.appendChild(r);
		});
		labelX(svg, data, padL, pw, H - 8, n);
		svg.appendChild(el('line', { 'class': 'axis', x1: padL, y1: padT + ph, x2: W - padR, y2: padT + ph }));
	}

	function lineChart(container, data) {
		if (!data || !data.length) { msg(container, 'empty', 'No data in this window.'); return; }
		var f = frame(container), W = f.W, H = f.H, svg = f.svg;
		var padL = 42, padR = 12, padT = 12, padB = 26, pw = W - padL - padR, ph = H - padT - padB;
		var max = niceMax(Math.max.apply(null, data.map(function (d) { return +d[1] || 0; })));
		yAxis(svg, W, padL, padR, padT, ph, max);
		var n = data.length;
		function X(i) { return n <= 1 ? padL + pw / 2 : padL + pw * i / (n - 1); }
		function Y(v) { return padT + ph - (max > 0 ? ph * v / max : 0); }
		var dPath = '';
		data.forEach(function (d, i) { dPath += (i ? 'L' : 'M') + X(i) + ' ' + Y(+d[1] || 0) + ' '; });
		var dArea = 'M' + X(0) + ' ' + (padT + ph) + ' ' + dPath.replace(/^M/, 'L') + 'L' + X(n - 1) + ' ' + (padT + ph) + ' Z';
		svg.appendChild(el('path', { 'class': 'linearea', d: dArea }));
		svg.appendChild(el('path', { 'class': 'linepath', d: dPath.trim() }));
		if (n <= 31) data.forEach(function (d, i) { var c = el('circle', { 'class': 'dot-m', cx: X(i), cy: Y(+d[1] || 0), r: 2.5 }); c.appendChild(el('title', null, d[0] + ': ' + d[1])); svg.appendChild(c); });
		labelX(svg, data, padL, pw, H - 8, n);
		svg.appendChild(el('line', { 'class': 'axis', x1: padL, y1: padT + ph, x2: W - padR, y2: padT + ph }));
	}

	/* hour x day-of-week heatmap. data = [[dow(0=Mon..6=Sun), hour(0..23), count], …].
	   Single-hue purple intensity ramp — a brightness scale, colorblind-safe. */
	function heatmap(container, data) {
		if (!data || !data.length) { msg(container, 'empty', 'No calls in this window.'); return; }
		var f = frame(container), W = f.W, H = f.H, svg = f.svg;
		var padL = 34, padR = 8, padT = 8, padB = 18;
		var gw = (W - padL - padR) / 24, gh = (H - padT - padB) / 7;
		var days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'], max = 1;
		data.forEach(function (d) { var v = +d[2] || 0; if (v > max) max = v; });
		data.forEach(function (d) {
			var r = +d[0], h = +d[1], v = +d[2] || 0;
			if (r < 0 || r > 6 || h < 0 || h > 23) return;
			var op = v === 0 ? 0.04 : 0.15 + 0.85 * (v / max);
			var cell = el('rect', { x: padL + h * gw + 1, y: padT + r * gh + 1, width: Math.max(1, gw - 2), height: Math.max(1, gh - 2), rx: 2, fill: '#b97cd0', 'fill-opacity': op.toFixed(3) });
			cell.appendChild(el('title', null, days[r] + ' ' + h + ':00 — ' + v + ' calls')); svg.appendChild(cell);
		});
		for (var r = 0; r < 7; r++) svg.appendChild(el('text', { 'class': 'tick', x: padL - 6, y: padT + r * gh + gh / 2 + 3, 'text-anchor': 'end' }, days[r]));
		for (var h = 0; h <= 23; h += 3) svg.appendChild(el('text', { 'class': 'tick', x: padL + h * gw + gw / 2, y: H - 5, 'text-anchor': 'middle' }, String(h)));
	}

	/* call funnel. data = [["Started",n],["Answered",n],…] top-down, width ∝ share of stage 1. */
	function funnel(container, data) {
		var top = data && data.length ? (+data[0][1] || 0) : 0;
		if (!top) { msg(container, 'empty', 'No disposition events yet.'); return; }
		var f = frame(container), W = f.W, H = f.H, svg = f.svg;
		var padT = 10, padB = 8, n = data.length, bh = (H - padT - padB) / n, maxW = W - 24;
		data.forEach(function (d, i) {
			var v = +d[1] || 0, w = maxW * (v / top), x = (W - w) / 2, y = padT + i * bh + 3, pct = Math.round(v / top * 100);
			svg.appendChild(el('rect', { 'class': 'bar', x: x, y: y, width: Math.max(2, w), height: Math.max(2, bh - 8), rx: 4 }));
			svg.appendChild(el('text', { x: W / 2, y: y + (bh - 8) / 2 + 4, 'text-anchor': 'middle', fill: '#fff', 'font-size': '12', 'font-weight': '600' }, d[0] + '   ' + v + '  (' + pct + '%)'));
		});
	}

	/* ASR & abandonment %. data = [["date",answered,abandoned,started],…]. Two lines
	   told apart by style + end-label, not colour alone. */
	function dualLine(container, data) {
		if (!data || !data.length) { msg(container, 'empty', 'No disposition events yet.'); return; }
		var f = frame(container), W = f.W, H = f.H, svg = f.svg;
		var padL = 40, padR = 52, padT = 12, padB = 26, pw = W - padL - padR, ph = H - padT - padB, n = data.length;
		for (var i = 0; i <= 4; i++) { var y = padT + ph - ph * i / 4;
			svg.appendChild(el('line', { 'class': 'gridline', x1: padL, y1: y, x2: W - padR, y2: y }));
			svg.appendChild(el('text', { 'class': 'tick', x: padL - 6, y: y + 3, 'text-anchor': 'end' }, (i * 25) + '%')); }
		function X(i) { return n <= 1 ? padL + pw / 2 : padL + pw * i / (n - 1); }
		function pct(d, k) { var s = +d[3] || 0; return s > 0 ? (+d[k] || 0) / s * 100 : 0; }
		function Y(p) { return padT + ph - ph * p / 100; }
		function line(k, dash, color, label) {
			var dp = ''; data.forEach(function (d, i) { dp += (i ? 'L' : 'M') + X(i) + ' ' + Y(pct(d, k)) + ' '; });
			var p = el('path', { d: dp.trim(), fill: 'none', stroke: color, 'stroke-width': 2 });
			if (dash) p.setAttribute('stroke-dasharray', '5 4');
			svg.appendChild(p);
			svg.appendChild(el('text', { x: W - padR + 4, y: Y(pct(data[n - 1], k)) + 3, fill: color, 'font-size': '11', 'font-weight': '700' }, label));
		}
		line(1, false, '#86c9a6', 'ASR');
		line(2, true, '#dcb877', 'Aband.');
		labelX(svg, data, padL, pw, H - 8, n);
		svg.appendChild(el('line', { 'class': 'axis', x1: padL, y1: padT + ph, x2: W - padR, y2: padT + ph }));
	}

	/* ---- live cluster health (runtime MBeans, not the DB) ---------------- */
	function fmt(v) { return (v == null || v < 0) ? 'n/a' : Number(v).toLocaleString(); }

	/* State is shown as a WORD first (colourblind-safe); the dot only supplements. */
	function stateClass(state, drained) {
		if (drained) return 'state-draining';
		switch (String(state || '').toUpperCase()) {
			case 'RUNNING': return 'state-running';
			case 'SHUTDOWN': case 'FAILED': case 'FORCE_SHUTTING_DOWN': return 'state-down';
			default: return 'state-starting';
		}
	}

	function metric(container, label, value) {
		var d = document.createElement('div'); d.className = 'nm';
		var l = document.createElement('span'); l.className = 'nm__l'; l.textContent = label;
		var b = document.createElement('b'); b.textContent = value;
		d.appendChild(l); d.appendChild(b); container.appendChild(d);
	}

	function healthCard(node) {
		var card = document.createElement('div'); card.className = 'node';
		var top = document.createElement('div'); top.className = 'node__top';
		var badge = document.createElement('span'); badge.className = 'node__state ' + stateClass(node.state, node.drained);
		badge.appendChild(document.createElement('i'));
		badge.appendChild(document.createTextNode(node.drained ? 'DRAINING' : String(node.state || 'UNKNOWN').toUpperCase()));
		var name = document.createElement('span'); name.className = 'node__name'; name.textContent = node.server;
		top.appendChild(badge); top.appendChild(name); card.appendChild(top);

		var m = document.createElement('div'); m.className = 'node__metrics';
		if (node.heapUsedPct != null) {
			var heap = document.createElement('div'); heap.className = 'heap' + (node.heapUsedPct >= 85 ? ' hot' : '');
			var hl = document.createElement('div'); hl.className = 'heap__l';
			hl.innerHTML = 'Heap <b>' + node.heapUsedPct + '%</b>'
				+ (node.heapMaxMb != null ? ' <span>of ' + Number(node.heapMaxMb).toLocaleString() + ' MB</span>' : '')
				+ (node.heapUsedPct >= 85 ? ' <em>high</em>' : '');
			var bar = document.createElement('div'); bar.className = 'heap__bar';
			var fill = document.createElement('div'); fill.className = 'heap__fill'; fill.style.width = Math.min(100, node.heapUsedPct) + '%';
			bar.appendChild(fill); heap.appendChild(hl); heap.appendChild(bar); m.appendChild(heap);
		}
		metric(m, 'SIP sessions', fmt(node.sipSessions));
		metric(m, 'App sessions', fmt(node.appSessions));
		metric(m, 'SIP throughput', fmt(node.sipThroughput));
		var threads = fmt(node.threadsTotal);
		if (node.threadsStuck > 0) threads += '  (' + node.threadsStuck + ' stuck)';
		else if (node.threadsPending > 0) threads += '  (' + node.threadsPending + ' pending)';
		metric(m, 'Threads', threads);
		card.appendChild(m);

		if (node.jdbc && node.jdbc.length) {
			var j = document.createElement('div'); j.className = 'node__jdbc';
			j.textContent = 'JDBC  ' + node.jdbc.map(function (d) {
				return d.name + ' ' + fmt(d.active) + '/' + fmt(d.capacity) + (d.waiting > 0 ? ' (' + d.waiting + ' waiting)' : '');
			}).join('    ');
			card.appendChild(j);
		}
		return card;
	}

	/* ---- history: a client-side rolling hour, per server ----------------- */
	/* The MBeans give an instant; we accumulate the 5s samples into a ring so
	   the overlay has a time axis. Resets on reload (server-side buffer later). */
	var HIST = {};                 // server -> [{t, sip, app, thru, heap, threads}]
	var HIST_MS = 3600000;         // keep the last hour
	var lastNodes = null;

	var METRICS = {
		sip:     { label: 'SIP sessions',   hk: 'sip',     pct: false },
		heap:    { label: 'Heap %',         hk: 'heap',    pct: true },
		thru:    { label: 'SIP throughput', hk: 'thru',    pct: false },
		threads: { label: 'Threads',        hk: 'threads', pct: false }
	};
	var healthMetric = 'sip';
	/* Colour is only a secondary cue; the end-of-line label is the identifier. */
	var SERIES_COLORS = ['#b97cd0', '#86c9a6', '#dcb877', '#7fb0e0', '#e0899f', '#9d8cf0'];

	function pushHistory(nodes, now) {
		nodes.forEach(function (n) {
			var a = HIST[n.server] || (HIST[n.server] = []);
			a.push({ t: now, sip: n.sipSessions, app: n.appSessions, thru: n.sipThroughput, heap: n.heapUsedPct, threads: n.threadsTotal });
			var cut = now - HIST_MS;
			while (a.length && a[0].t < cut) a.shift();
		});
	}

	function clusterLabel(c) {
		if (c == null) return 'Admin';
		if (/ENGINE/i.test(c)) return 'Engine tier';
		if (/MEDIA/i.test(c)) return 'Media tier';
		return c;
	}

	/* One line per server, distinguished by an end-of-line NAME label (not colour
	   alone). Drawn from the client-side history buffer. */
	function overlayChart(container, servers, metricKey) {
		var m = METRICS[metricKey];
		var series = servers.map(function (s) {
			return { name: s, pts: (HIST[s] || []).filter(function (p) { return p[m.hk] != null && p[m.hk] >= 0; }) };
		}).filter(function (s) { return s.pts.length; });
		if (!series.length) { msg(container, 'empty', 'Collecting… the line fills in as samples arrive.'); return; }
		var f = frame(container), W = f.W, H = f.H, svg = f.svg;
		var padL = 40, padR = 66, padT = 12, padB = 22, pw = W - padL - padR, ph = H - padT - padB;
		var tMax = 0, tMin = Infinity, vMax = m.pct ? 100 : 1;
		series.forEach(function (s) { s.pts.forEach(function (p) {
			if (p.t > tMax) tMax = p.t; if (p.t < tMin) tMin = p.t;
			if (!m.pct && p[m.hk] > vMax) vMax = p[m.hk];
		}); });
		if (!m.pct) vMax = niceMax(vMax);
		if (tMax === tMin) tMin = tMax - 1000;
		function X(t) { return padL + pw * (t - tMin) / (tMax - tMin); }
		function Y(v) { return padT + ph - ph * Math.min(v, vMax) / vMax; }
		for (var i = 0; i <= 4; i++) { var y = padT + ph - ph * i / 4;
			svg.appendChild(el('line', { 'class': 'gridline', x1: padL, y1: y, x2: W - padR, y2: y }));
			svg.appendChild(el('text', { 'class': 'tick', x: padL - 6, y: y + 3, 'text-anchor': 'end' }, Math.round(vMax * i / 4) + (m.pct ? '%' : ''))); }
		[0, 0.5, 1].forEach(function (fr) { var t = tMin + (tMax - tMin) * fr, x = X(t);
			svg.appendChild(el('text', { 'class': 'tick', x: x, y: H - 6, 'text-anchor': fr === 0 ? 'start' : (fr === 1 ? 'end' : 'middle') },
				new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))); });
		series.forEach(function (s, idx) {
			var color = SERIES_COLORS[idx % SERIES_COLORS.length];
			var dp = ''; s.pts.forEach(function (p, j) { dp += (j ? 'L' : 'M') + X(p.t).toFixed(1) + ' ' + Y(p[m.hk]).toFixed(1) + ' '; });
			svg.appendChild(el('path', { d: dp.trim(), fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
			var last = s.pts[s.pts.length - 1];
			svg.appendChild(el('text', { x: W - padR + 5, y: Y(last[m.hk]) + 3, fill: color, 'font-size': '11', 'font-weight': '700' }, s.name));
		});
		svg.appendChild(el('line', { 'class': 'axis', x1: padL, y1: padT + ph, x2: W - padR, y2: padT + ph }));
	}

	function renderGroups(nodes) {
		var host = document.getElementById('cluster-groups');
		if (!host) return;
		clear(host);
		var groups = {};
		nodes.forEach(function (n) { var k = n.cluster || ' '; (groups[k] || (groups[k] = [])).push(n); });
		Object.keys(groups).sort().forEach(function (k) {
			var members = groups[k], cluster = k === ' ' ? null : k;
			var group = document.createElement('div'); group.className = 'cluster-group';

			var head = document.createElement('div'); head.className = 'cluster-group__head';
			var nm = document.createElement('span'); nm.className = 'cluster-group__name'; nm.textContent = clusterLabel(cluster);
			var meta = document.createElement('span'); meta.className = 'cluster-group__meta';
			meta.textContent = members.length + (members.length === 1 ? ' node' : ' nodes') + '  ·  ' + (cluster || 'standalone');
			head.appendChild(nm); head.appendChild(meta); group.appendChild(head);

			var chartCard = document.createElement('div'); chartCard.className = 'card cluster-chart';
			var chHead = document.createElement('div'); chHead.className = 'card__head';
			var chTitle = document.createElement('span'); chTitle.className = 'card__title'; chTitle.textContent = METRICS[healthMetric].label + ' over time';
			var chSub = document.createElement('span'); chSub.className = 'card__sub'; chSub.textContent = 'last hour';
			chHead.appendChild(chTitle); chHead.appendChild(chSub); chartCard.appendChild(chHead);
			var chBody = document.createElement('div'); chBody.className = 'card__body'; chartCard.appendChild(chBody);
			group.appendChild(chartCard);

			var cards = document.createElement('div'); cards.className = 'cluster-nodes';
			members.forEach(function (n) { cards.appendChild(healthCard(n)); });
			group.appendChild(cards);

			host.appendChild(group);
			overlayChart(chBody, members.map(function (n) { return n.server; }), healthMetric);
		});
	}

	function initMetricToggle() {
		var host = document.getElementById('metric-toggle');
		if (!host) return;
		Object.keys(METRICS).forEach(function (k) {
			var b = document.createElement('button');
			b.type = 'button'; b.textContent = METRICS[k].label; b.className = (k === healthMetric ? 'on' : '');
			b.addEventListener('click', function () {
				healthMetric = k;
				Array.prototype.forEach.call(host.children, function (c) { c.className = (c === b ? 'on' : ''); });
				if (lastNodes) renderGroups(lastNodes);
			});
			host.appendChild(b);
		});
	}

	function loadHealth() {
		return getJson('health').then(function (r) {
			var host = document.getElementById('cluster-groups');
			if (!r || r.error) { if (host) msg(host, 'error', (r && r.error) || 'health failed'); return; }
			var nodes = r.nodes || [], running = 0, sip = 0, app = 0, thru = 0;
			pushHistory(nodes, Date.now());
			nodes.forEach(function (n) {
				if (String(n.state).toUpperCase() === 'RUNNING' && !n.drained) running++;
				if (n.sipSessions > 0) sip += n.sipSessions;
				if (n.appSessions > 0) app += n.appSessions;
				if (n.sipThroughput > 0) thru += n.sipThroughput;
			});
			setTile('t-nodes', running + ' / ' + nodes.length);
			setTile('t-sip-sessions', sip.toLocaleString());
			setTile('t-app-sessions', app.toLocaleString());
			setTile('t-throughput', thru.toLocaleString());
			lastNodes = nodes;
			renderGroups(nodes);
		});
	}

	var CHARTS = [
		{ id: 'chart-calls-per-day',   q: 'calls-per-day&days=30',   render: barChart },
		{ id: 'chart-calls-per-hour',  q: 'calls-per-hour&days=2',   render: barChart },
		{ id: 'chart-concurrent',      q: 'concurrent&days=2',       render: lineChart },
		{ id: 'chart-heatmap',         q: 'heatmap&days=30',         render: heatmap },
		{ id: 'chart-asr-abandon',     q: 'asr-abandon&days=30',     render: dualLine },
		{ id: 'chart-funnel',          q: 'funnel&days=30',          render: funnel },
		{ id: 'chart-duration-hist',   q: 'duration-hist&days=30',   render: barChart },
		{ id: 'chart-calls-by-app',    q: 'calls-by-app&days=7',     render: barChart },
		{ id: 'chart-avg-duration',    q: 'avg-duration&days=30',    render: lineChart },
		{ id: 'chart-calls-by-tenant', q: 'calls-by-tenant&days=30', render: barChart },
		{ id: 'chart-event-types',     q: 'event-types&days=30',     render: barChart }
	];

	function loadCharts() {
		CHARTS.forEach(function (c) {
			var box = document.getElementById(c.id);
			getJson('data?q=' + c.q).then(function (rows) {
				if (rows && rows.error) { msg(box, 'error', rows.error); return; }
				c.render(box, rows);
			}).catch(function (e) { if (e.message !== 'auth') msg(box, 'error', 'query failed'); });
		});
	}

	function setTile(id, v, unit) {
		var e = document.getElementById(id); if (!e) return;
		if (v == null) { e.textContent = '—'; return; }
		e.textContent = String(v);
		if (unit) { var em = document.createElement('em'); em.textContent = unit; e.appendChild(em); }
	}

	function loadStats() {
		return getJson('data?q=stats').then(function (s) {
			if (!s || s.error) return;
			setTile('t-callsToday', s.callsToday);
			setTile('t-activeCalls', s.activeCalls);
			setTile('t-avgDurationSec', s.avgDurationSec, 's');
			setTile('t-apps', s.apps);
		});
	}

	function refresh() {
		setStatus('', 'refreshing…');
		loadHealth().catch(function () {});
		loadStats().then(function () {
			loadCharts();
			updatedEl.textContent = new Date().toLocaleTimeString();
			setStatus('', 'live');
		}).catch(function (e) { if (e.message !== 'auth') setStatus('err', 'error'); });
	}

	var rz;
	window.addEventListener('resize', function () { clearTimeout(rz); rz = setTimeout(function () { loadCharts(); if (lastNodes) renderGroups(lastNodes); }, 200); });
	initMetricToggle();
	refresh();
	setInterval(refresh, REFRESH_MS);
	setInterval(function () { loadHealth().catch(function () {}); }, HEALTH_MS);
})();
