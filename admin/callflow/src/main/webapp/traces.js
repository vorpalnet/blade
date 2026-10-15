/*
 * BLADE Trace — trace timeline client.
 *
 *   GET  api/traces          → every node's buffered steps, per app
 *   GET  api/traces/rules    → armed rules across the domain
 *   POST api/traces/arm      → arm a rule on every app (query params)
 *   POST api/traces/disarm   → drop all rules
 *   POST api/traces/clear    → empty the step buffers
 *   GET  api/source?app&className → source, reused for the line view
 *
 * Steps arrive tagged with app + server; this page merges them into per-call
 * timelines by X-Vorpal-Session (sorted by epochMillis, then per-app order) —
 * the "which app in the chain misbehaved" view. Flight-recorder model: arm,
 * reproduce, read the recording.
 *
 * Snapshot mode: "Save Snapshot" packages the current captures — trace data,
 * the source file for every step, this script, and the stylesheets — into ONE
 * self-contained .html anyone can open without OCCAS, login, or network. The
 * exported page runs this same script with window.BLADE_TRACE_SNAPSHOT set
 * (data embedded in a <script type="application/json"> block) and no arming
 * controls, so every element lookup below is guarded. "Load Snapshot" reads a
 * saved file back into the live viewer; Refresh returns to live data.
 *
 * The data model (aggregate) and ladder geometry (buildLadder/ladderSvg) live
 * in trace-model.js — shared with the printable report.html — and must be
 * loaded before this script (snapshots bundle it the same way).
 */
