/*
 * BLADE admin theme: light or dark, the same choice in every admin app.
 *
 * Served at /blade/portal/brand/theme.js and loaded in each page's <head>,
 * right after brand.css, so the theme is set before the page first paints.
 * It sets data-theme="light" or "dark" on <html>; brand.css (and the
 * dashboard's own stylesheet) key their tokens on it.
 *
 * The theme follows the computer's light or dark setting until the person
 * picks one with the switch; the pick is remembered in this browser and,
 * since every admin app shares one origin, holds in every app. A pick made
 * in one tab reaches the others as it happens.
 *
 * The switch goes into the top bar: an element marked data-theme-switch if
 * the page has one, else the brand top bar's right-hand group. The login
 * page does not load this file; it keeps its own look.
 */
(function () {
	var KEY = 'blade.theme';
	var root = document.documentElement;
	var dark = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
	var button = null;

	function picked() {
		try {
			var v = localStorage.getItem(KEY);
			return v === 'light' || v === 'dark' ? v : null;
		} catch (e) {
			return null;
		}
	}

	function current() {
		return picked() || (dark && dark.matches ? 'dark' : 'light');
	}

	function apply() {
		root.setAttribute('data-theme', current());
		label();
	}

	/* The switch names the theme it turns on, in words and by its icon. */
	var SUN = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5"/>'
		+ '<path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"/></svg>';
	var MOON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/></svg>';

	function label() {
		if (!button) return;
		var toDark = root.getAttribute('data-theme') !== 'dark';
		button.innerHTML = (toDark ? MOON : SUN) + '<span>' + (toDark ? 'Dark' : 'Light') + '</span>';
		button.title = toDark ? 'Switch to the dark theme' : 'Switch to the light theme';
	}

	function mount() {
		var host = document.querySelector('[data-theme-switch]');
		if (!host) {
			var bar = document.querySelector('.vorpal-topbar');
			if (!bar) return;
			host = bar.querySelector('.vorpal-topbar-right');
			if (!host) {
				host = document.createElement('div');
				host.className = 'vorpal-topbar-right';
				bar.appendChild(host);
			}
		}
		button = document.createElement('button');
		button.type = 'button';
		button.className = 'theme-switch';
		button.addEventListener('click', function () {
			var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
			try {
				localStorage.setItem(KEY, next);
			} catch (e) {
				// Private window: the switch still works for this page.
			}
			root.setAttribute('data-theme', next);
			label();
		});
		host.appendChild(button);
		label();
	}

	apply();
	if (dark && dark.addEventListener) dark.addEventListener('change', apply);
	window.addEventListener('storage', function (e) {
		if (e.key === KEY) apply();
	});
	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', mount);
	} else {
		mount();
	}
})();
