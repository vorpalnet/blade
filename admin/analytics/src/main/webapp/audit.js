/*
 * Fetches /api/audit, renders one row per resource finding. State is
 * communicated by glyph + word + position (border edge), never by hue alone.
 */

(function () {
	const findingsEl = document.getElementById('findings');
	const errorEl = document.getElementById('error');
	const overallPill = document.getElementById('overall-pill');
	const refreshBtn = document.getElementById('refresh-btn');
	const expCf = document.getElementById('exp-cf');
	const expQ = document.getElementById('exp-q');
	const expDs = document.getElementById('exp-ds');

	function setOverall(state, label) {
		overallPill.className = 'vorpal-pill pill-' + state;
		overallPill.textContent = label;
	}

	function renderFindings(data) {
		findingsEl.innerHTML = '';
		errorEl.hidden = true;

		expCf.textContent = data.expected.connectionFactoryJndi;
		expQ.textContent = data.expected.queueJndi;
		expDs.textContent = data.expected.dataSourceJndi;

		for (const f of data.findings) {
			const row = document.createElement('div');
			const state = f.present ? 'ok' : 'missing';
			row.className = 'audit-row ' + state;

			const left = document.createElement('div');
			const badge = document.createElement('span');
			badge.className = 'audit-badge ' + state;
			badge.textContent = f.present ? 'OK' : 'MISSING';
			left.appendChild(badge);

			const body = document.createElement('div');
			body.className = 'audit-row-body';
			const h3 = document.createElement('h3');
			h3.textContent = f.label;
			const p = document.createElement('p');
			p.textContent = f.detail;
			body.appendChild(h3);
			body.appendChild(p);

			row.appendChild(left);
			row.appendChild(body);
			findingsEl.appendChild(row);
		}

		if (data.ready) {
			setOverall('ok', 'Pipeline ready');
		} else {
			const missing = data.findings.filter(f => !f.present).length;
			setOverall('missing', missing + ' resource' + (missing === 1 ? '' : 's') + ' missing');
		}
	}

	function renderError(msg) {
		findingsEl.innerHTML = '';
		errorEl.hidden = false;
		errorEl.textContent = msg;
		setOverall('error', 'Audit failed');
	}

	function fetchAudit() {
		setOverall('missing', 'Auditing…');
		fetch('api/audit')
			.then(r => {
				if (!r.ok) {
					return r.text().then(t => { throw new Error('HTTP ' + r.status + ': ' + t); });
				}
				return r.json();
			})
			.then(renderFindings)
			.catch(err => renderError(String(err)));
	}

	refreshBtn.addEventListener('click', fetchAudit);
	fetchAudit();
})();
