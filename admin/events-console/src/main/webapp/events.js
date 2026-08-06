/* BLADE Events console.
 *
 * Vanilla JS, no framework, no build step — the house style across every admin
 * app. State is signalled by glyph + word + border position, never by hue
 * alone.
 */
var events = (function () {
	'use strict';

	var API = 'api/v1';

	function el(id) { return document.getElementById(id); }

	function pill(text) {
		var p = el('status-pill');
		if (p) { p.textContent = text; }
	}

	function text(value, fallback) {
		return (value === null || value === undefined || value === '') ? (fallback || '—') : String(value);
	}

	/** Escape before inserting anything server-supplied into markup. */
	function esc(value) {
		return String(value === null || value === undefined ? '' : value)
			.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;');
	}

	function getJson(path) {
		return fetch(path, { credentials: 'same-origin' }).then(function (r) {
			return r.json().catch(function () {
				// A WebLogic HTML error page would land here. Say so rather than
				// throwing an opaque parse error.
				throw new Error('server did not return JSON (HTTP ' + r.status + ')');
			});
		});
	}

	function postJson(path, body) {
		return fetch(path, {
			method: 'POST',
			credentials: 'same-origin',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(body)
		}).then(function (r) {
			return r.json().catch(function () {
				throw new Error('server did not return JSON (HTTP ' + r.status + ')');
			});
		});
	}

	function rows(tableId) {
		return el(tableId).querySelector('tbody');
	}

	function num(value) {
		return '<td class="ev-num">' + esc(value === undefined ? 0 : value) + '</td>';
	}

	// ─────────────────────────────────────────────────────────── Catalog page

	function initCatalog() {
		var refresh = el('refresh');
		if (refresh) { refresh.addEventListener('click', loadCatalog); }
		loadCatalog();
	}

	function loadCatalog() {
		pill('Loading…');
		getJson(API + '/catalog').then(function (catalog) {
			var types = catalog.types || [];
			var body = rows('types-table');
			body.innerHTML = '';

			types.forEach(function (t) {
				var tr = document.createElement('tr');
				tr.innerHTML =
					'<td><code>' + esc(t.type) + '</code></td>' +
					'<td>' + esc(text(t.title)) + '</td>' +
					'<td><code>' + esc(text(t.destinationJndi, catalog.defaultDestinationJndi)) + '</code></td>' +
					'<td>' + esc(text(t.destinationKind, 'TOPIC')) + '</td>' +
					num((t.fields || []).length) +
					'<td><code>eventType = \'' + esc(t.type) + '\'</code></td>';
				body.appendChild(tr);
			});

			el('type-count').textContent = types.length + (types.length === 1 ? ' type' : ' types');
			el('types-empty').hidden = types.length > 0;
			el('catalog-source').textContent = catalog.published
				? 'Published catalog, read from config/custom/vorpal/events.json.'
				: 'No catalog published yet — showing the event types the framework itself emits.';
			pill(catalog.published ? 'Catalog published' : 'Defaults');
			return loadDrift();
		}).catch(function (e) {
			pill('Error');
			el('catalog-source').textContent = String(e);
		});
	}

	function loadDrift() {
		return getJson(API + '/jms/drift').then(function (report) {
			var body = el('drift-body');
			var findings = report.findings || [];
			body.innerHTML = '';

			if (report.error) {
				body.innerHTML = '<div class="ev-finding is-problem"><div class="ev-kind">✕ Cannot read JMS</div>' +
					'<div class="ev-detail">' + esc(report.error) + '</div></div>';
				el('drift-count').textContent = 'unavailable';
				return;
			}

			if (!findings.length) {
				body.innerHTML = '<div class="ev-finding"><div class="ev-kind">✓ Agreed</div>' +
					'<div class="ev-detail">Every declared type has a destination, every declared ' +
					'subscription has a live durable subscriber carrying the selector it derives, ' +
					'and no subscription is live that the catalog does not name.</div></div>';
				el('drift-count').textContent = 'no drift';
				return;
			}

			findings.forEach(function (f) {
				var div = document.createElement('div');
				// "not-deployed" is expected right after a catalog edit and is not
				// yet a problem, so it is marked apart from the rest — by glyph and
				// by border weight, never by colour alone.
				var expected = f.kind === 'not-deployed';
				div.className = 'ev-finding' + (expected ? '' : ' is-problem');
				div.innerHTML = '<div class="ev-kind">' + (expected ? '○ ' : '✕ ') +
					esc(f.kind) + ' — ' + esc(f.subject) + '</div>' +
					'<div class="ev-detail">' + esc(f.detail) + '</div>';
				body.appendChild(div);
			});
			el('drift-count').textContent = findings.length + (findings.length === 1 ? ' finding' : ' findings');
		});
	}

	// ────────────────────────────────────────────────────── Destinations page

	function initDestinations() {
		var refresh = el('refresh');
		if (refresh) { refresh.addEventListener('click', loadInventory); }

		el('new-destination').addEventListener('click', function () {
			var panel = el('create-panel');
			panel.hidden = !panel.hidden;
			if (!panel.hidden) { loadTargets(); }
		});
		el('c-cancel').addEventListener('click', function () { el('create-panel').hidden = true; });
		el('c-create').addEventListener('click', createDestination);
		el('browse-close').addEventListener('click', function () { el('browse-panel').hidden = true; });

		loadInventory();
	}

	/** Show a step log from a write operation. */
	function showLog(result) {
		var pre = el('action-log');
		pre.hidden = false;
		if (result.error) {
			pre.textContent = '✕ ' + result.error;
			return false;
		}
		pre.textContent = (result.steps || ['done']).join('\n');
		return true;
	}

	function loadTargets() {
		getJson(API + '/admin/targets').then(function (targets) {
			var select = el('c-targets');
			select.innerHTML = '<option value="">(the only cluster)</option>';
			(targets || []).forEach(function (t) {
				var option = document.createElement('option');
				option.value = t.name;
				option.textContent = t.name + ' (' + t.kind + ')';
				select.appendChild(option);
			});
		}).catch(function () {
			// A picker that cannot load still leaves the default, which resolves
			// to the sole cluster server-side.
		});
	}

	function createDestination() {
		var target = el('c-targets').value;
		postJson(API + '/admin/destinations', {
			name: el('c-name').value.trim(),
			jndiName: el('c-jndi').value.trim(),
			kind: el('c-kind').value,
			quotaBytes: Number(el('c-quota').value) || 1073741824,
			targets: target ? [target] : []
		}).then(function (result) {
			if (showLog(result)) {
				el('create-panel').hidden = true;
				loadInventory();
			}
		}).catch(function (e) { showLog({ error: String(e) }); });
	}

	function control(name, operation) {
		postJson(API + '/admin/destinations/control', { name: name, operation: operation })
			.then(function (result) {
				if (showLog(result)) { loadInventory(); }
			}).catch(function (e) { showLog({ error: String(e) }); });
	}

	/* Destructive actions type-to-confirm rather than OK/Cancel, and the prompt
	 * states the pending count — an OK/Cancel dialog is muscle memory, typing a
	 * name is not. */
	function purge(name, pending) {
		var typed = window.prompt('Purging deletes messages permanently.\n\n"' + name + '" currently has '
			+ pending + ' pending.\n\nType the destination name to confirm:');
		if (typed !== name) { return; }
		postJson(API + '/admin/destinations/purge', { name: name, jndiName: '', selector: '' })
			.then(function (result) {
				if (result.error) { showLog(result); return; }
				showLog({ steps: ['✓ deleted ' + result.deleted + ' messages from ' + name] });
				loadInventory();
			}).catch(function (e) { showLog({ error: String(e) }); });
	}

	function destroy(name, jndiName, kind, pending) {
		var typed = window.prompt('Deleting a destination is irreversible.\n\n"' + name + '" currently has '
			+ pending + ' pending messages.\n\nType the destination name to confirm:');
		if (typed !== name) { return; }
		postJson(API + '/admin/destinations/delete', { name: name, jndiName: jndiName, kind: kind })
			.then(function (result) {
				if (showLog(result)) { loadInventory(); }
			}).catch(function (e) { showLog({ error: String(e) }); });
	}

	function browse(name) {
		el('browse-panel').hidden = false;
		el('browse-title').textContent = 'Messages on ' + name;
		el('browse-body').textContent = 'Reading…';
		getJson(API + '/admin/destinations/browse?name=' + encodeURIComponent(name) + '&count=25')
			.then(function (page) {
				if (page.error) {
					el('browse-body').textContent = '✕ ' + page.error;
					return;
				}
				el('browse-body').textContent = (page.messages && page.messages.length)
					? (page.total + ' message(s) at rest; showing ' + page.messages.length + '\n\n'
						+ JSON.stringify(page.messages, null, 2))
					: 'Nothing at rest on this destination.';
			}).catch(function (e) { el('browse-body').textContent = '✕ ' + e; });
	}

	/** Small inline button. */
	function action(label, title) {
		return '<button type="button" class="vorpal-btn vorpal-btn-secondary" data-act="' + label
			+ '" title="' + esc(title) + '" style="padding:.15rem .45rem;font-size:12px">' + label + '</button> ';
	}

	function loadInventory() {
		pill('Loading…');
		getJson(API + '/jms/inventory').then(function (inv) {
			if (inv.error) {
				el('jms-error').hidden = false;
				el('jms-error-detail').textContent = inv.error;
				pill('Error');
			} else {
				el('jms-error').hidden = true;
			}

			var destinations = inv.destinations || [];
			var dBody = rows('destinations-table');
			dBody.innerHTML = '';
			var subs = [];

			destinations.forEach(function (d) {
				var tr = document.createElement('tr');
				tr.innerHTML =
					'<td>' + esc(d.name) + '</td>' +
					'<td><code>' + esc(text(d.jndiName)) + '</code></td>' +
					'<td>' + esc(text(d.module)) + '</td>' +
					'<td>' + esc(text(d.kind)) + '</td>' +
					num(d.messagesCurrent) + num(d.messagesPending) +
					num(d.messagesReceived) + num(d.consumersCurrent) +
					'<td>' + (d.live ? esc(text(d.state, 'running')) : '— not running') + '</td>' +
					'<td>' +
					action(d.paused ? 'resume' : 'pause', 'Pause or resume production and consumption') +
					action('browse', 'Read messages at rest without consuming them') +
					action('purge', 'Delete messages permanently') +
					action('delete', 'Destroy the destination') +
					'</td>';
				dBody.appendChild(tr);

				// Wire the row's buttons to the row's own data, so nothing has
				// to be looked up again by name at click time.
				Array.prototype.forEach.call(tr.querySelectorAll('button[data-act]'), function (button) {
					button.addEventListener('click', function () {
						switch (button.getAttribute('data-act')) {
						case 'pause':   control(d.name, 'pause'); break;
						case 'resume':  control(d.name, 'resume'); break;
						case 'browse':  browse(d.name); break;
						case 'purge':   purge(d.name, d.messagesPending); break;
						case 'delete':
							destroy(d.name, d.jndiName,
								(d.kind || '').indexOf('Queue') >= 0 ? 'QUEUE' : 'TOPIC',
								d.messagesPending);
							break;
						default: break;
						}
					});
				});

				(d.durableSubscribers || []).forEach(function (s) {
					s._destination = d.name;
					subs.push(s);
				});
			});

			el('destination-count').textContent = destinations.length +
				(destinations.length === 1 ? ' destination' : ' destinations');
			el('destinations-empty').hidden = destinations.length > 0;

			var sBody = rows('subscriptions-table');
			sBody.innerHTML = '';
			subs.forEach(function (s) {
				var tr = document.createElement('tr');
				tr.innerHTML =
					'<td>' + esc(text(s.subscriptionName)) + '</td>' +
					'<td>' + esc(text(s._destination)) + '</td>' +
					'<td>' + esc(text(s.clientId)) + '</td>' +
					'<td><code>' + esc(text(s.selector, '(everything)')) + '</code></td>' +
					'<td>' + (s.active ? '✓ active' : '✕ inactive') + '</td>' +
					num(s.messagesPending) +
					'<td>' + action('purge', 'Drop this subscription\'s backlog, leaving the subscription in place') + '</td>';
				sBody.appendChild(tr);

				tr.querySelector('button[data-act]').addEventListener('click', function () {
					var typed = window.prompt('Purging drops this subscription\'s backlog permanently.\n\n"'
						+ s.subscriptionName + '" has ' + s.messagesPending
						+ ' pending.\n\nType the subscription name to confirm:');
					if (typed !== s.subscriptionName) { return; }
					postJson(API + '/admin/subscriptions/purge',
						{ destination: s._destination, subscription: s.subscriptionName })
						.then(function (result) {
							if (showLog(result)) { loadInventory(); }
						}).catch(function (e) { showLog({ error: String(e) }); });
				});
			});
			el('subscription-count').textContent = subs.length +
				(subs.length === 1 ? ' subscription' : ' subscriptions');
			el('subscriptions-empty').hidden = subs.length > 0;

			var servers = inv.servers || [];
			var svBody = rows('servers-table');
			svBody.innerHTML = '';
			servers.forEach(function (s) {
				var tr = document.createElement('tr');
				tr.innerHTML =
					'<td>' + esc(s.name) + '</td>' +
					'<td>' + esc(text(s.persistentStore)) + '</td>' +
					'<td>' + esc((s.targets || []).join(', ') || '—') + '</td>' +
					num(s.destinationsCurrent) + num(s.messagesCurrent) + num(s.messagesPending) +
					'<td>' + esc(text(s.health)) + '</td>';
				svBody.appendChild(tr);
			});
			el('server-count').textContent = servers.length + (servers.length === 1 ? ' server' : ' servers');

			if (!inv.error) { pill(destinations.length + ' destinations'); }
		}).catch(function (e) {
			pill('Error');
			el('jms-error').hidden = false;
			el('jms-error-detail').textContent = String(e);
		});
	}

	// ────────────────────────────────────────────────────────── Designer page

	var preview = {};
	var activeTab = 'java';

	function initDesigner() {
		['d-type', 'd-title', 'd-description', 'd-package', 'd-class', 'd-destination'].forEach(function (id) {
			el(id).addEventListener('input', schedule);
		});
		['s-name', 's-description', 's-package'].forEach(function (id) {
			el(id).addEventListener('input', schedule);
		});
		el('d-kind').addEventListener('change', schedule);
		el('d-persist').addEventListener('change', schedule);
		el('s-mode').addEventListener('change', schedule);
		el('s-durable').addEventListener('change', schedule);
		el('d-add-field').addEventListener('click', function () { addFieldRow(); schedule(); });

		Array.prototype.forEach.call(document.querySelectorAll('.ev-tab'), function (tab) {
			tab.addEventListener('click', function () {
				activeTab = tab.getAttribute('data-tab');
				Array.prototype.forEach.call(document.querySelectorAll('.ev-tab'), function (t) {
					t.setAttribute('aria-selected', String(t === tab));
				});
				render();
			});
		});

		el('d-copy').addEventListener('click', function () {
			var body = el('d-preview').textContent;
			if (navigator.clipboard) { navigator.clipboard.writeText(body); }
		});
		el('d-download').addEventListener('click', download);
		el('s-download').addEventListener('click', downloadSubscription);

		// The long-form prose lives in the <dialog>; close is a method="dialog"
		// form inside it, so only opening needs script.
		el('d-help-open').addEventListener('click', function () { el('d-help').showModal(); });

		addFieldRow();
		schedule();
	}

	function addFieldRow(name, type, required) {
		var row = document.createElement('div');
		row.className = 'ev-field-row';
		row.innerHTML =
			'<input type="text" placeholder="wire name, e.g. when_text" value="' + esc(name || '') + '">' +
			'<select>' +
			['STRING', 'INTEGER', 'LONG', 'NUMBER', 'BOOLEAN', 'INSTANT', 'ENUM', 'OBJECT', 'ARRAY']
				.map(function (t) {
					return '<option' + (t === (type || 'STRING') ? ' selected' : '') + '>' + t + '</option>';
				}).join('') +
			'</select>' +
			'<label class="ev-muted"><input type="checkbox"' + (required ? ' checked' : '') + '> required</label>' +
			'<button type="button" class="vorpal-btn vorpal-btn-secondary">Remove</button>';

		row.querySelector('button').addEventListener('click', function () {
			row.remove();
			schedule();
		});
		Array.prototype.forEach.call(row.querySelectorAll('input, select'), function (input) {
			input.addEventListener('input', schedule);
			input.addEventListener('change', schedule);
		});
		el('d-fields').appendChild(row);
	}

	function declaration() {
		var fields = [];
		Array.prototype.forEach.call(el('d-fields').children, function (row) {
			var name = row.querySelector('input[type=text]').value.trim();
			if (!name) { return; }
			fields.push({
				name: name,
				type: row.querySelector('select').value,
				required: row.querySelector('input[type=checkbox]').checked
			});
		});
		return {
			type: el('d-type').value.trim(),
			title: el('d-title').value.trim() || null,
			description: el('d-description').value.trim() || null,
			javaPackage: el('d-package').value.trim() || null,
			javaClassName: el('d-class').value.trim() || null,
			destinationJndi: el('d-destination').value.trim() || null,
			destinationKind: el('d-kind').value,
			persist: el('d-persist').checked,
			fields: fields
		};
	}

	/**
	 * The subscriber being authored alongside the declaration.
	 *
	 * `types` is always the one event type on this page: the designer is a
	 * worked example of one event and one consumer of it. A subscriber spanning
	 * several types is a catalog edit, not a designer one.
	 */
	function subscription() {
		var name = el('s-name').value.trim();
		if (!name) { return null; }
		var type = el('d-type').value.trim();
		return {
			name: name,
			description: el('s-description').value.trim() || null,
			javaPackage: el('s-package').value.trim() || null,
			types: type ? [type] : [],
			durable: el('s-durable').checked,
			selectorMode: el('s-mode').value
		};
	}

	/** The subscription plus the one-type catalog its consumer is generated against. */
	function subscriptionRequest() {
		return { subscription: subscription(), catalog: { types: [declaration()] } };
	}

	var pending = null;

	/** Debounce: the preview regenerates on every keystroke, so coalesce. */
	function schedule() {
		if (pending) { clearTimeout(pending); }
		pending = setTimeout(refreshPreview, 180);
	}

	function refreshPreview() {
		postJson(API + '/designer/preview', declaration()).then(function (result) {
			if (result.error) {
				preview = { java: result.error };
				render();
				return;
			}
			preview = result;
			el('d-selector').textContent = result.selector || 'no type yet';
			refreshConsumer();
			render();
		}).catch(function (e) {
			preview = { java: String(e) };
			render();
		});
	}

	/**
	 * The consumer is generated from the SUBSCRIPTION, so it is a second call.
	 * Without a subscription name there is nothing to generate: the client id,
	 * the durable subscription name and the class name all come from it.
	 */
	function refreshConsumer() {
		if (!subscription()) {
			preview.consumer = 'Name a subscriber above.\n\n'
				+ 'A consumer belongs to a subscriber, not to an event. Several\n'
				+ 'applications may want this same event — the transfer app acting on\n'
				+ 'it while analytics records it — and each needs its own subscription\n'
				+ 'identity to receive its own copy.';
			showFindings([]);
			render();
			return;
		}
		postJson(API + '/designer/preview-subscription', subscriptionRequest()).then(function (result) {
			preview.consumer = result.error || result.consumer;
			showFindings(result.findings || []);
			render();
		}).catch(function (e) {
			preview.consumer = String(e);
			render();
		});
	}

	function showFindings(findings) {
		var box = el('d-findings');
		if (!findings.length) {
			box.hidden = true;
			box.textContent = '';
			return;
		}
		box.hidden = false;
		box.innerHTML = findings.map(function (f) {
			return '<div class="ev-finding">' + esc(f) + '</div>';
		}).join('');
	}

	function render() {
		var value = preview[activeTab];
		if (activeTab === 'schema' && value && typeof value === 'object') {
			value = JSON.stringify(value, null, 2);
		}
		el('d-preview').textContent = value || 'Start typing an event type…';
	}

	function downloadSubscription() {
		if (!subscription()) {
			pill('Name a subscriber first');
			return;
		}
		blobDownload(API + '/designer/download-subscription', subscriptionRequest(),
			(el('s-name').value.trim().replace(/[^A-Za-z0-9]/g, '-').toLowerCase() || 'consumer') + '-consumer.zip');
	}

	function download() {
		blobDownload(API + '/designer/download', declaration(),
			(el('d-type').value.trim().replace(/[^A-Za-z0-9]/g, '-').toLowerCase() || 'event') + '.zip');
	}

	function blobDownload(url, body, filename) {
		fetch(url, {
			method: 'POST',
			credentials: 'same-origin',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(body)
		}).then(function (r) {
			if (!r.ok) { throw new Error('HTTP ' + r.status); }
			return r.blob();
		}).then(function (blob) {
			var href = URL.createObjectURL(blob);
			var a = document.createElement('a');
			a.href = href;
			a.download = filename;
			document.body.appendChild(a);
			a.click();
			a.remove();
			URL.revokeObjectURL(href);
		}).catch(function (e) {
			pill('Download failed: ' + e);
		});
	}

	return {
		initCatalog: initCatalog,
		initDestinations: initDestinations,
		initDesigner: initDesigner
	};
})();
