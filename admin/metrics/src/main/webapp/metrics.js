/* BLADE Metrics console. Vanilla JS, no framework, no build step. */
(function () {
	'use strict';

	var API = 'api/v1/metrics';
	var timer = null;

	function el(id) { return document.getElementById(id); }

	function esc(v) {
		return String(v === null || v === undefined ? '' : v)
			.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
	}

	function n(v) { return Number(v || 0).toLocaleString(); }

	/** Per-second rate over the app's uptime. A raw total says nothing without
	 *  the window it accumulated over, which is why uptime is on the wire. */
	function rate(total, uptimeMillis) {
		if (!uptimeMillis) { return ''; }
		var perSec = (Number(total) * 1000) / Number(uptimeMillis);
		if (perSec >= 1) { return perSec.toFixed(1) + '/s'; }
		if (perSec > 0) { return (perSec * 60).toFixed(1) + '/min'; }
		return '';
	}

	function load() {
		fetch(API, { credentials: 'same-origin' })
			.then(function (r) { return r.json(); })
			.then(render)
			.catch(function (e) {
				el('error').hidden = false;
				el('error-detail').textContent = String(e);
				el('status-pill').textContent = 'Error';
			});
	}

	function render(data) {
		el('error').hidden = !data.error;
		if (data.error) { el('error-detail').textContent = data.error; }

		var apps = data.apps || [];
		var host = el('apps');
		host.innerHTML = '';
		el('empty').hidden = apps.length > 0;

		apps.forEach(function (app) {
			host.appendChild(renderApp(app));
		});

		el('status-pill').textContent = apps.length + (apps.length === 1 ? ' app' : ' apps');
	}

	function renderApp(app) {
		var panel = document.createElement('div');
		panel.className = 'ev-panel m-app';

		var nodes = (app.nodes || []).map(function (x) { return x.node; }).join(', ');
		panel.innerHTML = '<header><span>' + esc(app.app) + '</span>'
			+ '<span class="m-nodes ev-muted">' + (app.nodes || []).length + ' node(s): ' + esc(nodes) + '</span>'
			+ '</header><div class="ev-body"></div>';
		var body = panel.querySelector('.ev-body');

		(app.counters || []).forEach(function (c) { body.appendChild(renderCounter(c, app.uptimeMillis)); });
		(app.gauges || []).forEach(function (g) { body.appendChild(renderGauge(g)); });
		(app.histograms || []).forEach(function (h) { body.appendChild(renderHistogram(h)); });

		if (!(app.counters || []).length && !(app.gauges || []).length && !(app.histograms || []).length) {
			var none = document.createElement('p');
			none.className = 'ev-muted';
			none.textContent = 'Registered, but has not recorded anything yet.';
			body.appendChild(none);
		}
		return panel;
	}

	function renderCounter(counter, uptime) {
		var div = document.createElement('div');
		div.style.marginBottom = '.75rem';

		var head = '<div><strong>' + esc(counter.name) + '</strong> '
			+ '<span class="ev-muted">' + n(counter.total) + ' ' + esc(rate(counter.total, uptime)) + '</span></div>';
		if (counter.description) {
			head += '<div class="ev-muted">' + esc(counter.description) + '</div>';
		}

		var values = counter.values || {};
		var names = Object.keys(values);
		if (names.length) {
			var max = 0;
			names.forEach(function (k) { max = Math.max(max, Number(values[k])); });
			head += '<table class="ev-table">';
			names.forEach(function (k) {
				var value = Number(values[k]);
				var width = max ? Math.max(1, Math.round((value / max) * 160)) : 1;
				head += '<tr><td>' + esc(k) + '</td>'
					+ '<td class="ev-num">' + n(value) + '</td>'
					+ '<td><span class="m-bar" style="width:' + width + 'px"></span></td></tr>';
			});
			head += '</table>';
		}
		div.innerHTML = head;
		return div;
	}

	function renderGauge(gauge) {
		var div = document.createElement('div');
		div.style.marginBottom = '.5rem';
		div.innerHTML = '<div><strong>' + esc(gauge.name) + '</strong> '
			+ '<span class="ev-muted">' + n(gauge.value) + '</span></div>'
			+ (gauge.description ? '<div class="ev-muted">' + esc(gauge.description) + '</div>' : '');
		return div;
	}

	function renderHistogram(h) {
		var div = document.createElement('div');
		div.style.marginBottom = '.75rem';

		var counts = h.bucketCounts || [];
		var bounds = h.boundsMillis || [];
		var max = 0;
		counts.forEach(function (c) { max = Math.max(max, Number(c)); });

		var bars = '<div class="m-hist">';
		counts.forEach(function (c) {
			var pct = max ? Math.max(2, Math.round((Number(c) / max) * 100)) : 1;
			bars += '<div style="height:' + pct + '%" title="' + n(c) + '"></div>';
		});
		bars += '</div><div class="m-hist-axis">';
		bounds.forEach(function (b) { bars += '<div>' + b + '</div>'; });
		bars += '<div>+</div></div>';

		div.innerHTML = '<div><strong>' + esc(h.name) + '</strong> '
			+ '<span class="ev-muted">' + n(h.count) + ' samples · '
			+ 'p50 ' + n(h.p50Millis) + 'ms · p90 ' + n(h.p90Millis) + 'ms · p99 ' + n(h.p99Millis) + 'ms · '
			+ 'max ' + n(h.maxMillis) + 'ms</span></div>'
			+ (h.description ? '<div class="ev-muted">' + esc(h.description) + '</div>' : '')
			+ bars
			+ (h.mismatchedBounds
				? '<div class="ev-finding is-problem"><div class="ev-kind">✕ mismatched buckets</div>'
					+ '<div class="ev-detail">A node reported different bucket boundaries — likely a rolling '
					+ 'upgrade in progress. Its samples are excluded rather than summed into a plausible, '
					+ 'wrong distribution.</div></div>'
				: '');
		return div;
	}

	el('refresh').addEventListener('click', load);
	el('auto').addEventListener('change', function () {
		if (timer) { clearInterval(timer); timer = null; }
		if (el('auto').checked) { timer = setInterval(load, 10000); }
	});

	load();
})();
