/*
 * Single source of truth for the Trace app's data model and sequence-diagram
 * geometry. Consumed by BOTH:
 *   - traces.js    — the live viewer (and the self-contained snapshot export,
 *                    which bundles this file ahead of traces.js),
 *   - report.html  — the printable per-call trace report renders the same
 *                    ladder from the same aggregation, so the two can never
 *                    disagree.
 * Plain script (no dependencies), attached to window. Everything in here is
 * pure — no DOM access, no fetch, no page state.
 */

function escapeHtml(s) {
	return String(s == null ? '' : s)
		.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function pad(n, w) { n = String(n); while (n.length < (w || 2)) { n = '0' + n; } return n; }

function hhmmss(millis) {
	var d = new Date(millis);
	return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + '.' + pad(d.getMilliseconds(), 3);
}

function simpleName(className) {
	var dot = (className || '').lastIndexOf('.');
	return dot >= 0 ? className.slice(dot + 1) : className;
}

// Synthetic pin hints the framework uses when no app method handled the
// message — a receive nobody processed (a fire-and-forget request's
// response) or a servlet-level send. A real callflow method (process,
// processContinue, a lambda's enclosing method) is anything else.
function isHandlerMethod(m) {
	return !!m && m !== 'received' && m !== 'send' && m !== '?'
		&& m !== 'doRequest' && m !== 'doResponse' && m !== 'sendResponse';
}

// ---- data ----

// Merge every node's step buffer into per-call timelines keyed by
// X-Vorpal-Session, sorted by epochMillis then per-app order — newest call
// first. Returns [{sessionId, steps:[...], firstMillis, apps:[...]}].
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
				message: s.message, peer: s.peer, leg: s.leg
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

// True when the message carries a session description: an SDP body, alone or
// as one part of a multipart body. A Content-Length of 0 means no body at all.
function hasSdp(message) {
	var type = sipHeader(message, 'content-type', 'c');
	if (!type || sipHeader(message, 'content-length', 'l') === '0') { return false; }
	return /application\/sdp/i.test(type)
		|| (/^multipart\//i.test(type) && /content-type:\s*application\/sdp/i.test(message));
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
	if (!s.message && s.peer) { return s.peer; } // a recorded step: the leg's address
	var msg = s.message;
	var val = s.kind === 'request'
		? (s.direction === 'in' ? sipHeader(msg, 'from', 'f') : sipHeader(msg, 'to', 't'))
		: (s.direction === 'in' ? sipHeader(msg, 'to', 't') : sipHeader(msg, 'from', 'f'));
	var name = addrLabel(val)
		|| addrLabel(sipHeader(msg, 'contact', 'm'))
		|| viaHost(sipHeader(msg, 'via', 'v'));
	return name || '(network)';
}

// An HTTP message of the call (the REST request that placed it, a connector's
// callout), as the framework's HttpSteps records it.
function isHttp(s) {
	return s.kind === 'http-request' || s.kind === 'http-response';
}

// "request", "response", or the same with "HTTP " in front: how a step's kind
// reads in the timeline, the step detail and the report.
function kindWord(s) {
	switch (s.kind) {
	case 'http-request': return 'HTTP request';
	case 'http-response': return 'HTTP response';
	case 'request': return 'request';
	default: return 'response';
	}
}

// The far end of each HTTP step, by index: "HTTP client" for whoever called
// this app's API, and the host a callout went to. A callout's response does
// not name its host, so it takes the one its app's last callout went to.
function httpParties(steps) {
	var lastHost = {}, out = {};
	steps.forEach(function (s, i) {
		if (!isHttp(s)) { return; }
		var callout = (s.kind === 'http-request') === (s.direction === 'out');
		if (!callout) { out[i] = 'HTTP client'; return; }
		if (s.kind === 'http-request') {
			var m = /^\S+\s+\w+:\/\/([^\/:\s?]+)/.exec(s.message || '');
			lastHost[s.app] = m ? m[1] : 'HTTP server';
		}
		out[i] = lastHost[s.app] || 'HTTP server';
	});
	return out;
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
//
// The out may sort AFTER its in. A send is recorded once the container's
// send() returns, and a co-located next app takes the message on the sending
// thread: gateway logs receiving tpcc's INVITE before tpcc logs sending it.
// So a Call-ID match looks both ways and takes the nearest unmatched out; the
// hop sorts at its earlier step, which puts the arrow where the message moved.
//
// HTTP steps carry no Call-ID and are never paired: each is one arrow between
// its app and the party httpParties names.
function buildLadder(call) {
	var steps = call.steps;
	var httpPeer = httpParties(steps);
	var outs = [];      // every out step: {i, app, label, cid, cseq, used}
	var hops = [];      // {steps:[…], canonical, fromP, toP, label}
	var appsOn = {};    // Call-ID → the traced apps that saw it
	var keyed = steps.map(function (s, i) {
		var k = { i: i, app: s.app || '(app)', label: s.label,
			cid: isHttp(s) ? null : sipHeader(s.message, 'call-id', 'i'), cseq: sipHeader(s.message, 'cseq', null) };
		if (isHttp(s)) {
			hops.push(s.direction === 'in'
				? { steps: [i], canonical: i, fromP: httpPeer[i], toP: k.app, label: s.label }
				: { steps: [i], canonical: i, fromP: k.app, toP: httpPeer[i], label: s.label });
			return k;
		}
		if (s.direction !== 'in') { outs.push(k); }
		if (k.cid) {
			var on = appsOn[k.cid] = appsOn[k.cid] || [];
			if (on.indexOf(k.app) < 0) { on.push(k.app); }
		}
		return k;
	});
	// A step whose other half went unrecorded (tracing is best-effort, so a send
	// on a session the app just tore down can be lost) still names its peer when
	// exactly one other traced app shares its Call-ID: that leg runs between them.
	function peer(k) {
		var others = (appsOn[k.cid] || []).filter(function (a) { return a !== k.app; });
		return others.length === 1 ? others[0] : farParty(steps[k.i]);
	}
	steps.forEach(function (s, i) {
		if (s.direction !== 'in' || isHttp(s)) { return; }
		var k = keyed[i], best = null;
		outs.forEach(function (c) {
			if (c.used || c.app === k.app || c.label !== k.label) { return; }
			if (k.cid) {
				if (c.cid !== k.cid || c.cseq !== k.cseq) { return; }
			} else if (c.i > i) { return; } // no Call-ID (legacy) → nearest earlier same-method out
			if (!best || Math.abs(c.i - i) < Math.abs(best.i - i)) { best = c; }
		});
		if (best) {
			best.used = true;
			hops.push({ steps: [best.i, i], canonical: best.i, fromP: best.app, toP: k.app, label: s.label });
		} else { // arrived from outside the chain — name the real caller
			hops.push({ steps: [i], canonical: i, fromP: peer(k), toP: k.app, label: s.label });
		}
	});
	outs.forEach(function (out) { // left the chain — name the real callee
		if (!out.used) {
			hops.push({ steps: [out.i], canonical: out.i, fromP: out.app, toP: peer(out), label: out.label });
		}
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
		var first = steps[h.steps[0]];
		if (isHttp(first)) { // said in words, so it reads apart from SIP without colour
			return { steps: h.steps, canonical: h.canonical, from: lanes.indexOf(h.fromP), to: lanes.indexOf(h.toP),
				label: 'HTTP ' + h.label };
		}
		var sdp = h.steps.some(function (i) { return hasSdp(steps[i].message); });
		return { steps: h.steps, canonical: h.canonical, from: lanes.indexOf(h.fromP), to: lanes.indexOf(h.toP),
			label: h.label + (sdp ? ' (SDP)' : '') };
	});
	return { lanes: lanes, arrows: arrows };
}

// The ladder SVG as a markup string. selectedStep highlights the arrow
// carrying that step (pass -1 for none, e.g. the printable report). Styling
// comes from the including page's .tr-ladder-svg / .tr-life / .tr-lane-* /
// .tr-larrow-* rules, so screen and print skins share this geometry.
function ladderSvg(call, selectedStep) {
	var model = buildLadder(call), lanes = model.lanes, arrows = model.arrows;
	var padX = 74, colGap = 150, headH = 50, rowH = 46, footer = 16;
	var laneX = lanes.map(function (_, i) { return padX + i * colGap; });
	var width = padX * 2 + Math.max(1, lanes.length - 1) * colGap;
	var height = headH + arrows.length * rowH + footer;
	var appSet = {};
	(call.apps || []).forEach(function (a) { appSet[a] = true; });

	var out = ['<svg class="tr-ladder-svg" viewBox="0 0 ' + width + ' ' + height + '" width="' + width
		+ '" height="' + height + '" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Sequence diagram">'];

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
	return out.join('');
}
