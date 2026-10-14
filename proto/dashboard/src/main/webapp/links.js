/* Where a problem is solved: links from the board into the admin app that can
   show why, already at the call, log, file or server.

   The board does not know those apps' URLs. Each app declares what it opens in
   an `opens.json` beside its pages:

     { "app": "Logs",
       "opens": { "log": "logs.html?server={server}&app={app}&at={at}&q={q}" } }

   Each key is a noun the board asks for: call, recorded, traceRule, log,
   config, endpoint. `"embed": true` says the app can show itself in a frame
   (?embed=1), so the board offers a quick look beside it. A `{name}` is
   filled from what the board knows, encoded;
   a parameter left empty is dropped. The board finds the apps from the
   portal's card list, so an undeployed app gives no link and a new app that
   declares a noun is linked without a change here. All admin apps share one
   sign-in (the BLADEADMINSESSION cookie), so the board can read their files.

   One named window per app: ten clicks on dropped calls reuse one Trace. */

(function () {
	'use strict';

	/* Asked directly when the portal is not deployed or cannot list its cards. */
	var KNOWN = ['blade/callflow', 'blade/logs', 'blade/configurator', 'blade/balancer'];

	var nouns = {};      // noun -> { app, base, template, window }
	var ready = null;

	/* WebLogic serves a static .json with no content type, so the body decides:
	   a sign-in page in its place fails to parse and counts as missing. */
	function getJson(url) {
		return fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' }).then(function (r) {
			if (!r.ok) throw new Error(url + ': ' + r.status);
			return r.json();
		});
	}

	function roots() {
		return getJson('/blade/portal/api/v1/cards').then(function (c) {
			return (c.cards || []).filter(function (card) { return card.kind !== 'service' && card.contextRoot; })
				.map(function (card) { return card.contextRoot.replace(/^\/+|\/+$/g, ''); });
		}).catch(function () { return KNOWN; });
	}

	function declare(root, d) {
		if (!d || !d.opens) return;
		Object.keys(d.opens).forEach(function (noun) {
			if (nouns[noun]) return;   // the first app to declare a noun keeps it
			nouns[noun] = { app: d.app || root, base: '/' + root + '/', template: d.opens[noun],
				window: 'blade-' + root.split('/').pop(), embed: d.embed === true };
		});
	}

	/* Read every app's declarations once; resolves when they are in. */
	function load() {
		if (!ready) {
			ready = roots().then(function (list) {
				return Promise.all(list.map(function (root) {
					return getJson('/' + root + '/opens.json').then(function (d) { declare(root, d); }, function () {});
				}));
			});
		}
		return ready;
	}

	/* `{href, target, app, embed}` for a noun, filled from `values`; null when no app
	   deployed here opens it. */
	function to(noun, values) {
		var n = nouns[noun];
		if (!n) return null;
		values = values || {};
		var filled = n.template.replace(/\{(\w+)\}/g, function (m, k) {
			return values[k] == null ? '' : encodeURIComponent(String(values[k]));
		});
		var hash = '', q = filled.indexOf('?');
		var at = filled.indexOf('#');
		if (at >= 0) { hash = filled.slice(at); filled = filled.slice(0, at); }
		if (q >= 0) {
			var params = new URLSearchParams(filled.slice(q + 1)), keep = new URLSearchParams();
			params.forEach(function (v, k) { if (v !== '') keep.append(k, v); });
			filled = filled.slice(0, q) + (keep.toString() ? '?' + keep.toString() : '');
		}
		return { href: n.base + filled + hash, target: n.window, app: n.app, embed: n.embed };
	}

	function has(noun) { return !!nouns[noun]; }

	window.BladeLinks = { load: load, to: to, has: has };
})();
