/*
 * BLADE admin frame: the rail of applications and services down the left,
 * and the account menu at the right of the top bar. The same in every admin
 * application, the dashboard's look.
 *
 * Served at /blade/portal/brand/shell.js. A page loads it after brand.css and
 * theme.js:
 *
 *   <script src="/blade/portal/brand/shell.js" data-context="/blade/logs" defer></script>
 *
 * It asks the page's own application (the framework's blade-shell endpoint,
 * under that application's sign-in) who is signed in and what the rail lists:
 *
 *   data-context   the application's context path; the endpoints hang off it
 *   data-me        who is signed in, if not <context>/blade-shell/me
 *   data-cards     the rail's list, if not <context>/blade-shell/cards
 *   data-sees      optional: an endpoint whose `sees` lines say what this
 *                  viewer may see here, shown in the account menu
 *
 * Into the top bar: an element marked data-shell-account, else the end of
 * the brand top bar's right-hand group. The rail: an element marked
 * data-shell-rail, else one put beside the page's content, the element marked
 * data-shell-main or else its .vorpal-main. A page with neither (a drawing
 * canvas such as Flow's) and a viewer the list is not for (no WebLogic admin
 * role) get no rail.
 *
 * The class names are the dashboard's (rail__*, acct__*), so its stylesheet
 * draws them there; brand.css draws them on every other page, under the
 * vorpal-shell class this adds to <body>.
 */
