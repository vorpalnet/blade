/* The network map's model: /network and /health put together into what
   charts.js networkMap draws, each site's members and state and the lines
   between sites. Pure: no DOM, no fetch, the time passed in, so it is
   exercised outside a browser. */
(function (global) {
	'use strict';

	function stateWord(n) {
		if (n.drained) return 'DRAINING';
		return String(n.state || 'UNKNOWN').toUpperCase();
	}

	function ago(ms, now) {
		var m = Math.round((now - ms) / 60000);
		return m < 1 ? 'just now' : m < 60 ? m + ' min ago' : m < 2880 ? Math.round(m / 60) + ' h ago' : Math.round(m / 1440) + ' days ago';
	}

	/* The fuller of a server's two SIP work queues (the options app's
	   SipQueues MBean): work waiting for a thread as a share of the depth at
	   which the container refuses every request with 503. Null when the
	   server does not publish them. `busy` is the share at which the health
	   ping answers 503 first. */
	function queueFill(n) {
		var q = n && n.sipQueues;
		if (!q) return null;
		var worst = null;
		['transport', 'timer'].forEach(function (k) {
			var x = q[k];
			if (!x || x.depth == null || !x.capacity) return;
			var pct = Math.floor(x.depth * 100 / x.capacity);
			if (!worst || pct > worst.pct) worst = { queue: k, pct: pct, depth: x.depth, capacity: x.capacity };
		});
		if (worst) worst.busy = q.busyPercent || 0;
		return worst;
	}

	/* An engine over its overload line, in words: refusing new calls when the
	   container acts on the verdict, otherwise why nothing refuses yet. */
	function overloadNote(o) {
		var at = 'SIP messages taking ' + o.latencyMillis + ' ms, resumes below ' + Math.floor(o.refuseMillis / 2) + ' ms';
		switch (o.protection) {
		case 'active': return 'refusing new calls (503 Server busy): ' + at;
		case 'not installed': return 'overloaded, ' + at + ': nothing refuses new calls, blade-overload.jar is not installed';
		default: return 'overloaded, ' + at + ': the container is not asking for the verdict (sipserver.xml)';
		}
	}

	/* One of this domain's servers, from /health: running, a stuck thread,
	   shut down (a choice, so no colour), or any other state (trouble). */
	function serverMember(name, nodes) {
		var n = nodes.filter(function (x) { return x.server === name; })[0];
		if (!n) return { label: name, state: 'none', note: 'not reported' };
		var w = stateWord(n);
		if (w === 'RUNNING') {
			var f = queueFill(n);
			var o = n.sipQueues && n.sipQueues.overload;
			if (f && f.pct >= 100) return { label: name, state: 'down', note: 'SIP ' + f.queue + ' queue full: refusing every request (503)' };
			if (o && o.refusing) return { label: name, state: 'down', note: overloadNote(o) };
			if (f && f.busy && f.pct >= f.busy) return { label: name, state: 'warn', note: 'SIP ' + f.queue + ' queue ' + f.pct + '% full: pings answer 503 Busy' };
			if (o && o.refuseMillis && o.latencyMillis != null && o.latencyMillis * 2 >= o.refuseMillis) {
				return { label: name, state: 'warn', note: 'SIP messages taking ' + o.latencyMillis + ' ms: pings answer 503 Busy' };
			}
			if (o && o.warnings && o.warnings.length) return { label: name, state: 'warn', note: o.warnings.join('; ') };
			return n.threadsStuck > 0 ? { label: name, state: 'warn', note: n.threadsStuck + ' stuck threads' }
				: { label: name, state: 'up', note: 'running' };
		}
		if (w === 'SHUTDOWN') return { label: name, state: 'none', note: 'shut down' };
		return { label: name, state: /STARTING|RESUMING|DRAINING|ADMIN/.test(w) ? 'warn' : 'down', note: w.toLowerCase().replace(/_/g, ' ') };
	}

	/* A balancer endpoint as every engine sees it. Pings failing on every
	   engine is down, on some is trouble (a path, not the server). A refusal
	   (a 503, with the reason phrase the endpoint gave) is trouble wherever it
	   is: the endpoint answered, so it is alive, and the phrase says why, as
	   "Draining" or "Busy" does from a BLADE engine. The calls each engine
	   offered it are summed. */
	function endpointMembers(site, now) {
		var byName = {};
		(site.endpoints || []).forEach(function (e) { (byName[e.name] = byName[e.name] || []).push(e); });
		return Object.keys(byName).map(function (name) {
			var views = byName[name].filter(function (v) { return v.enabled; });
			if (!views.length) return { label: name, state: 'none', note: 'disabled' };
			var calls = traffic(views);
			var refusing = views.filter(function (v) { return v.status === 'down' && v.refused; });
			var failing = views.filter(function (v) { return (v.status === 'down' || !v.routable) && !v.refused; });
			if (failing.length) {
				var why = failing[0].note ? ' (' + failing[0].note + ')' : '';
				return failing.length === views.length ? { label: name, state: 'down', note: 'not answering pings' + why + calls }
					: { label: name, state: 'warn', note: 'not answering ' + nodeNames(failing) + why + calls };
			}
			if (refusing.length) {
				return { label: name, state: 'warn', note: 'refused: ' + refusing[0].refused
					+ (refusing.length === views.length ? '' : ' on ' + nodeNames(refusing)) + until(refusing, now) + calls };
			}
			if (views.every(function (v) { return v.status === 'unknown'; })) return { label: name, state: 'none', note: 'not pinged yet' + calls };
			var rtt = views.map(function (v) { return v.rttMs; }).filter(function (r) { return r != null && r >= 0; });
			return { label: name, state: 'up', note: (rtt.length ? 'pings ' + Math.max.apply(null, rtt) + ' ms' : 'answering') + calls };
		});
	}

	/* How long a refusal keeps calls away: the Retry-After it asked for, or,
	   for a refused ping, until a ping is answered. */
	function until(refusing, now) {
		var back = Math.max.apply(null, refusing.map(function (v) { return v.downUntil || 0; }));
		if (back > now) return ', resumes in ' + Math.max(1, Math.ceil((back - now) / 60000)) + ' min';
		return back ? ', being offered calls again' : ', until it answers a ping';
	}

	function nodeNames(views) { return views.map(function (v) { return v.node; }).join(', '); }

	/* ' · 120 calls, 2 failed over', summed over every engine; nothing when
	   no engine has offered it a call. */
	function traffic(views) {
		var offered = 0, failed = 0;
		views.forEach(function (v) { offered += v.attempts || 0; failed += v.failovers || 0; });
		if (!offered) return '';
		return ' · ' + offered + (offered === 1 ? ' call' : ' calls') + (failed ? ', ' + failed + ' failed over' : '');
	}

	/* A connection pool as every server reports it (/health): down when a
	   server has it suspended, unhealthy or shut down; trouble when overloaded,
	   when a request is waiting for a connection, or when a reserve or a
	   reconnect failed within the window. */
	function datasourceMembers(site, nodes, now, windowMin) {
		return (site.datasources || []).map(function (name) {
			var views = [];
			nodes.forEach(function (n) {
				(n.jdbc || []).forEach(function (d) { if (d.name === name) views.push({ server: n.server, d: d }); });
			});
			var label = 'pool ' + name;
			if (!views.length) return { label: label, state: 'none', note: 'not reported by any server' };
			var worst = { state: 'up', note: '' };
			views.forEach(function (v) {
				var d = v.d, st = String(d.state || '').toLowerCase(), m;
				if (st && st !== 'running') {
					m = { state: st === 'overloaded' ? 'warn' : 'down', note: st + ' on ' + v.server };
				} else if (d.failuresAt && now - d.failuresAt < windowMin * 60000) {
					m = { state: 'warn', note: 'connections failing on ' + v.server + ' (' + d.failures + ' since it started)' };
				} else if (d.waiting > 0) {
					m = { state: 'warn', note: d.waiting + ' waiting for a connection on ' + v.server };
				} else {
					m = { state: 'up', note: d.active + ' of ' + d.capacity + ' connections in use' };
				}
				if (rank(m.state) > rank(worst.state)) worst = m;
				else if (worst.state === 'up' && m.state === 'up' && views.length > 1) worst = { state: 'up', note: 'running on ' + views.length + ' servers' };
			});
			return { label: label, state: worst.state, note: worst.note, route: true };
		});
	}

	/* The services applications call (framework Remotes), one member per
	   service however many applications and servers call it: down while a
	   circuit breaker has it cut off, trouble when a call failed within the
	   window, working when it has answered. */
	function remoteMembers(site, now, windowMin) {
		var by = {};
		(site.remotes || []).forEach(function (r) {
			var k = r.kind + ' ' + r.remote;
			(by[k] = by[k] || []).push(r);
		});
		return Object.keys(by).map(function (k) {
			var views = by[k], calls = 0, failures = 0, open = [], recent = null;
			views.forEach(function (r) {
				calls += r.calls; failures += r.failures;
				if (r.open) open.push(r);
				if (r.lastFailureAt && now - r.lastFailureAt < windowMin * 60000 && (!recent || r.lastFailureAt > recent.lastFailureAt)) recent = r;
			});
			var who = function (list) { return list.map(function (r) { return r.application + ' on ' + r.server; }).join(', '); };
			if (open.length) return { label: k, state: 'down', note: 'cut off by ' + who(open) + (open[0].lastFailure ? ': ' + open[0].lastFailure : ''), route: true };
			if (recent) return { label: k, state: 'warn', note: failures + ' of ' + calls + ' calls failed, last ' + ago(recent.lastFailureAt, now) + ': ' + recent.lastFailure, route: true };
			if (calls) return { label: k, state: 'up', note: calls + ' calls answered', route: true };
			return { label: k, state: 'none', note: 'not called yet' };
		});
	}

	/* The event bus as the broker sees it: a subscription with no consumer is
	   down (it receives nothing, and its messages pile up), a backlog of a
	   thousand is trouble, and so is anything parked on the error queue. */
	function busMembers(bus) {
		if (!bus) return [];
		var out = (bus.subscriptions || []).map(function (s) {
			var label = 'bus: ' + s.name;
			if (!s.active) return { label: label, state: 'down', note: 'receiving nothing, ' + s.waiting + ' waiting' };
			if (s.waiting >= 1000) return { label: label, state: 'warn', note: s.waiting + ' waiting' };
			return { label: label, state: 'up', note: 'consuming' + (s.waiting ? ', ' + s.waiting + ' waiting' : '') };
		});
		if (bus.errorQueue > 0) out.push({ label: 'bus: error queue', state: 'warn', note: bus.errorQueue + ' events failed processing' });
		if (bus.topicConsumers === 0) out.push({ label: 'bus: topic', state: 'warn', note: 'nothing subscribed' });
		return out;
	}

	/* "2 × 503 server unavailable "Server Busy", 1 × 408 no answer from the
	   server in 15 min": the reason phrase the server wrote follows the
	   meaning, since for a 503 it is usually the cause. */
	function failureNote(failures, windowMin) {
		var by = {};
		failures.forEach(function (f) {
			var k = f.status + ' ' + f.meaning + (f.reason ? ' "' + f.reason + '"' : '')
				+ (f.retryAfter ? ' (asked to wait ' + f.retryAfter + ' s)' : '');
			by[k] = (by[k] || 0) + f.calls;
		});
		return Object.keys(by).map(function (k) { return by[k] + ' × ' + k; }).join(', ') + ' in ' + windowMin + ' min';
	}

	/* A site's refusals by who has to act (SipStatus on the server):
	   equipment failing turns the site amber, and red at failingCalls in the
	   window; configuration refusals turn it amber; callees saying no are
	   shown when zoomed in and colour nothing. */
	function failureMembers(site, net) {
		var kinds = { failing: [], misconfigured: [], callee: [] }, out = [];
		(site.failures || []).forEach(function (f) { if (kinds[f.kind]) kinds[f.kind].push(f); });
		var count = function (list) { return list.reduce(function (n, f) { return n + f.calls; }, 0); };
		if (kinds.failing.length) {
			out.push({ label: 'server failures', state: count(kinds.failing) >= (net.failingCalls || 3) ? 'down' : 'warn',
				note: failureNote(kinds.failing, net.windowMinutes), route: true });
		}
		if (kinds.misconfigured.length) {
			out.push({ label: 'configuration', state: 'warn', note: failureNote(kinds.misconfigured, net.windowMinutes), route: true });
		}
		if (kinds.callee.length) {
			out.push({ label: 'callees refusing', state: 'none', note: failureNote(kinds.callee, net.windowMinutes) + ' (normal)' });
		}
		return out;
	}

	/* What a member is, for the zoomed-in view: the WebLogic servers
	   ('server') and the SIP peers calls travel to and from ('sip': balancer
	   endpoints, trunks, calls arriving) are drawn; the other connections
	   (pools, remote services, the bus) and refusals are listed. */
	function kind(m, k) { m.kind = k; return m; }

	/* /network and /health into what the map draws: each site's members and
	   state, and the lines between sites. */
	function model(net, nodes, now) {
		var cluster = [], sites = net.sites.map(function (s) {
			var members = (s.servers || []).map(function (n) { return kind(serverMember(n, nodes), 'server'); })
				.concat(endpointMembers(s, now).map(function (m) { return kind(m, 'sip'); }));
			(s.trunks || []).forEach(function (t) {
				members.push({ label: 'trunk ' + t.gateway, state: 'down', kind: 'sip',
					note: 'registration failing' + (t.status ? ': ' + t.status + (t.reason ? ' ' + t.reason : '') : '') });
			});
			datasourceMembers(s, nodes, now, net.windowMinutes || 15).forEach(function (m) { members.push(kind(m, 'pool')); });
			remoteMembers(s, now, net.windowMinutes || 15).forEach(function (m) { members.push(kind(m, 'remote')); });
			var route = members.slice();
			busMembers(s.bus).forEach(function (m) { members.push(kind(m, 'bus')); });
			failureMembers(s, net).forEach(function (f) {
				members.push(kind(f, 'refusal'));
				if (f.route) route.push(f);
			});
			var inbound = null;
			if ((s.roles || []).indexOf('inbound') >= 0) {
				var last = s.inbound && s.inbound.last, q = s.quietMinutes;
				var quiet = q != null && (!last || now - last > q * 60000);
				inbound = { label: 'calls arriving', kind: 'sip', state: quiet ? 'warn' : last ? 'up' : 'none',
					note: last ? 'last ' + ago(last, now) : 'none in the last day' };
				members.push(inbound);
			}
			var worst = members.reduce(function (w, m) { return rank(m.state) > rank(w.state) ? m : w; }, { state: 'none' });
			var site = { name: s.name, city: s.city, lat: s.lat, lon: s.lon, roles: s.roles || [], members: members,
				state: worst.state, summary: worst.note ? worst.label + ': ' + worst.note : '',
				routeState: route.reduce(function (w, m) { return rank(m.state) > rank(w) ? m.state : w; }, 'none'),
				routeNote: route.filter(function (m) { return m.state === 'down' || m.state === 'warn'; })
					.map(function (m) { return m.label + ': ' + m.note; }).join('; '),
				inbound: inbound };
			if (site.roles.indexOf('cluster') >= 0) cluster.push(site);
			return site;
		});
		var links = [];
		cluster.forEach(function (c) {
			sites.forEach(function (s) {
				if (s === c) return;
				if (s.roles.indexOf('downstream') >= 0) {
					links.push({ from: c.name, to: s.name, state: s.routeState === 'none' ? 'none' : s.routeState,
						note: c.name + ' → ' + s.name + ': ' + (s.routeNote || (s.routeState === 'up' ? 'working' : 'no data')) });
				}
				if (s.inbound) {
					links.push({ from: s.name, to: c.name, state: s.inbound.state,
						note: s.name + ' → ' + c.name + ': calls arriving, ' + s.inbound.note });
				}
			});
		});
		return { sites: sites, links: links };
	}

	function rank(state) { return { down: 3, warn: 2, up: 1 }[state] || 0; }

	global.BladeNetwork = { model: model, rank: rank, ago: ago, endpointMembers: endpointMembers, queueFill: queueFill,
		serverMember: serverMember };
})(typeof window !== 'undefined' ? window : globalThis);
