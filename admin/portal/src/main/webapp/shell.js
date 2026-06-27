/*
 * BLADE Portal launcher — fetch the live card deck from /api/v1/cards
 * and render it. No iframes, no client-side routing, no manifest
 * machinery. Each card is a full-page navigation target.
 *
 * The cards endpoint (PortalCardsResource) walks the AdminServer's
 * deployment registry via JMX, reads each app's name/tagline/description metadata
 * via the same JMX path, and returns one entry per deployed admin app
 * (kind:"app") plus one per deployed SIP service (kind:"service").
 * See memory note `portal-card-discovery` for the discovery flow.
 *
 * Two tiers render into two grids:
 *   - kind:"app"     → Administration Tools (navigate to the app's context-root)
 *   - kind:"service" → SIP Services (open the Configurator for that service)
 */

(function() {
	'use strict';

	const grid = document.getElementById('p-card-grid');
	const serviceGrid = document.getElementById('p-service-grid');
	const servicesSection = document.getElementById('p-services-section');
	const empty = document.getElementById('p-empty-state');
	const countEl = document.getElementById('p-card-count');

	async function loadCards() {
		try {
			const res = await fetch('api/v1/cards', { credentials: 'same-origin', cache: 'no-cache' });
			if (!res.ok) throw new Error('HTTP ' + res.status);
			const data = await res.json();
			return Array.isArray(data.cards) ? data.cards : [];
		} catch (err) {
			console.error('Portal: failed to load card deck:', err);
			countEl.textContent = 'Discovery failed';
			grid.innerHTML = ''
				+ '<div class="vorpal-callout vorpal-callout-warn" style="grid-column:1/-1;">'
				+ '<div class="vorpal-callout-title">Cannot reach the cards endpoint</div>'
				+ '<p>The portal couldn&rsquo;t read the deployment registry. '
				+ 'Check the AdminServer log for stack traces from <code>PortalCardsResource</code>. '
				+ '(' + err.message + ')</p></div>';
			return null;
		}
	}

	// Builds one card anchor. A service card opens the Configurator for that
	// service; an app card navigates to its own context-root.
	function buildCardEl(card) {
		const isService = card.kind === 'service';

		const a = document.createElement('a');
		a.className = 'vorpal-card' + (card.hasMetadata ? '' : ' p-card-barebones');
		a.href = isService
			? '/blade/configurator/?domain=' + encodeURIComponent(card.configuratorDomain)
			: '/' + card.contextRoot + '/';
		a.setAttribute('aria-label', card.name);

		// Icon = the app's favicon. Services have no webapp of their own, so
		// they always fall back to the shared Vorpal Boy. Single-shot
		// (onerror=null) so we don't loop if the fallback also fails.
		const icon = document.createElement('img');
		icon.className = 'vorpal-card-icon';
		icon.alt = '';
		icon.src = isService
			? '/blade/portal/brand/favicon.svg'
			: '/' + card.contextRoot + '/favicon.svg';
		icon.setAttribute('onerror',
			"this.onerror=null; this.src='/blade/portal/brand/favicon.svg';");
		a.appendChild(icon);

		// Body column flows to the right of the icon (see portal.css).
		const body = document.createElement('div');
		body.className = 'p-card-body';

		const h3 = document.createElement('h3');
		h3.textContent = card.name;
		body.appendChild(h3);

		if (card.tagline) {
			const tag = document.createElement('div');
			tag.className = 'p-card-tagline';
			tag.textContent = card.tagline;
			body.appendChild(tag);
		}

		if (card.description) {
			const p = document.createElement('p');
			p.textContent = card.description;
			body.appendChild(p);
		}

		const link = document.createElement('span');
		link.className = 'vorpal-card-link';
		link.textContent = isService ? 'Configure' : 'Open';
		body.appendChild(link);

		a.appendChild(body);
		return a;
	}

	function render(cards) {
		grid.innerHTML = '';
		serviceGrid.innerHTML = '';

		const apps = cards.filter(c => c.kind !== 'service');
		const services = cards.filter(c => c.kind === 'service');

		// Apps tier.
		if (apps.length === 0) {
			empty.style.display = '';
		} else {
			empty.style.display = 'none';
			apps.forEach(card => grid.appendChild(buildCardEl(card)));
		}

		// Services tier — hide the whole section when none are deployed.
		if (services.length === 0) {
			servicesSection.style.display = 'none';
		} else {
			servicesSection.style.display = '';
			services.forEach(card => serviceGrid.appendChild(buildCardEl(card)));
		}

		const appLabel = apps.length + (apps.length === 1 ? ' app' : ' apps');
		const svcLabel = services.length + (services.length === 1 ? ' service' : ' services');
		countEl.textContent = appLabel + ' · ' + svcLabel;
	}

	(async function start() {
		const cards = await loadCards();
		if (cards !== null) render(cards);
	})();
})();
