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

	/* One of this domain's servers, from /health: running, a stuck thread,
	   shut down (a choice, so no colour), or any other state (trouble). */
	function serverMember(name, nodes) {
		var n = nodes.filter(function (x) { return x.server === name; })[0];
		if (!n) return { label: name, state: 'none', note: 'not reported' };
		var w = stateWord(n);
		if (w === 'RUNNING') {
			return n.threadsStuck > 0 ? { label: name, state: 'warn', note: n.threadsStuck + ' stuck threads' }
				: { label: name, state: 'up', note: 'running' };
		}
		if (w === 'SHUTDOWN') return { label: name, state: 'none', note: 'shut down' };
		return { label: name, state: /STARTING|RESUMING|DRAINING|ADMIN/.test(w) ? 'warn' : 'down', note: w.toLowerCase().replace(/_/g, ' ') };
	}

	/* A balancer endpoint as every engine sees it: down where every engine's
	   pings fail, trouble where only some do (a path, not the server). */
	function endpointMembers(site) {
		var byName = {};
		(site.endpoints || []).forEach(function (e) { (byName[e.name] = byName[e.name] || []).push(e); });
		return Object.keys(byName).map(function (name) {
			var views = byName[name].filter(function (v) { return v.enabled; });
			if (!views.length) return { label: name, state: 'none', note: 'disabled' };
			var bad = views.filter(function (v) { return v.status === 'down' || !v.routable; });
			if (bad.length) {
				var why = bad[0].note ? ' (' + bad[0].note + ')' : '';
				return bad.length === views.length ? { label: name, state: 'down', note: 'not answering pings' + why }
					: { label: name, state: 'warn', note: 'not answering ' + bad.map(function (v) { return v.node; }).join(', ') + why };
			}
			if (views.every(function (v) { return v.status === 'unknown'; })) return { label: name, state: 'none', note: 'not pinged yet' };
			var rtt = views.map(function (v) { return v.rttMs; }).filter(function (r) { return r != null && r >= 0; });
			return { label: name, state: 'up', note: rtt.length ? 'pings ' + Math.max.apply(null, rtt) + ' ms' : 'answering' };
		});
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

	/* "2 × 503 server unavailable, 1 × 408 no answer from the server in 15 min" */
	function failureNote(failures, windowMin) {
		var by = {};
		failures.forEach(function (f) {
			var k = f.status + ' ' + f.meaning + (f.retryAfter ? ' (asked to wait ' + f.retryAfter + ' s)' : '');
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

	/* /network and /health into what the map draws: each site's members and
	   state, and the lines between sites. */
	function model(net, nodes, now) {
		var cluster = [], sites = net.sites.map(function (s) {
			var members = (s.servers || []).map(function (n) { return serverMember(n, nodes); }).concat(endpointMembers(s));
			(s.trunks || []).forEach(function (t) {
				members.push({ label: 'trunk ' + t.gateway, state: 'down',
					note: 'registration failing' + (t.status ? ': ' + t.status + (t.reason ? ' ' + t.reason : '') : '') });
			});
			datasourceMembers(s, nodes, now, net.windowMinutes || 15).forEach(function (m) { members.push(m); });
			remoteMembers(s, now, net.windowMinutes || 15).forEach(function (m) { members.push(m); });
			var route = members.slice();
			busMembers(s.bus).forEach(function (m) { members.push(m); });
			failureMembers(s, net).forEach(function (f) {
				members.push(f);
				if (f.route) route.push(f);
			});
			var inbound = null;
			if ((s.roles || []).indexOf('inbound') >= 0) {
				var last = s.inbound && s.inbound.last, q = s.quietMinutes;
				var quiet = q != null && (!last || now - last > q * 60000);
				inbound = { label: 'calls arriving', state: quiet ? 'warn' : last ? 'up' : 'none',
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

	global.BladeNetwork = { model: model, rank: rank, ago: ago };
})(typeof window !== 'undefined' ? window : globalThis);
