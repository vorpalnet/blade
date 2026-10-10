/* BLADE Dashboard board: everything on one screen, nothing below the fold.

   A row of headline numbers, then six panels. Each panel shows one view at a
   time and switches between views with the tabs in its header, rather than
   stacking more charts beneath it. A panel's title opens its full report
   (report.html), which is where the scrolling detail lives.

   This domain's servers and operations are live (runtime MBeans and the event
   bus, every 5 s). Another domain's servers come from the minute samples every
   domain's dashboard writes to the analytics database. The analytics panels
   come from the reporting views (cached on the server, every 60 s), over the
   range and domain chosen in the top bar. Charts are drawn by charts.js; the
   report definitions are in reports.js. */
(function () {
	'use strict';
	var C = window.BladeCharts, R = window.BladeReports, getJson = R.getJson;
	/* Replaced by the dashboard's settings (/settings) when the board opens. */
	var LIVE_MS = 5000, ANALYTICS_MS = 60000, HEAP_HIGH = 85;
	/* What this viewer's grants show, from /settings. The server enforces it;
	   the board only says so instead of showing empty panels. */
	var RIGHTS = { platform: true, content: true };
	var RANGES = [['1', 'Last day'], ['7', '7 days'], ['30', '30 days']];
	var LINE_COLORS = function () { return [C.token('--accent'), C.token('--ok'), C.token('--warn'), C.token('--info')]; };

	function store(key, value) {
		try {
			if (value === undefined) return localStorage.getItem('blade.board.' + key);
			localStorage.setItem('blade.board.' + key, value);
		} catch (e) { /* private window: the board works without it */ }
		return null;
	}

	var days = store('days') || '7';
	if (!RANGES.some(function (r) { return r[0] === days; })) days = '7';

	/* The domain shown: '' is every domain. localDomain is the one this board
	   runs in, the only one it can read live. */
	var scope = store('domain') || '', localDomain = null;
	function remoteScope() { return !!scope && !!localDomain && scope !== localDomain; }
	function scopeQuery() { return scope ? '&domain=' + encodeURIComponent(scope) : ''; }

	function el(tag, cls, text) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (text != null) e.textContent = text;
		return e;
	}

	function cell(result, column) {
		if (!result || !result.rows || !result.rows.length) return null;
		var i = result.columns.indexOf(column);
		return i < 0 ? null : result.rows[0][i];
	}

	function rowsOf(result) { return (result && result.rows) || []; }

	function seriesOf(result, names) {
		return names.map(function (name) {
			var i = result.columns.indexOf(name);
			return { name: name, values: result.rows.map(function (r) { return [r[0], r[i]]; }) };
		});
	}

	/* ── headline numbers ──────────────────────────────────────────────── */

	/* Each tile: a label, the number, and a line in words. The word carries any
	   state ("all running", "2 down"), never the colour alone. */
	var KPI = [
		{ id: 'nodes', label: 'Servers running' },
		{ id: 'sessions', label: 'SIP sessions now' },
		{ id: 'calls', label: 'Calls', ranged: true },
		{ id: 'answered', label: 'Answered', ranged: true },
		{ id: 'duration', label: 'Median call', ranged: true },
		{ id: 'flagged', label: 'Calls flagged', ranged: true },
		{ id: 'trouble', label: 'Wrong now' },
		{ id: 'gaps', label: 'Data gaps', ranged: true }
	];
	var kpiEls = {};

	function buildKpis() {
		var host = document.getElementById('kpis');
		KPI.forEach(function (k) {
			var t = el('div', 'kpi');
			var l = el('div', 'kpi__l', k.label);
			if (k.ranged) { var r = el('span', 'kpi__range'); l.appendChild(r); k.rangeEl = r; }
			var v = el('div', 'kpi__v', '—');
			var s = el('div', 'kpi__s', '');
			t.appendChild(l); t.appendChild(v); t.appendChild(s);
			host.appendChild(t);
			kpiEls[k.id] = { tile: t, v: v, s: s, k: k };
		});
		labelRanges();
	}

	function labelRanges() {
		var word = RANGES.filter(function (r) { return r[0] === days; })[0][1].toLowerCase();
		KPI.forEach(function (k) { if (k.rangeEl) k.rangeEl.textContent = ' · ' + word; });
	}

	/* alert: the tile is outlined and its line says what is wrong. */
	function kpi(id, value, sub, alert) {
		var e = kpiEls[id];
		e.v.textContent = value == null ? '—' : (typeof value === 'number' ? C.fmt(value) : value);
		e.s.textContent = sub || '';
		e.tile.classList.toggle('kpi--alert', !!alert);
	}

	/* ── panels ────────────────────────────────────────────────────────── */

	/* views: [{ key, label, draw(body) }]. The chosen tab is remembered per panel. */
	function Panel(name, title, family, sub) {
		this.name = name;
		this.host = document.querySelector('[data-panel="' + name + '"]');
		var head = el('div', 'panel__head');
		var t = family ? el('a', 'panel__title', title) : el('span', 'panel__title', title);
		if (family) { t.href = 'report.html?r=' + family + '&days=' + days + scopeQuery(); t.title = 'Open the full report'; this.link = t; this.family = family; }
		this.sub = el('span', 'panel__sub', sub || '');
		this.tabs = el('div', 'tabs');
		head.appendChild(t); head.appendChild(this.sub); head.appendChild(this.tabs);
		this.body = el('div', 'panel__body');
		this.host.appendChild(head); this.host.appendChild(this.body);
		this.views = [];
		this.current = store('tab.' + name);
	}

	Panel.prototype.setViews = function (views) {
		var self = this;
		this.views = views;
		if (!views.some(function (v) { return v.key === self.current; })) this.current = views[0].key;
		this.tabs.textContent = '';
		if (views.length > 1) {
			views.forEach(function (v) {
				var b = el('button', v.key === self.current ? 'on' : '', v.label);
				b.type = 'button';
				b.addEventListener('click', function () {
					self.current = v.key; store('tab.' + self.name, v.key);
					Array.prototype.forEach.call(self.tabs.children, function (c) { c.className = c === b ? 'on' : ''; });
					self.draw();
					if (v.fetch) v.fetch();
				});
				self.tabs.appendChild(b);
			});
		}
	};

	Panel.prototype.draw = function () {
		var self = this;
		var view = this.views.filter(function (v) { return v.key === self.current; })[0];
		if (!view) return;
		this.body.textContent = '';
		this.sub.textContent = view.sub || '';
		try { view.draw(this.body); } catch (e) { C.message(this.body, 'error', String(e.message || e)); }
	};

	Panel.prototype.relink = function () {
		if (this.link) this.link.href = 'report.html?r=' + this.family + '&days=' + days + scopeQuery();
	};

	/* A report result into a panel body, or its error. */
	function guard(result, body, fn) {
		if (!result) return C.message(body, 'empty', 'Loading…');
		if (result.error) return C.message(body, 'error', result.error);
		return fn(result);
	}

	/* A table cut to the rows that fit, with the count of the rest. Nothing on
	   the board scrolls: the full list is one click away in the report. */
	function fitTable(body, result) {
		C.table(body, result, { limit: 40 });
		var wrap = body.querySelector('.table-wrap');
		if (!wrap) return;
		var rows = wrap.querySelectorAll('tbody tr');
		var room = body.clientHeight - (wrap.querySelector('thead') ? wrap.querySelector('thead').offsetHeight : 0) - 18;
		var used = 0, shown = 0;
		for (var i = 0; i < rows.length; i++) {
			used += rows[i].offsetHeight;
			if (used > room) { rows[i].style.display = 'none'; } else { shown++; }
		}
		var total = result.rows.length;
		if (shown < total) body.appendChild(el('div', 'panel__more', (total - shown) + ' more in the full report'));
	}

	/* Only the named columns of a report, in that order. */
	function pick(result, keep) {
		var idx = keep.map(function (c) { return result.columns.indexOf(c); });
		return { columns: keep, rows: result.rows.map(function (r) { return idx.map(function (i) { return r[i]; }); }) };
	}

	/* ── live: cluster health and operations ───────────────────────────── */

	var HIST = {}, HIST_MS = 3600000, lastNodes = [], lastOps = null;

	function pushHistory(nodes, now) {
		nodes.forEach(function (n) {
			var a = HIST[n.server] || (HIST[n.server] = []);
			a.push({ t: now, sip: n.sipSessions, heap: n.heapUsedPct, thru: n.sipThroughput });
			while (a.length && a[0].t < now - HIST_MS) a.shift();
		});
	}

	function liveSeries(key) {
		var times = [];
		Object.keys(HIST).forEach(function (s) { HIST[s].forEach(function (p) { if (times.indexOf(p.t) < 0) times.push(p.t); }); });
		times.sort(function (a, b) { return a - b; });
		var label = function (t) { return R.time(t, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }); };
		return Object.keys(HIST).sort().map(function (s) {
			var byT = {};
			HIST[s].forEach(function (p) { if (p[key] != null && p[key] >= 0) byT[p.t] = p[key]; });
			return { name: s, values: times.map(function (t) { return [label(t), byT[t] == null ? null : byT[t]]; }) };
		}).filter(function (s) { return s.values.some(function (v) { return v[1] != null; }); });
	}

	function stateWord(n) {
		if (n.drained) return 'DRAINING';
		return String(n.state || 'UNKNOWN').toUpperCase();
	}

	function stateClass(n) {
		if (n.drained) return 'state-draining';
		switch (stateWord(n)) {
			case 'RUNNING': return 'state-running';
			case 'SHUTDOWN': case 'FAILED': case 'FORCE_SHUTTING_DOWN': return 'state-down';
			default: return 'state-starting';
		}
	}

	function num(v) { return (v == null || v < 0) ? 'n/a' : C.fmt(v); }

	/* The servers in scope: this domain's from the runtime MBeans, every other
	   domain's from its latest minute sample (platform.latest), marked with the
	   sample's age. */
	function visibleServers() {
		var out = [];
		if (!remoteScope()) lastNodes.forEach(function (n) { n.domain = localDomain; out.push(n); });
		rowsOf(A['platform.latest']).forEach(function (r) {
			var domain = r[0];
			if (domain === localDomain || (scope && domain !== scope)) return;
			out.push({ domain: domain, server: r[1], cluster: r[2], state: r[3], heapUsedPct: r[4], appSessions: r[5],
				sipSessions: r[6], threadsStuck: r[7], threadsHogging: r[8], sampled: r[9] });
		});
		return out;
	}

	/* One line per server: state word, name, heap bar with its number, sessions
	   and threads. Grouped by domain, then cluster, in name order; a domain read
	   from its samples says how old the sample is. */
	function drawServers(body) {
		if (localOnly(body, 'Servers')) return;
		var nodes = visibleServers();
		if (!nodes.length) return C.message(body, 'empty', remoteScope()
			? 'No sample from ' + scope + ' in the last three minutes. Its dashboard samples each minute; is it running?'
			: 'Reading the runtime MBeans…');
		var list = el('div', 'srv');
		nodes.sort(function (a, b) {
			return String(a.domain || '').localeCompare(String(b.domain || '')) || String(a.cluster || '').localeCompare(String(b.cluster || ''))
				|| String(a.server).localeCompare(String(b.server));
		});
		var domains = nodes.map(function (n) { return n.domain; }).filter(function (d, i, a) { return a.indexOf(d) === i; });
		var head = el('div', 'srv__row srv__row--head');
		['state', 'server', 'heap', 'SIP sessions', 'threads'].forEach(function (h) { head.appendChild(el('span', '', h)); });
		list.appendChild(head);
		var shownDomain;
		nodes.forEach(function (n) {
			if (domains.length > 1 && n.domain !== shownDomain) {
				shownDomain = n.domain;
				var age = n.sampled ? Math.max(0, Math.round((Date.now() - n.sampled) / 1000)) : null;
				list.appendChild(el('div', 'srv__domain', (n.domain || 'unnamed domain')
					+ (n.domain === localDomain ? ' · live' : (age == null ? '' : ' · sampled ' + age + ' s ago'))));
			}
			var row = el('div', 'srv__row');
			var st = el('span', 'srv__state ' + stateClass(n)); st.appendChild(el('i')); st.appendChild(document.createTextNode(stateWord(n)));
			row.appendChild(st);
			var name = el('span', 'srv__name', n.server);
			name.title = n.cluster || 'standalone';
			row.appendChild(name);
			var heap = el('span', 'srv__heap' + (n.heapUsedPct >= HEAP_HIGH ? ' hot' : ''));
			if (n.heapUsedPct != null) {
				var bar = el('span', 'srv__bar'); var fill = el('span', 'srv__fill'); fill.style.width = Math.min(100, n.heapUsedPct) + '%';
				bar.appendChild(fill); heap.appendChild(bar);
				heap.appendChild(el('b', '', n.heapUsedPct + '%' + (n.heapUsedPct >= HEAP_HIGH ? ' high' : '')));
			} else heap.appendChild(el('b', '', 'n/a'));
			row.appendChild(heap);
			row.appendChild(el('span', 'srv__n', num(n.sipSessions)));
			var threads = n.sampled ? '' : num(n.threadsTotal);
			if (n.threadsStuck > 0) threads += (threads ? ' · ' : '') + n.threadsStuck + ' stuck';
			else if (n.threadsPending > 0) threads += ' · ' + n.threadsPending + ' pending';
			else if (n.threadsHogging > 0) threads += (threads ? ' · ' : '') + n.threadsHogging + ' hogging';
			if (!threads) threads = 'none stuck';
			row.appendChild(el('span', 'srv__n' + (n.threadsStuck > 0 ? ' warn' : ''), threads));
			list.appendChild(row);
		});
		body.appendChild(list);
	}

	/* A view that only this domain's runtime can answer. */
	function localOnly(body, what) {
		if (!RIGHTS.platform) {
			C.message(body, 'empty', what + ' are not in your grant.');
			return true;
		}
		if (!remoteScope()) return false;
		C.message(body, 'empty', what + ' come from this domain, ' + localDomain + ', read live. Choose it, or every domain, to see them.');
		return true;
	}

	function drawJdbc(body) {
		if (localOnly(body, 'Connection pools')) return;
		var rows = [];
		lastNodes.forEach(function (n) {
			(n.jdbc || []).forEach(function (d) { rows.push([n.server, d.name, d.active, d.capacity, d.waiting]); });
		});
		if (!rows.length) return C.message(body, 'empty', 'No JDBC pools reported.');
		fitTable(body, { columns: ['server', 'pool', 'active', 'capacity', 'waiting'], rows: rows });
	}

	var OPS_WORDS = {
		'org.vorpal.blade.endpoint.down': 'down', 'org.vorpal.blade.endpoint.up': 'up',
		'org.vorpal.blade.trunk.failed': 'failing', 'org.vorpal.blade.trunk.registered': 'registered',
		'org.vorpal.blade.trunk.unregistered': 'unregistered', 'org.vorpal.blade.queue.depth': 'queue',
		'org.vorpal.blade.registration.added': 'registered', 'org.vorpal.blade.registration.removed': 'removed',
		'org.vorpal.blade.presence.published': 'presence', 'org.vorpal.blade.sip.denied': 'refused',
		'org.vorpal.blade.config.saved': 'config', 'org.vorpal.blade.config.published': 'pushed'
	};
	var OPS_BAD = { down: 1, failing: 1, refused: 1 };

	function opsSummary(e) {
		switch (e.type) {
		case 'org.vorpal.blade.endpoint.down':
		case 'org.vorpal.blade.endpoint.up': return e.endpoint + (e.note ? ' · ' + e.note : '');
		case 'org.vorpal.blade.trunk.failed': return e.gateway + ' · ' + (e.status || '') + ' ' + (e.reason || '');
		case 'org.vorpal.blade.trunk.registered':
		case 'org.vorpal.blade.trunk.unregistered': return e.gateway + ' · ' + e.registrar;
		case 'org.vorpal.blade.queue.depth': return e.queue + ' · ' + e.depth + ' waiting (low ' + e.low + ', high ' + e.high + ')';
		case 'org.vorpal.blade.registration.added':
		case 'org.vorpal.blade.registration.removed': return e.aor + ' · ' + e.contact + (e.reason ? ' · ' + e.reason : '');
		case 'org.vorpal.blade.presence.published': return e.entity + (e.basic ? ' · ' + e.basic : '');
		case 'org.vorpal.blade.sip.denied': return e.method + ' from ' + e.sourceAddress;
		case 'org.vorpal.blade.config.saved': return e.file + ' ' + e.change + ' by ' + e.actor + ' (' + e.editor + ')';
		case 'org.vorpal.blade.config.published': return e.file + ' → ' + (e.pushedTo || []).join(', ') + ((e.failed && e.failed.length) ? ' · FAILED on ' + e.failed.join(', ') : '');
		default: return e.type;
		}
	}

	/* Rows that fit, newest first, then a count of the rest. */
	function opsRows(body, items, word, empty) {
		if (localOnly(body, 'Operations events')) return;
		if (!lastOps) return C.message(body, 'empty', 'Listening to the event bus…');
		if (!items.length) return C.message(body, 'empty', empty);
		var box = el('div', 'ops-list');
		body.appendChild(box);
		var shown = 0;
		for (var i = 0; i < items.length; i++) {
			var e = items[i], w = word(e);
			var row = el('div', 'ops-row');
			row.appendChild(el('span', 'ops-row__what' + (OPS_BAD[w] ? ' bad' : ''), w));
			row.appendChild(el('span', 'ops-row__main', opsSummary(e) + (e.node ? ' · ' + e.node : '')));
			row.appendChild(el('span', 'ops-row__when', e.time ? R.time(new Date(e.time).getTime()) : ''));
			box.appendChild(row);
			if (box.scrollHeight > body.clientHeight - 20) { box.removeChild(row); break; }
			shown++;
		}
		if (shown < items.length) body.appendChild(el('div', 'panel__more', (items.length - shown) + ' more'));
	}

	function wrongNow() {
		if (!lastOps) return [];
		return lastOps.endpointsDown.map(function (e) { e.type = 'org.vorpal.blade.endpoint.down'; return e; })
			.concat(lastOps.trunksFailing.map(function (e) { e.type = 'org.vorpal.blade.trunk.failed'; return e; }));
	}

	/* ── analytics ─────────────────────────────────────────────────────── */

	var A = {};   // report name → result

	function hourly() { return +days <= 7; }

	function analyticsReports() {
		var r = ['calls.domains', 'platform.latest', 'calls.summary', 'calls.daily', 'calls.heatmap', 'calls.by-application',
			'outcomes.summary', 'outcomes.daily', 'outcomes.funnel', 'outcomes.by-application',
			'duration.summary', 'duration.bands',
			'health.summary', 'health.coverage',
			'risk.summary', 'highrisk.by-band', 'risk.daily'];
		// Individual calls only while their tab is open: a request for them is
		// audited as someone looking at calls, which a hidden tab is not.
		if (P.risk && P.risk.current === 'calls') r.push('highrisk.calls');
		if (hourly()) r.push('calls.hourly');
		return r;
	}

	/* ── panel definitions ─────────────────────────────────────────────── */

	var P = {};

	function buildPanels() {
		P.cluster = new Panel('cluster', 'Servers', 'platform');
		P.cluster.setViews([
			{ key: 'servers', label: 'Now', sub: 'live, other domains a minute behind', draw: drawServers },
			{ key: 'jdbc', label: 'JDBC', sub: 'live', draw: drawJdbc }
		]);

		P.traffic = new Panel('traffic', 'Traffic', 'calls');
		var live = function (key, pct, what) {
			return function (body) {
				if (localOnly(body, 'Live lines')) return;
				C.multiLine(body, liveSeries(key), { pct: pct, colors: LINE_COLORS(),
					empty: 'Collecting… the line fills in as the 5-second samples arrive.',
					tickFormat: function (s) { return String(s).slice(0, 5); } });
			};
		};
		P.traffic.setViews([
			{ key: 'sip', label: 'SIP sessions', sub: 'per server · last hour, live', draw: live('sip', false) },
			{ key: 'heap', label: 'Heap', sub: 'per server · last hour, live', draw: live('heap', true) },
			{ key: 'calls', label: 'Calls', sub: 'per hour, or per day past a week', draw: function (body) {
				var r = hourly() ? A['calls.hourly'] : A['calls.daily'];
				guard(r, body, function (res) { C.bar(body, res.rows); });
			} },
			{ key: 'when', label: 'When', sub: 'weekday × hour of day', draw: function (body) {
				guard(A['calls.heatmap'], body, function (res) { C.heatmap(body, res.rows); });
			} }
		]);

		P.ops = new Panel('ops', 'Operations', null);
		P.ops.setViews([
			{ key: 'wrong', label: 'Wrong now', sub: 'until the event that clears it', draw: function (body) {
				opsRows(body, wrongNow(), function (e) { return OPS_WORDS[e.type]; },
					'Nothing down. An endpoint or trunk shows here from the event that reports it until the one that clears it.');
			} },
			{ key: 'queues', label: 'Queues', sub: 'last minute · per node', draw: function (body) {
				var q = lastOps ? lastOps.queues.map(function (e) { e.type = 'org.vorpal.blade.queue.depth'; return e; }) : [];
				opsRows(body, q, function () { return 'queue'; }, 'No queue has held a caller since this server started listening.');
			} },
			{ key: 'recent', label: 'Recent', sub: 'newest first', draw: function (body) {
				opsRows(body, lastOps ? lastOps.recent : [], function (e) { return OPS_WORDS[e.type] || 'event'; }, 'No operations events yet.');
			} }
		]);

		P.outcomes = new Panel('outcomes', 'Outcomes', 'outcomes');
		P.outcomes.setViews([
			{ key: 'funnel', label: 'Funnel', sub: 'applications that report answering', draw: function (body) {
				guard(A['outcomes.funnel'], body, function (res) { C.funnel(body, res.rows); });
			} },
			{ key: 'daily', label: 'Per day', draw: function (body) {
				guard(A['outcomes.daily'], body, function (res) {
					C.multiLine(body, seriesOf(res, ['started', 'answered', 'lost']), { colors: LINE_COLORS() });
				});
			} },
			{ key: 'apps', label: 'By application', draw: function (body) {
				guard(A['outcomes.by-application'], body, function (res) { fitTable(body, res); });
			} }
		]);

		P.apps = new Panel('apps', 'Applications', 'health');
		P.apps.setViews([
			{ key: 'calls', label: 'Calls', sub: 'a call through two applications counts for each', draw: function (body) {
				guard(A['calls.by-application'], body, function (res) { C.hbar(body, res.rows, { limit: 8 }); });
			} },
			{ key: 'length', label: 'Length', sub: 'calls by duration', draw: function (body) {
				guard(A['duration.bands'], body, function (res) { C.bar(body, res.rows); });
			} },
			{ key: 'gaps', label: 'Gaps', sub: 'lifecycle events each application never sends', draw: function (body) {
				guard(A['health.coverage'], body, function (res) {
					fitTable(body, pick(res, ['application', 'calls', 'gaps']));
				});
			} }
		]);

		P.risk = new Panel('risk', 'Call risk', 'risk');
		P.risk.setViews([
			{ key: 'bands', label: 'Bands', sub: 'calls by worst band reached', draw: function (body) {
				guard(A['highrisk.by-band'], body, function (res) { R.bandList(body, res.rows); });
			} },
			{ key: 'daily', label: 'Per day', sub: 'calls assessed', draw: function (body) {
				guard(A['risk.daily'], body, function (res) { C.bar(body, res.rows); });
			} },
			{ key: 'calls', label: 'Flagged calls', sub: 'highest score first', fetch: function () {
				if (!A['highrisk.calls']) loadAnalytics();
			}, draw: function (body) {
				// Four columns fit a third of the screen; the full report has the rest.
				guard(A['highrisk.calls'], body, function (res) { fitTable(body, pick(res, ['worst band', 'peak score', 'call', 'first assessed'])); });
			} }
		]);
	}

	function drawAll() { Object.keys(P).forEach(function (k) { P[k].draw(); }); }

	/* ── loading ───────────────────────────────────────────────────────── */

	var statusEl = document.getElementById('status'), statusText = document.getElementById('statusText');
	function setStatus(kind, text) { statusEl.className = 'status' + (kind ? ' ' + kind : ''); statusText.textContent = text; }

	function loadHealth() {
		return getJson('health').then(function (r) {
			if (!r || r.error) { kpi('nodes', null, (r && r.error) || 'health failed', true); return; }
			var nodes = r.nodes || [];
			pushHistory(nodes, Date.now());
			lastNodes = nodes;
			if (r.domain && r.domain !== localDomain) { localDomain = r.domain; fillScope(); }
			serverKpis();
			P.cluster.draw();
			if (P.traffic.current === 'sip' || P.traffic.current === 'heap') P.traffic.draw();
		});
	}

	/* Servers running and SIP sessions, over the servers in scope. */
	function serverKpis() {
		var nodes = visibleServers(), running = 0, sip = 0, app = 0, notRunning = [];
		nodes.forEach(function (n) {
			if (stateWord(n) === 'RUNNING') running++; else notRunning.push(n.server + ' ' + stateWord(n).toLowerCase());
			if (n.sipSessions > 0) sip += n.sipSessions;
			if (n.appSessions > 0) app += n.appSessions;
		});
		if (!nodes.length) { kpi('nodes', null, remoteScope() ? 'no recent sample' : ''); kpi('sessions', null, ''); return; }
		kpi('nodes', running + ' / ' + nodes.length, notRunning.length ? notRunning.join(', ') : 'all running', notRunning.length > 0);
		kpi('sessions', sip, C.fmt(app) + ' application sessions' + (remoteScope() ? ' · a minute behind' : ''));
	}

	/* The domain picker: every domain with calls in the last month or a recent
	   server sample, and this one. */
	function fillScope() {
		var sel = document.getElementById('scope'), names = [];
		var add = function (d) { if (d && d !== '(none)' && names.indexOf(d) < 0) names.push(d); };
		add(localDomain);
		rowsOf(A['calls.domains']).forEach(function (r) { add(r[0]); });
		rowsOf(A['platform.latest']).forEach(function (r) { add(r[0]); });
		add(scope);
		names.sort();
		sel.textContent = '';
		var all = el('option', '', 'all domains'); all.value = ''; sel.appendChild(all);
		names.forEach(function (d) {
			var o = el('option', '', d + (d === localDomain ? ' (this one)' : '')); o.value = d; sel.appendChild(o);
		});
		sel.value = scope;
	}

	function scopeChanged(value) {
		scope = value; store('domain', scope);
		Object.keys(P).forEach(function (k) { P[k].relink(); });
		A = {};
		serverKpis();
		drawAll();
		loadOps().catch(function () {});
		loadAnalytics();
	}

	function loadOps() {
		return getJson('ops').then(function (o) {
			if (!o || o.error) return;
			lastOps = o;
			if (remoteScope()) { kpi('trouble', null, 'read live in ' + localDomain + ' only'); P.ops.draw(); return; }
			var wrong = wrongNow().length;
			var queued = o.queues.reduce(function (n, q) { return n + (q.depth || 0); }, 0);
			kpi('trouble', wrong, wrong ? o.endpointsDown.length + ' endpoints down · ' + o.trunksFailing.length + ' trunks failing'
				: 'nothing down · ' + C.fmt(queued) + ' queued', wrong > 0);
			P.ops.draw();
		});
	}

	function loadAnalytics() {
		return getJson('data?r=' + analyticsReports().join(',') + '&days=' + days + scopeQuery() + '&tz=' + encodeURIComponent(R.zone())).then(function (d) {
			A = d;
			fillScope();
			serverKpis();
			P.cluster.draw();
			var cs = d['calls.summary'], os = d['outcomes.summary'], ds = d['duration.summary'],
				rs = d['risk.summary'], hs = d['health.summary'];
			kpi('calls', cell(cs, 'calls'), C.fmt(cell(cs, 'today')) + ' today · ' + C.fmt(cell(cs, 'open')) + ' open');
			kpi('answered', cell(os, 'answered'), C.fmt(cell(os, 'started')) + ' started · ' + C.fmt(cell(os, 'lost')) + ' lost');
			var med = cell(ds, 'median');
			kpi('duration', med == null ? null : Math.round(med) + ' s', 'average ' + Math.round(cell(ds, 'average') || 0) + ' s');
			var flagged = cell(rs, 'calls flagged');
			kpi('flagged', flagged, C.fmt(cell(rs, 'calls assessed')) + ' assessed', flagged > 0);
			var gaps = cell(hs, 'with gaps'), dropped = cell(hs, 'dropped events'), leaked = cell(cs, 'leaked');
			kpi('gaps', gaps, gaps == null ? '' : (gaps === 1 ? 'application' : 'applications') + ' with gaps'
				+ (dropped ? ' · ' + C.fmt(dropped) + ' events dropped' : '')
				+ (leaked ? ' · ' + C.fmt(leaked) + ' never closed' : ''), gaps > 0 || dropped > 0 || leaked > 0);
			['traffic', 'outcomes', 'apps', 'risk'].forEach(function (k) { P[k].draw(); });
			setStatus('', 'live · ' + R.time(Date.now(), { hour: '2-digit', minute: '2-digit' }));
		}).catch(function (e) { if (e.message !== 'auth') setStatus('err', 'analytics failed'); });
	}

	function buildRange() {
		var host = document.getElementById('range');
		RANGES.forEach(function (r) {
			var b = el('button', r[0] === days ? 'on' : '', r[1]);
			b.type = 'button';
			b.addEventListener('click', function () {
				days = r[0]; store('days', days);
				Array.prototype.forEach.call(host.children, function (c) { c.className = c === b ? 'on' : ''; });
				labelRanges();
				Object.keys(P).forEach(function (k) { P[k].relink(); });
				A = {};
				drawAll();
				loadAnalytics();
			});
			host.appendChild(b);
		});
	}

	/* The time now in the viewer's zone. Every time on the board is in that
	   zone; the select beside the clock changes it. */
	function tick() {
		document.getElementById('clock').textContent = R.time(Date.now(), { hour: '2-digit', minute: '2-digit' });
	}

	/* A new zone: the live chart's times and every analytics bucket move. */
	function zoneChanged() {
		tick();
		A = {};
		drawAll();
		loadAnalytics();
	}

	buildKpis();
	buildPanels();
	buildRange();
	fillScope();
	document.getElementById('scope').addEventListener('change', function (e) { scopeChanged(e.target.value); });
	R.zoneSelect(document.getElementById('tz'), zoneChanged);
	drawAll();
	tick();
	setStatus('', 'loading…');
	var live = function () { loadHealth().catch(function () {}); loadOps().catch(function () {}); };
	getJson('settings').catch(function () { return null; }).then(function (cfg) {
		if (cfg) {
			LIVE_MS = (cfg.liveRefreshSeconds || 5) * 1000;
			ANALYTICS_MS = (cfg.analyticsRefreshSeconds || 60) * 1000;
			HEAP_HIGH = cfg.heapHighPercent || HEAP_HIGH;
			RIGHTS = { platform: cfg.platform !== false, content: cfg.content !== false };
		}
		if (!RIGHTS.platform) {
			live = function () {};
			['nodes', 'sessions', 'trouble'].forEach(function (k) { kpi(k, null, 'not in your grant'); });
			drawAll();
		}
		live();
		loadAnalytics();
		setInterval(live, LIVE_MS);
		setInterval(loadAnalytics, ANALYTICS_MS);
	});
	setInterval(tick, 15000);
	var rz;
	window.addEventListener('resize', function () { clearTimeout(rz); rz = setTimeout(drawAll, 150); });
})();
