/* BLADE Dashboard board: everything on one screen, nothing below the fold.

   A row of headline numbers, then six panels. Each panel shows one view at a
   time and switches between views with the tabs in its header, rather than
   stacking more charts beneath it. A panel's title opens its full report
   (report.html), which is where the scrolling detail lives.

   Detail grows left to right. The left column says whether things work
   (servers; how calls ended), the middle what they are doing (traffic; which
   applications), the right what went wrong (operations; the calls
   themselves). Choosing something in a panel narrows the panels to its
   right: a server narrows Traffic and Operations, a time on a Traffic chart
   narrows Operations, an outcome narrows Applications and Calls, an
   application narrows Calls. A narrowed panel says so in its header, with a
   ✕ to undo it, and the headline numbers never narrow: the top of the board
   always tells the whole truth.

   Everything shows the range chosen in the top bar: Live (the last hour), a
   number of days, or a custom range. A view that can only show now, such as
   the connection pools, says "live" in its header instead.

   The range and every choice live in the URL's hash, so Back undoes a step
   and a link reproduces the view. This domain's servers and operations are
   read live (runtime MBeans and the event bus, every 5 s); everything over a
   range comes from the reporting views. Charts are drawn by charts.js; the
   report definitions are in reports.js. */
(function () {
	'use strict';
	var C = window.BladeCharts, R = window.BladeReports, getJson = R.getJson;
	/* Replaced by the dashboard's settings (/settings) when the board opens. */
	var LIVE_MS = 5000, ANALYTICS_MS = 60000, HEAP_HIGH = 85;
	/* How often the range's numbers are re-read while the board shows Live. */
	var LIVE_ANALYTICS_MS = 15000;
	/* What this viewer's grants show, from /settings. The server enforces it;
	   the board only says so instead of showing empty panels. */
	var RIGHTS = { platform: true, content: true };
	/* The range buttons between Live and Custom, in days; replaced by the
	   settings' ranges (/settings) when the board opens. */
	var RANGES = ['1', '7', '30'];
	function rangeLabel(d) { return d === '1' ? 'Last day' : d + ' days'; }
	/* Lines that are peers (servers, applications) take colours that rank
	   nothing; the good/fair/bad tokens are kept for outcomes. Each line is
	   named at its end, so the colour is never the only channel. */
	var LINE_COLORS = function () { return [C.token('--accent'), C.token('--info'), C.token('--ink-2')]; };
	var WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

	function store(key, value) {
		try {
			if (value === undefined) return localStorage.getItem('blade.board.' + key);
			localStorage.setItem('blade.board.' + key, value);
		} catch (e) { /* private window: the board works without it */ }
		return null;
	}

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

	/* ── what the board shows ──────────────────────────────────────────── */

	/* The range in the top bar and what the viewer chose to narrow by:
	   range is 'live', a number of days, or 'custom' with from and until in
	   epoch millis; slice is a time on a Traffic chart, {from, until}, or a
	   cell of the weekday grid, {dow, hod} with Monday 1. */
	var state = { range: 'live', from: null, until: null, server: null, slice: null, outcome: null, app: null };

	function isLive() { return state.range === 'live'; }
	/* Operations is read from the event bus while the board shows the live
	   hour with no time chosen; any other time comes from the database. */
	function liveFeed() { return isLive() && !state.slice; }

	function readHash() {
		var p = new URLSearchParams(location.hash.replace(/^#/, '')), st = {};
		var range = p.get('range') || 'live';
		st.range = range === 'live' || range === 'custom' || /^\d+$/.test(range) ? range : 'live';
		st.from = +p.get('from') || null;
		st.until = +p.get('until') || null;
		if (st.range === 'custom' && !(st.from && st.until && st.from < st.until)) st.range = 'live';
		st.server = p.get('server') || null;
		st.outcome = p.get('outcome') || null;
		st.app = p.get('app') || null;
		st.slice = null;
		var s = p.get('slice');
		var m = s && /^(\d+)-(\d+)$/.exec(s), w = s && /^([1-7])@(\d{1,2})$/.exec(s);
		if (m) st.slice = { from: +m[1], until: +m[2] };
		else if (w) st.slice = { dow: +w[1], hod: +w[2] };
		return st;
	}

	function hashOf(st) {
		var p = new URLSearchParams();
		if (st.range !== 'live') p.set('range', st.range);
		if (st.range === 'custom') { p.set('from', st.from); p.set('until', st.until); }
		if (st.server) p.set('server', st.server);
		if (st.slice) p.set('slice', st.slice.dow ? st.slice.dow + '@' + st.slice.hod : st.slice.from + '-' + st.slice.until);
		if (st.outcome) p.set('outcome', st.outcome);
		if (st.app) p.set('app', st.app);
		return p.toString();
	}

	/* A choice: the hash changes (a step Back can undo), then the board follows. */
	function choose(changes) {
		var next = {};
		Object.keys(state).forEach(function (k) { next[k] = k in changes ? changes[k] : state[k]; });
		var h = hashOf(next);
		if (h === location.hash.replace(/^#/, '')) { apply(next); return; }
		location.hash = h;
	}

	/* Where the range opens, in epoch millis, as the server cuts it. */
	function rangeStart(st) {
		st = st || state;
		if (st.range === 'custom') return st.from;
		if (st.range === 'live') return Date.now() - 3600000;
		if (st.range === '1') return Date.now() - 86400000;
		var d = new Date(Date.now() - (+st.range - 1) * 86400000);
		var parts = d.toLocaleDateString('en-CA', { timeZone: R.zone() }).split('-');
		return R.zonedMs(+parts[0], +parts[1], +parts[2], 0, 0);
	}
	function rangeEnd(st) { st = st || state; return st.range === 'custom' ? st.until : Date.now(); }

	function apply(next) {
		var prev = state;
		/* A time chosen outside the new range goes; the rest of the choice stays. */
		if (next.slice && next.slice.from && (next.slice.until < rangeStart(next) || next.slice.from > rangeEnd(next))) next.slice = null;
		state = next;
		var rangeChanged = prev.range !== next.range || prev.from !== next.from || prev.until !== next.until;
		var sliceChanged = JSON.stringify(prev.slice) !== JSON.stringify(next.slice);
		var groups = {};
		if (rangeChanged) { ['base', 'servers', 'traffic', 'ops', 'apps', 'calls'].forEach(function (g) { groups[g] = 1; }); }
		if (prev.server !== next.server) { groups.traffic = 1; groups.ops = 1; }
		if (sliceChanged) groups.ops = 1;
		if (prev.outcome !== next.outcome) { groups.apps = 1; groups.calls = 1; }
		if (prev.app !== next.app) groups.calls = 1;
		if (rangeChanged) { buildRange(); labelRanges(); relinkAll(); scheduleAnalytics(); }
		Object.keys(groups).forEach(function (g) { D[g] = undefined; loadGroup(g); });
		drawAll();
	}

	/* "Thu 10/8" in the viewer's zone. */
	function dayText(ms) {
		return new Date(ms).toLocaleDateString([], { timeZone: R.zone(), weekday: 'short', month: 'numeric', day: 'numeric' }).replace(',', '');
	}
	function clockText(ms) { return R.time(ms, { hour: '2-digit', minute: '2-digit' }); }
	/* "Thu 10/8 14:00–14:15", or across days "Thu 10/8 14:00 – Fri 10/9 09:00". */
	function spanText(from, until) {
		var end = until + 1;
		if (dayText(from) === dayText(until)) return dayText(from) + ' ' + clockText(from) + '–' + clockText(end);
		return dayText(from) + ' ' + clockText(from) + ' – ' + dayText(until) + ' ' + clockText(end);
	}

	/* The range as a phrase on a tile or panel. One day is a rolling 24 hours;
	   longer ranges are whole days, today's included (AnalyticsReports.since). */
	function rangePhrase() {
		if (state.range === 'live') return 'last hour';
		if (state.range === 'custom') return spanText(state.from, state.until - 1);
		return state.range === '1' ? 'last 24 hours' : 'last ' + state.range + ' days';
	}

	function rangeQuery() {
		if (state.range === 'custom') return '&from=' + state.from + '&until=' + state.until;
		return '&days=' + (state.range === 'live' ? 0 : state.range);
	}

	/* The range narrowed to the time chosen on a Traffic chart, for Operations. */
	function sliceQuery() {
		var s = state.slice;
		if (!s) return rangeQuery();
		if (s.dow) return rangeQuery() + '&dow=' + s.dow + '&hod=' + s.hod;
		return '&from=' + s.from + '&until=' + s.until;
	}

	function sliceLabel() {
		var s = state.slice;
		if (!s) return '';
		if (s.dow) return 'every ' + WEEKDAYS[s.dow - 1] + ' ' + (s.hod < 10 ? '0' : '') + s.hod + ':00';
		return spanText(s.from, s.until);
	}

	/* The time a chart bucket spans: "2026-10-08" is a day, "2026-10-08 14:15"
	   starts a minute, a quarter-hour or an hour, as the report's first column
	   (`grain`) says. Read in the viewer's zone, as the server cut it. */
	function sliceOf(bucket, grain) {
		var m = /^(\d{4})-(\d{2})-(\d{2})(?: (\d{2}):(\d{2}))?$/.exec(bucket);
		if (!m) return null;
		var from = R.zonedMs(+m[1], +m[2], +m[3], +(m[4] || 0), +(m[5] || 0));
		var until = grain === 'day' ? R.zonedMs(+m[1], +m[2], +m[3] + 1, 0, 0) - 1
			: from + ({ minute: 1, quarter: 15, hour: 60 }[grain] || 60) * 60000 - 1;
		return { from: from, until: until };
	}

	/* A Traffic chart's buckets as choices: clicking one narrows Operations
	   to it, clicking it again lets it go. */
	function bucketPick(res) {
		var grain = res.columns[0];
		var picked = null;
		if (state.slice && state.slice.from) {
			res.rows.forEach(function (r) { var s = sliceOf(r[0], grain); if (s && s.from === state.slice.from) picked = r[0]; });
		}
		return {
			pick: function (bucket) {
				var s = sliceOf(bucket, grain);
				choose({ slice: picked === bucket || !s ? null : s });
			},
			picked: picked,
			pickLabel: when
		};
	}

	function mergeOpts(a, b) { Object.keys(b).forEach(function (k) { a[k] = b[k]; }); return a; }

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
		var word = rangePhrase();
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

	/* views: [{ key, label, sub, live, narrow, first, draw(body) }]. The chosen
	   tab is remembered per panel; until one is, the view marked `first` shows,
	   else the leftmost. A panel built with ranged shows the top bar's range
	   in its head; a view whose `live` is true (or returns true) can only show
	   now, and says "live" instead. `narrows` names the choices made to the
	   left that this panel follows; each shows as a chip in its head, unless the
	   view says `narrow: false`. */
	function Panel(name, title, family, ranged, narrows) {
		this.name = name;
		this.host = document.querySelector('[data-panel="' + name + '"]');
		var head = el('div', 'panel__head');
		var t = family ? el('a', 'panel__title', title) : el('span', 'panel__title', title);
		if (family) { t.title = 'Open the full report'; this.link = t; this.family = family; }
		this.ranged = !!ranged;
		this.narrows = narrows || [];
		this.range = el('span', 'panel__range');
		this.chips = el('span', 'panel__chips');
		this.sub = el('span', 'panel__sub', '');
		this.tabs = el('div', 'tabs');
		head.appendChild(t); head.appendChild(this.range); head.appendChild(this.chips); head.appendChild(this.sub);
		head.appendChild(this.tabs);
		this.body = el('div', 'panel__body');
		this.host.appendChild(head); this.host.appendChild(this.body);
		this.views = [];
		this.current = store('tab.' + name);
		this.relink();
	}

	Panel.prototype.view = function () {
		var self = this;
		return this.views.filter(function (v) { return v.key === self.current; })[0];
	};

	Panel.prototype.setViews = function (views) {
		var self = this;
		this.views = views;
		if (!views.some(function (v) { return v.key === self.current; })) {
			this.current = (views.filter(function (v) { return v.first; })[0] || views[0]).key;
		}
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
		var view = this.view();
		if (!view) return;
		this.body.textContent = '';
		this.sub.textContent = typeof view.sub === 'function' ? view.sub() : (view.sub || '');
		this.sub.title = (view.tip ? view.tip + '. ' : '') + this.sub.textContent;
		this.showRange();
		this.showChips();
		try { view.draw(this.body); } catch (e) { C.message(this.body, 'error', String(e.message || e)); }
	};

	Panel.prototype.showRange = function () {
		var view = this.view();
		var live = view && (typeof view.live === 'function' ? view.live() : view.live);
		this.range.className = 'panel__range' + (live ? ' panel__range--live' : '');
		this.range.textContent = live ? 'live' : (this.ranged ? rangePhrase() : '');
		this.range.title = live ? 'Read now: this view has no history' : 'The range chosen in the top bar';
		this.range.hidden = !live && !this.ranged;
	};

	/* What the panel is narrowed to, each with a ✕ that lets it go. */
	var CHIP = {
		server: function () { return state.server; },
		slice: function () { return state.slice && sliceLabel(); },
		outcome: function () { return state.outcome && state.outcome.charAt(0).toUpperCase() + state.outcome.slice(1) + ' calls'; },
		app: function () { return state.app; }
	};
	Panel.prototype.showChips = function () {
		var view = this.view();
		this.chips.textContent = '';
		if (view && view.narrow === false) return;
		var self = this;
		this.narrows.forEach(function (key) {
			var text = CHIP[key]();
			if (!text) return;
			var chip = el('span', 'chip');
			chip.appendChild(el('span', 'chip__text', text));
			var x = el('button', 'chip__x', '✕');
			x.type = 'button';
			x.title = 'Show all again';
			x.setAttribute('aria-label', 'Stop narrowing to ' + text);
			x.addEventListener('click', function () { var c = {}; c[key] = null; choose(c); });
			chip.appendChild(x);
			self.chips.appendChild(chip);
		});
	};

	/* The full report, over the board's range. */
	Panel.prototype.relink = function () {
		if (!this.link) return;
		var q = state.range === 'custom'
			? '&days=' + Math.max(1, Math.ceil((state.until - state.from) / 86400000)) + '&from=' + state.from + '&until=' + state.until
			: '&days=' + (state.range === 'live' ? 1 : state.range);
		this.link.href = 'report.html?r=' + this.family + q + scopeQuery();
	};

	function relinkAll() { Object.keys(P).forEach(function (k) { P[k].relink(); }); }

	/* A report result into a panel body, or its error. */
	function guard(result, body, fn) {
		if (!result) return C.message(body, 'empty', 'Loading…');
		if (result.error) return C.message(body, 'error', result.error);
		return fn(result);
	}

	/* A table cut to the rows that fit, with the count of the rest. Nothing on
	   the board scrolls: the full list is one click away in the report. */
	function fitTable(body, result, opts) {
		opts = opts || {};
		C.table(body, result, { limit: 40, wrap: opts.wrap, rowOpen: opts.rowOpen });
		var wrap = body.querySelector('.table-wrap');
		if (!wrap) return;
		var rows = wrap.querySelectorAll('tbody tr');
		// The body is the table's offset parent: anything above the table (a
		// link line) takes room too, and so does the "more" line under it.
		var room = body.clientHeight - wrap.offsetTop - (wrap.querySelector('thead') ? wrap.querySelector('thead').offsetHeight : 0) - 28;
		var used = 0, shown = 0;
		for (var i = 0; i < rows.length; i++) {
			used += rows[i].offsetHeight;
			if (used > room) { rows[i].style.display = 'none'; } else { shown++; }
		}
		var total = result.rows.length;
		if (shown < total) body.appendChild(el('div', 'panel__more', (total - shown) + ' ' + (opts.more || 'more in the full report')));
	}

	/* Only the named columns of a report, in that order. */
	function pick(result, keep) {
		var idx = keep.map(function (c) { return result.columns.indexOf(c); });
		return { columns: keep, rows: result.rows.map(function (r) { return idx.map(function (i) { return r[i]; }); }) };
	}

	/* ── servers ───────────────────────────────────────────────────────── */

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

	/* Every domain's servers: this domain's from the runtime MBeans, every
	   other domain's from its latest minute sample (platform.latest), marked
	   with the sample's age. The Servers panel lists them all, so a domain can
	   be chosen there; the headline tile counts the domain in scope. */
	function allServers() {
		var out = [];
		lastNodes.forEach(function (n) { n.domain = localDomain; out.push(n); });
		rowsOf(D.servers && D.servers['platform.latest']).forEach(function (r) {
			var domain = r[0];
			if (domain === localDomain) return;
			out.push({ domain: domain, server: r[1], cluster: r[2], state: r[3], heapUsedPct: r[4], appSessions: r[5],
				sipSessions: r[6], threadsStuck: r[7], threadsHogging: r[8], sampled: r[9], cpuPct: r[10] });
		});
		return out;
	}

	function visibleServers() {
		return allServers().filter(function (n) { return !scope || n.domain === scope; });
	}

	function byDomainThenName(a, b) {
		return String(a.domain || '').localeCompare(String(b.domain || '')) || String(a.cluster || '').localeCompare(String(b.cluster || ''))
			|| String(a.server).localeCompare(String(b.server));
	}

	/* A domain's heading: choosing it scopes the whole board to it, headline
	   numbers included; choosing it again shows every domain. */
	function domainRow(list, domain, note) {
		var on = scope === domain;
		var head = el('button', 'srv__domain' + (on ? ' srv__domain--on' : ''));
		head.type = 'button';
		head.appendChild(el('span', 'srv__domain-name', (on ? '▸ ' : '') + (domain || 'unnamed domain')));
		if (note) head.appendChild(el('span', 'srv__domain-note', note));
		head.title = on ? 'Showing ' + domain + ' only. Click to show every domain.' : 'Show ' + domain + ' only, everywhere on the board';
		head.addEventListener('click', function () { scopeChanged(on ? '' : domain); });
		list.appendChild(head);
	}

	/* A server's name: choosing it narrows Traffic and Operations to it. A
	   server in another domain than the one in scope brings its domain in. */
	function serverName(row, n) {
		var on = state.server === n.server && (!scope || scope === n.domain);
		var name = el('button', 'srv__name' + (on ? ' srv__name--on' : ''), (on ? '▸ ' : '') + n.server);
		name.type = 'button';
		name.title = (n.cluster || 'standalone') + '\n' + (on ? 'Click: every server again' : 'Click: Traffic and Operations for ' + n.server + ' only');
		name.addEventListener('click', function () {
			if (on) return choose({ server: null });
			if (scope && scope !== n.domain) scopeChanged(n.domain);
			choose({ server: n.server });
		});
		var cellEl = el('span', 'srv__who');
		cellEl.appendChild(name);
		/* This domain's servers open their own log; another domain's are read
		   by that domain's Logs. */
		var log = n.domain === localDomain ? L.to('log', { server: n.server, at: Date.now() }) : null;
		if (log) {
			var go = el('a', 'srv__go', '↗');
			go.href = log.href; go.target = log.target;
			go.title = 'Open ' + n.server + "'s server log";
			cellEl.appendChild(go);
		}
		row.appendChild(cellEl);
		if (on) row.classList.add('srv__row--on');
	}

	/* One line per server: state word, name, heap bar with its number, sessions
	   and threads. Grouped by domain, then cluster, in name order; a domain read
	   from its samples says how old the sample is. */
	function drawServersNow(body) {
		var nodes = allServers();
		if (!nodes.length) return C.message(body, 'empty', 'Reading the runtime MBeans…');
		var list = el('div', 'srv');
		nodes.sort(byDomainThenName);
		var head = el('div', 'srv__row srv__row--head');
		srvHead(head, [['state'], ['server'], ['heap'], ['cpu'], ['sessions', 'SIP sessions now'], ['threads', 'stuck, pending or hogging threads']]);
		list.appendChild(head);
		var shownDomain;
		nodes.forEach(function (n) {
			if (n.domain !== shownDomain) {
				shownDomain = n.domain;
				var age = n.sampled ? Math.max(0, Math.round((Date.now() - n.sampled) / 1000)) : null;
				domainRow(list, n.domain, n.domain === localDomain ? 'this domain · live' : (age == null ? '' : 'sampled ' + age + ' s ago'));
			}
			var row = el('div', 'srv__row');
			var st = el('span', 'srv__state ' + stateClass(n)); st.appendChild(el('i')); st.appendChild(document.createTextNode(stateWord(n)));
			row.appendChild(st);
			serverName(row, n);
			if (n.up === false) {
				['', '', '', ''].forEach(function () { row.appendChild(el('span', 'srv__n', '—')); });
				list.appendChild(row);
				return;
			}
			row.appendChild(heapCell(n.heapUsedPct, n.heapUsedPct != null ? n.heapUsedPct + '%' : 'n/a'));
			row.appendChild(el('span', 'srv__n', n.cpuPct == null ? '—' : n.cpuPct + '%'));
			row.appendChild(el('span', 'srv__n', num(n.sipSessions)));
			var threads = n.sampled ? '' : num(n.threadsTotal);
			if (n.threadsStuck > 0) threads += (threads ? ' · ' : '') + n.threadsStuck + ' stuck';
			else if (n.threadsPending > 0) threads += ' · ' + n.threadsPending + ' pending';
			else if (n.threadsHogging > 0) threads += (threads ? ' · ' : '') + n.threadsHogging + ' hogging';
			if (!threads) threads = 'none';
			/* Stuck threads open the log at the container's stuck-thread warning
			   (BEA-000337). */
			var stuck = n.threadsStuck > 0 && n.domain === localDomain
				? L.to('log', { server: n.server, at: Date.now() - 15 * 60000, q: 'BEA-000337' }) : null;
			var th = el(stuck ? 'a' : 'span', 'srv__n' + (n.threadsStuck > 0 ? ' warn' : '') + (stuck ? ' srv__n--link' : ''), threads);
			if (stuck) { th.href = stuck.href; th.target = stuck.target; th.title = 'The stuck-thread warnings in ' + n.server + "'s server log"; }
			row.appendChild(th);
			list.appendChild(row);
		});
		body.appendChild(list);
	}

	/* The servers table's head: one short word a column, the long meaning on
	   hover, the number columns aligned with their numbers. */
	function srvHead(head, cols) {
		cols.forEach(function (c, i) {
			var h = el('span', i >= 3 ? 'srv__n' : '', c[0]);
			if (c[1]) h.title = c[1];
			head.appendChild(h);
		});
	}

	function heapCell(pct, text) {
		var heap = el('span', 'srv__heap' + (pct >= HEAP_HIGH ? ' hot' : ''));
		if (pct != null) {
			var bar = el('span', 'srv__bar'); var fill = el('span', 'srv__fill'); fill.style.width = Math.min(100, pct) + '%';
			bar.appendChild(fill); heap.appendChild(bar);
			heap.appendChild(el('b', '', text + (pct >= HEAP_HIGH ? ' high' : '')));
		} else heap.appendChild(el('b', '', 'n/a'));
		return heap;
	}

	/* Each server over the range, from its minute samples (platform.servers):
	   how it stood at its last sample, the highest heap and CPU, how often it
	   restarted, and the most threads stuck at once. */
	function drawServersPast(body) {
		guard(D.servers && D.servers['platform.servers'], body, function (res) {
			var col = function (n) { return res.columns.indexOf(n); };
			var rows = res.rows.map(function (r) {
				return { domain: r[col('domain')], server: r[col('server')], state: r[col('state')], heap: r[col('peak heap %')],
					cpu: r[col('peak cpu %')], restarts: r[col('restarts')], stuck: r[col('most stuck threads')],
					refused: r[col('refused')] };
			});
			if (!rows.length) return C.message(body, 'empty', 'No server samples in this range.');
			rows.sort(byDomainThenName);
			var list = el('div', 'srv');
			var head = el('div', 'srv__row srv__row--head');
			srvHead(head, [['state', 'the last state in the range'], ['server'], ['heap', 'the highest heap reading in the range'],
				['cpu', 'the busiest minute in the range'], ['restarts'], ['stuck', 'the most threads stuck at once']]);
			list.appendChild(head);
			var shownDomain;
			rows.forEach(function (n) {
				if (n.domain !== shownDomain) { shownDomain = n.domain; domainRow(list, n.domain, n.domain === localDomain ? 'this domain' : ''); }
				var row = el('div', 'srv__row');
				var st = el('span', 'srv__state ' + stateClass(n)); st.appendChild(el('i')); st.appendChild(document.createTextNode(stateWord(n)));
				row.appendChild(st);
				serverName(row, n);
				row.appendChild(heapCell(n.heap == null ? null : Math.round(n.heap), n.heap == null ? 'n/a' : Math.round(n.heap) + '%'));
				row.appendChild(el('span', 'srv__n', n.cpu == null ? '—' : Math.round(n.cpu) + '%'));
				row.appendChild(el('span', 'srv__n' + (n.restarts > 0 ? ' warn' : ''), n.restarts ? n.restarts + '×' : 'none'));
				row.appendChild(el('span', 'srv__n' + (n.stuck > 0 ? ' warn' : ''), n.stuck > 0 ? n.stuck + ' stuck' : 'none'));
				list.appendChild(row);
			});
			body.appendChild(list);
		});
	}

	function drawServers(body) {
		if (!RIGHTS.platform) return C.message(body, 'empty', 'Servers are not in your grant.');
		if (isLive()) drawServersNow(body); else drawServersPast(body);
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
			if (state.server && n.server !== state.server) return;
			(n.jdbc || []).forEach(function (d) {
				rows.push([n.server, d.name, d.state || '—', d.active, d.capacity, d.waiting, d.failures,
					d.failuresAt ? R.time(d.failuresAt) : '—']);
			});
		});
		if (!rows.length) return C.message(body, 'empty', 'No JDBC pools reported.');
		fitTable(body, { columns: ['server', 'pool', 'state', 'active', 'capacity', 'waiting', 'failures', 'last failure'], rows: rows });
	}

	/* ── operations ────────────────────────────────────────────────────── */

	var OPS_WORDS = {
		'org.vorpal.blade.endpoint.down': 'down', 'org.vorpal.blade.endpoint.up': 'up',
		'org.vorpal.blade.remote.down': 'cut off', 'org.vorpal.blade.remote.up': 'back',
		'org.vorpal.blade.trunk.failed': 'trunk down', 'org.vorpal.blade.trunk.registered': 'trunk up',
		'org.vorpal.blade.trunk.unregistered': 'trunk off', 'org.vorpal.blade.queue.depth': 'queue',
		'org.vorpal.blade.sip.denied': 'refused',
		'org.vorpal.blade.config.saved': 'config', 'org.vorpal.blade.config.published': 'pushed',
		'org.vorpal.blade.call.dropped': 'dropped', 'org.vorpal.blade.problem.raised': 'problem',
		'org.vorpal.blade.problem.cleared': 'solved'
	};
	var OPS_BAD = { down: 1, 'trunk down': 1, refused: 1, 'cut off': 1, dropped: 1, problem: 1 };

	/* Where a problem is solved: the admin app that can show why, already at
	   the call, log, file or time (links.js; each app declares what it opens).
	   A problem an application raised names its own page. */
	var L = window.BladeLinks;
	function solveLink(e) {
		var at = e.time ? new Date(e.time).getTime() : null;
		switch (e.type) {
		case 'org.vorpal.blade.problem.raised':
			return /^\/blade\/[\w-]/.test(e.open || '')
				? { href: e.open, target: 'blade-' + e.open.split('/')[2], app: (e.application || 'its') + ' admin page' } : null;
		case 'org.vorpal.blade.call.dropped':
			return e.vorpalId ? L.to('call', { call: e.vorpalId }) : null;
		case 'org.vorpal.blade.config.saved':
		case 'org.vorpal.blade.config.published':
			return L.to('config', { application: e.application || String(e.file || '').replace(/^.*\//, '').replace(/\.json$/, '') });
		case 'org.vorpal.blade.endpoint.down':
		case 'org.vorpal.blade.endpoint.up':
			return L.to('endpoint', { endpoint: e.endpoint }) || L.to('log', { server: e.node, at: at, q: e.endpoint });
		case 'org.vorpal.blade.remote.down':
		case 'org.vorpal.blade.remote.up':
			return L.to('log', { server: e.node, app: e.application, at: at, q: e.remote });
		case 'org.vorpal.blade.trunk.failed':
		case 'org.vorpal.blade.trunk.registered':
		case 'org.vorpal.blade.trunk.unregistered':
			return L.to('log', { server: e.node, app: 'gateway', at: at, q: e.gateway });
		default:
			return null;
		}
	}

	/* ── the peek drawer ──
	   A quick look at an app that can show itself embedded (it declares
	   "embed" in its opens.json), beside the board; "Open in its own window"
	   is one click away. New windows stay the default: the drawer opens only
	   from its own button. */
	var drawer = null;
	function peek(link) {
		if (!drawer) {
			drawer = el('aside', 'drawer');
			drawer.setAttribute('aria-label', 'Quick look');
			var head = el('div', 'drawer__head');
			drawer.title_ = el('span', 'drawer__title', '');
			drawer.own = el('a', 'drawer__own', 'Open in its own window ↗');
			var close = el('button', 'drawer__close', 'Close ✕');
			close.type = 'button';
			close.addEventListener('click', function () { drawer.hidden = true; drawer.frame.src = 'about:blank'; });
			head.appendChild(drawer.title_); head.appendChild(drawer.own); head.appendChild(close);
			drawer.frame = el('iframe', 'drawer__frame');
			drawer.frame.title = 'Quick look';
			drawer.appendChild(head); drawer.appendChild(drawer.frame);
			document.body.appendChild(drawer);
			document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape' && !drawer.hidden) close.click(); });
		}
		var hash = link.href.indexOf('#'), base = hash < 0 ? link.href : link.href.slice(0, hash);
		drawer.frame.src = base + (base.indexOf('?') < 0 ? '?' : '&') + 'embed=1' + (hash < 0 ? '' : link.href.slice(hash));
		drawer.title_.textContent = link.app;
		drawer.own.href = link.href; drawer.own.target = link.target;
		drawer.hidden = false;
	}

	/* A "peek" button for a link whose app can show itself embedded, or null. */
	function peekButton(link) {
		if (!link || !link.embed) return null;
		var b = el('button', 'peek', 'peek');
		b.type = 'button';
		b.title = 'Quick look at ' + link.app + ' beside the board';
		b.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); peek(link); });
		return b;
	}

	/* When an event happened, in the viewer's zone: the clock time today, the
	   date as well before today. */
	function opsWhen(ms) {
		var day = function (t) { return new Date(t).toLocaleDateString([], { timeZone: R.zone() }); };
		var clockTime = R.time(ms, { hour: 'numeric', minute: '2-digit', second: '2-digit' });
		if (day(ms) === day(Date.now())) return clockTime;
		return new Date(ms).toLocaleDateString([], { timeZone: R.zone(), month: 'short', day: 'numeric' }) + ' ' + clockTime;
	}

	/* How long something lasted: "40 s", "6 min", "2 h 5 min", "3 d 4 h". */
	function duration(ms) {
		var s = Math.max(0, Math.round(ms / 1000));
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
		case 'org.vorpal.blade.remote.up': return (e.kind ? e.kind + ' ' : '') + e.remote + (e.application ? ' · ' + e.application : '') + (e.reason ? ' · ' + e.reason : '');
		case 'org.vorpal.blade.trunk.failed': return e.gateway + ' · ' + (e.status || '') + ' ' + (e.reason || '');
		case 'org.vorpal.blade.trunk.registered':
		case 'org.vorpal.blade.trunk.unregistered': return e.gateway + (e.registrar ? ' · ' + e.registrar : '');
		case 'org.vorpal.blade.queue.depth': return e.queue + ' · ' + e.depth + ' waiting (low ' + e.low + ', high ' + e.high + ')';
		case 'org.vorpal.blade.sip.denied': return e.method + ' from ' + e.sourceAddress;
		case 'org.vorpal.blade.config.saved': return e.file + ' ' + e.change + ' by ' + e.actor + ' (' + e.editor + ')';
		case 'org.vorpal.blade.config.published': return e.file + ' → ' + (e.pushedTo || []).join(', ') + ((e.failed && e.failed.length) ? ' · FAILED on ' + e.failed.join(', ') : '');
		/* Each leg as it stood when the call expired: "caller EARLY, waiting". */
		case 'org.vorpal.blade.call.dropped': return (e.appName || 'call') + ' · ' + (e.legs || []).map(function (l) {
			return l.role + ' ' + l.state + (l.inviteOpen ? ', waiting' : '');
		}).join(' · ') + (e.vorpalId ? ' · call ' + e.vorpalId : '');
		case 'org.vorpal.blade.problem.raised': return (e.application ? e.application + ' · ' : '') + (e.summary || e.key);
		case 'org.vorpal.blade.problem.cleared': return (e.application ? e.application + ' · ' : '') + e.key;
		default: return e.type;
		}
	}

	/* Rows that fit, newest first, then a count of the rest. `when(e)` words
	   the right column (default: when it happened); the full line and the full
	   date are on hover either way. */
	function opsRows(body, items, word, empty, when) {
		if (!items.length) return C.message(body, 'empty', empty);
		var box = el('div', 'ops-list');
		body.appendChild(box);
		var shown = 0;
		for (var i = 0; i < items.length; i++) {
			var e = items[i], w = word(e);
			var link = solveLink(e);
			var row = el('div', 'ops-row' + (link ? ' ops-row--link' : '')), main = opsSummary(e) + (e.node ? ' · ' + e.node : '');
			var go = row;
			if (link) { go = el('a', 'ops-row__go'); go.href = link.href; go.target = link.target; row.appendChild(go); }
			var at = e.time ? new Date(e.time).getTime() : null;
			go.appendChild(el('span', 'ops-row__what' + (OPS_BAD[w] ? ' bad' : ''), w));
			go.appendChild(el('span', 'ops-row__main', main));
			go.appendChild(el('span', 'ops-row__when', at == null ? '' : when ? when(e) : opsWhen(at)));
			var pb = peekButton(link);
			if (pb) row.appendChild(pb);
			row.title = w + ' · ' + main + (at == null ? '' : '\n'
				+ new Date(at).toLocaleString([], { timeZone: R.zone(), dateStyle: 'medium', timeStyle: 'medium' }))
				+ (e.until ? ' until ' + new Date(e.until).toLocaleString([], { timeZone: R.zone(), dateStyle: 'medium', timeStyle: 'medium' }) : '')
				+ (link ? '\nOpen in ' + link.app : '');
			box.appendChild(row);
			if (box.scrollHeight > body.clientHeight - 20) { box.removeChild(row); break; }
			shown++;
		}
		if (shown < items.length) body.appendChild(el('div', 'panel__more', (items.length - shown) + ' more'));
	}

	/* The live feed's events, narrowed to the chosen server. */
	function onServer(items) {
		return state.server ? items.filter(function (e) { return e.node === state.server; }) : items;
	}

	function liveIssues() {
		if (!lastOps) return [];
		return onServer(lastOps.endpointsDown.map(function (e) { e.type = 'org.vorpal.blade.endpoint.down'; return e; })
			.concat(lastOps.trunksFailing.map(function (e) { e.type = 'org.vorpal.blade.trunk.failed'; return e; }))
			.concat((lastOps.remotesDown || []).map(function (e) { e.type = 'org.vorpal.blade.remote.down'; return e; }))
			.concat((lastOps.problems || []).map(function (e) { e.type = 'org.vorpal.blade.problem.raised'; return e; })));
	}

	/* An operations event from the database (ops.events, ops.issues) in the
	   shape the live feed gives it, so one renderer words both. */
	function opsEvent(type, at, node, application, subject, detail, extra) {
		var e = { type: type, time: at, node: node, application: application };
		switch (type.replace(/^org\.vorpal\.blade\./, '').split('.')[0]) {
		case 'endpoint': e.endpoint = subject; e.note = detail; break;
		case 'remote': e.remote = subject; e.reason = detail; break;
		case 'trunk': e.gateway = subject; e.reason = detail; break;
		case 'queue': e.queue = subject; break;
		case 'problem': e.key = subject; e.summary = detail; break;
		}
		Object.keys(extra || {}).forEach(function (k) { if (extra[k] != null) e[k] = extra[k]; });
		return e;
	}

	function pastEvents() {
		return rowsOf(D.ops && D.ops['ops.events']).map(function (r) {
			return opsEvent(r[1], r[0], r[2], r[3], r[4], r[5], { kind: r[6], registrar: r[7], status: r[8], low: r[9], high: r[10],
				depth: r[11], open: r[12] });
		});
	}

	function pastIssues() {
		return rowsOf(D.ops && D.ops['ops.issues']).map(function (r) {
			return opsEvent(r[0], r[5], r[2], r[3], r[1], r[4], { until: r[6], open: r[7] });
		});
	}

	/* Operations needs the platform grant and, for the live feed, this domain. */
	function opsAllowed(body) {
		if (!RIGHTS.platform) { C.message(body, 'empty', 'Operations events are not in your grant.'); return false; }
		if (liveFeed()) {
			if (localOnly(body, 'Operations events')) return false;
			if (!lastOps) { C.message(body, 'empty', 'Listening to the event bus…'); return false; }
		}
		return true;
	}

	function drawIssues(body) {
		if (!opsAllowed(body)) return;
		if (liveFeed()) {
			return opsRows(body, liveIssues(), function (e) { return OPS_WORDS[e.type]; },
				'No open issues' + (state.server ? ' on ' + state.server : '') + '. An endpoint, trunk, cut-off service or a problem an application raised shows here from the event that reports it until the one that clears it.',
				function (e) { return 'for ' + duration(Date.now() - new Date(e.time).getTime()); });
		}
		guard(D.ops && D.ops['ops.issues'], body, function () {
			opsRows(body, pastIssues(), function (e) { return OPS_WORDS[e.type]; },
				'Nothing was down and no problem was open at this time.',
				function (e) {
					return e.until ? duration(e.until - e.time) + ', from ' + clockText(e.time) : 'since ' + opsWhen(e.time) + ', still open';
				});
		});
	}

	function drawQueues(body) {
		if (!opsAllowed(body)) return;
		if (liveFeed()) {
			var q = lastOps ? onServer(lastOps.queues.map(function (e) { e.type = 'org.vorpal.blade.queue.depth'; return e; })) : [];
			return opsRows(body, q, function () { return 'queue'; }, 'No queue has held a caller since this server started listening.');
		}
		guard(D.ops && D.ops['ops.queues'], body, function (res) {
			if (!res.rows.length) return C.message(body, 'empty', 'No queue held a caller at this time.');
			fitTable(body, { columns: res.columns, rows: res.rows.map(function (r) {
				return [r[0], r[1], r[2], r[3], r[4], r[5] == null ? '—' : opsWhen(r[5])];
			}) });
		});
	}

	function drawRecent(body) {
		if (!opsAllowed(body)) return;
		if (liveFeed()) {
			return opsRows(body, onServer(lastOps ? lastOps.recent : []), function (e) { return OPS_WORDS[e.type] || 'event'; }, 'No operations events yet.');
		}
		guard(D.ops && D.ops['ops.events'], body, function () {
			opsRows(body, pastEvents(), function (e) { return OPS_WORDS[e.type] || 'event'; }, 'No operations events at this time.');
		});
	}

	/* What changed in the range (the `changes` report), newest first: one
	   row a day, or within a day or less one row a minute. Restarts counted
	   per server, configuration files by name. Each row's every change, with
	   its time, is on hover. */
	function changeDays(res, byMinute) {
		var col = function (name) { return res.columns.indexOf(name); };
		var byDay = {}, order = [];
		res.rows.forEach(function (r) {
			var day = byMinute ? String(r[col('at')] - r[col('at')] % 60000) : String(r[col('bucket')]).slice(0, 10);
			var at = r[col('at')], change = r[col('change')], server = r[col('server')], what = r[col('what')];
			var file = r[col('file')], failed = r[col('failed on')];
			failed = failed && failed !== '[]' ? String(failed).replace(/[\[\]"]/g, '') : '';
			var d = byDay[day];
			if (!d) { d = byDay[day] = { day: day, lines: [], servers: {}, apps: {}, configs: [] }; order.push(d); }
			var line;
			switch (change) {
			case 'server restarted':
				line = server + ' restarted';
				d.servers[server] = (d.servers[server] || 0) + 1;
				break;
			case 'application restarted':
				line = what + ' restarted on ' + server;
				(d.apps[server] = d.apps[server] || []).push(what);
				break;
			case 'config pushed':
				line = file + ' pushed' + (failed ? ', FAILED on ' + failed : '');
				if (d.configs.indexOf(line) < 0) d.configs.push(line);
				break;
			default: // config saved: what is "actor · saved|restored|deleted"
				line = file + ' ' + String(what).split(' · ').reverse().join(' by ');
				if (d.configs.indexOf(line) < 0) d.configs.push(line);
			}
			d.lines.push(clockText(at) + ' ' + line);
		});
		order.reverse();
		return {
			columns: [byMinute ? 'time' : 'day', 'what changed'],
			rows: order.map(function (d) {
				var parts = [];
				Object.keys(d.servers).forEach(function (srv) {
					var n = d.servers[srv];
					parts.push(srv + ' restarted' + (n > 1 ? ' ' + n + ' times' : ''));
				});
				Object.keys(d.apps).forEach(function (srv) {
					var apps = d.apps[srv], names = apps.filter(function (a, i) { return apps.indexOf(a) === i; });
					var times = function (a) { var n = apps.filter(function (b) { return b === a; }).length; return n > 1 ? a + ' ×' + n : a; };
					parts.push(names.length <= 4 ? names.map(times).join(', ') + ' restarted on ' + srv
						: apps.length + ' restarts of ' + names.length + ' applications on ' + srv);
				});
				if (byMinute) return [dayText(+d.day) + ' ' + clockText(+d.day), parts.concat(d.configs).join(' · ')];
				var p = d.day.split('-'), date = new Date(+p[0], +p[1] - 1, +p[2]);
				return [date.toLocaleDateString([], { weekday: 'short' }) + ' ' + p[1] + '/' + p[2], parts.concat(d.configs).join(' · ')];
			}),
			titles: order.map(function (d) {
				var shown = d.lines.slice(0, 20);
				return (d.lines.length === 1 ? '1 change' : d.lines.length + ' changes') + ':\n' + shown.join('\n')
					+ (d.lines.length > shown.length ? '\n… ' + (d.lines.length - shown.length) + ' more' : '');
			})
		};
	}

	function drawChanges(body) {
		if (!RIGHTS.platform) return C.message(body, 'empty', 'Changes are not in your grant.');
		guard(D.ops && D.ops.changes, body, function (res) {
			// A day or less is told by the minute; anything longer by the day.
			var s = state.slice;
			var span = s && s.from ? s.until - s.from : (s ? 3600000 : rangeEnd() - rangeStart());
			var t = changeDays(res, span <= 86400000 + 60000);
			if (!t.rows.length) return C.message(body, 'empty', 'Nothing restarted and no configuration changed at this time.');
			fitTable(body, t, { wrap: true, more: span <= 86400000 + 60000 ? 'more' : 'more days' });
			body.querySelectorAll('tbody tr').forEach(function (tr, i) { tr.title = t.titles[i]; });
		});
	}

	/* ── analytics ─────────────────────────────────────────────────────── */

	/* Report results by group: each group is one request, over the range and
	   the choices the panels it fills follow. */
	var D = {};

	/* How calls ended, good to bad, bottom up: the stoplight order, so position
	   says what the colour says. Declined is ordinary traffic, fair like an
	   abandon; failed and dropped are faults. */
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
	   tab shares a time axis. A gap is a bucket with no sample, not a zero. */
	function perServer(report, what) {
		return function (body) {
			if (!RIGHTS.platform) return C.message(body, 'empty', what + ' are not in your grant.');
			guard(D.traffic && D.traffic[report], body, function (res) {
				var calls = D.traffic['calls.by-outcome'];
				var buckets = rowsOf(calls).map(function (r) { return r[0]; });
				var byServer = {};
				res.rows.forEach(function (r) { (byServer[r[1]] = byServer[r[1]] || {})[r[0]] = r[2]; });
				var series = Object.keys(byServer).sort().map(function (server) {
					return { name: server, values: buckets.map(function (b) { return [b, b in byServer[server] ? byServer[server][b] : null]; }) };
				});
				if (!series.length) return C.message(body, 'empty', 'No server samples at this time.');
				C.multiLine(body, series, mergeOpts({ pct: true, colors: LINE_COLORS() }, calls ? bucketPick(calls) : {}));
			});
		};
	}

	/* "busiest: Thu 00:00 · 28 calls", the bucket with the most calls started,
	   in the viewer's zone, as the report's buckets already are. */
	function busiest(res) {
		var best = null, most = 0;
		res.rows.forEach(function (r) {
			var n = 0;
			for (var i = 1; i < r.length; i++) n += r[i] || 0;
			if (n > most) { most = n; best = r[0]; }
		});
		if (!best) return 'no calls at this time';
		return 'busiest: ' + when(best) + ' · ' + C.fmt(most) + (most === 1 ? ' call' : ' calls');
	}

	/* "Thu 10/08 14:00" for a bucket with a time, "Thu 10/08" for a day:
	   buckets are already in the viewer's zone. */
	function when(bucket) {
		var m = /^(\d{4})-(\d{2})-(\d{2})(?: (\d{2}:\d{2}))?$/.exec(bucket);
		if (!m) return bucket;
		return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][new Date(+m[1], +m[2] - 1, +m[3]).getDay()] + ' ' + m[2] + '/'
			+ m[3] + (m[4] ? ' ' + m[4] : '');
	}

	/* The reports each group asks for. Individual calls only while their tab
	   is open: a request for them is audited as someone looking at calls,
	   which a hidden tab is not. */
	var GROUPS = {
		base: function () {
			return { r: ['calls.summary', 'outcomes.summary', 'outcomes.ended', 'outcomes.daily', 'outcomes.by-application',
				'duration.summary', 'risk.summary'], q: rangeQuery() + scopeQuery() };
		},
		/* Every domain's servers, so the Servers panel can offer each domain. */
		servers: function () {
			return { r: RIGHTS.platform ? ['platform.latest', 'platform.servers'] : [], q: rangeQuery() };
		},
		traffic: function () {
			var r = ['calls.by-outcome', 'calls.concurrent', 'calls.heatmap'];
			if (RIGHTS.platform) r.push('platform.heap', 'platform.cpu');
			return { r: r, q: rangeQuery() + scopeQuery() + (state.server ? '&server=' + encodeURIComponent(state.server) : '') };
		},
		ops: function () {
			var r = RIGHTS.platform ? ['changes'] : [];
			if (RIGHTS.platform && !liveFeed()) r.push('ops.events', 'ops.issues', 'ops.queues');
			return { r: r, q: sliceQuery() + scopeQuery() + (state.server ? '&server=' + encodeURIComponent(state.server) : '') };
		},
		apps: function () {
			return { r: ['calls.by-application', 'duration.bands', 'health.coverage'],
				q: rangeQuery() + scopeQuery() + (state.outcome ? '&outcome=' + state.outcome : '') };
		},
		calls: function () {
			var r = ['highrisk.by-band', 'risk.daily'];
			if (P.risk && P.risk.current === 'flagged') r.push('highrisk.calls');
			if (P.risk && P.risk.current === 'list' && RIGHTS.content) r.push('calls.list');
			return { r: r, q: rangeQuery() + scopeQuery() + (state.outcome ? '&outcome=' + state.outcome : '')
				+ (state.app ? '&app=' + encodeURIComponent(state.app) : '') };
		}
	};
	/* The panels each group fills. */
	var DRAWS = { base: ['outcomes'], servers: ['cluster'], traffic: ['traffic'], ops: ['ops'], apps: ['apps'], calls: ['risk'] };

	var seq = {};
	function loadGroup(name) {
		var g = GROUPS[name]();
		if (!g.r.length) { D[name] = {}; DRAWS[name].forEach(function (k) { P[k].draw(); }); return Promise.resolve(); }
		var n = seq[name] = (seq[name] || 0) + 1;
		return getJson('data?r=' + g.r.join(',') + g.q + '&tz=' + encodeURIComponent(R.zone())).then(function (d) {
			if (n !== seq[name]) return;   // a newer choice asked again
			D[name] = d;
			if (name === 'base') baseKpis(d);
			if (name === 'servers') serverKpis();
			DRAWS[name].forEach(function (k) { P[k].draw(); });
		});
	}

	function baseKpis(d) {
		var cs = d['calls.summary'], os = d['outcomes.summary'], ds = d['duration.summary'], rs = d['risk.summary'];
		kpi('calls', cell(cs, 'calls'), state.range === 'custom' ? '' : C.fmt(cell(cs, 'today')) + ' today');
		var started = cell(os, 'started'), answered = cell(os, 'answered');
		kpi('answered', answered, (started ? Math.round(100 * answered / started) + '% of ' + C.fmt(started) + ' started'
			: 'none started') + ' · ' + C.fmt(cell(os, 'lost')) + ' lost');
		var med = cell(ds, 'median');
		kpi('duration', clock(med), med == null ? '' : 'average ' + clock(cell(ds, 'average')) + ' · longest ' + clock(cell(ds, 'longest')));
		var flagged = cell(rs, 'calls flagged');
		kpi('flagged', flagged, C.fmt(cell(rs, 'calls assessed')) + ' assessed', flagged > 0);
	}

	function loadAnalytics() {
		return Promise.all(Object.keys(GROUPS).map(loadGroup)).then(function () {
			// The clock beside it says the time; the refresh time is on hover only.
			setStatus('', 'live');
			statusEl.title = 'Analytics refreshed at ' + R.time(Date.now(), { hour: '2-digit', minute: '2-digit', second: '2-digit' });
		}).catch(function (e) { if (e.message !== 'auth') setStatus('err', 'analytics failed'); });
	}

	/* Live re-reads the last hour often, a range of days each minute, and a
	   custom range never: it does not move. */
	var analyticsTimer = null;
	function scheduleAnalytics() {
		clearInterval(analyticsTimer);
		if (state.range === 'custom') return;
		analyticsTimer = setInterval(loadAnalytics, isLive() ? Math.min(ANALYTICS_MS, LIVE_ANALYTICS_MS) : ANALYTICS_MS);
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
		if (remoteScope()) return C.message(body, 'empty', 'The network map shows this domain. Choose it in Servers.');
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

	/* ── panel definitions ─────────────────────────────────────────────── */

	var P = {};

	function buildPanels() {
		P.cluster = new Panel('cluster', 'Servers', 'platform', false);
		P.cluster.setViews([
			{ key: 'servers', label: 'Servers', live: isLive,
				sub: function () { return isLive() ? 'click one to narrow' : 'peaks in the range'; }, tip: 'Click a server to narrow Traffic and Operations to it',
				draw: drawServers },
			{ key: 'jdbc', label: 'JDBC', live: true, sub: 'connection pools', draw: drawJdbc }
		]);
		P.cluster.showRange = function () {
			Panel.prototype.showRange.call(this);
			var v = this.view();
			if (v && v.key === 'servers' && !isLive()) { this.range.hidden = false; this.range.textContent = rangePhrase(); }
		};

		P.traffic = new Panel('traffic', 'Traffic', 'calls', true, ['server']);
		P.traffic.setViews([
			{ key: 'calls', label: 'Calls', sub: 'click a time to narrow', tip: 'Click a time to narrow Operations to it', draw: function (body) {
				guard(D.traffic && D.traffic['calls.by-outcome'], body, function (res) {
					P.traffic.sub.textContent = busiest(res);
					C.stackBar(body, res.rows, mergeOpts({ parts: OUTCOME_PARTS, empty: 'No calls ended at this time.' }, bucketPick(res)));
				});
			} },
			{ key: 'sessions', label: 'Sessions', sub: 'peak concurrent calls', draw: function (body) {
				guard(D.traffic && D.traffic['calls.concurrent'], body, function (res) {
					var best = res.rows.reduce(function (b, r) { return r[1] > b[1] ? r : b; }, ['', 0]);
					P.traffic.sub.textContent = best[1] ? 'peak ' + C.fmt(best[1]) + ' · ' + when(best[0]) : 'no calls at this time';
					if (!best[1]) return C.message(body, 'empty', 'No calls at this time.');
					C.line(body, res.rows, mergeOpts({ name: 'peak', endLabel: false }, bucketPick(res)));
				});
			} },
			{ key: 'heap', label: 'Heap', sub: 'highest per server', draw: perServer('platform.heap', 'Heap readings') },
			{ key: 'cpu', label: 'CPU', sub: 'busiest minute per server', draw: perServer('platform.cpu', 'CPU readings') },
			{ key: 'when', label: 'When', sub: 'weekday × hour', tip: 'Click an hour to narrow Operations to every such hour in the range', draw: function (body) {
				guard(D.traffic && D.traffic['calls.heatmap'], body, function (res) {
					var s = state.slice;
					C.heatmap(body, res.rows, {
						pick: function (r) {
							var dow = WEEKDAYS.indexOf(r[0]) + 1;
							choose({ slice: s && s.dow === dow && s.hod === +r[1] ? null : { dow: dow, hod: +r[1] } });
						},
						picked: s && s.dow ? [WEEKDAYS[s.dow - 1], s.hod] : null
					});
				});
			} },
			{ key: 'network', label: 'Network', sub: 'sites and routes', live: true, narrow: false, first: true, draw: drawNetwork,
				fetch: function () { loadNetwork().catch(function () {}); } }
		]);

		P.ops = new Panel('ops', 'Operations', null, true, ['server', 'slice']);
		P.ops.setViews([
			{ key: 'issues', label: 'Issues', live: liveFeed,
				sub: function () { return liveFeed() ? 'open now' : 'open in the range'; }, tip: 'An issue stays until it is cleared', draw: drawIssues },
			{ key: 'queues', label: 'Queues', live: liveFeed,
				sub: function () { return liveFeed() ? 'last minute' : 'minutes with callers'; }, tip: 'Per node: the minutes each queue held callers', draw: drawQueues },
			{ key: 'recent', label: 'Recent', live: liveFeed, sub: 'newest first', draw: drawRecent },
			{ key: 'changes', label: 'Changes', sub: 'newest first', tip: 'Restarts and configuration changes; hover a row for each one and its time', draw: drawChanges }
		]);
		/* Operations narrowed to a time shows that time, not the whole range. */
		P.ops.showRange = function () {
			Panel.prototype.showRange.call(this);
			var v = this.view();
			var live = v && (typeof v.live === 'function' ? v.live() : v.live);
			if (state.slice && !live) this.range.hidden = true;
		};

		P.outcomes = new Panel('outcomes', 'Outcomes', 'outcomes', true);
		P.outcomes.setViews([
			{ key: 'ended', label: 'Ended', sub: 'click one to narrow', tip: 'Each call counted once. Click an outcome to narrow Applications and Calls to it', draw: function (body) {
				guard(D.base && D.base['outcomes.ended'], body, function (res) {
					var by = {};
					res.rows.forEach(function (r) { by[r[0]] = r[1] || 0; });
					var total = Object.keys(by).reduce(function (n, k) { return n + by[k]; }, 0);
					if (!total) return C.message(body, 'empty', 'No call ended in this range.');
					var rows = OUTCOME_PARTS.map(function (p) { return [p.name, by[p.name.toLowerCase()] || 0, p]; });
					C.hbar(body, rows, { valueWidth: 90,
						pick: function (r) {
							var o = r[0].toLowerCase();
							choose({ outcome: state.outcome === o ? null : o });
						},
						picked: state.outcome ? state.outcome.charAt(0).toUpperCase() + state.outcome.slice(1) : null,
						value: function (r) { return C.fmt(r[1]) + ' · ' + Math.round(100 * r[1] / total) + '%'; },
						color: function (r) { return r[2].color; },
						title: function (r) {
							return r[0] + ': ' + C.fmt(r[1]) + '\n' + r[2].explain + '\nClick: Applications and Calls for these calls only';
						} });
				});
			} },
			{ key: 'daily', label: 'Over time', sub: 'how calls ended', draw: function (body) {
				guard(D.base && D.base['outcomes.daily'], body, function (res) {
					C.multiLine(body, seriesOf(res, OUTCOME_NAMES),
						{ colors: OUTCOME_PARTS.map(function (p) { return C.token(p.color); }) });
				});
			} },
			{ key: 'apps', label: 'By application', sub: 'per application', draw: function (body) {
				guard(D.base && D.base['outcomes.by-application'], body, function (res) { fitTable(body, res); });
			} }
		]);

		P.apps = new Panel('apps', 'Applications', 'health', true, ['outcome']);
		P.apps.setViews([
			{ key: 'calls', label: 'Calls', sub: 'click one to narrow', tip: 'A call through two applications counts for each. Click an application to narrow Calls to it', draw: function (body) {
				guard(D.apps && D.apps['calls.by-application'], body, function (res) {
					C.hbar(body, res.rows, { limit: 8,
						pick: function (r) { choose({ app: state.app === r[0] ? null : r[0] }); },
						picked: state.app,
						title: function (r) { return r[0] + ': ' + C.fmt(r[1]) + ' calls\nClick: the Calls panel for ' + r[0] + "'s calls only"; } });
				});
			} },
			{ key: 'length', label: 'Length', sub: 'calls by duration', draw: function (body) {
				guard(D.apps && D.apps['duration.bands'], body, function (res) { C.bar(body, res.rows); });
			} },
			{ key: 'gaps', label: 'Gaps', narrow: false, sub: 'events never sent', tip: 'Lifecycle events each application never sends, over every call', draw: function (body) {
				guard(D.apps && D.apps['health.coverage'], body, function (res) {
					fitTable(body, pick(res, ['application', 'calls', 'gaps']));
				});
			} }
		]);

		P.risk = new Panel('risk', 'Calls', 'risk', true, ['outcome', 'app']);
		P.risk.setViews([
			{ key: 'list', label: 'Calls', sub: 'click one for Trace', tip: 'Newest first. Click a call to open it in Trace', fetch: function () { loadGroup('calls'); },
				draw: function (body) {
					if (!RIGHTS.content) return C.message(body, 'empty', 'Individual calls are not in your grant.');
					/* The flight recorder keeps a trace of every failed and dropped call. */
					if ((state.outcome === 'failed' || state.outcome === 'dropped') && L.has('recorded')) {
						var rec = L.to('recorded', { since: rangeStart() });
						var a = el('a', 'panel__link', 'Their traces, as the flight recorder kept them, in Trace ↗');
						a.href = rec.href; a.target = rec.target;
						body.appendChild(a);
					}
					// The table draws into a box of its own: it clears what it draws into.
					var box = el('div', 'panel__fill');
					body.appendChild(box);
					guard(D.calls && D.calls['calls.list'], box, function (res) {
						if (!res.rows.length) return C.message(box, 'empty', 'No calls at this time.');
						fitTable(box, res, { rowOpen: function (row) { return L.to('call', { call: row[0] }); } });
					});
				} },
			{ key: 'bands', label: 'Risk', sub: 'calls by worst band reached', draw: function (body) {
				guard(D.calls && D.calls['highrisk.by-band'], body, function (res) { R.bandList(body, res.rows); });
			} },
			{ key: 'daily', label: 'Over time', sub: 'calls assessed', draw: function (body) {
				guard(D.calls && D.calls['risk.daily'], body, function (res) { C.bar(body, res.rows); });
			} },
			{ key: 'flagged', label: 'Flagged', sub: 'highest score first', fetch: function () { loadGroup('calls'); },
				draw: function (body) {
					// Four columns fit a third of the screen; the full report has the rest.
					guard(D.calls && D.calls['highrisk.calls'], body, function (res) { fitTable(body, pick(res, ['worst band', 'peak score', 'call', 'first assessed'])); });
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
			lastNodes = r.nodes || [];
			/* A past range may have drawn before this domain's name was known. */
			var named = r.domain && !localDomain;
			if (r.domain) localDomain = r.domain;
			serverKpis();
			if (named || isLive() || P.cluster.current === 'jdbc') P.cluster.draw();
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

	/* A domain chosen in Servers scopes the whole board, headline numbers
	   included. Remembered per browser. */
	function scopeChanged(value) {
		scope = value; store('domain', scope);
		relinkAll();
		['base', 'traffic', 'ops', 'apps', 'calls'].forEach(function (g) { D[g] = undefined; });
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
			if (remoteScope()) { kpi('queued', null, 'read live in ' + localDomain + ' only'); }
			else {
				/* Calls waiting in a queue, live from the queue application's gauges;
				   the line is how long the longest of them has waited. */
				var q = o.queuedNow || {};
				if (q.calls == null) kpi('queued', null, q.error ? 'queue reading failed' : 'no queue application running', !!q.error);
				else kpi('queued', q.calls, q.calls ? 'longest waiting ' + clock(q.longestMs / 1000) : 'none waiting');
			}
			if (liveFeed() && P.ops.current !== 'changes') P.ops.draw();
		});
	}

	/* The range buttons: Live, the settings' ranges, and Custom, which opens
	   two date pickers read in the board's time zone. */
	function buildRange() {
		var host = document.getElementById('range');
		host.textContent = '';
		var add = function (key, label, title) {
			var b = el('button', state.range === key ? 'on' : '', label);
			b.type = 'button';
			if (title) b.title = title;
			b.addEventListener('click', function () {
				if (key === 'custom') return openCustom(b);
				closeCustom();
				choose({ range: key, from: null, until: null });
			});
			host.appendChild(b);
		};
		add('live', 'Live', 'The last hour, as it happens');
		RANGES.forEach(function (r) { add(r, rangeLabel(r)); });
		add('custom', state.range === 'custom' ? spanText(state.from, state.until - 1) : 'Custom', 'Choose the start and end');
	}

	var customBox = null;
	function closeCustom() { if (customBox) customBox.hidden = true; }

	/* "2026-10-14T08:00" for a datetime-local input, in the board's zone. */
	function pickerValue(ms) {
		var p = {};
		new Intl.DateTimeFormat('en-CA', { timeZone: R.zone(), hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
			hour: '2-digit', minute: '2-digit' }).formatToParts(new Date(ms)).forEach(function (x) { p[x.type] = x.value; });
		return p.year + '-' + p.month + '-' + p.day + 'T' + p.hour + ':' + p.minute;
	}

	function pickerMs(value) {
		var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value || '');
		return m ? R.zonedMs(+m[1], +m[2], +m[3], +m[4], +m[5]) : null;
	}

	function openCustom(anchor) {
		if (!customBox) {
			customBox = el('div', 'custom');
			customBox.setAttribute('role', 'dialog');
			customBox.setAttribute('aria-label', 'Custom range');
			var row = function (label, input) { var l = el('label', 'custom__row'); l.appendChild(el('span', '', label)); l.appendChild(input); return l; };
			customBox.from = el('input'); customBox.from.type = 'datetime-local';
			customBox.until = el('input'); customBox.until.type = 'datetime-local';
			customBox.zone = el('div', 'custom__zone');
			customBox.err = el('div', 'custom__err');
			var ok = el('button', 'custom__ok', 'Show this range'); ok.type = 'button';
			var cancel = el('button', 'custom__cancel', 'Cancel'); cancel.type = 'button';
			ok.addEventListener('click', function () {
				var from = pickerMs(customBox.from.value), until = pickerMs(customBox.until.value);
				if (!from || !until || until <= from) { customBox.err.textContent = 'The end must come after the start.'; return; }
				if (from > Date.now()) { customBox.err.textContent = 'The start is in the future.'; return; }
				closeCustom();
				choose({ range: 'custom', from: from, until: Math.min(until, Date.now()) });
			});
			cancel.addEventListener('click', closeCustom);
			customBox.appendChild(row('From', customBox.from));
			customBox.appendChild(row('To', customBox.until));
			customBox.appendChild(customBox.zone);
			customBox.appendChild(customBox.err);
			var buttons = el('div', 'custom__buttons'); buttons.appendChild(cancel); buttons.appendChild(ok);
			customBox.appendChild(buttons);
			document.body.appendChild(customBox);
			document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeCustom(); });
		}
		var until = state.range === 'custom' ? state.until : Date.now(), from = state.range === 'custom' ? state.from : until - 86400000;
		customBox.from.value = pickerValue(from);
		customBox.until.value = pickerValue(until);
		customBox.zone.textContent = 'Times are ' + R.zone() + ', the zone chosen in the top bar. Long ranges can take a while.';
		customBox.err.textContent = '';
		var r = anchor.getBoundingClientRect();
		customBox.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 320)) + 'px';
		customBox.style.top = (r.bottom + 6) + 'px';
		customBox.hidden = false;
		customBox.from.focus();
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
		Object.keys(GROUPS).forEach(function (g) { D[g] = undefined; });
		buildRange();
		labelRanges();
		drawAll();
		loadAnalytics();
	}

	state = readHash();
	buildPanels();
	buildKpis();
	buildRange();
	R.zoneSelect(document.getElementById('tz'), zoneChanged);
	drawAll();
	tick();
	setStatus('', 'loading…');
	// The window the apps' "Dashboard" link comes back to.
	if (!window.name) window.name = 'blade-dashboard';
	// Links appear once the apps' declarations are in.
	L.load().then(drawAll, function () {});
	window.addEventListener('hashchange', function () { apply(readHash()); });
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
			/* The deployment's ranges, between Live and Custom. */
			if (cfg.ranges && cfg.ranges.length) {
				RANGES = cfg.ranges.map(String);
				buildRange();
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
		scheduleAnalytics();
	});
	setInterval(tick, 15000);
	var rz;
	window.addEventListener('resize', function () { clearTimeout(rz); rz = setTimeout(drawAll, 150); });
})();
