// FSMAR Flow Editor — service-plan dispatch support
//
// A "dispatch" is a family of same-state, same-method transitions whose
// conditions all test one variable for equality — the gold/silver/bronze
// pattern: `${tier} == 'gold'`, `${tier} == 'silver'`, plus one
// unconditional default. Conditions of that shape are mutually exclusive by
// construction, so first-match-wins evaluation order stops mattering —
// which is exactly what makes them safe to author visually.
//
// This module is recognition + sugar only. Nothing here is stored in the
// FSMAR JSON beyond plain transitions; hand-written configs that follow the
// pattern light up identically. (Classification — which caller IS gold —
// stays table data: a TableSelector row set operators edit in the
// Configurator. RELEASE.md: "Gold/silver/bronze is one config plus a table
// operators edit.")

window.flowPlans = (function() {

	// Exactly  ${var} == 'value'   (or bare unquoted value).
	// Anything more complex (&&, matches, !=) is not a dispatch condition.
	var DISPATCH_RE = /^\s*\$\{([A-Za-z0-9_.]+)\}\s*==\s*(?:'([^']*)'|([A-Za-z0-9_.\-]+))\s*$/;

	function parseDispatch(when) {
		if (!when) return null;
		var m = DISPATCH_RE.exec(when);
		if (!m) return null;
		return { variable: m[1], value: (m[2] !== undefined ? m[2] : m[3]) };
	}

	// True when other Transition edges with the same source and SIP method
	// exist — i.e. this edge is part of an ordered family.
	function hasSiblings(cell) {
		if (!cell || !cell.source || !cell.source.edges) return false;
		var method = cell.getAttribute('label') || '';
		for (var i = 0; i < cell.source.edges.length; i++) {
			var e = cell.source.edges[i];
			if (e !== cell && e.source === cell.source
					&& e.value && e.value.tagName === 'Transition'
					&& (e.getAttribute('label') || '') === method) {
				return true;
			}
		}
		return false;
	}

	// Canvas display label for a Transition edge. Derived only — the stored
	// `label` attribute stays the bare SIP method.
	function displayLabel(cell) {
		var method = cell.getAttribute('label') || '';
		var when = cell.getAttribute('when') || '';
		var d = parseDispatch(when);
		if (d) return method + ' · ' + d.variable + '=' + d.value;
		if (!when && hasSiblings(cell)) return method + ' · default';
		return method;
	}

	// ----- authoring dialog ---------------------------------------------------

	var SIP_METHODS = ['INVITE', 'REGISTER', 'SUBSCRIBE', 'OPTIONS', 'MESSAGE',
			'PUBLISH', 'REFER', 'NOTIFY'];

	function rowHtml(value, app) {
		return '<div class="plan-row" style="display:flex; gap:4px; margin-top:4px;">' +
			'<input class="plan-value" type="text" placeholder="gold" value="' + esc(value) + '" style="flex:1;" />' +
			'<span style="align-self:center;">&rarr;</span>' +
			'<input class="plan-app" type="text" placeholder="premium-screening" value="' + esc(app) + '" style="flex:2;" />' +
			'<svg style="color:#c00; cursor:pointer; align-self:center;" title="Remove plan" ' +
				'viewBox="0 0 16 16" width="14" height="14" ' +
				'onclick="this.parentNode.parentNode.removeChild(this.parentNode);">' +
				'<circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/>' +
				'<path d="M4.5 8h7" stroke="currentColor" stroke-width="1.5"/></svg>' +
			'</div>';
	}

	function esc(s) {
		return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
	}

	function showDispatchDialog(sourceCell) {
		var div = document.createElement('div');
		div.style.padding = '10px';
		div.style.fontSize = '12px';
		div.style.fontFamily = 'Arial';

		var methodOptions = SIP_METHODS.map(function(m) {
			return '<option>' + m + '</option>';
		}).join('');

		div.innerHTML =
			'<div style="margin-bottom:8px;">Creates one transition per plan with a ' +
				'mutually exclusive condition (<code>${var} == \'value\'</code>) plus an ' +
				'optional default &mdash; evaluation order cannot matter. The variable is ' +
				'published by a selector (typically a table selector mapping callers to plans).</div>' +
			'<label><b>Variable</b></label><br/>' +
			'<input id="plan-variable" type="text" value="tier" style="width:100%; box-sizing:border-box;" /><br/>' +
			'<label style="margin-top:6px; display:block;"><b>SIP Method</b></label>' +
			'<select id="plan-method" style="width:100%;">' + methodOptions + '</select>' +
			'<label style="margin-top:6px; display:block;"><b>Plans</b> ' +
				'<svg id="plan-add-row" title="Add plan" style="color:green; cursor:pointer;" ' +
					'viewBox="0 0 16 16" width="14" height="14">' +
					'<circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/>' +
					'<path d="M8 4.5v7M4.5 8h7" stroke="currentColor" stroke-width="1.5"/></svg></label>' +
			'<div id="plan-rows">' +
				rowHtml('gold', '') + rowHtml('silver', '') + rowHtml('bronze', '') +
			'</div>' +
			'<label style="margin-top:6px; display:block;"><b>Default application</b> ' +
				'<span style="color:#666; font-size:10px;">(no condition; runs last; blank = none)</span></label>' +
			'<input id="plan-default" type="text" style="width:100%; box-sizing:border-box;" />' +
			'<div style="margin-top:10px; text-align:right;">' +
				'<button id="plan-generate">Generate</button> ' +
				'<button id="plan-cancel">Cancel</button>' +
			'</div>';

		var wnd = new mxWindow('Add plan dispatch', div, 120, 120, 380, 420, true, true);
		wnd.setClosable(true);
		wnd.setVisible(true);

		div.querySelector('#plan-add-row').onclick = function() {
			var d = document.createElement('div');
			d.innerHTML = rowHtml('', '');
			div.querySelector('#plan-rows').appendChild(d.firstChild);
		};
		div.querySelector('#plan-cancel').onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
		};
		div.querySelector('#plan-generate').onclick = function() {
			var variable = div.querySelector('#plan-variable').value.trim();
			var method = div.querySelector('#plan-method').value;
			var rows = [];
			var rowEls = div.querySelectorAll('#plan-rows .plan-row');
			for (var i = 0; i < rowEls.length; i++) {
				var v = rowEls[i].querySelector('.plan-value').value.trim();
				var a = rowEls[i].querySelector('.plan-app').value.trim();
				if (v && a) rows.push({ value: v, app: a });
			}
			var defaultApp = div.querySelector('#plan-default').value.trim();
			if (!variable) { mxUtils.alert('Variable is required'); return; }
			if (rows.length === 0) { mxUtils.alert('At least one plan row (value and application) is required'); return; }
			wnd.setVisible(false);
			wnd.destroy();
			generateDispatch(sourceCell, {
				variable: variable, method: method, rows: rows, defaultApp: defaultApp
			});
		};
	}

	// ----- generation -----------------------------------------------------------

	function generateDispatch(sourceCell, spec) {
		var graph = window.flowGraph;
		var model = graph.getModel();
		var parent = graph.getDefaultParent();

		model.beginUpdate();
		try {
			// Existing State vertices by label, and the max seq already used
			// by same-method transitions from this source (new family appends
			// after whatever is already there).
			var byLabel = {};
			var maxSeq = -1;
			for (var id in model.cells) {
				var c = model.cells[id];
				if (!c || !c.value) continue;
				if (c.vertex && c.value.tagName === 'State') {
					byLabel[c.getAttribute('label') || ''] = c;
				}
				if (c.edge && c.value.tagName === 'Transition' && c.source === sourceCell
						&& (c.getAttribute('label') || '') === spec.method) {
					var s = parseInt(c.getAttribute('seq'), 10);
					if (!isNaN(s) && s > maxSeq) maxSeq = s;
				}
			}

			var doc = sourceCell.value.ownerDocument;
			var geo = sourceCell.geometry || { x: 60, y: 60 };
			var all = spec.rows.slice();
			if (spec.defaultApp) all.push({ value: null, app: spec.defaultApp });

			for (var i = 0; i < all.length; i++) {
				var target = byLabel[all[i].app];
				if (!target) {
					var stateEl = doc.createElement('State');
					stateEl.setAttribute('label', all[i].app);
					target = graph.insertVertex(parent, null, stateEl,
							geo.x + 220, geo.y + i * 120, 120, 48, 'state');
					byLabel[all[i].app] = target;
				}
				var txEl = doc.createElement('Transition');
				txEl.setAttribute('label', spec.method);
				if (all[i].value !== null) {
					txEl.setAttribute('when', '${' + spec.variable + "} == '" + all[i].value + "'");
				}
				txEl.setAttribute('seq', String(maxSeq + 1 + i));
				graph.insertEdge(parent, null, txEl, sourceCell, target);
			}
		} finally {
			model.endUpdate();
		}
	}

	return {
		parseDispatch: parseDispatch,
		displayLabel: displayLabel,
		showDispatchDialog: showDispatchDialog
	};

})();
