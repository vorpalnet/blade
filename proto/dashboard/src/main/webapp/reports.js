/* BLADE Dashboard report registry: the sections of each report family's
   detail page (report.html?r=<family>), queried live. The board (index.html,
   board.js) links each panel to one of these. Adding a report is one entry
   here plus its query in AnalyticsReports.java. */
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

	/* rows of [label, a, b, c] → one series per numeric column, for multiLine. */
	function seriesOf(result, names) {
		return names.map(function (name) {
			var i = result.columns.indexOf(name);
			return { name: name, values: result.rows.map(function (r) { return [r[0], r[i]]; }) };
		});
	}

	var FAMILIES = {
		health: {
			title: 'Data health',
			lede: 'Whether the other reports can be trusted: what each application reports about its calls and what it never sends, events the bus refused, which applications share a call, and calls recorded twice.',
			sections: [
				{ r: 'health.summary', kind: 'tiles' },
				{ r: 'health.coverage', title: 'What each application reports', sub: 'calls handled, and the lifecycle events sent for them', kind: 'table', wide: true },
				{ r: 'health.chains', title: 'Applications per call', sub: 'calls by the set of applications that handled them', kind: 'table' },
				{ r: 'health.publishing', title: 'What each application sent', sub: 'from its own counters and the container’s, sampled each minute · publishes failed are events analytics never received · sessions held through quiet minutes are leaks', kind: 'table', wide: true },
				{ r: 'health.publishing-daily', title: 'Events published and dropped per day', kind: 'lines', series: ['published', 'failed'], wide: true },
				{ r: 'health.split-calls', title: 'Calls recorded twice', sub: 'one Vorpal-ID, two rows under a minute apart · newest first', kind: 'table', wide: true }
			]
		},
		metrics: {
			title: 'Application metrics',
			lede: 'Every counter and timing each application keeps about itself, sampled each minute and kept past the redeploy that resets them. An application that counts its own failures is seen here with nothing else to build.',
			sections: [
				{ r: 'metrics.summary', kind: 'tiles' },
				{ r: 'metrics.counters', title: 'Counters', sub: 'by application, most counted first', kind: 'table', wide: true },
				{ r: 'metrics.timings', title: 'Timings', sub: 'mean over the range', kind: 'table', wide: true }
			]
		},
		calls: {
			title: 'Call volume',
			lede: 'How many calls, when they happen, and where they ran.',
			sections: [
				{ r: 'calls.summary', kind: 'tiles' },
				{ r: 'calls.daily', title: 'Calls per day', kind: 'bar', wide: true },
				{ r: 'calls.hourly', title: 'Calls per hour', kind: 'bar', wide: true, maxDays: 7 },
				{ r: 'calls.concurrent', title: 'Most calls up at once', sub: 'per hour', kind: 'line', name: 'peak', wide: true, maxDays: 7 },
				{ r: 'calls.heatmap', title: 'When calls happen', sub: 'weekday × hour of day', kind: 'heatmap', wide: true, tall: true },
				{ r: 'calls.hour-of-day', title: 'By hour of day', kind: 'bar' },
				{ r: 'calls.weekday', title: 'By weekday', kind: 'bar' },
				{ r: 'calls.by-application', title: 'By application', sub: 'a call through two applications counts for each', kind: 'hbar' },
				{ r: 'calls.by-engine', title: 'By engine', kind: 'hbar' },
				{ r: 'calls.by-tenant', title: 'By tenant', kind: 'hbar' },
				{ r: 'calls.by-area', title: 'Where callers are', sub: 'how their calls ended, by state; from the caller\'s area code, never the number', kind: 'areamap', wide: true, tall: true }
			]
		},
		duration: {
			title: 'Call duration',
			lede: 'How long calls last, per application. Medians, because a few long calls drag an average.',
			sections: [
				{ r: 'duration.summary', kind: 'tiles', units: 's' },
				{ r: 'duration.daily', title: 'Average seconds per day', kind: 'line', name: 'avg', wide: true },
				{ r: 'duration.bands', title: 'Calls by length', kind: 'bar' },
				{ r: 'duration.by-application', title: 'Duration by application', sub: 'seconds', kind: 'table' }
			]
		},
		outcomes: {
			title: 'Call outcomes',
			lede: 'How calls ended: completed after an answer; abandoned by the caller; declined by the callee (busy, unavailable, not found, no answer); failed, when nothing answered, a server erred or credentials were refused; or dropped, when a bug stopped relaying the signaling. Each call counts once; a call through several applications counts for each in the per-application table.',
			sections: [
				{ r: 'outcomes.summary', kind: 'tiles' },
				{ r: 'outcomes.ended', title: 'How calls ended', kind: 'hbar' },
				{ r: 'outcomes.daily', title: 'Calls per day by how they ended', kind: 'lines', series: ['completed', 'abandoned', 'declined', 'failed', 'dropped'], colors: ['--ok', '--warn', '--warn', '--bad', '--bad'], wide: true },
				{ r: 'outcomes.by-application', title: 'How each application\'s calls ended', kind: 'table' }
			]
		},
		sessions: {
			title: 'Open sessions',
			lede: 'Sessions that never closed. A session open for days is a leak, not a call; these ignore the date range.',
			sections: [
				{ r: 'sessions.summary', kind: 'tiles' },
				{ r: 'sessions.by-application', title: 'Open by application and engine', kind: 'table' },
				{ r: 'sessions.open', title: 'Every open session', sub: 'newest first', kind: 'table', wide: true }
			]
		},
		platform: {
			title: 'Platform activity',
			lede: 'Application starts and stops, the versions that handled calls, and how each server stood minute by minute.',
			sections: [
				{ r: 'platform.summary', kind: 'tiles' },
				{ r: 'platform.daily', title: 'Application starts per day', kind: 'bar', wide: true },
				{ r: 'platform.by-application', title: 'Starts and stops by application', kind: 'table' },
				{ r: 'platform.versions', title: 'Versions in service', kind: 'table' },
				{ r: 'platform.servers', title: 'Each server', sub: 'sampled each minute · refused messages, and requests routed past an undeployed application, never reached the application meant for them', kind: 'table', wide: true },
				{ r: 'platform.servers-hourly', title: 'SIP messages received and refused per hour', kind: 'lines', series: ['received', 'refused'], wide: true, maxDays: 7 }
			]
		},
		events: {
			title: 'Event activity',
			lede: 'Every fact the analytics service recorded, by type and application.',
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
			sections: [
				{ r: 'risk.summary', kind: 'tiles' },
				{ r: 'risk.daily', title: 'Calls assessed and flagged per day', kind: 'lines', series: ['calls assessed', 'calls flagged'], wide: true },
				{ r: 'risk.by-band', title: 'Assessments by band', sub: 'worst first', kind: 'bands' },
				{ r: 'risk.by-trigger', title: 'By triggering signal', kind: 'hbar' },
				{ r: 'risk.signals', title: 'What each signal contributed', sub: 'average log-odds, then raw reading, per band', kind: 'table', wide: true },
				{ r: 'risk.calibration', title: 'Was the score right?', sub: 'worst band each call reached, against the agent’s verdict · (none) = judged, never scored', kind: 'table' },
				{ r: 'risk.verdicts', title: 'Agent verdicts', sub: 'from the Agent Console', kind: 'table' }
			]
		},
		highrisk: {
			title: 'High-risk calls',
			lede: 'Calls whose risk reached WATCH or SUSPECT, worst first.',
			sections: [
				{ r: 'highrisk.by-band', title: 'Calls by worst band reached', kind: 'bands' },
				{ r: 'highrisk.calls', title: 'Calls that reached WATCH or SUSPECT', sub: 'highest score first', kind: 'table', wide: true }
			]
		},
		conversation: {
			title: 'Caller conversation',
			lede: 'What callers said to the system: utterances, intents, and whether the system thought it was being addressed.',
			sections: [
				{ r: 'conversation.summary', kind: 'tiles' },
				{ r: 'conversation.daily', title: 'Utterances per day', kind: 'bar', wide: true },
				{ r: 'conversation.by-intent', title: 'By intent', kind: 'hbar' },
				{ r: 'conversation.by-addressed', title: 'Addressed to the system?', kind: 'hbar' },
				{ r: 'conversation.intents', title: 'Intents and entities', kind: 'table', wide: true }
			]
		}
	};

	var ORDER = ['health', 'metrics', 'calls', 'duration', 'outcomes', 'sessions', 'platform', 'events', 'risk', 'highrisk', 'conversation'];

	/* ── time zone ─────────────────────────────────────────────────────
	   The data is UTC. Every page shows it in the viewer's zone: the
	   browser's own unless the viewer picked another, remembered per browser.
	   The server cuts days and hours in it (the tz parameter); the pages
	   print every clock time in it. */
	var TZ_KEY = 'blade.dashboard.tz';

	function browserZone() {
		try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (e) { return 'UTC'; }
	}

	function zone() {
		var z = null;
		try { z = localStorage.getItem(TZ_KEY); } catch (e) { /* private window */ }
		return z || browserZone();
	}

	function setZone(z) {
		try { if (z) localStorage.setItem(TZ_KEY, z); else localStorage.removeItem(TZ_KEY); } catch (e) { /* private window */ }
	}

	/* A clock time in the viewer's zone. */
	function time(ms, opts) {
		var o = { timeZone: zone() };
		Object.keys(opts || {}).forEach(function (k) { o[k] = opts[k]; });
		return new Date(ms).toLocaleTimeString([], o);
	}

	/* Fills a <select> with every zone the browser knows, the browser's own
	   first, and calls onChange after the viewer picks one. */
	function zoneSelect(select, onChange) {
		var mine = browserZone(), chosen = null;
		try { chosen = localStorage.getItem(TZ_KEY); } catch (e) { /* private window */ }
		var zones = [];
		try { zones = Intl.supportedValuesOf('timeZone'); } catch (e) { zones = ['UTC']; }
		if (zones.indexOf('UTC') < 0) zones.unshift('UTC');
		select.textContent = '';
		var own = document.createElement('option'); own.value = ''; own.textContent = mine + ' (this browser)';
		select.appendChild(own);
		zones.forEach(function (z) {
			if (z === mine) return;
			var o = document.createElement('option'); o.value = z; o.textContent = z; select.appendChild(o);
		});
		select.value = chosen && chosen !== mine ? chosen : '';
		select.addEventListener('change', function () { setZone(select.value); onChange(); });
	}

	function asOf(ms) {
		return ms ? time(ms, { hour: '2-digit', minute: '2-digit' }) : '—';
	}

	/* ── one section of a detail page ──────────────────────────────────── */

	function renderSection(body, sec, result, days) {
		if (sec.maxDays && days > sec.maxDays) {
			return C.message(body, 'empty', 'Hour by hour reads best over a week or less. Choose a shorter range.');
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
			{ colors: (sec.colors || ['--accent', '--ok', '--warn']).map(C.token) });
		case 'heatmap': return C.heatmap(body, rows);
		case 'bands': return bandList(body, rows);
		case 'table': return C.table(body, result);
		case 'areamap': return areaMap(body, result);
		default: return C.message(body, 'error', 'unknown section kind ' + sec.kind);
		}
	}

	/* ── where callers are (calls.by-area) ── */

	/* A state judged on fewer calls than this is left uncoloured: one declined
	   call is not a red state. The health thresholds come from the settings
	   (healthyPercent, unhealthyPercent) when the page loads them. */
	var AREA = { healthy: 0.9, unhealthy: 0.6, minCalls: 5 };
	var PROVINCES = /^(AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)$/;
	var usAtlas = null;

	/* Where an area from calls.by-area is: a US state the map draws, or a
	   name for the line under it. The table is the framework's vorpal/geo.js,
	   the one the agent console names callers with. */
	function placeOf(area) {
		var geo = global.GEO || { area: {}, country: {}, state: {} };
		if (!area) return { name: 'caller not recorded' };
		if (area.charAt(0) === '+') {
			for (var i = Math.min(3, area.length - 1); i > 0; i--) {
				var country = geo.country[area.substr(1, i)];
				if (country) return { name: country };
			}
			return { name: 'international' };
		}
		var city = geo.area[area];
		if (!city) return { name: 'unlisted area codes' };
		var st = city.split(', ')[1];
		if (st && geo.state[st]) return { state: geo.state[st] };
		if (st && PROVINCES.test(st)) return { name: 'Canada' };
		return { name: st === 'PR' ? 'Puerto Rico' : city };
	}

	function areaMap(body, result) {
		if (!global.topojson || !global.GEO) return C.message(body, 'error', 'Map files missing (vendor/topojson-client.min.js, vorpal/geo.js).');
		if (!usAtlas) {
			C.message(body, 'empty', 'Loading map…');
			return getJson('vendor/states-albers-10m.json').then(function (a) { usAtlas = a; areaMap(body, result); });
		}
		var byState = {}, elsewhere = {};
		result.rows.forEach(function (r) {
			var where = placeOf(r[0]), into = where.state ? byState : elsewhere, key = where.state || where.name;
			var n = into[key] = into[key] || result.columns.slice(1).map(function () { return 0; });
			for (var i = 1; i < r.length; i++) n[i - 1] += +r[i] || 0;
		});
		function total(n) { return n.reduce(function (a, b) { return a + b; }, 0); }
		var other = Object.keys(elsewhere).sort(function (a, b) { return total(elsewhere[b]) - total(elsewhere[a]); })
			.map(function (k) { return k + ' ' + C.fmt(total(elsewhere[k])); });
		C.statesMap(body, usAtlas, byState, {
			healthy: AREA.healthy, unhealthy: AREA.unhealthy, minCalls: AREA.minCalls, names: result.columns.slice(1),
			foot: other.length ? 'Not on the map: ' + other.join(' · ') : ''
		});
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

	/* ── detail page ───────────────────────────────────────────────────── */

	function params() {
		var p = new URLSearchParams(window.location.search);
		return { r: p.get('r') || 'calls', days: p.get('days') || '', sample: p.get('sample') === 'include', domain: p.get('domain') || '', account: p.get('account') || '' };
	}

	function setParams(p) {
		var q = new URLSearchParams();
		q.set('r', p.r); q.set('days', p.days);
		if (p.sample) q.set('sample', 'include');
		if (p.domain) q.set('domain', p.domain);
		if (p.account) q.set('account', p.account);
		history.replaceState(null, '', 'report.html?' + q.toString());
	}

	function scope(p) {
		return '&days=' + encodeURIComponent(p.days) + '&tz=' + encodeURIComponent(zone()) + (p.sample ? '&sample=include' : '')
			+ (p.domain ? '&domain=' + encodeURIComponent(p.domain) : '')
			+ (p.account ? '&account=' + encodeURIComponent(p.account) : '');
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
			domain = document.getElementById('f-domain'), refresh = document.getElementById('f-refresh'),
			status = document.getElementById('f-asof'), account = document.getElementById('f-account');
		sample.checked = p.sample; account.value = p.account;

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
			p.days = days.value; p.sample = sample.checked; p.domain = domain.value; p.account = account.value.trim();
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

		// The domain list comes from the data, in the current sample scope.
		function loadDomains() {
			return getJson('data?r=calls.domains&days=' + encodeURIComponent(days.value) + (sample.checked ? '&sample=include' : ''))
				.then(function (d) {
					var res = d['calls.domains'], keep = p.domain;
					domain.textContent = '';
					var all = document.createElement('option'); all.value = ''; all.textContent = 'all domains'; domain.appendChild(all);
					((res && res.rows) || []).forEach(function (r) {
						var o = document.createElement('option'); o.value = r[0]; o.textContent = r[0] + ' (' + C.fmt(r[1]) + ')';
						domain.appendChild(o);
					});
					domain.value = keep;
				}).catch(function () {});
		}

		days.addEventListener('change', function () { loadDomains(); load(); });
		sample.addEventListener('change', function () { loadDomains(); load(); });
		domain.addEventListener('change', load);
		account.addEventListener('change', load);
		zoneSelect(document.getElementById('f-tz'), load);
		refresh.addEventListener('click', load);
		var rz;
		window.addEventListener('resize', function () { clearTimeout(rz); rz = setTimeout(draw, 200); });
		getJson('settings').catch(function () { return null; }).then(function (cfg) {
			fillDays(days, cfg, p.days);
			return loadDomains();
		}).then(load);
	}

	/* The range choices: the deployment's ranges (/settings), starting on the
	   one the link asked for, else the default. A link naming a range the
	   settings no longer list still opens on it, added as a choice. */
	function fillDays(select, cfg, asked) {
		if (cfg && cfg.healthyPercent) AREA.healthy = cfg.healthyPercent / 100;
		if (cfg && cfg.unhealthyPercent != null) AREA.unhealthy = cfg.unhealthyPercent / 100;
		var ranges = ((cfg && cfg.ranges) || [1, 7, 30]).map(String);
		var start = asked || String((cfg && cfg.defaultDays) || 7);
		if (ranges.indexOf(start) < 0) {
			ranges.push(start);
			ranges.sort(function (a, b) { return a - b; });
		}
		select.textContent = '';
		ranges.forEach(function (d) {
			var o = document.createElement('option');
			o.value = d; o.textContent = d === '1' ? 'last day' : 'last ' + d + ' days';
			select.appendChild(o);
		});
		select.value = start;
	}

	global.BladeReports = { FAMILIES: FAMILIES, ORDER: ORDER, getJson: getJson, bandList: bandList, renderDetail: renderDetail,
		zone: zone, time: time, zoneSelect: zoneSelect };

	// report.html has a sections container; the overview does not.
	if (document.getElementById('sections')) renderDetail();
})(window);
