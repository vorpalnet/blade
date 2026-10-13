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
	/* The range buttons, in days; replaced by the settings' ranges and
	   defaultDays (/settings) when the board opens. */
	var RANGES = ['1', '7', '30'], DEFAULT_DAYS = '7';
	function rangeLabel(d) { return d === '1' ? 'Last day' : d + ' days'; }
	/* The range as a phrase on a tile or panel. One day is a rolling 24 hours;
	   longer ranges are whole days, today's included (AnalyticsReports.since). */
	function rangePhrase(d) { return d === '1' ? 'last 24 hours' : 'last ' + d + ' days'; }
	/* Lines that are peers (servers, applications) take colours that rank
	   nothing; the good/fair/bad tokens are kept for outcomes. Each line is
	   named at its end, so the colour is never the only channel. */
	var LINE_COLORS = function () { return [C.token('--accent'), C.token('--info'), C.token('--ink-2')]; };

	function store(key, value) {
		try {
			if (value === undefined) return localStorage.getItem('blade.board.' + key);
			localStorage.setItem('blade.board.' + key, value);
		} catch (e) { /* private window: the board works without it */ }
		return null;
	}

	var days = store('days') || DEFAULT_DAYS;
	if (RANGES.indexOf(days) < 0) days = DEFAULT_DAYS;

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
		{ id: 'nodes', label: 'Servers running', platform: true },
		{ id: 'now', label: 'Calls now' },
		{ id: 'calls', label: 'Calls', ranged: true },
		{ id: 'answered', label: 'Answered', ranged: true },
		{ id: 'duration', label: 'Median call', ranged: true },
		{ id: 'flagged', label: 'Calls flagged', ranged: true },
		{ id: 'queued', label: 'Queued now', platform: true }
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
		host.style.setProperty('--kpis', KPI.length);
		labelRanges();
	}

	function labelRanges() {
		var word = rangePhrase(days);
		KPI.forEach(function (k) { if (k.rangeEl) k.rangeEl.textContent = ' · ' + word; });
		Object.keys(P).forEach(function (k) { P[k].showRange(); });
	}

	/* A viewer without the platform grant gets no server tiles at all, rather
	   than tiles that say so: the strip closes up around the call numbers. */
	function hidePlatformKpis() {
		var shown = 0;
		KPI.forEach(function (k) {
			var hide = k.platform === true;
			kpiEls[k.id].tile.style.display = hide ? 'none' : '';
			if (!hide) shown++;
		});
		document.getElementById('kpis').style.setProperty('--kpis', shown);
	}

	/* 65 -> "1:05", 3725 -> "1:02:05": a call length as people read one. */
	function clock(seconds) {
		if (seconds == null) return null;
		var t = Math.round(seconds), h = Math.floor(t / 3600), m = Math.floor(t / 60) % 60, sec = t % 60;
		var mm = h ? (m < 10 ? '0' : '') + m : String(m);
		return (h ? h + ':' : '') + mm + ':' + (sec < 10 ? '0' : '') + sec;
	}

	/* alert: the tile is outlined and its line says what is wrong. */
	function kpi(id, value, sub, alert) {
		var e = kpiEls[id];
		e.v.textContent = value == null ? '—' : (typeof value === 'number' ? C.fmt(value) : value);
		e.s.textContent = sub || '';
		e.tile.classList.toggle('kpi--alert', !!alert);
	}

	/* ── panels ────────────────────────────────────────────────────────── */

	/* views: [{ key, label, sub, live, draw(body) }]. The chosen tab is
	   remembered per panel. A panel built with ranged reads the database, so its
	   head names the range in the top bar; a view marked live does not. */
	function Panel(name, title, family, ranged) {
		this.name = name;
		this.host = document.querySelector('[data-panel="' + name + '"]');
		var head = el('div', 'panel__head');
		var t = family ? el('a', 'panel__title', title) : el('span', 'panel__title', title);
		if (family) { t.href = 'report.html?r=' + family + '&days=' + days + scopeQuery(); t.title = 'Open the full report'; this.link = t; this.family = family; }
		this.ranged = !!ranged;
		this.range = el('span', 'panel__range');
		this.range.title = 'The range chosen in the top bar';
		this.sub = el('span', 'panel__sub', '');
		this.tabs = el('div', 'tabs');
		head.appendChild(t); head.appendChild(this.range); head.appendChild(this.sub); head.appendChild(this.tabs);
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
		this.showRange();
		try { view.draw(this.body); } catch (e) { C.message(this.body, 'error', String(e.message || e)); }
	};

	Panel.prototype.showRange = function () {
		var self = this;
		var view = this.views.filter(function (v) { return v.key === self.current; })[0];
		var on = this.ranged && view && !view.live;
		this.range.textContent = on ? rangePhrase(days) : '';
		this.range.hidden = !on;
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

	var lastNodes = [], lastOps = null;

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
				sipSessions: r[6], threadsStuck: r[7], threadsHogging: r[8], sampled: r[9], cpuPct: r[10] });
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
		['state', 'server', 'heap', 'cpu', 'SIP sessions', 'threads'].forEach(function (h) { head.appendChild(el('span', '', h)); });
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
			if (n.up === false) {
				['', '', '', ''].forEach(function () { row.appendChild(el('span', 'srv__n', '—')); });
				list.appendChild(row);
				return;
			}
			var heap = el('span', 'srv__heap' + (n.heapUsedPct >= HEAP_HIGH ? ' hot' : ''));
			if (n.heapUsedPct != null) {
				var bar = el('span', 'srv__bar'); var fill = el('span', 'srv__fill'); fill.style.width = Math.min(100, n.heapUsedPct) + '%';
				bar.appendChild(fill); heap.appendChild(bar);
				heap.appendChild(el('b', '', n.heapUsedPct + '%' + (n.heapUsedPct >= HEAP_HIGH ? ' high' : '')));
			} else heap.appendChild(el('b', '', 'n/a'));
			row.appendChild(heap);
			row.appendChild(el('span', 'srv__n', n.cpuPct == null ? '—' : n.cpuPct + '%'));
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
			(n.jdbc || []).forEach(function (d) {
				rows.push([n.server, d.name, d.state || '—', d.active, d.capacity, d.waiting, d.failures,
					d.failuresAt ? R.time(d.failuresAt) : '—']);
			});
		});
		if (!rows.length) return C.message(body, 'empty', 'No JDBC pools reported.');
		fitTable(body, { columns: ['server', 'pool', 'state', 'active', 'capacity', 'waiting', 'failures', 'last failure'], rows: rows });
	}

	var OPS_WORDS = {
		'org.vorpal.blade.endpoint.down': 'down', 'org.vorpal.blade.endpoint.up': 'up',
		'org.vorpal.blade.remote.down': 'cut off', 'org.vorpal.blade.remote.up': 'back',
		'org.vorpal.blade.trunk.failed': 'trunk down', 'org.vorpal.blade.trunk.registered': 'trunk up',
		'org.vorpal.blade.trunk.unregistered': 'trunk off', 'org.vorpal.blade.queue.depth': 'queue',
		'org.vorpal.blade.sip.denied': 'refused',
		'org.vorpal.blade.config.saved': 'config', 'org.vorpal.blade.config.published': 'pushed',
		'org.vorpal.blade.call.dropped': 'dropped'
	};
	var OPS_BAD = { down: 1, 'trunk down': 1, refused: 1, 'cut off': 1, dropped: 1 };

	/* When an event happened, in the viewer's zone: the clock time today, the
	   date as well before today. */
	function opsWhen(ms) {
		var day = function (t) { return new Date(t).toLocaleDateString([], { timeZone: R.zone() }); };
		var clock = R.time(ms, { hour: 'numeric', minute: '2-digit', second: '2-digit' });
		if (day(ms) === day(Date.now())) return clock;
		return new Date(ms).toLocaleDateString([], { timeZone: R.zone(), month: 'short', day: 'numeric' }) + ' ' + clock;
	}

	/* How long something has been so: "40 s", "6 min", "2 h 5 min", "3 d 4 h". */
	function opsFor(ms) {
		var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
		if (s < 60) return s + ' s';
		var m = Math.floor(s / 60);
		if (m < 60) return m + ' min';
		var h = Math.floor(m / 60);
		if (h < 24) return h + ' h' + (m % 60 ? ' ' + (m % 60) + ' min' : '');
		return Math.floor(h / 24) + ' d' + (h % 24 ? ' ' + (h % 24) + ' h' : '');
	}

	function opsSummary(e) {
		switch (e.type) {
		case 'org.vorpal.blade.endpoint.down':
		case 'org.vorpal.blade.endpoint.up': return e.endpoint + (e.note ? ' · ' + e.note : '');
		case 'org.vorpal.blade.remote.down':
		case 'org.vorpal.blade.remote.up': return e.kind + ' ' + e.remote + ' · ' + e.application + (e.reason ? ' · ' + e.reason : '');
		case 'org.vorpal.blade.trunk.failed': return e.gateway + ' · ' + (e.status || '') + ' ' + (e.reason || '');
		case 'org.vorpal.blade.trunk.registered':
		case 'org.vorpal.blade.trunk.unregistered': return e.gateway + ' · ' + e.registrar;
		case 'org.vorpal.blade.queue.depth': return e.queue + ' · ' + e.depth + ' waiting (low ' + e.low + ', high ' + e.high + ')';
		case 'org.vorpal.blade.sip.denied': return e.method + ' from ' + e.sourceAddress;
		case 'org.vorpal.blade.config.saved': return e.file + ' ' + e.change + ' by ' + e.actor + ' (' + e.editor + ')';
		case 'org.vorpal.blade.config.published': return e.file + ' → ' + (e.pushedTo || []).join(', ') + ((e.failed && e.failed.length) ? ' · FAILED on ' + e.failed.join(', ') : '');
		/* Each leg as it stood when the call expired: "caller EARLY, waiting". */
		case 'org.vorpal.blade.call.dropped': return (e.appName || 'call') + ' · ' + (e.legs || []).map(function (l) {
			return l.role + ' ' + l.state + (l.inviteOpen ? ', waiting' : '');
		}).join(' · ') + (e.vorpalId ? ' · call ' + e.vorpalId : '');
		default: return e.type;
		}
	}

	/* Rows that fit, newest first, then a count of the rest. With `open`, the
	   right column says how long each has been so, not when it began; the full
	   line and the full date are on hover either way. */
	function opsRows(body, items, word, empty, open) {
		if (localOnly(body, 'Operations events')) return;
		if (!lastOps) return C.message(body, 'empty', 'Listening to the event bus…');
		if (!items.length) return C.message(body, 'empty', empty);
		var box = el('div', 'ops-list');
		body.appendChild(box);
		var shown = 0;
		for (var i = 0; i < items.length; i++) {
			var e = items[i], w = word(e);
			var row = el('div', 'ops-row'), main = opsSummary(e) + (e.node ? ' · ' + e.node : '');
			var at = e.time ? new Date(e.time).getTime() : null;
			row.appendChild(el('span', 'ops-row__what' + (OPS_BAD[w] ? ' bad' : ''), w));
			row.appendChild(el('span', 'ops-row__main', main));
			row.appendChild(el('span', 'ops-row__when', at == null ? '' : open ? 'for ' + opsFor(at) : opsWhen(at)));
			row.title = w + ' · ' + main + (at == null ? '' : '\n' + (open ? 'since ' : '')
				+ new Date(at).toLocaleString([], { timeZone: R.zone(), dateStyle: 'medium', timeStyle: 'medium' }));
			box.appendChild(row);
			if (box.scrollHeight > body.clientHeight - 20) { box.removeChild(row); break; }
			shown++;
		}
		if (shown < items.length) body.appendChild(el('div', 'panel__more', (items.length - shown) + ' more'));
	}

	function issues() {
		if (!lastOps) return [];
		return lastOps.endpointsDown.map(function (e) { e.type = 'org.vorpal.blade.endpoint.down'; return e; })
			.concat(lastOps.trunksFailing.map(function (e) { e.type = 'org.vorpal.blade.trunk.failed'; return e; }))
			.concat((lastOps.remotesDown || []).map(function (e) { e.type = 'org.vorpal.blade.remote.down'; return e; }));
	}

	/* ── analytics ─────────────────────────────────────────────────────── */

	var A = {};   // report name → result

	/* How calls ended, good to bad, bottom up: the stoplight order, so position
	   says what the colour says. */
	/* Good to bad, the order of every outcome report's columns. Declined is
	   ordinary traffic, fair like an abandon; failed and dropped are faults. */
	var OUTCOME_PARTS = [
		{ name: 'Completed', color: '--ok', explain: 'answered, then hung up normally' },
		{ name: 'Abandoned', color: '--warn', explain: 'the caller hung up before anyone answered' },
		{ name: 'Declined', color: '--warn', explain: 'the callee said no: busy, unavailable, not found, or no answer' },
		{ name: 'Failed', color: '--bad', explain: 'the call could not be put through: nothing answered, a server error (5xx), or refused on credentials (401, 403, 407)' },
		{ name: 'Dropped', color: '--bad', explain: 'a bug stopped relaying the signaling: the call was left half set up or half hung up until it expired, or the caller gave up on an answer that never reached them' }
	];
	var OUTCOME_NAMES = OUTCOME_PARTS.map(function (p) { return p.name.toLowerCase(); });

	/* One line per server for a per-minute sample reading (platform.heap,
	   platform.cpu), drawn on the call charts' own buckets so every Traffic
	   tab shares a time axis. A gap is an hour with no sample, not a zero. */
	function perServer(report, what) {
		return function (body) {
			if (!RIGHTS.platform) return C.message(body, 'empty', what + ' are not in your grant.');
			guard(A[report], body, function (res) {
				var buckets = rowsOf(A['calls.by-outcome']).map(function (r) { return r[0]; });
				var byServer = {};
				res.rows.forEach(function (r) { (byServer[r[1]] = byServer[r[1]] || {})[r[0]] = r[2]; });
				var series = Object.keys(byServer).sort().map(function (server) {
					return { name: server, values: buckets.map(function (b) { return [b, b in byServer[server] ? byServer[server][b] : null]; }) };
				});
				if (!series.length) return C.message(body, 'empty', 'No server samples in this range.');
				C.multiLine(body, series, { pct: true, colors: LINE_COLORS() });
			});
		};
	}

	/* "busiest: Thu 00:00 · 28 calls", the hour (or day) with the most calls
	   started, in the viewer's zone, as the report's buckets already are. */
	function busiest(res) {
		var best = null, most = 0;
		res.rows.forEach(function (r) {
			var n = (r[1] || 0) + (r[2] || 0) + (r[3] || 0);
			if (n > most) { most = n; best = r[0]; }
		});
		if (!best) return 'no calls in this range';
		return 'busiest: ' + when(best) + ' · ' + C.fmt(most) + (most === 1 ? ' call' : ' calls');
	}

	/* "Thu 10/08 00:00" for an hour bucket, "Thu 10/08" for a day: buckets are
	   already in the viewer's zone. */
	function when(bucket) {
		var m = /^(\d{4})-(\d{2})-(\d{2})(?: (\d{2}):00)?$/.exec(bucket);
		if (!m) return bucket;
		return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][new Date(+m[1], +m[2] - 1, +m[3]).getDay()] + ' ' + m[2] + '/'
			+ m[3] + (m[4] ? ' ' + m[4] + ':00' : '');
	}

	/* Peak concurrent calls per hour (calls.concurrent), or per day past a
	   week: a day's peak is its highest hour. This is the number a per-session
	   price is metered on. */
	function peaks(rows) {
		if (+days <= 7) return rows;
		var byDay = {}, order = [];
		rows.forEach(function (r) {
			var d = String(r[0]).slice(0, 10);
			if (!(d in byDay)) { byDay[d] = 0; order.push(d); }
			byDay[d] = Math.max(byDay[d], +r[1] || 0);
		});
		return order.map(function (d) { return [d, byDay[d]]; });
	}

	/* ── the network map ── */

	/* /network, read on the live cycle while its tab shows; the zoomed place
	   is kept across refreshes so a refresh does not throw the viewer out. */
	var lastNetwork = null, netAtlases = { us: null, world: null }, netAtlasLoading = false, netZoomed = null;

	function loadNetwork() {
		if (!RIGHTS.platform || remoteScope()) return Promise.resolve();
		return getJson('network').then(function (n) {
			lastNetwork = n;
			if (P.traffic.current === 'network') P.traffic.draw();
		});
	}

	function drawNetwork(body) {
		if (!RIGHTS.platform) return C.message(body, 'empty', 'The network map is not in your grant.');
		if (remoteScope()) return C.message(body, 'empty', 'The network map shows this domain. Choose it in the top bar.');
		if (!lastNetwork) { C.message(body, 'empty', 'Loading…'); loadNetwork().catch(function () {}); return; }
		if (lastNetwork.error) return C.message(body, 'error', lastNetwork.error);
		if (!lastNetwork.sites || !lastNetwork.sites.length) {
			return C.message(body, 'empty', 'No sites yet: add them under "sites" in the dashboard settings.');
		}
		if (!window.topojson) return C.message(body, 'error', 'Map files missing (vendor/topojson-client.min.js).');
		if (!netAtlases.us || !netAtlases.world) {
			C.message(body, 'empty', 'Loading map…');
			if (!netAtlasLoading) {
				netAtlasLoading = true;
				Promise.all([getJson('vendor/states-albers-10m.json'), getJson('vendor/land-110m.json')]).then(function (a) {
					netAtlases = { us: a[0], world: a[1] };
					if (P.traffic.current === 'network') P.traffic.draw();
				}).catch(function (e) { netAtlasLoading = false; C.message(body, 'error', 'Map atlas: ' + (e.message || e)); });
			}
			return;
		}
		var model = window.BladeNetwork.model(lastNetwork, lastNodes, Date.now());
		var trouble = model.sites.filter(function (s) { return s.state === 'down' || s.state === 'warn'; })
			.sort(function (a, b) { return window.BladeNetwork.rank(b.state) - window.BladeNetwork.rank(a.state); });
		P.traffic.sub.textContent = trouble.length
			? (trouble[0].state === 'down' ? 'down: ' : 'trouble: ') + trouble[0].name + ' · ' + trouble[0].summary
			: 'every site working · refusals over the last ' + lastNetwork.windowMinutes + ' min';
		var foot = [];
		var unplaced = lastNetwork.unplaced || {};
		if (unplaced.origins && unplaced.origins.length) {
			foot.push('Calls from addresses no site claims: ' + unplaced.origins.map(function (o) { return o.origin + ' (' + o.calls + ')'; }).join(', '));
		}
		if (unplaced.failures && unplaced.failures.length) {
			foot.push('Refused by servers no site claims: ' + unplaced.failures.map(function (f) {
				return f.server + ' ' + f.calls + ' × ' + f.status + ' ' + f.meaning;
			}).join(', '));
		}
		if (unplaced.remotes && unplaced.remotes.length) {
			foot.push('Services failing that no site claims: ' + unplaced.remotes.map(function (r) {
				return r.kind + ' ' + r.remote + ' (' + r.failures + ' of ' + r.calls + ')';
			}).join(', '));
		}
		var noCoords = model.sites.filter(function (s) { return s.lat == null || s.lon == null; });
		if (noCoords.length) foot.push('No coordinates: ' + noCoords.map(function (s) { return s.name + ' (' + s.state + ')'; }).join(', '));
		if (lastNetwork.analyticsError) foot.push('Call data unavailable: ' + lastNetwork.analyticsError);
		C.networkMap(body, netAtlases, model, {
			zoomed: netZoomed, onZoom: function (k) { netZoomed = k; }, foot: foot.join(' · ')
		});
	}

	function analyticsReports() {
		var r = ['calls.domains', 'platform.latest', 'calls.summary', 'calls.by-outcome', 'calls.heatmap', 'calls.by-application',
			'outcomes.summary', 'outcomes.daily', 'outcomes.ended', 'outcomes.by-application',
			'duration.summary', 'duration.bands',
			'health.coverage',
			'risk.summary', 'highrisk.by-band', 'risk.daily', 'calls.concurrent'];
		if (RIGHTS.platform) r.push('platform.heap', 'platform.cpu');
		// Individual calls only while their tab is open: a request for them is
		// audited as someone looking at calls, which a hidden tab is not.
		if (P.risk && P.risk.current === 'calls') r.push('highrisk.calls');
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

		P.traffic = new Panel('traffic', 'Traffic', 'calls', true);
		P.traffic.setViews([
			{ key: 'calls', label: 'Calls', sub: 'per hour, or per day past a week', draw: function (body) {
				guard(A['calls.by-outcome'], body, function (res) {
					P.traffic.sub.textContent = busiest(res);
					C.stackBar(body, res.rows, { parts: OUTCOME_PARTS, empty: 'No calls ended in this range.' });
				});
			} },
			{ key: 'sessions', label: 'Sessions', sub: 'peak concurrent calls', draw: function (body) {
				guard(A['calls.concurrent'], body, function (res) {
					var rows = peaks(res.rows);
					var best = rows.reduce(function (b, r) { return r[1] > b[1] ? r : b; }, ['', 0]);
					P.traffic.sub.textContent = best[1] ? 'peak ' + C.fmt(best[1]) + ' · ' + when(best[0]) : 'no calls in this range';
					if (!best[1]) return C.message(body, 'empty', 'No calls in this range.');
					C.line(body, rows, { name: 'peak', endLabel: false });
				});
			} },
			{ key: 'heap', label: 'Heap', sub: 'per server · highest reading', draw: perServer('platform.heap', 'Heap readings') },
			{ key: 'cpu', label: 'CPU', sub: 'per server · busiest minute', draw: perServer('platform.cpu', 'CPU readings') },
			{ key: 'when', label: 'When', sub: 'weekday × hour of day', draw: function (body) {
				guard(A['calls.heatmap'], body, function (res) { C.heatmap(body, res.rows); });
			} },
			{ key: 'network', label: 'Network', sub: 'sites, and the routes between them', live: true, draw: drawNetwork,
				fetch: function () { loadNetwork().catch(function () {}); } }
		]);

		P.ops = new Panel('ops', 'Operations', null);
		P.ops.setViews([
			{ key: 'issues', label: 'Issues', sub: 'until cleared', draw: function (body) {
				opsRows(body, issues(), function (e) { return OPS_WORDS[e.type]; },
					'No open issues. An endpoint, trunk or cut-off service shows here from the event that reports it until the one that clears it.', true);
			} },
			{ key: 'queues', label: 'Queues', sub: 'last minute · per node', draw: function (body) {
				var q = lastOps ? lastOps.queues.map(function (e) { e.type = 'org.vorpal.blade.queue.depth'; return e; }) : [];
				opsRows(body, q, function () { return 'queue'; }, 'No queue has held a caller since this server started listening.');
			} },
			{ key: 'recent', label: 'Recent', sub: 'newest first', draw: function (body) {
				opsRows(body, lastOps ? lastOps.recent : [], function (e) { return OPS_WORDS[e.type] || 'event'; }, 'No operations events yet.');
			} }
		]);

		P.outcomes = new Panel('outcomes', 'Outcomes', 'outcomes', true);
		P.outcomes.setViews([
			{ key: 'ended', label: 'Ended', sub: 'each call counted once', draw: function (body) {
				guard(A['outcomes.ended'], body, function (res) {
					var by = {};
					res.rows.forEach(function (r) { by[r[0]] = r[1] || 0; });
					var total = Object.keys(by).reduce(function (n, k) { return n + by[k]; }, 0);
					if (!total) return C.message(body, 'empty', 'No call ended in this range.');
					var rows = OUTCOME_PARTS.map(function (p) { return [p.name, by[p.name.toLowerCase()] || 0, p]; });
					C.hbar(body, rows, { valueWidth: 90,
						value: function (r) { return C.fmt(r[1]) + ' · ' + Math.round(100 * r[1] / total) + '%'; },
						color: function (r) { return r[2].color; },
						title: function (r) { return r[0] + ': ' + C.fmt(r[1]) + '\n' + r[2].explain; } });
				});
			} },
			{ key: 'daily', label: 'Per day', sub: 'how calls ended, by day', draw: function (body) {
				guard(A['outcomes.daily'], body, function (res) {
					C.multiLine(body, seriesOf(res, OUTCOME_NAMES),
						{ colors: OUTCOME_PARTS.map(function (p) { return C.token(p.color); }) });
				});
			} },
			{ key: 'apps', label: 'By application', sub: 'per application', draw: function (body) {
				guard(A['outcomes.by-application'], body, function (res) { fitTable(body, res); });
			} }
		]);

		P.apps = new Panel('apps', 'Applications', 'health', true);
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

		P.risk = new Panel('risk', 'Call risk', 'risk', true);
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

	/* ── who is signed in ──────────────────────────────────────────────── */

	/* The account menu: the person's initials and name; opened, what their
	   grant shows them and Sign out. Built from /settings, so it says what the
	   server will actually enforce. */
	function buildAccount(cfg) {
		var host = document.getElementById('acct');
		var name = cfg && (cfg.displayName || cfg.user);
		if (!name) return;
		var initials = name.split(/[\s._@-]+/).filter(Boolean).slice(0, 2)
			.map(function (w) { return w.charAt(0).toUpperCase(); }).join('');
		var btn = el('button', 'acct__btn');
		btn.type = 'button';
		btn.setAttribute('aria-haspopup', 'true');
		btn.setAttribute('aria-expanded', 'false');
		btn.appendChild(el('span', 'acct__badge', initials || '?'));
		btn.appendChild(el('span', 'acct__name', name));
		var menu = el('div', 'acct__menu');
		menu.hidden = true;
		menu.appendChild(el('div', 'acct__who', name));
		var how = (cfg.user && cfg.user !== name ? cfg.user + ' · ' : '') + 'signed in with ' + (cfg.signedInWith || 'WebLogic');
		menu.appendChild(el('div', 'acct__how', how));
		if (cfg.sees && cfg.sees.length) {
			menu.appendChild(el('div', 'acct__h', 'What you see'));
			var list = el('ul', 'acct__sees');
			cfg.sees.forEach(function (line) { list.appendChild(el('li', '', line)); });
			menu.appendChild(list);
		}
		var out = el('a', 'acct__out', 'Sign out');
		out.href = 'signout';
		menu.appendChild(out);
		var open = function (yes) { menu.hidden = !yes; btn.setAttribute('aria-expanded', String(yes)); };
		btn.addEventListener('click', function (e) { e.stopPropagation(); open(menu.hidden); });
		document.addEventListener('click', function (e) { if (!host.contains(e.target)) open(false); });
		document.addEventListener('keydown', function (e) { if (e.key === 'Escape') open(false); });
		host.appendChild(btn);
		host.appendChild(menu);
		host.hidden = false;
	}

	/* ── loading ───────────────────────────────────────────────────────── */

	var statusEl = document.getElementById('status'), statusText = document.getElementById('statusText');
	function setStatus(kind, text) { statusEl.className = 'status' + (kind ? ' ' + kind : ''); statusText.textContent = text; }

	function loadHealth() {
		return getJson('health').then(function (r) {
			if (!r || r.error) { kpi('nodes', null, (r && r.error) || 'health failed', true); return; }
			var nodes = r.nodes || [];
			lastNodes = nodes;
			if (r.domain && r.domain !== localDomain) { localDomain = r.domain; fillScope(); }
			serverKpis();
			P.cluster.draw();
		});
	}

	/* Servers running and SIP sessions, over the servers in scope. */
	function serverKpis() {
		if (!RIGHTS.platform) return;
		var nodes = visibleServers(), running = 0, trouble = false, byState = {};
		nodes.forEach(function (n) {
			var w = stateWord(n);
			if (w === 'RUNNING') running++;
			else {
				var word = w === 'SHUTDOWN' ? 'shut down' : w.toLowerCase().replace(/_/g, ' ');
				(byState[word] = byState[word] || []).push(n.server);
				/* Shut down is a choice; anything else that is not running is not. */
				if (w !== 'SHUTDOWN') trouble = true;
			}
		});
		if (!nodes.length) { kpi('nodes', null, remoteScope() ? 'no recent sample' : ''); return; }
		/* "engine1, engine2 shut down": one phrase per state, so it fits the tile. */
		var down = Object.keys(byState).map(function (word) { return byState[word].join(', ') + ' ' + word; });
		kpi('nodes', running + ' / ' + nodes.length, down.length ? down.join(' · ') : 'all running', trouble);
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
		loadNow().catch(function () {});
		loadAnalytics();
	}

	/* Calls up now: Vorpal-IDs some application still holds, from the call
	   rows (calls.now), on the live cycle. The line is the last hour's peak. */
	function loadNow() {
		return getJson('data?r=calls.now' + scopeQuery() + '&tz=' + encodeURIComponent(R.zone())).then(function (d) {
			var n = d && d['calls.now'];
			if (!n || n.error) { kpi('now', null, (n && n.error) ? 'analytics unavailable' : '', true); return; }
			var peak = cell(n, 'peak last hour'), at = cell(n, 'peak at');
			kpi('now', cell(n, 'now'), peak ? 'peak ' + C.fmt(peak) + ' at ' + String(at).substring(11, 16) + ' · last hour'
				: 'none in the last hour');
		});
	}

	function loadOps() {
		return getJson('ops').then(function (o) {
			if (!o || o.error) return;
			lastOps = o;
			if (remoteScope()) { kpi('queued', null, 'read live in ' + localDomain + ' only'); P.ops.draw(); return; }
			/* Calls waiting in a queue, live from the queue application's gauges;
			   the line is how long the longest of them has waited. */
			var q = o.queuedNow || {};
			if (q.calls == null) kpi('queued', null, q.error ? 'queue reading failed' : 'no queue application running', !!q.error);
			else kpi('queued', q.calls, q.calls ? 'longest waiting ' + clock(q.longestMs / 1000) : 'none waiting');
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
				rs = d['risk.summary'];
			kpi('calls', cell(cs, 'calls'), C.fmt(cell(cs, 'today')) + ' today');
			var started = cell(os, 'started'), answered = cell(os, 'answered');
			kpi('answered', answered, (started ? Math.round(100 * answered / started) + '% of ' + C.fmt(started) + ' started'
				: 'none started') + ' · ' + C.fmt(cell(os, 'lost')) + ' lost');
			var med = cell(ds, 'median');
			kpi('duration', clock(med), med == null ? '' : 'average ' + clock(cell(ds, 'average')) + ' · longest ' + clock(cell(ds, 'longest')));
			var flagged = cell(rs, 'calls flagged');
			kpi('flagged', flagged, C.fmt(cell(rs, 'calls assessed')) + ' assessed', flagged > 0);
			['traffic', 'outcomes', 'apps', 'risk'].forEach(function (k) { P[k].draw(); });
			setStatus('', 'live · ' + R.time(Date.now(), { hour: '2-digit', minute: '2-digit' }));
		}).catch(function (e) { if (e.message !== 'auth') setStatus('err', 'analytics failed'); });
	}

	function buildRange() {
		var host = document.getElementById('range');
		host.textContent = '';
		RANGES.forEach(function (r) {
			var b = el('button', r === days ? 'on' : '', rangeLabel(r));
			b.type = 'button';
			b.addEventListener('click', function () {
				days = r; store('days', days);
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
		loadNow().catch(function () {});
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
	var live = function () {
		loadHealth().catch(function () {}); loadOps().catch(function () {}); loadNow().catch(function () {});
		if (P.traffic.current === 'network') loadNetwork().catch(function () {});
	};
	getJson('settings').catch(function () { return null; }).then(function (cfg) {
		if (cfg) {
			LIVE_MS = (cfg.liveRefreshSeconds || 5) * 1000;
			ANALYTICS_MS = (cfg.analyticsRefreshSeconds || 60) * 1000;
			HEAP_HIGH = cfg.heapHighPercent || HEAP_HIGH;
			RIGHTS = { platform: cfg.platform !== false, content: cfg.content !== false };
			/* The deployment's ranges. A viewer's remembered choice stands while it
			   is still one of them; otherwise they start on the default. */
			if (cfg.ranges && cfg.ranges.length) {
				RANGES = cfg.ranges.map(String);
				DEFAULT_DAYS = String(cfg.defaultDays || RANGES[0]);
				var remembered = store('days');
				days = (remembered && RANGES.indexOf(remembered) >= 0) ? remembered : DEFAULT_DAYS;
				buildRange();
				labelRanges();
				Object.keys(P).forEach(function (k) { P[k].relink(); });
			}
			buildAccount(cfg);
		}
		if (!RIGHTS.platform) {
			live = function () { loadNow().catch(function () {}); };
			hidePlatformKpis();
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
