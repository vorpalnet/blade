/* BLADE Dashboard report registry: what each report family shows on its
   overview card and on its detail page (report.html?r=<family>).

   A family = a card (one headline number and a small trend, from the cached
   data) + sections (full charts and tables, queried live). Adding a report is
   one entry here plus its query in AnalyticsReports.java. */
(function (global) {
	'use strict';
	var C = global.BladeCharts;

	/* fetch that copes with FORM auth: an expired session returns the login page
	   (HTML, not JSON), so on anything but JSON we reload into the form. */
	function getJson(url) {
		return fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
			.then(function (r) {
				var ct = r.headers.get('content-type') || '';
				if (r.status === 401 || r.status === 403 || ct.indexOf('text/html') >= 0) {
					window.location.reload(); throw new Error('auth');
				}
				return r.text().then(function (t) {
					try { return JSON.parse(t); } catch (e) { window.location.reload(); throw new Error('auth'); }
				});
			});
	}

	/* The one value of a single-row report, by column name. */
	function cell(result, column) {
		if (!result || !result.rows || !result.rows.length) return null;
		var i = result.columns.indexOf(column);
		return i < 0 ? null : result.rows[0][i];
	}

	/* rows of [label, a, b, c] → one series per numeric column, for multiLine. */
	function seriesOf(result, names) {
		return names.map(function (name) {
			var i = result.columns.indexOf(name);
			return { name: name, values: result.rows.map(function (r) { return [r[0], r[i]]; }) };
		});
	}

	var FAMILIES = {
		calls: {
			title: 'Call volume',
			lede: 'How many calls, when they happen, and where they ran.',
			card: {
				reports: ['calls.summary', 'calls.daily'],
				headline: function (d) { return { value: cell(d['calls.summary'], 'calls'), label: 'calls',
					sub: C.fmt(cell(d['calls.summary'], 'today')) + ' today' }; },
				trend: 'calls.daily'
			},
			sections: [
				{ r: 'calls.summary', kind: 'tiles' },
				{ r: 'calls.daily', title: 'Calls per day', kind: 'bar', wide: true },
				{ r: 'calls.hourly', title: 'Calls per hour', kind: 'bar', wide: true, maxDays: 7 },
				{ r: 'calls.concurrent', title: 'Most calls up at once', sub: 'per hour', kind: 'line', name: 'peak', wide: true, maxDays: 7 },
				{ r: 'calls.heatmap', title: 'When calls happen', sub: 'weekday × hour of day', kind: 'heatmap', wide: true, tall: true },
				{ r: 'calls.hour-of-day', title: 'By hour of day', kind: 'bar' },
				{ r: 'calls.weekday', title: 'By weekday', kind: 'bar' },
				{ r: 'calls.by-application', title: 'By application', kind: 'hbar' },
				{ r: 'calls.by-engine', title: 'By engine', kind: 'hbar' },
				{ r: 'calls.by-tenant', title: 'By tenant', kind: 'hbar' }
			]
		},
		duration: {
			title: 'Call duration',
			lede: 'How long calls last, per application. Medians, because a few long calls drag an average.',
			card: {
				reports: ['duration.summary', 'duration.daily'],
				headline: function (d) { return { value: cell(d['duration.summary'], 'median'), label: 'median seconds',
					sub: 'average ' + C.fmt(cell(d['duration.summary'], 'average')) + ' s' }; },
				trend: 'duration.daily'
			},
			sections: [
				{ r: 'duration.summary', kind: 'tiles', units: 's' },
				{ r: 'duration.daily', title: 'Average seconds per day', kind: 'line', name: 'avg', wide: true },
				{ r: 'duration.bands', title: 'Calls by length', kind: 'bar' },
				{ r: 'duration.by-application', title: 'Duration by application', sub: 'seconds', kind: 'table' }
			]
		},
		outcomes: {
			title: 'Call outcomes',
			lede: 'Started, answered, completed and lost calls. Answer rate is per application only: some applications never publish "answered", and a pooled rate would count their calls as unanswered.',
			card: {
				reports: ['outcomes.summary', 'outcomes.daily'],
				headline: function (d) { return { value: cell(d['outcomes.summary'], 'answered'), label: 'calls answered',
					sub: C.fmt(cell(d['outcomes.summary'], 'started')) + ' started · ' + C.fmt(cell(d['outcomes.summary'], 'lost')) + ' lost' }; },
				trend: function (d) {
					var r = d['outcomes.daily'];
					return r ? r.rows.map(function (x) { return [x[0], x[2]]; }) : [];
				}
			},
			sections: [
				{ r: 'outcomes.summary', kind: 'tiles' },
				{ r: 'outcomes.daily', title: 'Calls per day by outcome', kind: 'lines', series: ['started', 'answered', 'abandoned'], wide: true },
				{ r: 'outcomes.funnel', title: 'From started to completed', sub: 'applications that report answering', kind: 'funnel' },
				{ r: 'outcomes.by-application', title: 'Outcomes by application', kind: 'table' }
			]
		},
		sessions: {
			title: 'Open sessions',
			lede: 'Sessions that never closed. A session open for days is a leak, not a call; these ignore the date range.',
			card: {
				reports: ['sessions.summary'],
				headline: function (d) { return { value: cell(d['sessions.summary'], 'open'), label: 'never closed',
					sub: cell(d['sessions.summary'], 'oldest') ? 'oldest ' + String(cell(d['sessions.summary'], 'oldest')).slice(0, 10) : 'none' }; }
			},
			sections: [
				{ r: 'sessions.summary', kind: 'tiles' },
				{ r: 'sessions.by-application', title: 'Open by application and engine', kind: 'table' },
				{ r: 'sessions.open', title: 'Every open session', sub: 'newest first', kind: 'table', wide: true }
			]
		},
		platform: {
			title: 'Platform activity',
			lede: 'Application starts and stops, and the versions that handled calls.',
			card: {
				reports: ['platform.summary', 'platform.daily'],
				headline: function (d) { return { value: cell(d['platform.summary'], 'starts'), label: 'application starts',
					sub: plural(cell(d['platform.summary'], 'stops'), 'stop') }; },
				trend: 'platform.daily'
			},
			sections: [
				{ r: 'platform.summary', kind: 'tiles' },
				{ r: 'platform.daily', title: 'Application starts per day', kind: 'bar', wide: true },
				{ r: 'platform.by-application', title: 'Starts and stops by application', kind: 'table' },
				{ r: 'platform.versions', title: 'Versions in service', kind: 'table' }
			]
		},
		events: {
			title: 'Event activity',
			lede: 'Every fact the analytics service recorded, by type and application.',
			card: {
				reports: ['events.summary', 'events.daily'],
				headline: function (d) { return { value: cell(d['events.summary'], 'events'), label: 'events',
					sub: C.fmt(cell(d['events.summary'], 'types')) + ' types' }; },
				trend: 'events.daily'
			},
			sections: [
				{ r: 'events.summary', kind: 'tiles' },
				{ r: 'events.daily', title: 'Events per day', kind: 'bar', wide: true },
				{ r: 'events.by-type', title: 'By type', kind: 'hbar', labelWidth: 150 },
				{ r: 'events.by-type-application', title: 'By type and application', kind: 'table' }
			]
		},
		risk: {
			title: 'Call risk',
			lede: 'Fused risk assessments: how many, which band, which signal triggered, and what each signal contributed.',
			card: {
				reports: ['risk.summary', 'risk.daily'],
				headline: function (d) { return { value: cell(d['risk.summary'], 'calls flagged'), label: 'calls flagged',
					sub: C.fmt(cell(d['risk.summary'], 'calls assessed')) + ' assessed' }; },
				trend: 'risk.daily'
			},
			sections: [
				{ r: 'risk.summary', kind: 'tiles' },
				{ r: 'risk.daily', title: 'Assessments per day', kind: 'bar', wide: true },
				{ r: 'risk.by-band', title: 'Assessments by band', sub: 'worst first', kind: 'bands' },
				{ r: 'risk.by-trigger', title: 'By triggering signal', kind: 'hbar' },
				{ r: 'risk.signals', title: 'What each signal contributed', sub: 'average log-odds, then raw reading, per band', kind: 'table', wide: true }
			]
		},
		highrisk: {
			title: 'High-risk calls',
			lede: 'Calls whose risk reached WATCH or SUSPECT, worst first.',
			card: {
				reports: ['highrisk.by-band'],
				headline: function (d) {
					var r = d['highrisk.by-band'], n = 0;
					if (r && r.rows) r.rows.forEach(function (x) { if (x[0] === 'SUSPECT') n = x[1]; });
					return { value: n, label: 'calls reached SUSPECT', bands: r };
				}
			},
			sections: [
				{ r: 'highrisk.by-band', title: 'Calls by worst band reached', kind: 'bands' },
				{ r: 'highrisk.calls', title: 'Calls that reached WATCH or SUSPECT', sub: 'highest score first', kind: 'table', wide: true }
			]
		},
		conversation: {
			title: 'Caller conversation',
			lede: 'What callers said to the system: utterances, intents, and whether the system thought it was being addressed.',
			card: {
				reports: ['conversation.summary', 'conversation.daily'],
				headline: function (d) { return { value: cell(d['conversation.summary'], 'utterances'), label: 'utterances',
					sub: C.fmt(cell(d['conversation.summary'], 'calls with speech')) + ' calls with speech' }; },
				trend: 'conversation.daily'
			},
			sections: [
				{ r: 'conversation.summary', kind: 'tiles' },
				{ r: 'conversation.daily', title: 'Utterances per day', kind: 'bar', wide: true },
				{ r: 'conversation.by-intent', title: 'By intent', kind: 'hbar' },
				{ r: 'conversation.by-addressed', title: 'Addressed to the system?', kind: 'hbar' },
				{ r: 'conversation.intents', title: 'Intents and entities', kind: 'table', wide: true }
			]
		}
	};

	var ORDER = ['calls', 'duration', 'outcomes', 'sessions', 'platform', 'events', 'risk', 'highrisk', 'conversation'];

	function plural(n, word) { return C.fmt(n) + ' ' + word + (n === 1 ? '' : 's'); }

	function asOf(ms) {
		return ms ? new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';
	}

	/* ── one section of a detail page ──────────────────────────────────── */

	function renderSection(body, sec, result, days) {
		if (sec.maxDays && days > sec.maxDays) {
			return C.message(body, 'empty', 'Hour by hour reads best over a week or less. Choose "last 7 days" or "last day".');
		}
		if (!result) return C.message(body, 'error', 'no data');
		if (result.error) return C.message(body, 'error', result.error);
		var rows = result.rows;
		switch (sec.kind) {
		case 'tiles': return tiles(body, result, sec.units);
		case 'bar': return C.bar(body, rows);
		case 'hbar': return C.hbar(body, rows, { labelWidth: sec.labelWidth });
		case 'line': return C.line(body, rows, { name: sec.name });
		case 'lines': return C.multiLine(body, seriesOf(result, sec.series),
			{ colors: [C.token('--accent'), C.token('--ok'), C.token('--warn')] });
		case 'heatmap': return C.heatmap(body, rows);
		case 'funnel': return C.funnel(body, rows);
		case 'bands': return bandList(body, rows);
		case 'table': return C.table(body, result);
		default: return C.message(body, 'error', 'unknown section kind ' + sec.kind);
		}
	}

	/* A single-row report as tiles: one tile per column. */
	function tiles(body, result, units) {
		body.textContent = '';
		if (!result.rows.length) return C.message(body, 'empty', 'Nothing in this window.');
		result.columns.forEach(function (col, i) {
			var t = document.createElement('div'); t.className = 'tile';
			var l = document.createElement('div'); l.className = 'tile__l'; l.textContent = col;
			var v = document.createElement('div'); v.className = 'tile__v'; v.textContent = C.fmt(result.rows[0][i]);
			if (units && typeof result.rows[0][i] === 'number') { var em = document.createElement('em'); em.textContent = units; v.appendChild(em); }
			t.appendChild(l); t.appendChild(v); body.appendChild(t);
		});
	}

	/* Risk bands, worst first top to bottom: the coloured pill carries the word,
	   the bar and the count carry the size. Position, word, number, colour. */
	function bandList(body, rows) {
		body.textContent = '';
		if (!rows.length) return C.message(body, 'empty', 'Nothing in this window.');
		var max = d3.max(rows, function (r) { return +r[1] || 0; }) || 1;
		rows.forEach(function (r) {
			var row = document.createElement('div'); row.className = 'band-row';
			var pill = C.bandPill(r[0]);
			var track = document.createElement('div'); track.className = 'band-row__track';
			var fill = document.createElement('div'); fill.className = 'band-row__fill';
			fill.style.width = (100 * (+r[1] || 0) / max) + '%';
			// The pill carries its band's colour inline; the bar uses the same one.
			fill.style.background = pill.style.getPropertyValue('--pill') || 'var(--accent)';
			track.appendChild(fill);
			var n = document.createElement('span'); n.className = 'band-row__n'; n.textContent = C.fmt(r[1]);
			row.appendChild(pill); row.appendChild(track); row.appendChild(n); body.appendChild(row);
		});
	}

	/* ── overview cards ────────────────────────────────────────────────── */

	function cardReports() {
		var all = [];
		ORDER.forEach(function (k) { FAMILIES[k].card.reports.forEach(function (r) { if (all.indexOf(r) < 0) all.push(r); }); });
		return all;
	}

	/* Draws the card grid into `host`, one link card per family, from one
	   cached request. */
	function renderCards(host) {
		return getJson('data?r=' + cardReports().join(',')).then(function (d) {
			ORDER.forEach(function (k) {
				var fam = FAMILIES[k];
				var card = host.querySelector('[data-family="' + k + '"]');
				if (!card) {
					card = document.createElement('a'); card.className = 'rcard'; card.href = 'report.html?r=' + k;
					card.setAttribute('data-family', k);
					card.innerHTML = '<div class="rcard__head"><span class="rcard__title"></span><span class="rcard__go">details →</span></div>'
						+ '<div class="rcard__value"></div><div class="rcard__label"></div><div class="rcard__sub"></div>'
						+ '<div class="rcard__trend"></div><div class="rcard__asof"></div>';
					card.querySelector('.rcard__title').textContent = fam.title;
					host.appendChild(card);
				}
				var first = d[fam.card.reports[0]];
				var valueEl = card.querySelector('.rcard__value');
				if (!first || first.error) {
					valueEl.textContent = '—';
					card.querySelector('.rcard__label').textContent = first && first.error ? first.error : 'no data';
					card.querySelector('.rcard__label').classList.add('error');
					return;
				}
				card.querySelector('.rcard__label').classList.remove('error');
				var h = fam.card.headline(d);
				valueEl.textContent = C.fmt(h.value);
				card.querySelector('.rcard__label').textContent = h.label;
				card.querySelector('.rcard__sub').textContent = h.sub || '';
				var trendEl = card.querySelector('.rcard__trend');
				if (h.bands) bandList(trendEl, h.bands.rows || []);
				else if (fam.card.trend) {
					var rows = typeof fam.card.trend === 'function' ? fam.card.trend(d)
						: (d[fam.card.trend] && d[fam.card.trend].rows) || [];
					C.sparkline(trendEl, rows);
				}
				card.querySelector('.rcard__asof').textContent = 'as of ' + asOf(first.asOf);
			});
			return d;
		});
	}

	/* ── detail page ───────────────────────────────────────────────────── */

	function params() {
		var p = new URLSearchParams(window.location.search);
		return { r: p.get('r') || 'calls', days: p.get('days') || '30', sample: p.get('sample') === 'include', cluster: p.get('cluster') || '' };
	}

	function setParams(p) {
		var q = new URLSearchParams();
		q.set('r', p.r); q.set('days', p.days);
		if (p.sample) q.set('sample', 'include');
		if (p.cluster) q.set('cluster', p.cluster);
		history.replaceState(null, '', 'report.html?' + q.toString());
	}

	function scope(p) {
		return '&days=' + encodeURIComponent(p.days) + (p.sample ? '&sample=include' : '')
			+ (p.cluster ? '&cluster=' + encodeURIComponent(p.cluster) : '');
	}

	function renderDetail() {
		var p = params();
		var fam = FAMILIES[p.r];
		var host = document.getElementById('sections');
		if (!fam) { C.message(host, 'error', 'No report named "' + p.r + '".'); return; }
		document.title = fam.title + ' · BLADE Dashboard';
		document.getElementById('r-title').textContent = fam.title;
		document.getElementById('r-lede').textContent = fam.lede;

		var days = document.getElementById('f-days'), sample = document.getElementById('f-sample'),
			cluster = document.getElementById('f-cluster'), refresh = document.getElementById('f-refresh'),
			status = document.getElementById('f-asof');
		days.value = p.days; sample.checked = p.sample;

		// Build the section frames once; each load fills them.
		host.textContent = '';
		var bodies = fam.sections.map(function (sec) {
			var card = document.createElement('section');
			// Tiles and tables always take the full width: a table squeezed into half
			// a row hides its last columns, which are usually the ones that matter.
			var wide = sec.wide || sec.kind === 'tiles' || sec.kind === 'table';
			card.className = 'card' + (wide ? ' card--wide' : '') + (sec.tall ? ' card--tall' : '')
				+ (sec.kind === 'tiles' ? ' card--tiles' : '') + (sec.kind === 'table' ? ' card--table' : '');
			if (sec.title) {
				var head = document.createElement('div'); head.className = 'card__head';
				var t = document.createElement('span'); t.className = 'card__title'; t.textContent = sec.title;
				head.appendChild(t);
				if (sec.sub) { var s = document.createElement('span'); s.className = 'card__sub'; s.textContent = sec.sub; head.appendChild(s); }
				card.appendChild(head);
			}
			var body = document.createElement('div'); body.className = sec.kind === 'tiles' ? 'tiles' : 'card__body';
			card.appendChild(body); host.appendChild(card);
			return body;
		});

		var last = null;
		function draw() {
			if (!last) return;
			fam.sections.forEach(function (sec, i) { renderSection(bodies[i], sec, last[sec.r], +p.days); });
		}

		function load() {
			p.days = days.value; p.sample = sample.checked; p.cluster = cluster.value;
			setParams(p);
			status.textContent = 'querying…';
			var names = [];
			fam.sections.forEach(function (sec) {
				if (names.indexOf(sec.r) < 0 && !(sec.maxDays && +p.days > sec.maxDays)) names.push(sec.r);
			});
			return getJson('data?fresh=1&r=' + names.join(',') + scope(p)).then(function (d) {
				last = d; draw();
				var t = 0; names.forEach(function (n) { if (d[n] && d[n].asOf > t) t = d[n].asOf; });
				status.textContent = 'live · as of ' + asOf(t);
			}).catch(function (e) { if (e.message !== 'auth') status.textContent = 'query failed'; });
		}

		// The cluster list comes from the data, in the current sample scope.
		function loadClusters() {
			return getJson('data?r=calls.clusters&days=' + encodeURIComponent(days.value) + (sample.checked ? '&sample=include' : ''))
				.then(function (d) {
					var res = d['calls.clusters'], keep = p.cluster;
					cluster.textContent = '';
					var all = document.createElement('option'); all.value = ''; all.textContent = 'all clusters'; cluster.appendChild(all);
					((res && res.rows) || []).forEach(function (r) {
						var o = document.createElement('option'); o.value = r[0]; o.textContent = r[0] + ' (' + C.fmt(r[1]) + ')';
						cluster.appendChild(o);
					});
					cluster.value = keep;
				}).catch(function () {});
		}

		days.addEventListener('change', function () { loadClusters(); load(); });
		sample.addEventListener('change', function () { loadClusters(); load(); });
		cluster.addEventListener('change', load);
		refresh.addEventListener('click', load);
		var rz;
		window.addEventListener('resize', function () { clearTimeout(rz); rz = setTimeout(draw, 200); });
		loadClusters().then(load);
	}

	global.BladeReports = { FAMILIES: FAMILIES, ORDER: ORDER, getJson: getJson, renderCards: renderCards, renderDetail: renderDetail };

	// report.html has a sections container; the overview does not.
	if (document.getElementById('sections')) renderDetail();
})(window);