(function () {
	var me = document.currentScript;
	var ctx = (me && me.getAttribute('data-context')) || '';
	var URLS = {
		me: (me && me.getAttribute('data-me')) || ctx + '/blade-shell/me',
		cards: (me && me.getAttribute('data-cards')) || ctx + '/blade-shell/cards',
		sees: me && me.getAttribute('data-sees')
	};

	function el(tag, cls, text) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (text != null) e.textContent = text;
		return e;
	}

	function getJson(url) {
		return fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' }, cache: 'no-store' })
			.then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); });
	}

	/* ── the rail ──────────────────────────────────────────────────────── */

	/* Icons are drawn in the rail's own ink: each app's favicon is used as a
	   mask, so no icon depends on its colour. An app without an icon of its
	   own gets a plain window; a service, a plug. */
	function glyph(paths) {
		return 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
			+ '<g fill="none" stroke="#000" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + paths + '</g></svg>');
	}
	var GLYPH_APP = glyph('<rect x="4" y="6" width="24" height="20" rx="2.5"/><path d="M4 11h24 M8 8.5h.01 M11 8.5h.01"/>');
	var GLYPH_SERVICE = glyph('<path d="M12 4v6 M20 4v6 M8 10h16v5a8 8 0 0 1-16 0z M16 23v5"/>');
	var GLYPH_INVENTORY = glyph('<rect x="7" y="6" width="18" height="22" rx="2.5"/><path d="M12 4h8v4h-8z M11 14h.01 M15 14h6 M11 19h.01 M15 19h6 M11 24h.01 M15 24h6"/>');

	function maskIcon(icon, url) {
		var m = 'url("' + url + '")';
		icon.style.webkitMaskImage = m;
		icon.style.maskImage = m;
	}

	/* Where an application opens: here, unless every server it runs on is
	   another one (an engine-tier app such as Audit or Recordings), which a
	   browser reaches at the engine tier's own address. */
	function appHref(card, where) {
		var path = '/' + card.contextRoot + '/';
		var elsewhere = where.server && card.servers && card.servers.length
			&& card.servers.indexOf(where.server) < 0;
		return elsewhere && where.engineBaseUrl ? where.engineBaseUrl + path : path;
	}

	function railItem(card, where, current) {
		var service = card.kind === 'service';
		var a = el('a', 'rail__item');
		a.href = service ? '/blade/configurator/configurator.html?schema=' + encodeURIComponent(card.configuratorDomain || card.name)
			: appHref(card, where);
		a.target = service ? 'blade-configurator' : 'blade-' + card.contextRoot.replace(/^.*\//, '');
		if (current) {
			a.className += ' rail__item--here';
			a.setAttribute('aria-current', 'page');
			a.removeAttribute('target');
		}
		var icon = el('span', 'rail__icon');
		icon.setAttribute('aria-hidden', 'true');
		if (service) {
			maskIcon(icon, GLYPH_SERVICE);
		} else {
			// Only an icon that loads becomes the mask; a missing one, the window.
			var src = '/' + card.contextRoot + '/favicon.svg', probe = new Image();
			probe.onload = function () { maskIcon(icon, src); };
			probe.onerror = function () { maskIcon(icon, GLYPH_APP); };
			probe.src = src;
		}
		a.appendChild(icon);
		a.appendChild(el('span', 'rail__name', card.name));
		a.title = [card.name + (card.tagline ? ': ' + card.tagline : ''), card.description]
			.filter(Boolean).join('\n\n');
		return a;
	}

	function inventory() {
		var a = el('a', 'rail__item rail__item--first');
		a.href = '/blade/portal/report.html';
		a.target = 'blade-portal';
		a.title = 'Deployment Inventory\n\nEvery application and service, where each runs, in one printable page.';
		var icon = el('span', 'rail__icon');
		icon.setAttribute('aria-hidden', 'true');
		maskIcon(icon, GLYPH_INVENTORY);
		a.appendChild(icon);
		a.appendChild(el('span', 'rail__name', 'Deployment Inventory'));
		return a;
	}

	/* The page's own rail element, or one put beside its main area. A page
	   that has neither, or a viewer with nothing to list, gets none. */
	function railHost(cards) {
		var rail = document.querySelector('[data-shell-rail]');
		if (rail) return rail;
		var main = document.querySelector('[data-shell-main]') || document.querySelector('.vorpal-main');
		if (!main || !cards.length) return null;
		var body = el('div', 'vorpal-shell-body');
		main.parentNode.insertBefore(body, main);
		rail = el('nav', 'rail');
		rail.setAttribute('aria-label', 'Applications and services');
		// Pages that pin something to the window's left edge move it past the rail.
		document.body.classList.add('vorpal-shell--rail');
		body.appendChild(rail);
		body.appendChild(main);
		return rail;
	}

	function fillRail(cards, where) {
		var rail = railHost(cards);
		if (!rail) return;
		var here = ctx.replace(/^\//, '');
		rail.textContent = '';
		rail.appendChild(inventory());
		[['app', 'Apps'], ['service', 'Services']].forEach(function (g) {
			var some = cards.filter(function (c) { return (c.kind === 'service') === (g[0] === 'service'); });
			if (!some.length) return;
			rail.appendChild(el('div', 'rail__head', g[1]));
			some.forEach(function (c) { rail.appendChild(railItem(c, where, c.contextRoot === here)); });
		});
	}

	function railFailed() {
		var rail = document.querySelector('[data-shell-rail]');
		if (!rail) return;
		rail.textContent = '';
		rail.appendChild(el('p', 'rail__note', 'The application list is unavailable.'));
	}

	/* The engine tier's address, for the rail's engine-tier applications:
	   the dashboard's setting. Only asked for when some application runs on
	   another server; without it, those open at this host. */
	function engineBase(who, cards) {
		if (who.engineBaseUrl) return Promise.resolve(who.engineBaseUrl);
		var server = who.about && who.about.server;
		var needed = server && cards.some(function (c) { return c.servers && c.servers.length && c.servers.indexOf(server) < 0; });
		if (!needed) return Promise.resolve(null);
		return getJson('/blade/dashboard/settings').then(function (s) { return s && s.engineBaseUrl; },
			function () { return null; });
	}

	/* ── the account menu ──────────────────────────────────────────────── */

	function aboutBlock(about) {
		var box = el('div', 'acct__about');
		box.hidden = true;
		box.appendChild(el('div', 'acct__about-ver', 'BLADE ' + (about.version || '')
			+ (about.mode === 'dev' ? ' · development build' : '')));
		var rows = el('dl', 'acct__about-rows');
		[['Built', about.built], ['Server', about.server], ['Domain', about.domain],
			['WebLogic', about.weblogic], ['Java', about.java]].forEach(function (r) {
			if (!r[1]) return;
			rows.appendChild(el('dt', '', r[0]));
			rows.appendChild(el('dd', '', r[1]));
		});
		box.appendChild(rows);
		if (about.copyright || about.license) {
			box.appendChild(el('div', 'acct__about-legal', [about.copyright, about.license].filter(Boolean).join(' · ')));
		}
		return box;
	}

	function accountHost() {
		var host = document.querySelector('[data-shell-account]');
		if (host) return host;
		var right = document.querySelector('.vorpal-topbar .vorpal-topbar-right');
		if (!right) return null;
		host = el('div', 'acct');
		right.appendChild(host);
		return host;
	}

	function fillAccount(who, sees) {
		var host = accountHost();
		var name = who && (who.displayName || who.user);
		if (!host || !name) return;
		host.textContent = '';
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
		var how = (who.user && who.user !== name ? who.user + ' · ' : '') + 'signed in with ' + (who.signedInWith || 'WebLogic');
		menu.appendChild(el('div', 'acct__how', how));
		if (sees && sees.length) {
			menu.appendChild(el('div', 'acct__h', 'What you see'));
			var list = el('ul', 'acct__sees');
			sees.forEach(function (line) { list.appendChild(el('li', '', line)); });
			menu.appendChild(list);
		}
		if (who.about) {
			var toggle = el('button', 'acct__link', 'About this application');
			toggle.type = 'button';
			var about = aboutBlock(who.about);
			toggle.addEventListener('click', function (e) {
				e.stopPropagation();
				about.hidden = !about.hidden;
			});
			menu.appendChild(toggle);
			menu.appendChild(about);
		}
		var signOut = who.signOut === undefined ? 'signout' : who.signOut;
		if (signOut) {
			var out = el('a', 'acct__out', 'Sign out');
			out.href = signOut;
			menu.appendChild(out);
		}
		var open = function (yes) { menu.hidden = !yes; btn.setAttribute('aria-expanded', String(yes)); };
		btn.addEventListener('click', function (e) { e.stopPropagation(); open(menu.hidden); });
		document.addEventListener('click', function (e) { if (!host.contains(e.target)) open(false); });
		document.addEventListener('keydown', function (e) { if (e.key === 'Escape') open(false); });
		host.appendChild(btn);
		host.appendChild(menu);
		host.hidden = false;
	}

	/* ── start ─────────────────────────────────────────────────────────── */

	function start() {
		if (!document.querySelector('[data-shell-rail]')) document.body.classList.add('vorpal-shell');
		var who = getJson(URLS.me).catch(function () { return null; });
		var sees = URLS.sees ? getJson(URLS.sees).then(function (s) { return s && s.sees; }, function () { return null; })
			: Promise.resolve(null);
		Promise.all([who, sees]).then(function (r) {
			fillAccount(r[0], r[1] || (r[0] && r[0].sees));
		});
		Promise.all([who, getJson(URLS.cards)]).then(function (r) {
			var me = r[0] || {}, cards = (r[1] && r[1].cards) || [];
			return engineBase(me, cards).then(function (base) {
				fillRail(cards, { server: me.about && me.about.server, engineBaseUrl: base });
			});
		}).catch(railFailed);
	}

	// After theme.js has put its switch in the top bar, so the menu comes last.
	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
	else start();
})();
