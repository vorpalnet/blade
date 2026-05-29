/*
 * BLADE Portal launcher — fetch the live card deck from /api/v1/cards
 * and render it. No iframes, no client-side routing, no manifest
 * machinery. Each card is a full-page navigation target.
 *
 * The cards endpoint (PortalCardsResource) walks the AdminServer's
 * deployment registry via JMX, reads each app's name/tagline/description metadata
 * via the same JMX path, and returns one entry per deployed admin app.
 * See memory note `portal-card-discovery` for the discovery flow.
 */

(function() {
	'use strict';

	const grid = document.getElementById('p-card-grid');
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

	function render(cards) {
		grid.innerHTML = '';
		if (cards.length === 0) {
			empty.style.display = '';
			countEl.textContent = '0 apps';
			return;
		}
		empty.style.display = 'none';
		countEl.textContent = cards.length + (cards.length === 1 ? ' app' : ' apps');

		cards.forEach(card => {
			const a = document.createElement('a');
			a.className = 'vorpal-card' + (card.hasMetadata ? '' : ' p-card-barebones');
			a.href = '/' + card.contextRoot + '/';
			a.setAttribute('aria-label', card.name);

			// Icon = the app's favicon. If the app doesn't ship one,
			// onerror falls back to the shared Vorpal Boy. Single-shot
			// (onerror=null) so we don't loop if the fallback also fails.
			const icon = document.createElement('img');
			icon.className = 'vorpal-card-icon';
			icon.alt = '';
			icon.src = '/' + card.contextRoot + '/favicon.svg';
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
			link.textContent = 'Open';
			body.appendChild(link);

			a.appendChild(body);

			grid.appendChild(a);
		});
	}

	(async function start() {
		const cards = await loadCards();
		if (cards !== null) render(cards);
	})();
})();