(function () {
	'use strict';

	var API = 'api';

	var els = {
		status: document.getElementById('status'),
		calls: document.getElementById('tr-calls'),
		timeline: document.getElementById('tr-timeline'),
		ladder: document.getElementById('tr-ladder'),
		codePanel: document.getElementById('tr-code-panel'),
		tabSeq: document.getElementById('tr-tab-seq'),
		tabMsg: document.getElementById('tr-tab-msg'),
		tabSrc: document.getElementById('tr-tab-src'),
		title: document.getElementById('tr-title'),
		stepInfo: document.getElementById('tr-step-info'),
		sourcePath: document.getElementById('tr-source-path'),
		message: document.getElementById('tr-message'),
		code: document.getElementById('tr-code'),
		rules: document.getElementById('tr-rules'),
		armForm: document.getElementById('tr-arm-form'),
		attribute: document.getElementById('tr-attribute'),
		attributeOther: document.getElementById('tr-attribute-other'),
		pattern: document.getElementById('tr-pattern'),
		max: document.getElementById('tr-max'),
		label: document.getElementById('tr-label'),
		disarm: document.getElementById('tr-disarm'),
		clear: document.getElementById('tr-clear'),
		refresh: document.getElementById('tr-refresh'),
		auto: document.getElementById('tr-auto'),
		save: document.getElementById('tr-save'),
		load: document.getElementById('tr-load'),
		file: document.getElementById('tr-file'),
		report: document.getElementById('tr-report'),
		recorded: document.getElementById('tr-recorded'),
		like: document.getElementById('tr-like'),
		around: document.getElementById('tr-around'),
		copy: document.getElementById('tr-copy')
	};

	var snapshot = window.BLADE_TRACE_SNAPSHOT || null;
	var snapshotName = snapshot && snapshot.exportedAt
		? 'exported ' + new Date(snapshot.exportedAt).toLocaleString() : null;

	var calls = [];            // [{sessionId, steps:[...], firstMillis, apps:[...]}]
	var lastSources = null;    // raw per-app sources behind `calls` (for Save)
	var selectedCall = null;   // sessionId
	var selectedStep = null;   // index into the selected call's steps
	var ladderCall = null;     // call currently drawn in the ladder (delegated clicks)
	var activeTab = 'seq';     // which detail panel shows: seq | msg | src
	var sourceCache = {};      // "app|className" → source text
	var autoTimer = null;

	// Recorded mode reads calls the framework's flight recorder wrote out
	// (api/traces/recorded) instead of the live armed buffers. A link such as
	// the dashboard's opens it on one call: traces.html?call=<Vorpal-ID>.
	var params = new URLSearchParams(location.search);
	var linkedCall = params.get('call');
	// ?since=<epoch ms>: only calls recorded since then, the range a link was
	// looking at.
	var since = /^\d+$/.test(params.get('since') || '') ? params.get('since') : null;
	var recordedMode = !snapshot && (!!linkedCall || params.get('recorded') === '1');
	var verdicts = {};         // sessionId → [{app, verdict}] for recorded calls

	// What a recorded call's verdict says, in words. The finding names are the
	// framework's (CallLadder.Verdict); one sentence each.
	function verdictText(app, v) {
		if (!v) { return ''; }
		var peer = v.peer || 'the far end', m = v.method || 'request', st = v.status ? v.status + ' ' : '';
		switch (v.finding) {
		case 'silent': return peer + ' never answered the ' + m;
		case 'timeout': return peer + ' never answered the ' + m + '; the container gave up (' + st.trim() + ')';
		case 'forgot': return peer + ' no longer knew the call: ' + st + 'to the ' + m;
		case 'error': return peer + ' failed: ' + st + 'to the ' + m;
		case 'credentials': return peer + ' refused the credentials: ' + st + 'to the ' + m;
		case 'unanswered': return app + ' never answered the ' + m + ' from ' + peer;
		case 'unrelayed': return app + ' never passed on the BYE from ' + peer;
		default: return v.dropped ? 'the call expired dropped' : '';
		}
	}

	// One line per application that recorded the call: whose fault, then what.
	function verdictsOf(sessionId) {
		return (verdicts[sessionId] || []).map(function (x) {
			var v = x.verdict, text = verdictText(x.app, v);
			if (!text) { return null; }
			var whose = v.fault === 'application' ? x.app : v.fault === 'downstream' ? 'DOWNSTREAM' : 'DROPPED';
			return whose + ' · ' + text + (v.dropped && v.fault ? ' · dropped' : '');
		}).filter(Boolean);
	}

	function setStatus(t) { els.status.textContent = t; }

	// ---- data ----
	// aggregate() and the other pure helpers come from trace-model.js.

	function applyData(sources, statusPrefix) {
		lastSources = sources || [];
		verdicts = {};
		lastSources.forEach(function (src) {
			if (!src.recorded || !src.steps || !src.steps.length) { return; }
			var id = src.steps[0].sessionId;
			(verdicts[id] = verdicts[id] || []).push({ app: src.app, server: src.server, verdict: src.verdict || {} });
		});
		calls = aggregate(lastSources);
		if (linkedCall && !selectedCall) {
			var want = linkedCall.toUpperCase();
			if (calls.some(function (c) { return c.sessionId === want; })) { selectedCall = want; }
		}
		var steps = calls.reduce(function (n, c) { return n + c.steps.length; }, 0);
		setStatus((statusPrefix || '')
			+ calls.length + (calls.length === 1 ? ' call · ' : ' calls · ') + steps + ' steps');
		renderCalls();
		if (selectedCall && !calls.some(function (c) { return c.sessionId === selectedCall; })) {
			selectedCall = null;
			selectedStep = null;
		}
		renderTimeline();
		renderLadder(currentCall());
		if (recordedMode && currentCall() && selectedStep == null) { showVerdict(currentCall()); }
	}

	function currentCall() {
		return calls.find(function (c) { return c.sessionId === selectedCall; }) || null;
	}

	function refresh() {
		if (snapshot) {
			applyData(snapshot.sources, (snapshotName || 'snapshot') + ' · ');
			return;
		}
		if (els.recorded) { els.recorded.textContent = recordedMode ? 'Live Captures' : 'Recorded Calls'; }
		var url = recordedMode
			? API + '/traces/recorded' + (linkedCall ? '?call=' + encodeURIComponent(linkedCall)
				: since ? '?since=' + since : '')
			: API + '/traces';
		fetch(url, { headers: { Accept: 'application/json' } })
			.then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); })
			.then(function (data) {
				applyData(data.sources, recordedMode ? (linkedCall ? 'call ' + linkedCall.toUpperCase() : 'recorded') + ' · ' : '');
				if (recordedMode && linkedCall && !calls.length) {
					els.calls.innerHTML = '<p class="cf-muted">No recording of call ' + escapeHtml(linkedCall)
						+ '. The flight recorder writes out only calls that went wrong.</p>';
				}
			})
			.catch(function (err) {
				setStatus('Error');
				els.calls.innerHTML = '<p class="cf-muted">Could not load traces: ' + escapeHtml(err.message) + '</p>';
			});
		refreshRules();
	}

	function refreshRules() {
		fetch(API + '/traces/rules', { headers: { Accept: 'application/json' } })
			.then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); })
			.then(function (data) {
				if (snapshot) { return; } // a snapshot was loaded mid-flight; leave its note up
				// rules are armed domain-wide; any app's copy describes them.
				// captured counts differ per app/node, so show the max seen.
				var byLabel = {};
				var apps = 0;
				(data.sources || []).forEach(function (src) {
					if (src.enabled) { apps++; }
					(src.rules || []).forEach(function (r) {
						var cur = byLabel[r.label];
						if (!cur || (r.captured || 0) > cur.captured) { byLabel[r.label] = r; }
					});
				});
				var labels = Object.keys(byLabel);
				if (!labels.length) {
					els.rules.textContent = 'No rules armed.';
					return;
				}
				els.rules.textContent = labels.map(function (l) {
					var r = byLabel[l];
					var cap = r.maxCaptures > 0 ? r.captured + ' of ' + r.maxCaptures : r.captured + ' captured';
					return l + ' [' + cap + (r.exhausted ? ', done' : '') + ']';
				}).join('  ·  ') + '  —  armed on ' + apps + ' apps';
			})
			.catch(function () { /* rules strip is advisory */ });
	}

	// ---- rendering ----

	function renderCalls() {
		if (!calls.length) {
			els.calls.innerHTML = snapshot
				? '<p class="cf-muted">This snapshot holds no captured calls.</p>'
				: recordedMode
					? '<p class="cf-muted">No recorded calls. The flight recorder writes out only calls that went wrong.</p>'
					: '<p class="cf-muted">Nothing captured yet. Arm a rule, then place a matching call.</p>';
			return;
		}
		var frag = document.createDocumentFragment();
		calls.forEach(function (c) {
			var btn = document.createElement('button');
			btn.type = 'button';
			btn.className = 'cf-item' + (c.sessionId === selectedCall ? ' cf-active' : '');
			btn.innerHTML = '<span class="cf-item-title"></span><span class="cf-item-cat"></span>';
			// the X-Vorpal-Session id is the call's key — lead with it; then the
			// UNIQUE apps it touched (c.apps repeats every time the call bounces
			// back through an app, which just wastes space here)
			var uniqueApps = c.apps.filter(function (a, i) { return c.apps.indexOf(a) === i; });
			btn.querySelector('.cf-item-title').textContent =
				c.sessionId + '  ·  ' + c.steps.length + ' steps';
			var found = verdictsOf(c.sessionId);
			btn.querySelector('.cf-item-cat').textContent =
				hhmmss(c.firstMillis) + '  ·  ' + uniqueApps.join(', ') + (found.length ? '  ·  ' + found[0] : '');
			if (found.length) { btn.title = found.join('\n'); }
			btn.addEventListener('click', function () {
				selectedCall = c.sessionId;
				selectedStep = null;
				renderCalls();
				renderTimeline();
				renderLadder(c);
				setTab('seq');
				showVerdict(c);
			});
			frag.appendChild(btn);
		});
		els.calls.innerHTML = '';
		els.calls.appendChild(frag);
	}

	function renderTimeline() {
		var call = calls.find(function (c) { return c.sessionId === selectedCall; });
		if (!call) {
			els.timeline.innerHTML = '<p class="cf-muted">Pick a call above.</p>';
			return;
		}
		var frag = document.createDocumentFragment();
		call.steps.forEach(function (s, i) {
			var btn = document.createElement('button');
			btn.type = 'button';
			btn.className = 'cf-item tr-step' + (i === selectedStep ? ' cf-active' : '');
			btn.innerHTML = '<span class="cf-item-title"></span><span class="cf-item-cat"></span>';
			// direction-aware arrows: sent →, received ← (older data has no
			// direction — fall back to the original kind convention)
			var arrow = s.direction ? (s.direction === 'in' ? '←' : '→')
				: (s.kind === 'request' ? '→' : '←');
			btn.querySelector('.cf-item-title').textContent =
				'#' + (i + 1) + '  ' + s.app + '  ' + arrow + ' ' + s.label;
			// an in-step names its handler method when one was resolved
			// (process / processContinue / …); a receive nobody handled (a
			// fire-and-forget request's response) honestly names the servlet.
			btn.querySelector('.cf-item-cat').textContent = !s.className
				? (s.direction === 'in' ? 'from ' : 'to ') + (s.peer || 'the network') + '  ·  ' + hhmmss(s.epochMillis)
				: (s.direction === 'in'
					? (isHandlerMethod(s.methodName)
						? simpleName(s.className) + '.' + s.methodName + '  ·  received'
						: 'received by ' + simpleName(s.className))
					: simpleName(s.className) + '.' + s.methodName + (s.line > 0 ? ':' + s.line : ''))
				+ '  ·  ' + hhmmss(s.epochMillis);
			btn.addEventListener('click', function () { selectStep(call, i); });
			frag.appendChild(btn);
		});
		var end = document.createElement('div');
		end.className = 'tr-end';
		end.textContent = '— end of recorded steps —';
		frag.appendChild(end);
		els.timeline.innerHTML = '';
		els.timeline.appendChild(frag);
	}

	// Show one of the three detail panels; the ladder, message and source live in
	// tabs so they don't stack and push each other off-screen.
	function setTab(name) {
		activeTab = name;
		if (els.ladder) { els.ladder.hidden = name !== 'seq'; }
		if (els.message) { els.message.hidden = name !== 'msg'; }
		if (els.codePanel) { els.codePanel.hidden = name !== 'src'; }
		[['seq', els.tabSeq], ['msg', els.tabMsg], ['src', els.tabSrc]].forEach(function (t) {
			if (t[1]) { t[1].className = 'tr-tab' + (t[0] === name ? ' cf-active' : ''); }
		});
	}

	// Select a step from either side — the timeline row or a ladder arrow. Keeps
	// the two views, the source pane, and the SIP message all pointed at the same
	// step so clicking one highlights the other. The active tab is left alone —
	// selecting a step updates all three panels' content but never switches tabs.
	function selectStep(call, i) {
		selectedStep = i;
		renderTimeline();
		renderLadder(call);
		showStep(call, call.steps[i], i);
	}

	// ---- sequence (ladder) diagram ----
	// Geometry lives in trace-model.js (buildLadder/ladderSvg); this wrapper
	// only owns the page state — which call is drawn and which step is lit.

	function renderLadder(call) {
		if (!els.ladder) { return; }
		ladderCall = call || null;
		if (!call || !call.steps.length) {
			els.ladder.innerHTML = '<p class="cf-muted">Pick a call to see its sequence diagram.</p>';
			return;
		}
		els.ladder.innerHTML = ladderSvg(call, selectedStep == null ? -1 : selectedStep);

		var onEl = els.ladder.querySelector ? els.ladder.querySelector('.tr-larrow-on') : null;
		if (onEl && onEl.scrollIntoView) { onEl.scrollIntoView({ block: 'nearest' }); }
	}

	// A recorded call opens on what went wrong; the sequence diagram is below it.
	function showVerdict(call) {
		var found = verdictsOf(call.sessionId);
		if (els.like) { els.like.hidden = !origin(call); }
		if (els.copy) { els.copy.hidden = !found.length; }
		if (!found.length) {
			if (els.around) { els.around.hidden = true; }
			aroundFor = null;
			return;
		}
		els.title.textContent = 'Call ' + call.sessionId;
		els.stepInfo.textContent = found.join('  ·  ');
		els.sourcePath.hidden = true;
		showAround(call);
	}

	// ---- the call as text ----
	// What a person pastes into a ticket or sends a carrier: the verdict, each
	// step with its time and far end, and what surrounded the call. Plain
	// text, one fact a line, readable without the page.
	function callText(call) {
		var lines = ['Call ' + call.sessionId + (call.steps.length ? ', ' + new Date(call.firstMillis).toISOString() : '')];
		verdictsOf(call.sessionId).forEach(function (v) { lines.push(v); });
		lines.push('');
		call.steps.forEach(function (s, i) {
			lines.push('#' + (i + 1) + '  ' + hhmmss(s.epochMillis) + '  ' + s.app + (s.direction === 'in' ? ' <- ' : ' -> ')
				+ s.label + (s.direction === 'in' ? ' from ' : ' to ') + (s.peer || 'the network'));
		});
		if (els.around && !els.around.hidden) {
			lines.push('');
			lines.push(els.around.innerText.replace(/ ↗/g, '').replace(/\n{2,}/g, '\n').trim());
		}
		return lines.join('\n') + '\n';
	}

	if (els.copy) {
		els.copy.addEventListener('click', function () {
			var call = currentCall();
			if (!call) { return; }
			var text = callText(call);
			var done = function () { setStatus('copied call ' + call.sessionId + ' as text'); };
			if (navigator.clipboard && navigator.clipboard.writeText) {
				navigator.clipboard.writeText(text).then(done, function () { window.prompt('Copy the call:', text); });
			} else {
				window.prompt('Copy the call:', text);
			}
		});
	}

	// ---- around a recorded call ----
	// What surrounded the call, read from the dashboard's reports (same site,
	// same login): the server that minute, how many calls went wrong the same
	// way, the changes in the hour before, and the log. Without the dashboard
	// only the log link shows. Fetched once per call, not on every refresh.

	var aroundFor = null;
	var BOARD = '/blade/dashboard/';
	var ZONE = (function () {
		try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; }
	})();

	function report(name, query) {
		return fetch(BOARD + 'data?r=' + name + '&tz=' + encodeURIComponent(ZONE) + query, { credentials: 'same-origin' })
			.then(function (r) { return r.ok ? r.json() : null; })
			.then(function (j) { var t = j && j[name]; return t && t.rows ? t : null; })
			.catch(function () { return null; });
	}

	function hhmm(millis) { return hhmmss(millis).slice(0, 5); }

	function el(tag, text, cls) {
		var e = document.createElement(tag);
		if (text != null) { e.textContent = text; }
		if (cls) { e.className = cls; }
		return e;
	}

	function link(text, href) {
		var a = el('a', text + ' ↗');
		a.href = href;
		a.target = '_blank';
		a.rel = 'noopener';
		return a;
	}

	function boardLink(text, from, until, extra) {
		return link(text, BOARD + 'index.html#range=custom&from=' + from + '&until=' + until + (extra || ''));
	}

	// A report's row by column name.
	function cell(t, row, name) { return row[t.columns.indexOf(name)]; }

	// calls.causes names a cause by its columns; the row for this verdict is
	// found by them, so the dashboard's key is used as it built it.
	function causeRow(t, rec) {
		var v = rec.verdict;
		function norm(x) { return x == null || x === '(none)' ? '' : String(x); }
		var who = v.fault === 'downstream' ? v.peer : rec.app + ' on ' + rec.server;
		return t.rows.find(function (r) {
			return norm(cell(t, r, 'fault')) === norm(v.fault || 'dropped') && norm(cell(t, r, 'finding')) === norm(v.finding)
				&& norm(cell(t, r, 'method')) === norm(v.method) && norm(cell(t, r, 'status')) === norm(v.status)
				&& norm(cell(t, r, 'who')) === norm(who);
		}) || null;
	}

	// One change in words, as the dashboard's Changes tab says it (board.js
	// changeDays): keep the two alike.
	function changeText(t, x) {
		var change = cell(t, x, 'change'), server = cell(t, x, 'server'), what = cell(t, x, 'what');
		var file = cell(t, x, 'file'), failed = cell(t, x, 'failed on');
		failed = failed && failed !== '[]' ? String(failed).replace(/[\[\]"]/g, '') : '';
		switch (change) {
		case 'server restarted': return server + ' restarted';
		case 'application restarted': return what + ' restarted on ' + server;
		case 'config pushed': return file + ' pushed' + (failed ? ', FAILED on ' + failed : '');
		default: return file + ' ' + String(what).split(' · ').reverse().join(' by ');
		}
	}

	function showAround(call) {
		var box = els.around;
		if (!box) { return; }
		var recs = verdicts[call.sessionId] || [];
		if (!recs.length) { box.hidden = true; aroundFor = null; return; }
		if (aroundFor === call.sessionId) { box.hidden = false; return; }
		aroundFor = call.sessionId;
		var rec = recs[0], server = rec.server, t = call.firstMillis;
		var end = call.steps.length ? call.steps[call.steps.length - 1].epochMillis : t;
		box.innerHTML = '';
		box.appendChild(el('h2', 'Around this call', 'tr-around-title'));
		var list = el('dl', null, 'tr-around-list');
		box.appendChild(list);
		box.hidden = false;
		function row(term) {
			var dd = el('dd');
			list.appendChild(el('dt', term));
			list.appendChild(dd);
			return dd;
		}
		var minute = server ? row(server + ' that minute') : null;
		var same = row('The same cause');
		var changes = row('Changes in the hour before');
		var logs = row('Its log');
		if (server) {
			logs.appendChild(link(rec.app + ' on ' + server + ' at ' + hhmmss(t) + ', searched for ' + call.sessionId,
				'/blade/logs/logs.html?server=' + encodeURIComponent(server) + '&app=' + encodeURIComponent(rec.app)
				+ '&at=' + t + '&q=' + encodeURIComponent(call.sessionId)));
		} else {
			logs.textContent = 'the record names no server';
		}
		[minute, same, changes].forEach(function (dd) { if (dd) { dd.textContent = 'reading…'; } });

		var half = 12 * 3600000, from = t - half, until = Math.min(Date.now(), t + half);
		var reads = [
			server ? report('platform.servers', '&server=' + encodeURIComponent(server) + '&from=' + (t - 120000)
				+ '&until=' + (end + 60000)) : Promise.resolve(null),
			report('calls.causes', '&from=' + from + '&until=' + until),
			report('changes', '&from=' + (t - 3600000) + '&until=' + t)
		];
		Promise.all(reads).then(function (r) {
			if (aroundFor !== call.sessionId) { return; }
			if (!r[0] && !r[1] && !r[2]) {
				[minute, same, changes].forEach(function (dd) { if (dd) { dd.textContent = 'the dashboard is not reachable'; } });
				return;
			}
			if (minute) {
				var srv = r[0] && r[0].rows[0];
				minute.textContent = '';
				if (!srv) {
					minute.textContent = 'no sample that minute';
				} else {
					var p = r[0];
					minute.appendChild(document.createTextNode([
						cell(p, srv, 'state'),
						'heap at most ' + cell(p, srv, 'peak heap %') + '%',
						'CPU at most ' + cell(p, srv, 'peak cpu %') + '%',
						cell(p, srv, 'most stuck threads') + ' stuck threads',
						cell(p, srv, 'refused') + ' requests refused'
					].join(' · ') + '  '));
					minute.appendChild(boardLink('on the board', t - 1800000, Math.min(Date.now(), end + 1800000),
						'&server=' + encodeURIComponent(server)));
				}
			}
			same.textContent = '';
			var c = r[1] && causeRow(r[1], rec);
			if (!c) {
				same.textContent = r[1] ? 'no other call recorded it' : 'not known';
			} else {
				var n = cell(r[1], c, 'calls'), first = cell(r[1], c, 'first'), last = cell(r[1], c, 'last');
				same.appendChild(boardLink(n === 1 ? 'only this call, in the 12 hours either side'
					: n + ' calls, recorded ' + hhmmss(first).slice(0, 8) + ' to ' + hhmmss(last).slice(0, 8), from, until,
					'&cause=' + encodeURIComponent(cell(r[1], c, 'cause'))));
			}
			changes.textContent = '';
			var ch = r[2] ? r[2].rows.filter(function (x) { return cell(r[2], x, 'at') <= t; }) : null;
			if (!ch) {
				changes.textContent = 'not known';
			} else if (!ch.length) {
				changes.textContent = 'none';
			} else {
				ch.sort(function (a, b) { return cell(r[2], b, 'at') - cell(r[2], a, 'at'); });
				var ul = el('ul', null, 'tr-around-changes');
				ch.slice(0, 5).forEach(function (x) {
					var at = cell(r[2], x, 'at');
					ul.appendChild(el('li', hhmm(at) + '  ' + changeText(r[2], x) + ', '
						+ Math.round((t - at) / 60000) + ' min before'));
				});
				changes.appendChild(document.createTextNode(ch.length + (ch.length === 1 ? ' change' : ' changes')
					+ (ch.length > 5 ? ', the newest five:' : ':')));
				changes.appendChild(ul);
			}
		});
	}

	// The address a recorded call came from: its first step is the INVITE
	// received, and a recorded step names the far end as address:port.
	function origin(call) {
		if (!recordedMode || !els.armForm || !call || !call.steps.length) { return null; }
		var first = call.steps[0];
		if (first.direction !== 'in' || !first.peer || first.peer.indexOf('leg ') === 0) { return null; }
		// A port always follows: after the last colon, for IPv4 and IPv6 alike.
		var host = first.peer.replace(/:[^:]*$/, '').replace(/^\[|\]$/g, '');
		return /^[0-9a-fA-F.:]+$/.test(host) ? host : null;
	}

	// Fill in the arm form; the person checks it and presses Arm. Nothing is
	// armed from a link or a click here.
	function prefill(attribute, pattern, label, why) {
		if (!els.armForm) { return; }
		var known = Array.prototype.some.call(els.attribute.options, function (o) { return o.value === attribute; });
		els.attribute.value = known ? attribute : '__other__';
		els.attributeOther.hidden = known;
		if (!known) { els.attributeOther.value = attribute || ''; }
		els.pattern.value = pattern || '';
		els.label.value = label || '';
		els.armForm.scrollIntoView({ block: 'nearest' });
		els.armForm.querySelector('button[type="submit"]').focus();
		setStatus(why);
	}

	function showStep(call, s, i) {
		if (!s.className) { // recorded: no SIP text, no source position
			var kind = s.kind === 'request' ? 'request' : 'response';
			els.title.textContent = 'Step #' + (i + 1) + ' — ' + kind + ' ' + s.label
				+ (s.direction === 'in' ? ' received by ' : ' sent by ') + s.app;
			els.stepInfo.textContent = hhmmss(s.epochMillis) + '  ·  ' + (s.direction === 'in' ? 'from ' : 'to ')
				+ (s.peer || 'the network') + '  ·  leg ' + s.leg + (s.server ? '  ·  recorded on ' + s.server : '');
			els.sourcePath.hidden = true;
			if (els.message) { els.message.textContent = '(the flight recorder keeps no SIP text)'; }
			if (els.code) { els.code.innerHTML = '<li class="tr-line"><span class="tr-line-text">The flight recorder keeps no source position.</span></li>'; }
			return;
		}
		var kindWord = s.kind === 'request' ? 'request' : 'response';
		var dirWord = s.direction === 'in' ? 'received by' : 'sent by';
		els.title.textContent = 'Step #' + (i + 1) + ' — ' + kindWord + ' ' + s.label + ' ' + dirWord + ' ' + s.app;
		els.stepInfo.textContent = hhmmss(s.epochMillis) + '  ·  session ' + call.sessionId
			+ (s.server ? '  ·  recorded on ' + s.server : '');
		els.sourcePath.textContent = s.direction === 'in'
			? (isHandlerMethod(s.methodName)
				? s.className + '.' + s.methodName + ' (handler)'
				: 'received by ' + s.className + ' — no app-level handler (nothing processed this message)')
			: s.className + '.' + s.methodName + (s.line > 0 ? ':' + s.line : '');
		els.sourcePath.hidden = false;
		if (els.message) {
			els.message.textContent = s.message || '(no raw SIP message was recorded for this step)';
		}

		var key = s.app + '|' + s.className;
		if (sourceCache[key] != null) {
			renderSource(sourceCache[key], markLineFor(sourceCache[key], s));
			return;
		}
		if (snapshot) {
			var embedded = snapshot.sourceFiles ? snapshot.sourceFiles[key] : null;
			els.code.innerHTML = '';
			if (embedded != null) {
				sourceCache[key] = embedded;
				renderSource(embedded, markLineFor(embedded, s));
			} else {
				var msg = document.createElement('li');
				msg.className = 'tr-line';
				msg.textContent = 'Source was not included in this snapshot.';
				els.code.appendChild(msg);
			}
			return;
		}
		els.code.innerHTML = '<li class="tr-line"><span class="tr-line-text">Loading source…</span></li>';
		fetch(API + '/source?app=' + encodeURIComponent(s.app)
				+ '&className=' + encodeURIComponent(s.className))
			.then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.text(); })
			.then(function (src) {
				sourceCache[key] = src;
				renderSource(src, markLineFor(src, s));
			})
			.catch(function (err) {
				els.code.innerHTML = '';
				var li = document.createElement('li');
				li.className = 'tr-line';
				li.textContent = 'Could not load source: ' + err.message;
				els.code.appendChild(li);
			});
	}

	// A step without a line pin (a received message) still names the handling
	// method (e.g. "process") — find its declaration in the source so the
	// handler is highlighted anyway. Declaration-looking lines win; the
	// synthetic hints never match a real method.
	function markLineFor(src, s) {
		if (s.line > 0) { return s.line; }
		var m = s.methodName;
		if (!isHandlerMethod(m)) { return -1; }
		var lines = String(src).split('\n');
		var re = new RegExp('\\b' + m.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\(');
		var candidate = -1;
		for (var i = 0; i < lines.length; i++) {
			if (re.test(lines[i])) {
				if (/\b(public|protected|private)\b/.test(lines[i])) { return i + 1; }
				if (candidate < 0) { candidate = i + 1; }
			}
		}
		return candidate;
	}

	function renderSource(src, markLine) {
		var lines = String(src).split('\n');
		var frag = document.createDocumentFragment();
		var markEl = null;
		lines.forEach(function (text, idx) {
			var n = idx + 1;
			var li = document.createElement('li');
			li.className = 'tr-line' + (n === markLine ? ' tr-mark' : '');
			var gutter = document.createElement('span');
			gutter.className = 'tr-gutter';
			gutter.textContent = (n === markLine ? '▶ ' : '') + n;
			var code = document.createElement('span');
			code.className = 'tr-line-text';
			code.textContent = text;
			li.appendChild(gutter);
			li.appendChild(code);
			if (n === markLine) { markEl = li; }
			frag.appendChild(li);
		});
		els.code.innerHTML = '';
		els.code.appendChild(frag);
		if (markEl) { markEl.scrollIntoView({ block: 'center' }); }
	}

	// ---- snapshot save/load ----

	function fetchText(url) {
		return fetch(url).then(function (r) {
			if (!r.ok) { throw new Error(url + ': HTTP ' + r.status); }
			return r.text();
		});
	}

	function fetchDataUrl(url) {
		return fetch(url)
			.then(function (r) {
				if (!r.ok) { throw new Error(url + ': HTTP ' + r.status); }
				return r.blob();
			})
			.then(function (blob) {
				return new Promise(function (resolve, reject) {
					var fr = new FileReader();
					fr.onload = function () { resolve(fr.result); };
					fr.onerror = function () { reject(new Error(url + ': read failed')); };
					fr.readAsDataURL(blob);
				});
			});
	}

	// One source file per unique app|className across every captured step, so
	// the exported timeline keeps its marked-line code view. A class whose
	// source the gallery can't serve is skipped, not fatal.
	function fetchSources() {
		if (snapshot && snapshot.sourceFiles) { return Promise.resolve(snapshot.sourceFiles); }
		var wanted = {};
		calls.forEach(function (c) {
			c.steps.forEach(function (s) {
				if (s.className) { wanted[s.app + '|' + s.className] = s; }
			});
		});
		var keys = Object.keys(wanted);
		return Promise.all(keys.map(function (key) {
			if (sourceCache[key] != null) { return Promise.resolve(sourceCache[key]); }
			var s = wanted[key];
			return fetchText(API + '/source?app=' + encodeURIComponent(s.app)
					+ '&className=' + encodeURIComponent(s.className))
				.catch(function () { return null; });
		})).then(function (texts) {
			var files = {};
			keys.forEach(function (key, i) {
				if (texts[i] != null) { files[key] = texts[i]; }
			});
			return files;
		});
	}

	function saveSnapshot() {
		if (!calls.length) {
			alert('Nothing to save — capture some calls first.');
			return;
		}
		setStatus('Building snapshot…');
		Promise.all([
			fetchText('trace-model.js').then(function (model) {
				return fetchText('traces.js').then(function (js) {
					// model first — traces.js expects its globals at parse time
					return model + '\n' + js;
				});
			}),
			fetchText('callflow.css'),
			fetchText('/blade/portal/brand/brand.css'),
			fetchDataUrl('/blade/portal/brand/vorpal_splotch.svg').catch(function () { return null; }),
			fetchSources()
		]).then(function (parts) {
			download(buildSnapshotHtml({
				js: parts[0], appCss: parts[1], brandCss: parts[2],
				mark: parts[3], sourceFiles: parts[4]
			}));
			refresh();
		}).catch(function (err) {
			setStatus('Error');
			alert('Snapshot failed: ' + err.message);
		});
	}

	function buildSnapshotHtml(parts) {
		// never spell the closing script tag literally — this file gets
		// inlined into the snapshot's own <script> block
		var SCRIPT_END = '<' + '/script>';
		var now = new Date();
		var data = {
			kind: 'blade-trace-snapshot',
			version: 1,
			exportedAt: now.getTime(),
			host: location.host,
			sources: lastSources,
			sourceFiles: parts.sourceFiles
		};
		// `</` → `<\/` (a legal JSON escape) so embedded source text can never
		// terminate the data block early
		var json = JSON.stringify(data).replace(/<\//g, '<\\/');
		var brandCss = parts.brandCss;
		// The snapshot keeps the theme it was saved in; it carries no switch.
		var theme = document.documentElement.getAttribute('data-theme') || 'light';
		var when = now.toLocaleString();
		return [
			'<!DOCTYPE html>',
			'<html lang="en" data-theme="' + theme + '">',
			'<head>',
			'<meta charset="UTF-8">',
			'<meta name="viewport" content="width=device-width, initial-scale=1.0">',
			'<title>BLADE Trace Snapshot · ' + escapeHtml(when) + '</title>',
			'<style>' + brandCss + '</style>',
			'<style>' + parts.appCss + '</style>',
			'</head>',
			'<body class="vorpal-glass tr-app">',
			'<div class="vorpal-app">',
			'<header class="vorpal-topbar">',
			'<span class="vorpal-brand">'
				+ (parts.mark ? '<img class="vorpal-brand-mark" src="' + parts.mark + '" alt="Vorpal">' : '')
				+ '</span>',
			'<span class="vorpal-appmark">Trace Snapshot</span>',
			'<div class="vorpal-topbar-right"><span>exported ' + escapeHtml(when)
				+ (data.host ? ' from ' + escapeHtml(data.host) : '') + '</span>'
				+ '<span id="status" class="vorpal-pill">Loading…</span></div>',
			'</header>',
			'<main class="vorpal-main">',
			'<div class="tr-page">',
			'<div class="tr-layout">',
			'<aside class="tr-left" aria-label="Captured calls">',
			'<div class="cf-group-label">Captured calls</div>',
			'<div id="tr-calls"></div>',
			'<div class="cf-group-label">Timeline</div>',
			'<div id="tr-timeline"><p class="cf-muted">Pick a call above.</p></div>',
			'</aside>',
			'<article class="cf-detail">',
			'<div class="cf-detail-head">',
			'<h1 id="tr-title">Call Traces</h1>',
			'<p id="tr-step-info" class="cf-blurb">Each row on the left is one message an app sent or received. Pick a call to see its sequence diagram; pick a step to see the raw SIP message and, for sent steps, the source line that emitted it.</p>',
			'<div id="tr-source-path" class="cf-source-path" hidden></div>',
			'</div>',
			'<div class="tr-tabs" role="tablist" aria-label="Detail views">',
			'<button type="button" class="tr-tab cf-active" id="tr-tab-seq" data-tab="seq" role="tab">Sequence</button>',
			'<button type="button" class="tr-tab" id="tr-tab-msg" data-tab="msg" role="tab">Message</button>',
			'<button type="button" class="tr-tab" id="tr-tab-src" data-tab="src" role="tab">Source</button>',
			'</div>',
			'<div class="tr-panels">',
			'<section id="tr-ladder" class="tr-ladder tr-panel" data-tab="seq" aria-label="Sequence diagram"></section>',
			'<pre id="tr-message" class="tr-sipmsg tr-panel" data-tab="msg" hidden></pre>',
			'<pre id="tr-code-panel" class="cf-code tr-panel" data-tab="src" hidden><ol id="tr-code" class="tr-lines"></ol></pre>',
			'</div>',
			'</article>',
			'</div>',
			'</div>',
			'</main>',
			'</div>',
			'<script id="blade-trace-data" type="application/json">' + json + SCRIPT_END,
			'<script>window.BLADE_TRACE_SNAPSHOT=JSON.parse(document.getElementById(\'blade-trace-data\').textContent);' + SCRIPT_END,
			'<script>' + parts.js + SCRIPT_END,
			'</body>',
			'</html>'
		].join('\n');
	}

	function download(html) {
		var d = new Date();
		var name = 'blade-traces-' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate())
			+ '-' + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds()) + '.html';
		var blob = new Blob([html], { type: 'text/html' });
		var a = document.createElement('a');
		a.href = URL.createObjectURL(blob);
		a.download = name;
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
		setTimeout(function () { URL.revokeObjectURL(a.href); }, 5000);
	}

	function loadSnapshotFile(file) {
		var fr = new FileReader();
		fr.onload = function () {
			try {
				var text = String(fr.result);
				var parsed;
				if (text.charAt(0) === '{') {
					parsed = JSON.parse(text); // raw snapshot (or api/traces payload)
				} else {
					var m = text.match(/<script[^>]*id="blade-trace-data"[^>]*>([\s\S]*?)<\/script>/);
					if (!m) { throw new Error('no embedded trace data found'); }
					parsed = JSON.parse(m[1]);
				}
				if (!parsed.sources) { throw new Error('not a trace snapshot'); }
				snapshot = parsed;
				snapshotName = file.name;
				selectedCall = null;
				selectedStep = null;
				sourceCache = {};
				if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
				if (els.auto) { els.auto.checked = false; }
				if (els.rules) { els.rules.textContent = 'Viewing a loaded snapshot — Refresh returns to live.'; }
				refresh();
			} catch (err) {
				alert('Could not load snapshot: ' + err.message);
			}
		};
		fr.readAsText(file);
	}

	// ---- arming controls (absent in an exported snapshot page) ----

	if (els.armForm) {
		els.attribute.addEventListener('change', function () {
			els.attributeOther.hidden = els.attribute.value !== '__other__';
		});

		els.armForm.addEventListener('submit', function (ev) {
			ev.preventDefault();
			var attribute = els.attribute.value === '__other__'
				? els.attributeOther.value.trim() : els.attribute.value;
			if (!attribute || !els.pattern.value.trim()) { return; }
			post('/traces/arm?attribute=' + encodeURIComponent(attribute)
				+ '&pattern=' + encodeURIComponent(els.pattern.value.trim())
				+ '&maxCaptures=' + encodeURIComponent(els.max.value || '5')
				+ (els.label.value.trim() ? '&label=' + encodeURIComponent(els.label.value.trim()) : ''));
		});

		els.disarm.addEventListener('click', function () { post('/traces/disarm'); });
		els.clear.addEventListener('click', function () {
			selectedCall = null;
			selectedStep = null;
			post('/traces/clear');
		});
		if (els.like) {
			els.like.addEventListener('click', function () {
				var call = calls.filter(function (c) { return c.sessionId === selectedCall; })[0];
				var host = origin(call);
				if (!host) { return; }
				prefill('Origin-IP', '^' + host.replace(/[.]/g, '\\.') + '$', 'like call ' + call.sessionId,
					'Filled in: the next calls from ' + host + '. Check the rule, then press Arm.');
			});
		}
		// ?arm=1&attribute=&pattern=&label= from a link: filled in, never armed.
		if (params.get('arm') === '1' && !snapshot) {
			prefill(params.get('attribute') || 'From', params.get('pattern'), params.get('label'),
				'Filled in from a link. Check the rule, then press Arm.');
		}
		if (els.recorded) {
			els.recorded.addEventListener('click', function () {
				recordedMode = !recordedMode;
				linkedCall = null;
				if (els.like) { els.like.hidden = true; }
				if (els.copy) { els.copy.hidden = true; }
				selectedCall = null;
				selectedStep = null;
				refresh();
			});
		}
		els.refresh.addEventListener('click', function () {
			if (snapshot) { // leave snapshot view, back to live
				snapshot = null;
				snapshotName = null;
				sourceCache = {};
			}
			refresh();
		});

		els.auto.addEventListener('change', function () {
			if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
			if (els.auto.checked) { autoTimer = setInterval(refresh, 5000); }
		});

		els.save.addEventListener('click', saveSnapshot);
		els.report.addEventListener('click', function () {
			if (snapshot) { alert('The report prints live captures — Refresh back to live data first.'); return; }
			if (!selectedCall) { alert('Pick a call first.'); return; }
			window.open('report.html?session=' + encodeURIComponent(selectedCall), '_blank');
		});
		els.load.addEventListener('click', function () { els.file.click(); });
		els.file.addEventListener('change', function () {
			if (els.file.files && els.file.files[0]) {
				loadSnapshotFile(els.file.files[0]);
				els.file.value = ''; // same file re-loadable later
			}
		});
	}

	// Clicking a ladder arrow selects its step (the reverse of the timeline →
	// ladder link). Delegated so it survives every re-render of the SVG.
	if (els.ladder) {
		els.ladder.addEventListener('click', function (ev) {
			var t = ev.target;
			var g = t && t.closest ? t.closest('[data-step]') : null;
			if (!g || !ladderCall) { return; }
			var i = parseInt(g.getAttribute('data-step'), 10);
			if (!isNaN(i) && ladderCall.steps[i]) { selectStep(ladderCall, i); }
		});
	}

	// Detail-view tabs.
	[['seq', els.tabSeq], ['msg', els.tabMsg], ['src', els.tabSrc]].forEach(function (t) {
		if (t[1]) { t[1].addEventListener('click', function () { setTab(t[0]); }); }
	});

	function post(path) {
		fetch(API + path, { method: 'POST' })
			.then(function (r) {
				if (!r.ok) { return r.text().then(function (t) { throw new Error(t || ('HTTP ' + r.status)); }); }
				return r.json();
			})
			.then(refresh)
			.catch(function (err) { setStatus('Error'); alert(err.message); });
	}

	setTab('seq');
	refresh();
})();
