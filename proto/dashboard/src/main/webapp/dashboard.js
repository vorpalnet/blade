/* BLADE Dashboard — no dependencies, no CDN. Fetches the analytics servlet and
   draws hand-rolled SVG charts, refreshing on a timer. */
(function () {
	'use strict';
	var NS = 'http://www.w3.org/2000/svg';
	var REFRESH_MS = 30000;
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

	var CHARTS = [
		{ id: 'chart-calls-per-day', q: 'calls-per-day&days=30', render: barChart },
		{ id: 'chart-calls-by-app', q: 'calls-by-app&days=7', render: barChart },
		{ id: 'chart-avg-duration', q: 'avg-duration&days=30', render: lineChart }
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
		loadStats().then(function () {
			loadCharts();
			updatedEl.textContent = new Date().toLocaleTimeString();
			setStatus('', 'live');
		}).catch(function (e) { if (e.message !== 'auth') setStatus('err', 'error'); });
	}

	var rz;
	window.addEventListener('resize', function () { clearTimeout(rz); rz = setTimeout(loadCharts, 200); });
	refresh();
	setInterval(refresh, REFRESH_MS);
})();
