// FSMAR Flow Editor — property panel bindings
//
// Binds the currently-selected mxGraph cell's XML user object to the visible
// property panel. The cell's wrapper element (State, Ingress, Egress,
// Transition, FlowModel) is the source of truth; panels read on selection
// change and write back on every input change via mxCellAttributeChange for
// proper undo support.
//
// Model notes (must match FsmarImportServlet/FsmarExportServlet):
//  - Selectors live on State/Ingress vertices as <selector> children with
//    id/type/description/attribute/pattern/expression attributes plus an
//    `extra` attribute (JSON blob) preserving fields the form doesn't show
//    (table, namespaces, anything future).
//  - Transitions carry when/subscriber/region/routeModifier/seq attributes;
//    seq is the evaluation order within (state, method) — first match wins.
//  - `extra` attributes anywhere are round-trip passthrough; the UI never
//    deletes them.

window.flowTasks = (function() {

	// ----- helpers -----------------------------------------------------------

	function setAttr(cell, name, value) {
		if (!cell || !cell.value) return;
		var edit = new mxCellAttributeChange(cell, name, value);
		window.flowGraph.getModel().execute(edit);
	}

	function setChildElements(cell, tagName, items, attrSetter) {
		// Replace all child <tagName> elements on cell.value with the given items.
		// Wrapped in a model update so it goes through undo.
		if (!cell || !cell.value) return;
		var model = window.flowGraph.getModel();
		model.beginUpdate();
		try {
			var node = cell.value;
			// Remove existing children of this tag
			var i = node.childNodes.length - 1;
			while (i >= 0) {
				var child = node.childNodes[i];
				if (child.nodeType === 1 && child.nodeName === tagName) {
					node.removeChild(child);
				}
				i--;
			}
			// Append new children
			var doc = node.ownerDocument;
			for (var j = 0; j < items.length; j++) {
				var el = doc.createElement(tagName);
				attrSetter(el, items[j]);
				node.appendChild(el);
			}
			// Force a value-changed event so refreshTasks doesn't reset our edits
			model.setValue(cell, node);
		} finally {
			model.endUpdate();
		}
	}

	function getChildElements(cell, tagName) {
		var out = [];
		if (!cell || !cell.value || !cell.value.childNodes) return out;
		var kids = cell.value.childNodes;
		for (var i = 0; i < kids.length; i++) {
			if (kids[i].nodeType === 1 && kids[i].nodeName === tagName) {
				out.push(kids[i]);
			}
		}
		return out;
	}

	function escapeAttr(s) {
		return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
	}

	// Scope a jQuery selector to the panel of the currently-selected cell's
	// tag. state.html is loaded into #State, #Ingress AND #Egress, so every
	// lookup must be scoped or we'd read inputs from a hidden sibling panel.
	function panel(cell) {
		if (!cell || !cell.value) return $();
		return $('#' + cell.value.tagName);
	}

	// ----- node panel (shared by State / Gateway, legacy Ingress / Egress) ---

	function loadNode(cell) {
		var $p = panel(cell);
		$p.find('.node-name').val(cell.getAttribute('label') || '');

		// Egress exit node (Gateway with role="egress"): the mirror of an
		// ingress. It owns the routes baked onto each transition targeting it.
		// Nothing routes FROM it as a state, so hide selectors/dispatch/match/id.
		// The exit KIND is inferred from topology — a line back to a state makes
		// it ROUTE_BACK; otherwise ROUTE_FINAL.
		var isExitEgress = cell.getAttribute('role') === 'egress';
		$p.find('.egress-section').css('display', isExitEgress ? '' : 'none');
		if (isExitEgress) {
			$p.find('.state-selectors-section, .state-dispatch-section, .ingress-match-section, .state-id-section')
					.css('display', 'none');
			var ret = egressReturnState(cell);
			$p.find('.egress-kind').text(ret
					? 'Back to origin (ROUTE_BACK) → resumes at ' + ret
					: 'To destination (ROUTE_FINAL)');
			renderEgressRoutes(cell);
			return;
		}

		// Selectors and plan dispatch apply to State and Gateway (a state's
		// selectors run on entry). Legacy Egress is a pure sink — hide both.
		var isEgress = cell.value.tagName === 'Egress';
		$p.find('.state-selectors-section, .state-dispatch-section')
				.css('display', isEgress ? 'none' : '');
		if (!isEgress) {
			renderStateSelectors(cell);
		}
		// Source match: only an ingress (Gateway) has one, and only a NAMED
		// ingress (not the "default" box) should set it.
		var isIngress = cell.value.tagName === 'Gateway' || cell.value.tagName === 'Ingress';
		$p.find('.ingress-match-section').css('display', isIngress ? '' : 'none');
		if (isIngress) {
			$p.find('.node-match').val(cell.getAttribute('match') || '');
		}

		// State ID: the unique JSON key, separate from the application name
		// (the label). Shown for a State or a NAMED ingress; blank means "use
		// the name as the id" (the common one-instance-per-app case). Set a
		// distinct id to invoke the same application from two states.
		var hasMatch = (cell.getAttribute('match') || '').length > 0;
		var showId = cell.value.tagName === 'State' || (isIngress && hasMatch);
		$p.find('.state-id-section').css('display', showId ? '' : 'none');
		if (showId) {
			$p.find('.node-stateid').val(cell.getAttribute('stateId') || '');
		}
	}

	function bindNode() {
		$(document).off('change.flowNode', '.node-name').on('change.flowNode', '.node-name', function() {
			var cell = window.flowSelectedCell;
			if (cell) {
				window.flowGraph.cellLabelChanged(cell, $(this).val(), false);
			}
		});
		$(document).off('change.flowMatch', '.node-match').on('change.flowMatch', '.node-match', function() {
			var cell = window.flowSelectedCell;
			if (cell) {
				setAttr(cell, 'match', $(this).val());
			}
		});
		$(document).off('change.flowSid', '.node-stateid').on('change.flowSid', '.node-stateid', function() {
			var cell = window.flowSelectedCell;
			if (cell) {
				// Blank stateId → the export falls back to the name (label) as
				// the id. Refresh so the on-canvas qualifier reflects the change.
				setAttr(cell, 'stateId', ($(this).val() || '').trim());
				if (window.flowGraph) { window.flowGraph.refresh(); }
			}
		});
	}

	// ----- egress exit node ---------------------------------------------------

	// The egress's return state, if it has a route-back line: the state id (or
	// label) its out-edge points at. null when the egress has no out-edge —
	// then it's a ROUTE_FINAL exit. This topology is what determines the kind.
	function egressReturnState(cell) {
		if (!cell || !cell.edges) return null;
		for (var i = 0; i < cell.edges.length; i++) {
			var e = cell.edges[i];
			if (e.source === cell && e.target && e.target.value
					&& e.target.getAttribute('role') !== 'egress') {
				return e.target.getAttribute('stateId') || e.target.getAttribute('label') || '?';
			}
		}
		return null;
	}

	function renderEgressRoutes(cell) {
		var $c = panel(cell).find('.egress-routes').empty();
		var routes = getChildElements(cell, 'route');
		for (var i = 0; i < routes.length; i++) {
			var uri = routes[i].getAttribute('uri') || '';
			$c.append('<div class="egress-route-row" data-idx="' + i + '">' +
				'<input class="egress-route-uri" type="text" value="' + escapeAttr(uri) +
					'" placeholder="sip:${To.user}@carrier-trunk.example.com" />' +
				' <svg class="remove-btn remove-egress-route" title="Remove route" viewBox="0 0 16 16" width="14" height="14"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M4.5 8h7" stroke="currentColor" stroke-width="1.5"/></svg>' +
				'</div>');
		}
	}

	function saveEgressRoutes() {
		var cell = window.flowSelectedCell;
		if (!cell) return;
		var items = [];
		panel(cell).find('.egress-routes .egress-route-row').each(function() {
			var v = $(this).find('.egress-route-uri').val();
			if (v) items.push({ uri: v });
		});
		setChildElements(cell, 'route', items, function(el, item) {
			el.setAttribute('uri', item.uri);
		});
	}

	function bindEgress() {
		// No modifier control: the exit kind is inferred from the egress's
		// out-edge (a route-back line), recomputed in loadNode on every model
		// change. See egressReturnState.
		$(document).off('click.flowEg', '.add-egress-route').on('click.flowEg', '.add-egress-route', function() {
			var cell = window.flowSelectedCell;
			if (!cell || !cell.value) return false;
			cell.value.appendChild(cell.value.ownerDocument.createElement('route'));
			renderEgressRoutes(cell);
			return false;
		});
		$(document).off('click.flowEg', '.remove-egress-route').on('click.flowEg', '.remove-egress-route', function() {
			$(this).closest('.egress-route-row').remove();
			saveEgressRoutes();
			return false;
		});
		$(document).off('change.flowEg', '.egress-routes input').on('change.flowEg', '.egress-routes input', function() {
			saveEgressRoutes();
		});
	}

	// ----- state selectors ----------------------------------------------------

	var SELECTOR_TYPES = ['attribute', 'regex', 'json', 'xml', 'sdp', 'table'];

	// What the `attribute` field means per selector type (matches the
	// framework v3 selector classes). Empty label = field hidden.
	var ATTRIBUTE_LABELS = {
		'attribute': 'Attribute (header name / map key / pseudo-header)',
		'regex':     'Attribute (source: header name / map key / context value)',
		'json':      'Attribute (JsonPath, e.g. $.callDirection)',
		'xml':       'Attribute (XPath)',
		'sdp':       'Attribute (SDP field code)',
		'table':     ''  // TableSelector hides attribute — key lives on the table
	};

	// ----- table selector editor ----------------------------------------------
	// A table selector's data lives in the framework v3 TranslationTable model:
	//   { description?, match?(hash|prefix|range), keyExpression, translations }
	// where translations maps a lookup key to a flat string map of context vars
	// (plus an optional per-row description). The whole object rides in the
	// selector's `extra` JSON blob. Rather than make the operator hand-edit that
	// blob, we render it as a grid and keep the hidden .sel-extra textarea — the
	// single source saveStateSelectors() reads back — in sync underneath.

	var MATCH_STRATEGIES = ['hash', 'prefix', 'range'];

	function parseExtraObj(s) {
		if (!s) return {};
		try { var o = JSON.parse(s); return (o && typeof o === 'object') ? o : {}; }
		catch (e) { return {}; }
	}

	// Var-column names across all translations, first-seen order. 'description'
	// is the reserved per-row field, never a column.
	function tableColumns(translations) {
		var cols = [], seen = {};
		for (var k in translations) {
			if (!translations.hasOwnProperty(k)) continue;
			var v = translations[k] || {};
			for (var f in v) {
				if (!v.hasOwnProperty(f) || f === 'description') continue;
				if (!seen[f]) { seen[f] = 1; cols.push(f); }
			}
		}
		return cols;
	}

	var TBL_REMOVE_SVG = '<svg class="remove-btn CLS" title="Remove" viewBox="0 0 16 16" width="WH" height="WH"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M4.5 8h7" stroke="currentColor" stroke-width="1.5"/></svg>';
	function removeSvg(cls, wh) { return TBL_REMOVE_SVG.replace('CLS', cls).replace(/WH/g, wh); }

	function tableEditorHtml(table) {
		table = table || {};
		var keyExpr = table.keyExpression || '';
		var match = (table.match || 'hash').toLowerCase();
		if (MATCH_STRATEGIES.indexOf(match) < 0) match = 'hash';
		var translations = (table.translations && typeof table.translations === 'object') ? table.translations : {};
		var cols = tableColumns(translations);

		var matchOptions = MATCH_STRATEGIES.map(function(m) {
			return '<option' + (m === match ? ' selected' : '') + '>' + m + '</option>';
		}).join('');

		var head = '<tr><th class="tbl-key-col">Key</th>';
		for (var c = 0; c < cols.length; c++) {
			head += '<th><input class="tbl-col" type="text" value="' + escapeAttr(cols[c]) +
				'" placeholder="var" />' + removeSvg('tbl-remove-col', '12') + '</th>';
		}
		head += '<th class="tbl-col-add"><span class="add-btn tbl-add-col" title="Add column">+</span></th></tr>';

		var keys = [];
		for (var kk in translations) { if (translations.hasOwnProperty(kk)) keys.push(kk); }
		var body = '';
		for (var r = 0; r < keys.length; r++) {
			var key = keys[r];
			var val = translations[key] || {};
			body += '<tr class="tbl-row">' +
				'<td><input class="tbl-key" type="text" value="' + escapeAttr(key) + '" /></td>';
			for (var c2 = 0; c2 < cols.length; c2++) {
				var cellVal = (val[cols[c2]] != null) ? String(val[cols[c2]]) : '';
				body += '<td><input class="tbl-val" type="text" value="' + escapeAttr(cellVal) + '" /></td>';
			}
			body += '<td class="tbl-row-rm">' + removeSvg('tbl-remove-row', '14') + '</td></tr>';
		}

		return '' +
			'<div class="sel-field">' +
				'<label>Key expression <span class="hint">(${} template producing the lookup key)</span></label>' +
				'<input class="tbl-keyexpr" type="text" value="' + escapeAttr(keyExpr) + '" placeholder="${From.user}" />' +
			'</div>' +
			'<div class="sel-field">' +
				'<label>Match strategy <span class="hint">(hash = exact, prefix = longest-prefix, range = lo-hi)</span></label>' +
				'<select class="tbl-match">' + matchOptions + '</select>' +
			'</div>' +
			'<div class="sel-field">' +
				'<label>Translations <span class="hint">(lookup key → context variables)</span></label>' +
				'<table class="tbl-grid"><thead>' + head + '</thead><tbody>' + body + '</tbody></table>' +
				'<span class="add-btn tbl-add-row" title="Add row">+ add row</span>' +
			'</div>';
	}

	// Read the grid back into a TranslationTable object (omitting defaults the
	// way hand-written configs do: no match=hash, no empty fields).
	function readTableEditor($row) {
		var table = {};
		var keyExpr = $.trim($row.find('.tbl-keyexpr').val() || '');
		var match = $row.find('.tbl-match').val() || 'hash';
		var cols = [];
		$row.find('.tbl-grid thead .tbl-col').each(function() { cols.push($.trim($(this).val() || '')); });
		var translations = {};
		$row.find('.tbl-grid tbody .tbl-row').each(function() {
			var $r = $(this);
			var key = $.trim($r.find('.tbl-key').val() || '');
			if (!key) return;
			var entry = {};
			$r.find('.tbl-val').each(function(i) {
				var name = cols[i];
				if (!name) return;
				var v = $(this).val();
				if (v != null && v !== '') entry[name] = v;
			});
			translations[key] = entry;
		});
		if (match && match !== 'hash') table.match = match;
		if (keyExpr) table.keyExpression = keyExpr;
		table.translations = translations;
		return table;
	}

	// Push the grid into the hidden .sel-extra JSON sink, preserving any
	// non-table keys the form doesn't model.
	function syncTableToExtra($row) {
		var extra = parseExtraObj($row.find('.sel-extra').val());
		extra.table = readTableEditor($row);
		$row.find('.sel-extra').val(JSON.stringify(extra));
	}

	function renderStateSelectors(cell) {
		var $c = panel(cell).find('.state-selectors').empty();
		var selectors = getChildElements(cell, 'selector');
		for (var i = 0; i < selectors.length; i++) {
			$c.append(selectorRowHtml(selectors[i], i));
		}
	}

	function selectorRowHtml(el, idx) {
		var type = (el.getAttribute('type') || 'attribute').toLowerCase();
		if (SELECTOR_TYPES.indexOf(type) < 0) type = 'attribute';
		var id = el.getAttribute('id') || '';
		var attribute = el.getAttribute('attribute') || '';
		var pattern = el.getAttribute('pattern') || '';
		var expression = el.getAttribute('expression') || '';
		var extra = el.getAttribute('extra') || '';

		var typeOptions = SELECTOR_TYPES.map(function(opt) {
			return '<option' + (opt === type ? ' selected' : '') + '>' + opt + '</option>';
		}).join('');

		var isRegex = (type === 'regex');
		var attrLabel = ATTRIBUTE_LABELS[type];
		var hideAttr = attrLabel ? '' : ' style="display:none;"';
		var hideRegex = isRegex ? '' : ' style="display:none;"';
		// Table selectors get a dedicated grid editor; their data still rides in
		// the `extra` blob, but the raw textarea is hidden and used only as the
		// sync sink. Other types: the blob carries what the form doesn't model
		// (XML namespaces, future fields), shown only when present.
		var isTable = (type === 'table');
		var tableObj = isTable ? (parseExtraObj(extra).table || {}) : null;
		var tableBlock = isTable
			? '<div class="sel-field sel-table">' + tableEditorHtml(tableObj) + '</div>'
			: '';
		// Show the raw blob when it carries data, or always for xml (namespaces
		// are entered there). Never for table — the grid owns that data.
		var hideExtra = (!isTable && (extra || type === 'xml')) ? '' : ' style="display:none;"';

		return '' +
			'<fieldset class="selector-row" data-idx="' + idx + '">' +
				'<legend>Selector ' + (idx + 1) +
					' <svg class="remove-btn remove-state-selector" title="Remove" viewBox="0 0 16 16" width="14" height="14"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M4.5 8h7" stroke="currentColor" stroke-width="1.5"/></svg>' +
				'</legend>' +

				'<div class="sel-field">' +
					'<label>Type</label>' +
					'<select class="sel-type">' + typeOptions + '</select>' +
				'</div>' +

				'<div class="sel-field">' +
					'<label>Id <span class="hint">(also the context variable name, e.g. ${id})</span></label>' +
					'<input class="sel-id" type="text" value="' + escapeAttr(id) + '" placeholder="e.g. To" />' +
				'</div>' +

				'<div class="sel-field sel-attribute-field"' + hideAttr + '>' +
					'<label class="sel-attribute-label">' + (attrLabel || '') + '</label>' +
					'<input class="sel-attribute" type="text" value="' + escapeAttr(attribute) + '" />' +
				'</div>' +

				'<div class="sel-field sel-pattern-field"' + hideRegex + '>' +
					'<label>Pattern <span class="hint">(regex; named groups become ${id.group} variables)</span></label>' +
					'<input class="sel-pattern" type="text" value="' + escapeAttr(pattern) +
						'" placeholder="sips?:(?&lt;user&gt;[^@]+)@(?&lt;host&gt;[^;&gt;]+)" />' +
				'</div>' +

				'<div class="sel-field sel-expression-field"' + hideRegex + '>' +
					'<label>Expression <span class="hint">(optional ${} template; result stored under Id)</span></label>' +
					'<input class="sel-expression" type="text" value="' + escapeAttr(expression) +
						'" placeholder="${user}@${host}" />' +
				'</div>' +

				tableBlock +

				'<div class="sel-field sel-extra-field"' + hideExtra + '>' +
					'<label>Advanced <span class="hint">(raw JSON for fields the form does not show: namespaces, …)</span></label>' +
					'<textarea class="sel-extra">' + escapeAttr(extra) + '</textarea>' +
				'</div>' +

			'</fieldset>';
	}

	function saveStateSelectors() {
		var cell = window.flowSelectedCell;
		if (!cell) return;
		var items = [];
		panel(cell).find('.state-selectors .selector-row').each(function() {
			var $row = $(this);
			items.push({
				type: $row.find('.sel-type').val(),
				id: $row.find('.sel-id').val(),
				attribute: $row.find('.sel-attribute').val(),
				pattern: $row.find('.sel-pattern').val(),
				expression: $row.find('.sel-expression').val(),
				extra: $row.find('.sel-extra').val()
			});
		});
		setChildElements(cell, 'selector', items, function(el, item) {
			// 'attribute' is the schema default type — omit it for brevity,
			// matching how hand-written configs usually look.
			if (item.type && item.type !== 'attribute') el.setAttribute('type', item.type);
			if (item.id) el.setAttribute('id', item.id);
			if (item.attribute && item.type !== 'table') el.setAttribute('attribute', item.attribute);
			if (item.type === 'regex') {
				if (item.pattern) el.setAttribute('pattern', item.pattern);
				if (item.expression) el.setAttribute('expression', item.expression);
			}
			if (item.extra) el.setAttribute('extra', item.extra);
		});
	}

	function bindStateSelectors() {
		// Add selector
		$(document).off('click.flowSel', '.add-state-selector').on('click.flowSel', '.add-state-selector', function() {
			var cell = window.flowSelectedCell;
			if (!cell || !cell.value) return;
			var doc = cell.value.ownerDocument;
			cell.value.appendChild(doc.createElement('selector'));
			renderStateSelectors(cell);
			return false;
		});

		// Remove selector
		$(document).off('click.flowSel', '.remove-state-selector').on('click.flowSel', '.remove-state-selector', function() {
			$(this).closest('.selector-row').remove();
			saveStateSelectors();
			var cell = window.flowSelectedCell;
			if (cell) renderStateSelectors(cell);
			return false;
		});

		// Type change: which fields a selector shows is type-dependent (the table
		// editor appears/disappears), so re-render the whole panel from the model.
		$(document).off('change.flowSel', '.state-selectors .sel-type').on('change.flowSel', '.state-selectors .sel-type', function() {
			saveStateSelectors();
			var cell = window.flowSelectedCell;
			if (cell) renderStateSelectors(cell);
		});

		// Any other selector field change. For table rows, fold the grid back
		// into the hidden .sel-extra sink first so saveStateSelectors() sees it.
		$(document).off('change.flowSel', '.state-selectors input, .state-selectors textarea, .state-selectors select:not(.sel-type)')
				.on('change.flowSel', '.state-selectors input, .state-selectors textarea, .state-selectors select:not(.sel-type)', function() {
			var $row = $(this).closest('.selector-row');
			if ($row.find('.sel-type').val() === 'table') syncTableToExtra($row);
			saveStateSelectors();
		});

		// Table editor: add a translation row (blank — it persists once keyed).
		$(document).off('click.flowSel', '.tbl-add-row').on('click.flowSel', '.tbl-add-row', function() {
			var $row = $(this).closest('.selector-row');
			var nCols = $row.find('.tbl-grid thead .tbl-col').length;
			var cells = '<td><input class="tbl-key" type="text" /></td>';
			for (var i = 0; i < nCols; i++) cells += '<td><input class="tbl-val" type="text" /></td>';
			cells += '<td class="tbl-row-rm">' + removeSvg('tbl-remove-row', '14') + '</td>';
			$row.find('.tbl-grid tbody').append('<tr class="tbl-row" data-desc="">' + cells + '</tr>');
			return false;
		});

		// Table editor: remove a translation row.
		$(document).off('click.flowSel', '.tbl-remove-row').on('click.flowSel', '.tbl-remove-row', function() {
			var $row = $(this).closest('.selector-row');
			$(this).closest('.tbl-row').remove();
			syncTableToExtra($row);
			saveStateSelectors();
			return false;
		});

		// Table editor: add a value column (named once the operator types a var).
		$(document).off('click.flowSel', '.tbl-add-col').on('click.flowSel', '.tbl-add-col', function() {
			var $row = $(this).closest('.selector-row');
			$row.find('.tbl-grid thead .tbl-col-add').before(
				'<th><input class="tbl-col" type="text" placeholder="var" />' + removeSvg('tbl-remove-col', '12') + '</th>');
			$row.find('.tbl-grid tbody .tbl-row').each(function() {
				$(this).find('.tbl-row-rm').before('<td><input class="tbl-val" type="text" /></td>');
			});
			return false;
		});

		// Table editor: remove a value column (header + the cell at its position
		// in every row).
		$(document).off('click.flowSel', '.tbl-remove-col').on('click.flowSel', '.tbl-remove-col', function() {
			var $row = $(this).closest('.selector-row');
			var $th = $(this).closest('th');
			var idx = $row.find('.tbl-grid thead .tbl-col').index($th.find('.tbl-col'));
			$th.remove();
			$row.find('.tbl-grid tbody .tbl-row').each(function() {
				$(this).find('.tbl-val').eq(idx).closest('td').remove();
			});
			syncTableToExtra($row);
			saveStateSelectors();
			return false;
		});

		// Plan dispatch dialog
		$(document).off('click.flowSel', '.add-plan-dispatch').on('click.flowSel', '.add-plan-dispatch', function() {
			var cell = window.flowSelectedCell;
			if (cell && cell.value && window.flowPlans) {
				window.flowPlans.showDispatchDialog(cell);
			}
			return false;
		});
	}

	// ----- transition panel --------------------------------------------------

	function loadTransition(cell) {
		$('#transition-method').val(cell.getAttribute('label') || 'INVITE');
		$('#transition-when').val(cell.getAttribute('when') || '');
		$('#transition-seq').val(cell.getAttribute('seq') || '');
		$('#transition-txid').val(cell.getAttribute('txId') || '');
		$('#transition-subscriber').val(cell.getAttribute('subscriber') || '');
		$('#transition-region').val(cell.getAttribute('region') || '');
		$('#transition-route-modifier').val(cell.getAttribute('routeModifier') || '');
		var target = cell.target;
		$('#transition-next').val(target ? (target.getAttribute('label') || '') : '');
		renderRoutes(cell);

		// Routes belong at the egress (or are pointless app-to-app). Show this
		// transition's own route editor only for a legacy app-to-app transition
		// that already carries routes; for an egress target, point to the node.
		var targetIsEgress = !!(target && target.getAttribute('role') === 'egress');
		var hasRoutes = getChildElements(cell, 'route').length > 0;
		$('#transition-egress-note').css('display', targetIsEgress ? '' : 'none');
		$('#transition-routes-section').css('display',
				(!targetIsEgress && hasRoutes) ? '' : 'none');
	}

	function renderRoutes(cell) {
		var $c = $('#transition-routes').empty();
		var routes = getChildElements(cell, 'route');
		for (var i = 0; i < routes.length; i++) {
			$c.append(routeRowHtml(routes[i], i));
		}
	}

	function routeRowHtml(el, idx) {
		var uri = el.getAttribute('uri') || '';
		return '<div class="route-row" data-idx="' + idx + '">' +
			'<input class="route-uri" type="text" value="' + escapeAttr(uri) + '" placeholder="sip:${To.user}@proxy.example.com" />' +
			' <svg class="remove-btn remove-route" title="Remove route" viewBox="0 0 16 16" width="14" height="14"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M4.5 8h7" stroke="currentColor" stroke-width="1.5"/></svg>' +
			'</div>';
	}

	function saveRoutes() {
		var cell = window.flowSelectedCell;
		if (!cell) return;
		var items = [];
		$('#transition-routes .route-row').each(function() {
			var v = $(this).find('.route-uri').val();
			if (v) items.push({ uri: v });
		});
		setChildElements(cell, 'route', items, function(el, item) {
			el.setAttribute('uri', item.uri);
		});
	}

	function bindTransition() {
		// Vanilla tab strip (replaces jQuery-UI tabs). Active state lives in
		// the tab-active class on both the button and its page.
		$(document).off('click.flowTx', '#transition-tabs .tab-btn').on('click.flowTx', '#transition-tabs .tab-btn', function() {
			var target = $(this).attr('data-tab');
			$('#transition-tabs .tab-btn').removeClass('tab-active');
			$(this).addClass('tab-active');
			$('#transition-tabs .tab-page').removeClass('tab-active');
			$('#' + target).addClass('tab-active');
			return false;
		});

		// Method change updates label
		$(document).off('change.flowTx', '#transition-method').on('change.flowTx', '#transition-method', function() {
			var cell = window.flowSelectedCell;
			if (cell) {
				window.flowGraph.cellLabelChanged(cell, $(this).val(), false);
			}
		});

		// Simple attribute fields
		var attrFields = {
			'#transition-when': 'when',
			'#transition-seq': 'seq',
			'#transition-txid': 'txId',
			'#transition-subscriber': 'subscriber',
			'#transition-region': 'region',
			'#transition-route-modifier': 'routeModifier'
		};
		Object.keys(attrFields).forEach(function(sel) {
			$(document).off('change.flowTx', sel).on('change.flowTx', sel, function() {
				setAttr(window.flowSelectedCell, attrFields[sel], $(this).val());
			});
		});

		// Add route
		$(document).off('click.flowTx', '#add-route').on('click.flowTx', '#add-route', function() {
			var cell = window.flowSelectedCell;
			if (!cell || !cell.value) return;
			var doc = cell.value.ownerDocument;
			var el = doc.createElement('route');
			cell.value.appendChild(el);
			renderRoutes(cell);
			return false;
		});

		// Remove route
		$(document).off('click.flowTx', '.remove-route').on('click.flowTx', '.remove-route', function() {
			$(this).closest('.route-row').remove();
			saveRoutes();
			return false;
		});

		// Save route field changes
		$(document).off('change.flowTx', '#transition-routes input').on('change.flowTx', '#transition-routes input', function() {
			saveRoutes();
		});
	}

	// ----- view filter panel ------------------------------------------------
	//
	// A persistent panel (not a .task) at the top of the Tasks window listing
	// every distinct Transition method name in the model with a checkbox each.
	// Unchecking hides all edges of that type so the canvas stays legible when
	// multiple flows overlap. View-only — visibility changes don't go through
	// the undo stack.

	var hiddenMethods = {};
	var hiddenPlans = {};

	function collectMethods(graph) {
		var set = {};
		var cells = graph.getModel().cells;
		for (var id in cells) {
			var cell = cells[id];
			if (cell && cell.edge && cell.value && cell.value.tagName === 'Transition') {
				var label = cell.getAttribute('label') || '(none)';
				set[label] = true;
			}
		}
		return Object.keys(set).sort();
	}

	// Plan key for an edge: "tier=gold" for dispatch conditions, "default"
	// for the unconditional member of a family, null for everything else
	// (non-dispatch edges are never plan-filtered).
	function planKeyOf(cell) {
		if (!window.flowPlans) return null;
		var when = cell.getAttribute('when') || '';
		var d = window.flowPlans.parseDispatch(when);
		if (d) return d.variable + '=' + d.value;
		return null;
	}

	function collectPlans(graph) {
		var set = {};
		var cells = graph.getModel().cells;
		for (var id in cells) {
			var cell = cells[id];
			if (cell && cell.edge && cell.value && cell.value.tagName === 'Transition') {
				var key = planKeyOf(cell);
				if (key) set[key] = true;
			}
		}
		return Object.keys(set).sort();
	}

	function rebuildView(graph) {
		var methods = collectMethods(graph);
		// Drop hidden entries for methods that no longer exist
		for (var key in hiddenMethods) {
			if (methods.indexOf(key) < 0) delete hiddenMethods[key];
		}
		var $list = $('#view-methods').empty();
		if (methods.length === 0) {
			$list.append('<div class="view-empty">No transitions yet</div>');
		}
		for (var i = 0; i < methods.length; i++) {
			var m = methods[i];
			var checked = hiddenMethods[m] ? '' : ' checked';
			$list.append(
				'<label class="view-item"><input type="checkbox" class="view-method" value="' +
				escapeAttr(m) + '"' + checked + '> ' + escapeAttr(m) + '</label>'
			);
		}

		// Plans section: one checkbox per dispatch variable=value. Only shown
		// when the model actually contains dispatch-shaped conditions, so
		// non-plan flows don't grow UI. "Show only the gold path" = uncheck
		// the others.
		var plans = collectPlans(graph);
		for (var pkey in hiddenPlans) {
			if (plans.indexOf(pkey) < 0) delete hiddenPlans[pkey];
		}
		$('#view-plans-header').css('display', plans.length > 0 ? '' : 'none');
		var $plans = $('#view-plans').empty();
		for (var p = 0; p < plans.length; p++) {
			var pk = plans[p];
			var pchecked = hiddenPlans[pk] ? '' : ' checked';
			$plans.append(
				'<label class="view-item"><input type="checkbox" class="view-plan" value="' +
				escapeAttr(pk) + '"' + pchecked + '> ' + escapeAttr(pk) + '</label>'
			);
		}
	}

	function applyFilter(graph) {
		var cells = graph.getModel().cells;
		for (var id in cells) {
			var cell = cells[id];
			if (cell && cell.edge && cell.value && cell.value.tagName === 'Transition') {
				var label = cell.getAttribute('label') || '(none)';
				var planKey = planKeyOf(cell);
				cell.visible = !hiddenMethods[label] && !(planKey && hiddenPlans[planKey]);
			}
		}
		graph.refresh();
	}

	function bindView(graph) {
		$(document).off('change.flowView', '.view-method').on('change.flowView', '.view-method', function() {
			var m = $(this).val();
			if ($(this).is(':checked')) {
				delete hiddenMethods[m];
			} else {
				hiddenMethods[m] = true;
			}
			applyFilter(graph);
		});
		$(document).off('change.flowView', '.view-plan').on('change.flowView', '.view-plan', function() {
			var p = $(this).val();
			if ($(this).is(':checked')) {
				delete hiddenPlans[p];
			} else {
				hiddenPlans[p] = true;
			}
			applyFilter(graph);
		});
		$(document).off('click.flowView', '#view-show-all').on('click.flowView', '#view-show-all', function() {
			hiddenMethods = {};
			hiddenPlans = {};
			rebuildView(graph);
			applyFilter(graph);
			return false;
		});
		$(document).off('click.flowView', '#view-hide-all').on('click.flowView', '#view-hide-all', function() {
			var methods = collectMethods(graph);
			hiddenMethods = {};
			for (var i = 0; i < methods.length; i++) hiddenMethods[methods[i]] = true;
			rebuildView(graph);
			applyFilter(graph);
			return false;
		});
	}

	function initView(graph) {
		rebuildView(graph);
		bindView(graph);
		graph.getModel().addListener(mxEvent.CHANGE, function() {
			rebuildView(graph);
			applyFilter(graph);
		});
	}

	// ----- flow model (root) panel ------------------------------------------

	function loadFlowModel(cell) {
		if (!cell) return;
		$('#config-default-app').val(cell.getAttribute('defaultApplication') || '');
	}

	function bindFlowModel() {
		$(document).off('change.flowCfg', '#config-default-app').on('change.flowCfg', '#config-default-app', function() {
			setAttr(window.flowSelectedCell, 'defaultApplication', $(this).val());
		});
	}

	// ----- main entry point --------------------------------------------------

	function loadFromCell(cell) {
		if (!cell || !cell.value) return;
		var tag = cell.value.tagName;
		if (tag === 'State' || tag === 'Gateway' || tag === 'Ingress' || tag === 'Egress') {
			loadNode(cell);
		} else if (tag === 'Transition') {
			loadTransition(cell);
		} else if (tag === 'FlowModel') {
			loadFlowModel(cell);
		}
	}

	// Bind handlers once on document ready (panels load via $.load() and inputs
	// only exist after that, so we use delegated handlers on document).
	$(function() {
		bindNode();
		bindEgress();
		bindStateSelectors();
		bindTransition();
		bindFlowModel();
	});

	return {
		loadFromCell: loadFromCell,
		initView: initView
	};

})();
