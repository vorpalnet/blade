/*
 * BLADE API Explorer: front-end glue.
 *
 *  1. GET api/v1/services  → { engineBaseUrl, services: [{app,title,version,specUrl,serverUrl}] }
 *  2. Populate the topbar pulldown.
 *  3. Resolve the target from ?app=<contextRoot> (else the first service).
 *  4. Render it with Scalar, loading the document through our same-origin
 *     spec proxy (specUrl) and pointing "try it" at the engine tier (serverUrl).
 *
 * The pulldown navigates by URL so a selection is bookmarkable and the portal
 * can deep-link straight to a service with ?app=<contextRoot>.
 */
(function () {
	'use strict';

	var select = document.getElementById('svc-select');
	var notice = document.getElementById('api-notice');

	// Map Scalar's theming variables onto the shared brand tokens (defined by
	// /blade/portal/brand/brand.css, linked before this script). CSS custom
	// properties inherit into Scalar's subtree, so the var(--vorpal-*)
	// references resolve and follow the page's light or dark theme. Scalar is
	// held in its own light mode so it never switches palettes underneath the
	// tokens; Scalar's conventional HTTP-method badge colours are left alone.
	var BRAND_THEME_CSS = [
		// Both selectors get the same palette: Scalar hard-codes `dark-mode` on
		// its request-example / code panels even in light mode, so theming
		// only `.light-mode` left those panels stock-dark.
		'.light-mode, .dark-mode {',
		'  --scalar-color-1: var(--vorpal-ink-900);',
		'  --scalar-color-2: var(--vorpal-ink-700);',
		'  --scalar-color-3: var(--vorpal-slate-600);',
		'  --scalar-color-accent: var(--vorpal-purple);',
		'  --scalar-background-1: var(--vorpal-surface);',
		'  --scalar-background-2: var(--vorpal-surface-alt);',
		'  --scalar-background-3: var(--vorpal-slate-100);',
		'  --scalar-background-accent: var(--vorpal-purple-soft);',
		'  --scalar-border-color: var(--vorpal-divider);',
		'  --scalar-font: var(--vorpal-font-sans);',
		'  --scalar-font-code: var(--vorpal-font-mono);',
		'  --scalar-radius: var(--vorpal-radius-sm);',
		'  --scalar-radius-lg: var(--vorpal-radius-md);',
		'  --scalar-radius-xl: var(--vorpal-radius-md);',
		'  --scalar-sidebar-background-1: var(--vorpal-surface-alt);',
		'  --scalar-sidebar-color-1: var(--vorpal-ink-900);',
		'  --scalar-sidebar-color-2: var(--vorpal-slate-600);',
		'  --scalar-sidebar-border-color: var(--vorpal-slate-200);',
		'  --scalar-sidebar-item-hover-color: var(--vorpal-purple);',
		'  --scalar-sidebar-item-hover-background: var(--vorpal-purple-soft);',
		'  --scalar-sidebar-item-active-background: var(--vorpal-purple-100);',
		'  --scalar-sidebar-color-active: var(--vorpal-purple);',
		'  --scalar-sidebar-search-background: var(--vorpal-surface);',
		'  --scalar-sidebar-search-border-color: var(--vorpal-slate-300);',
		'  --scalar-sidebar-search-color: var(--vorpal-slate-600);',
		'}'
	].join('\n');

	/** A failure shows as the shared error message, anything else as a note. */
	function showNotice(title, body, isError) {
		notice.innerHTML = '';
		notice.className = isError ? 'vorpal-error' : 'vorpal-note';
		var t = document.createElement('strong');
		t.textContent = title;
		notice.appendChild(t);
		if (body) {
			var p = document.createElement('p');
			p.textContent = body;
			notice.appendChild(p);
		}
		notice.hidden = false;
	}

	function requestedApp() {
		return new URLSearchParams(window.location.search).get('app');
	}

	function renderScalar(svc) {
		// Scalar reads the document from our same-origin proxy (svc.specUrl);
		// baseServerURL points live "try it" requests at the engine tier so
		// they don't hit the AdminServer origin.
		Scalar.createApiReference('#scalar', {
			url: svc.specUrl,
			baseServerURL: svc.serverUrl,
			theme: 'none',            // no preset: BRAND_THEME_CSS maps brand.css instead
			darkMode: false,          // the brand tokens carry the theme, not Scalar
			hideDarkModeToggle: true, // the top bar's theme switch is the one control
			customCss: BRAND_THEME_CSS
		});
	}

	fetch('api/v1/services', { headers: { 'Accept': 'application/json' } })
		.then(function (r) {
			return r.json().then(function (body) {
				return { ok: r.ok, body: body };
			});
		})
		.then(function (res) {
			var services = (res.body && res.body.services) || [];

			if (!res.ok && res.body && res.body.error) {
				showNotice('API discovery unavailable', res.body.error, true);
			}

			if (services.length === 0) {
				select.innerHTML = '<option>No services found</option>';
				if (res.ok) {
					showNotice('No OpenAPI documents found',
						'No deployed app answered at <engineBaseUrl>/<contextRoot>/resources/openapi.json. ' +
						'Check that the target services are deployed and that engineBaseUrl in blade-api.json points at the engine tier (e.g. http://host:8001).');
				}
				return;
			}

			select.innerHTML = '';
			services.forEach(function (s) {
				var opt = document.createElement('option');
				opt.value = s.app;
				opt.textContent = s.version ? (s.title + '  (v' + s.version + ')') : s.title;
				select.appendChild(opt);
			});
			select.disabled = false;

			var requested = requestedApp();
			var current = services[0];
			if (requested) {
				var match = services.filter(function (s) { return s.app === requested; })[0];
				if (match) {
					current = match;
				} else {
					showNotice('Unknown service "' + requested + '"',
						'That app is not in the discovered list — showing ' + current.title + ' instead.');
				}
			}
			select.value = current.app;

			select.addEventListener('change', function () {
				window.location.search = '?app=' + encodeURIComponent(select.value);
			});

			renderScalar(current);
		})
		.catch(function (err) {
			select.innerHTML = '<option>Error</option>';
			showNotice('Could not load the service list', String(err), true);
		});
})();
