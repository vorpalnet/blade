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
		file: document.getElementById('tr-file')
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

	function setStatus(t) { els.status.textContent = t; }

	function simpleName(className) {
		var dot = (className || '').lastIndexOf('.');
		return dot >= 0 ? className.slice(dot + 1) : className;
	}

	function pad(n, w) { n = String(n); while (n.length < (w || 2)) { n = '0' + n; } return n; }

	function hhmmss(millis) {
		var d = new Date(millis);
		return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + '.' + pad(d.getMilliseconds(), 3);
	}

	// ---- data ----

	function aggregate(sources) {
		var bySession = {};
		(sources || []).forEach(function (src) {
			(src.steps || []).forEach(function (s) {
				var id = s.sessionId || '(no session id)';
				(bySession[id] = bySession[id] || []).push({
					app: src.app, server: src.server,
					epochMillis: s.epochMillis, order: s.order,
					direction: s.direction, kind: s.kind, label: s.label,
					className: s.className, methodName: s.methodName, line: s.line,
					message: s.message
				});
			});
		});
		var out = [];
		Object.keys(bySession).forEach(function (id) {
			var steps = bySession[id];
			steps.sort(function (a, b) {
				return (a.epochMillis - b.epochMillis) || (a.order - b.order);
			});
			var apps = [];
			steps.forEach(function (s) {
				if (apps[apps.length - 1] !== s.app) { apps.push(s.app); }
			});
			out.push({ sessionId: id, steps: steps, firstMillis: steps[0].epochMillis, apps: apps });
		});
		out.sort(function (a, b) { return b.firstMillis - a.firstMillis; }); // newest first
		return out;
	}

	function applyData(sources, statusPrefix) {
		lastSources = sources || [];
		calls = aggregate(lastSources);
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
	}

	function currentCall() {
		return calls.find(function (c) { return c.sessionId === selectedCall; }) || null;
	}

	function refresh() {
		if (snapshot) {
			applyData(snapshot.sources, (snapshotName || 'snapshot') + ' · ');
			return;
		}
		fetch(API + '/traces', { headers: { Accept: 'application/json' } })
			.then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); })
			.then(function (data) { applyData(data.sources); })
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
			btn.querySelector('.cf-item-cat').textContent =
				hhmmss(c.firstMillis) + '  ·  ' + uniqueApps.join(', ');
			btn.addEventListener('click', function () {
				selectedCall = c.sessionId;
				selectedStep = null;
				renderCalls();
				renderTimeline();
				renderLadder(c);
				setTab('seq');
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
			btn.querySelector('.cf-item-cat').textContent =
				(s.direction === 'in'
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

	// First matching header value from the raw SIP text (long or compact name).
	function sipHeader(message, name, compact) {
		if (!message) { return null; }
		var lines = String(message).split(/\r?\n/);
		for (var i = 0; i < lines.length; i++) {
			if (lines[i] === '') { break; } // headers end at the blank line
			var c = lines[i].indexOf(':');
			if (c < 0) { continue; }
			var h = lines[i].slice(0, c).trim().toLowerCase();
			if (h === name || (compact && h === compact)) { return lines[i].slice(c + 1).trim(); }
		}
		return null;
	}

	// User part of a From/To/Contact value, falling back to the host — the same
	// identity rule the framework's ASCII sequence diagram uses (Logger.from/to).
	function addrLabel(headerVal) {
		if (!headerVal) { return null; }
		var m = /sips?:(?:([^@>;\s]+)@)?([^@>;:\s]+)/i.exec(headerVal);
		return m ? (m[1] || m[2] || null) : null;
	}

	// Host from a topmost Via ("SIP/2.0/UDP host:port;branch=…") — last-resort peer id.
	function viaHost(via) {
		if (!via) { return null; }
		var m = /SIP\/2\.0\/\w+\s+([^\s;:]+)/i.exec(via);
		return m ? m[1] : null;
	}

	// The non-BLADE party at the far end of this step's arrow. A received request
	// comes From the caller; a sent request goes To the callee; responses reverse.
	// Falls back to Contact/Via host, then "(network)" — never invents a name.
	function farParty(s) {
		var msg = s.message;
		var val = s.kind === 'request'
			? (s.direction === 'in' ? sipHeader(msg, 'from', 'f') : sipHeader(msg, 'to', 't'))
			: (s.direction === 'in' ? sipHeader(msg, 'to', 't') : sipHeader(msg, 'from', 'f'));
		var name = addrLabel(val)
			|| addrLabel(sipHeader(msg, 'contact', 'm'))
			|| viaHost(sipHeader(msg, 'via', 'v'));
		return name || '(network)';
	}

	// Turn the ordered steps into lifelines + one arrow per message HOP.
	//
	// In a routed chain a message is logged twice — app N sends it (out) and the
	// next app receives it (in). From/To are end-to-end (they name the ultimate
	// caller/callee, not the next hop), and co-located BLADE apps share one host,
	// so SIP addressing can't identify a hop's peer — only the trace's `app` can.
	// So we CORRELATE the out with the in that received THE SAME wire message,
	// keyed by Call-ID + CSeq (a message keeps its Call-ID hop to hop; a B2BUA
	// gives each leg its own, so `test-uas → bob` — a leg no traced app receives —
	// stays an egress to bob and never mis-pairs with an unrelated INVITE some
	// other app happened to receive next). From/To are used only for the leftover
	// boundary steps — the first receive (real caller) and an out nobody received
	// (real external callee). A routed call lays out caller | app₁ | … | appₙ |
	// callee. (Method+time is the fallback only when a step carries no message.)
	function buildLadder(call) {
		var steps = call.steps;
		var pending = [];   // unmatched out steps: {i, app, label, cid, cseq}
		var hops = [];      // {steps:[…], canonical, fromP, toP, label}
		steps.forEach(function (s, i) {
			var app = s.app || '(app)';
			var cid = sipHeader(s.message, 'call-id', 'i');
			var cseq = sipHeader(s.message, 'cseq', null);
			if (s.direction === 'in') {
				var m = -1;
				for (var p = pending.length - 1; p >= 0; p--) {
					var c = pending[p];
					if (c.app === app || c.label !== s.label) { continue; }
					if (cid) { if (c.cid === cid && c.cseq === cseq) { m = p; break; } }
					else { m = p; break; } // no Call-ID (legacy) → nearest same-method out
				}
				if (m >= 0) {
					var out = pending.splice(m, 1)[0];
					hops.push({ steps: [out.i, i], canonical: out.i, fromP: out.app, toP: app, label: s.label });
				} else { // arrived from outside the chain — name the real caller
					hops.push({ steps: [i], canonical: i, fromP: farParty(s), toP: app, label: s.label });
				}
			} else {
				pending.push({ i: i, app: app, label: s.label, cid: cid, cseq: cseq });
			}
		});
		pending.forEach(function (out) { // left the chain — name the real callee
			hops.push({ steps: [out.i], canonical: out.i, fromP: out.app, toP: farParty(steps[out.i]), label: out.label });
		});
		hops.sort(function (a, b) { return Math.min.apply(null, a.steps) - Math.min.apply(null, b.steps); });

		// Lanes: the BLADE apps form the spine in the order they first appear on a
		// hop (chain order); external endpoints pin to the edges — a party that
		// first SENDS into the chain is a caller (left), one that first RECEIVES is
		// a callee (right). So a routed call reads caller | app₁ … appₙ | callee,
		// and an app reached by a side branch still lands in the app spine, never
		// wedged between the caller and the first app.
		var isApp = {};
		steps.forEach(function (s) { isApp[s.app || '(app)'] = true; });
		var spine = [], side = {};
		hops.forEach(function (h) {
			if (isApp[h.fromP] && spine.indexOf(h.fromP) < 0) { spine.push(h.fromP); }
			if (isApp[h.toP] && spine.indexOf(h.toP) < 0) { spine.push(h.toP); }
			if (!isApp[h.fromP] && side[h.fromP] === undefined) { side[h.fromP] = 'L'; }
			if (!isApp[h.toP] && side[h.toP] === undefined) { side[h.toP] = 'R'; }
		});
		var left = [], right = [];
		Object.keys(side).forEach(function (n) { (side[n] === 'L' ? left : right).push(n); });
		var lanes = left.concat(spine, right);
		var arrows = hops.map(function (h) {
			return { steps: h.steps, canonical: h.canonical, from: lanes.indexOf(h.fromP), to: lanes.indexOf(h.toP), label: h.label };
		});
		return { lanes: lanes, arrows: arrows };
	}

	function renderLadder(call) {
		if (!els.ladder) { return; }
		ladderCall = call || null;
		if (!call || !call.steps.length) {
			els.ladder.innerHTML = '<p class="cf-muted">Pick a call to see its sequence diagram.</p>';
			return;
		}

		var model = buildLadder(call), lanes = model.lanes, arrows = model.arrows;
		var padX = 74, colGap = 150, headH = 50, rowH = 46, footer = 16;
		var laneX = lanes.map(function (_, i) { return padX + i * colGap; });
		var width = padX * 2 + Math.max(1, lanes.length - 1) * colGap;
		var height = headH + arrows.length * rowH + footer;
		var appSet = {};
		(call.apps || []).forEach(function (a) { appSet[a] = true; });

		var out = ['<svg class="tr-ladder-svg" viewBox="0 0 ' + width + ' ' + height + '" width="' + width
			+ '" height="' + height + '" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="SIP sequence diagram">'];

		lanes.forEach(function (name, i) {
			var x = laneX[i], isApp = !!appSet[name];
			out.push('<line class="tr-life' + (isApp ? ' tr-life-app' : '') + '" x1="' + x + '" y1="' + (headH - 6)
				+ '" x2="' + x + '" y2="' + (height - footer) + '"/>');
			var short = name.length > 16 ? name.slice(0, 15) + '…' : name;
			if (isApp) {
				var boxW = Math.max(70, short.length * 7.5 + 16);
				out.push('<rect class="tr-lane-box" x="' + (x - boxW / 2) + '" y="6" width="' + boxW + '" height="26" rx="3"/>');
			}
			out.push('<text class="tr-lane-label' + (isApp ? ' tr-lane-app' : '') + '" x="' + x
				+ '" y="23" text-anchor="middle">' + escapeHtml(short) + '</text>');
		});

		arrows.forEach(function (a, r) {
			var y = headH + r * rowH + Math.round(rowH * 0.6);
			var x1 = laneX[a.from], x2 = laneX[a.to];
			var on = a.steps.indexOf(selectedStep) >= 0;
			var label = escapeHtml(a.label);
			out.push('<g class="tr-larrow' + (on ? ' tr-larrow-on' : '') + '" data-step="' + a.canonical
				+ '" data-steps="' + a.steps.join(',') + '">');
			out.push('<rect class="tr-larrow-hit" x="0" y="' + (headH + r * rowH) + '" width="' + width + '" height="' + rowH + '"/>');
			if (x1 === x2) { // self-message: a small loop on the right of the lane
				out.push('<path class="tr-larrow-line" d="M' + x1 + ' ' + (y - 8) + ' h26 v16 h-26"/>');
				out.push('<path class="tr-larrow-head" d="M' + (x1 + 8) + ' ' + (y + 3) + ' L' + x1 + ' ' + (y + 8) + ' L' + (x1 + 8) + ' ' + (y + 13) + '"/>');
				out.push('<text class="tr-larrow-label" x="' + (x1 + 32) + '" y="' + (y - 2) + '" text-anchor="start">' + label + '</text>');
			} else {
				var hx = x2 > x1 ? x2 - 9 : x2 + 9;
				out.push('<line class="tr-larrow-line" x1="' + x1 + '" y1="' + y + '" x2="' + x2 + '" y2="' + y + '"/>');
				out.push('<path class="tr-larrow-head" d="M' + hx + ' ' + (y - 5) + ' L' + x2 + ' ' + y + ' L' + hx + ' ' + (y + 5) + '"/>');
				out.push('<text class="tr-larrow-label" x="' + ((x1 + x2) / 2) + '" y="' + (y - 7) + '" text-anchor="middle">' + label + '</text>');
			}
			out.push('</g>');
		});

		out.push('</svg>');
		els.ladder.innerHTML = out.join('');

		var onEl = els.ladder.querySelector ? els.ladder.querySelector('.tr-larrow-on') : null;
		if (onEl && onEl.scrollIntoView) { onEl.scrollIntoView({ block: 'nearest' }); }
	}

	function showStep(call, s, i) {
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

	// Synthetic pin hints the framework uses when no app method handled the
	// message — a receive nobody processed (a fire-and-forget request's
	// response) or a servlet-level send. A real callflow method (process,
	// processContinue, a lambda's enclosing method) is anything else.
	function isHandlerMethod(m) {
		return !!m && m !== 'received' && m !== 'send' && m !== '?'
			&& m !== 'doRequest' && m !== 'doResponse' && m !== 'sendResponse';
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
			fetchText('traces.js'),
			fetchText('callflow.css'),
			fetchText('/blade/portal/brand/brand.css'),
			fetchDataUrl('/blade/portal/brand/blurred.jpg').catch(function () { return null; }),
			fetchDataUrl('/blade/portal/brand/vorpal_splotch.svg').catch(function () { return null; }),
			fetchSources()
		]).then(function (parts) {
			download(buildSnapshotHtml({
				js: parts[0], appCss: parts[1], brandCss: parts[2],
				backdrop: parts[3], mark: parts[4], sourceFiles: parts[5]
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
		if (parts.backdrop) {
			brandCss = brandCss.split('/blade/portal/brand/blurred.jpg').join(parts.backdrop);
		}
		var when = now.toLocaleString();
		return [
			'<!DOCTYPE html>',
			'<html lang="en">',
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
				+ '<span class="vorpal-brand-text">'
				+ '<span class="vorpal-brand-product">BLADE Trace Snapshot</span>'
				+ '<span class="vorpal-brand-tagline">exported ' + escapeHtml(when)
				+ (data.host ? ' from ' + escapeHtml(data.host) : '') + '</span>'
				+ '</span></span>',
			'<span class="vorpal-appmark">Trace</span>',
			'<div class="vorpal-topbar-right"><span id="status" class="vorpal-pill">Loading…</span></div>',
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

	function escapeHtml(s) {
		return String(s == null ? '' : s)
			.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
	}

	setTab('seq');
	refresh();
})();
