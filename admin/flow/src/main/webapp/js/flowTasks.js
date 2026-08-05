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
//    id/type/attribute/pattern/expression/allInstances/namespaces attributes,
//    plus an `extra` attribute (JSON blob) preserving fields the form doesn't
//    show (table data, anything future). There is no `description` attribute —
//    that field was retired from the model and folded into Configuration.notes.
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

	// An ⓘ field-help icon carrying its popup text (html), shown by
	// flowUtils.initInfoTips. Same markup as the static tips in state.html.
	function infoTipHtml(ariaLabel, html) {
		return '<span class="info-tip" tabindex="0" role="button" aria-label="' + escapeAttr(ariaLabel) + '">' +
			'<svg viewBox="0 0 16 16" aria-hidden="true">' +
				'<circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5"/>' +
				'<path d="M8 7v4.2" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>' +
				'<circle cx="8" cy="4.7" r="1" fill="currentColor" stroke="none"/>' +
			'</svg>' +
			'<span class="info-tip-text">' + html + '</span>' +
		'</span>';
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

		// A cloud that a transition points INTO is where the call leaves OCCAS.
		// It owns the routes baked onto each transition targeting it. Nothing
		// routes FROM it as a state, so hide selectors/dispatch/match/id. The exit
		// KIND is inferred from topology too — a line back to a state makes it
		// ROUTE_BACK; otherwise ROUTE_FINAL.
		var isExitEgress = flowUtils.isExitCloud(cell);
		$p.find('.egress-section').css('display', isExitEgress ? '' : 'none');
		if (isExitEgress) {
			$p.find('.state-selectors-section, .state-dispatch-section, .ingress-match-section, .state-id-section')
					.css('display', 'none');
			var ret = egressReturnState(cell);
			// The exit kind follows the routes too: no routes at all = the
			// downstream exit (nothing pushed; the call continues on the
			// Request-URI). A route-back line without routes cannot export —
			// say so here rather than only at save time.
			var egRoutes = getChildElements(cell, 'route');
			var hasEgRoutes = false;
			for (var er = 0; er < egRoutes.length; er++) {
				if ((egRoutes[er].getAttribute('uri') || '').length > 0) {
					hasEgRoutes = true;
					break;
				}
			}
			var kind;
			if (ret) {
				kind = 'Back to origin (ROUTE_BACK) → resumes at ' + ret
					+ (hasEgRoutes ? '' : ' — needs at least one route below');
			} else if (hasEgRoutes) {
				kind = 'To destination (ROUTE_FINAL)';
			} else {
				kind = 'Downstream — no Route pushed; the call continues on the Request-URI';
			}
			$p.find('.egress-kind').text(kind);
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

		// Entry-transition fields. Only a NAMED ingress gets a generated
		// dispatch transition — the default ingress IS the "null" state, which
		// nothing dispatches into.
		var hasMatchNow = (cell.getAttribute('match') || '').length > 0;
		var showDispatch = isIngress && hasMatchNow;
		$p.find('.ingress-dispatch-section').css('display', showDispatch ? '' : 'none');
		if (showDispatch) {
			renderDispatchRows(cell);
		}

		// State ID: the unique JSON key, separate from the application name
		// (the label). States only — for an ingress the NAME is the state id
		// (renaming the box renames the state; export keys on the label), so
		// there is no separate id to edit.
		var showId = cell.value.tagName === 'State';
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

	// ----- ingress entry transition -------------------------------------------
	//
	// A named ingress's dispatch transition (null -> this ingress) is never
	// drawn: import absorbs it and export regenerates it from the ingress's
	// `match`. Its other fields ride the cell's `dispatchExtra` attribute — a
	// JSON object keyed by SIP method, the same shape as `triggerExtras` —
	// which FsmarExportServlet merges back over the regenerated transition.
	// This panel is what makes those fields visible and editable rather than
	// merely preserved.

	function parseDispatchExtra(cell) {
		var raw = cell.getAttribute('dispatchExtra') || '';
		if (!raw) return {};
		try {
			var o = JSON.parse(raw);
			return (o && typeof o === 'object') ? o : {};
		} catch (e) {
			return {};
		}
	}

	// Methods leaving this ingress — exactly the set export generates a
	// dispatch for. Falls back to INVITE when nothing is drawn yet, and
	// includes any method already carrying data so an imported config's fields
	// are never hidden just because its edge was deleted.
	function dispatchMethods(cell, extra) {
		var methods = [];
		function add(m) {
			if (m && methods.indexOf(m) < 0) methods.push(m);
		}
		if (cell && cell.edges) {
			for (var i = 0; i < cell.edges.length; i++) {
				var e = cell.edges[i];
				if (e.source === cell && e.value && e.value.tagName === 'Transition') {
					add(e.getAttribute('label') || 'INVITE');
				}
			}
		}
		for (var k in extra) {
			if (extra.hasOwnProperty(k)) add(k);
		}
		if (!methods.length) add('INVITE');
		return methods;
	}

	function renderDispatchRows(cell) {
		var extra = parseDispatchExtra(cell);
		var methods = dispatchMethods(cell, extra);
		var regions = window.flowMeta.regions();
		var $c = panel(cell).find('.ingress-dispatch-rows').empty();

		for (var i = 0; i < methods.length; i++) {
			var m = methods[i];
			var vals = extra[m] || {};
			var opts = '<option value=""></option>';
			for (var r = 0; r < regions.length; r++) {
				opts += '<option' + (regions[r] === vals.region ? ' selected' : '') + '>'
					+ regions[r] + '</option>';
			}
			$c.append(
				'<div class="dispatch-row" data-method="' + escapeAttr(m) + '">' +
					'<span class="dispatch-method">' + escapeAttr(m) + '</span>' +
					'<input class="dispatch-subscriber" type="text" placeholder="From"' +
						' title="Subscriber header" value="' + escapeAttr(vals.subscriber || '') + '" />' +
					'<select class="dispatch-region" title="Routing region">' + opts + '</select>' +
				'</div>');
		}
	}

	// Writes the rows back, preserving any fields this panel doesn't model
	// (a custom id, unknown keys) and dropping a method entry that has been
	// emptied so the exported config stays minimal.
	function saveDispatchRows() {
		var cell = window.flowSelectedCell;
		if (!cell || !cell.value) return;
		var extra = parseDispatchExtra(cell);

		panel(cell).find('.ingress-dispatch-rows .dispatch-row').each(function() {
			var $row = $(this);
			var method = $row.attr('data-method');
			var entry = extra[method] || {};
			var subscriber = $.trim($row.find('.dispatch-subscriber').val() || '');
			var region = $row.find('.dispatch-region').val() || '';

			if (subscriber) { entry.subscriber = subscriber; } else { delete entry.subscriber; }
			if (region) { entry.region = region; } else { delete entry.region; }

			if (Object.keys(entry).length) {
				extra[method] = entry;
			} else {
				delete extra[method];
			}
		});

		setAttr(cell, 'dispatchExtra', Object.keys(extra).length ? JSON.stringify(extra) : '');
	}

	function bindDispatch() {
		$(document).off('change.flowDispatch', '.ingress-dispatch-rows input, .ingress-dispatch-rows select')
				.on('change.flowDispatch', '.ingress-dispatch-rows input, .ingress-dispatch-rows select', function() {
			saveDispatchRows();
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
					&& !flowUtils.isExitCloud(e.target)) {
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

	// The virtual-gateway (;vgw=) param on a route URI: read / set / clear.
	function getVgwParam(uri) {
		var m = /;vgw=([^;>\s]*)/i.exec(uri || '');
		return m ? m[1] : '';
	}
	function setVgwParam(uri, vgw) {
		var stripped = (uri || '').replace(/;vgw=[^;>\s]*/ig, '');
		return vgw ? stripped + ';vgw=' + vgw : stripped;
	}

	// The trunk picker on a TRANSITION. FSMAR can only push Route headers, so the
	// trunk for an outbound call rides ;vgw=<name> on the Route pushed by the
	// transition INTO the gateway app, which reads it back with getPoppedRoute().
	// It therefore belongs on the arrow, not on a cloud: a cloud is where the call
	// leaves with no further application invoked, so a ;vgw= there reaches nothing.
	//
	// Whether the target IS a gateway app is asked, not assumed — /gatewayVgws
	// answers for any app name, and an app with no virtual gateways isn't one, so
	// the picker stays hidden. That works whatever context root a gateway app is
	// deployed under, and for more than one of them.
	function populateTransitionVgw(cell, target) {
		var $section = $('#transition-vgw-section');
		var $sel = $('#transition-vgw');
		var app = (target && target.value && target.value.tagName === 'State')
				? (target.getAttribute('label') || '') : '';
		var routes = getChildElements(cell, 'route');
		var current = routes.length ? getVgwParam(routes[0].getAttribute('uri') || '') : '';
		if (!app) {
			$section.hide();
			return;
		}
		$.get('gatewayVgws', { app: app }).done(function(data) {
			var names = Array.isArray(data) ? data : [];
			// Not a gateway app (and nothing already set) — nothing to offer.
			if (!names.length && !current) {
				$section.hide();
				return;
			}
			var html = '<option value="">(none)</option>';
			for (var i = 0; i < names.length; i++) {
				html += '<option value="' + escapeAttr(names[i]) + '">' + escapeAttr(names[i]) + '</option>';
			}
			if (current && names.indexOf(current) < 0) {
				html += '<option value="' + escapeAttr(current) + '">' + escapeAttr(current) + ' (not deployed)</option>';
			}
			$sel.html(html).val(current);
			$section.show();
		}).fail(function() {
			// Editor offline or the app name isn't one the servlet will look up.
			// Keep a value that is already set rather than silently dropping it.
			if (!current) {
				$section.hide();
				return;
			}
			$sel.html('<option value="' + escapeAttr(current) + '" selected>' + escapeAttr(current) + '</option>');
			$section.show();
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

	// From Selector's @JsonSubTypes, via flowMeta. Rendering order is the
	// model's own; ATTRIBUTE_LABELS below supplies the per-type wording and a
	// type with no entry there simply shows the generic label.
	function selectorTypes() {
		return window.flowMeta.selectorTypes();
	}

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

	// Pseudo-headers understood by Selector.readSource (framework v3
	// configuration.selectors.Selector). A closed, case-sensitive set with one
	// canonical spelling each — anything else is read as an ordinary SIP
	// header, so a typo fails silently. Offered as a datalist: suggestions,
	// not a constraint, since real header names are equally valid here.
	var PSEUDO_HEADERS = [
		['requestURI',        'the request URI'],
		['originIP',          'originating IP (X-Vorpal-ID, Via received=, remote addr)'],
		['peerIP',            'immediate peer IP'],
		['localIP',           'local SIP interface the message arrived on'],
		['localPort',         'local SIP port it arrived on'],
		['body',              'the message body'],
		['transport',         'UDP | TCP | TLS | WS | WSS'],
		['isSecure',          '"true" | "false"'],
		['clientCertSubject', 'TLS client certificate subject'],
		['clientCertIssuer',  'TLS client certificate issuer'],
		['tlsCipher',         'negotiated TLS cipher']
	];

	function pseudoHeaderDatalist(listId) {
		var opts = PSEUDO_HEADERS.map(function(p) {
			return '<option value="' + p[0] + '">' + escapeAttr(p[1]) + '</option>';
		}).join('');
		return '<datalist id="' + listId + '">' + opts + '</datalist>';
	}

	// ----- xml namespace editor -----------------------------------------------
	// XmlSelector.namespaces is a prefix -> URI map. Without it the XPath
	// evaluator stays namespace-unaware and a namespaced expression silently
	// extracts nothing, so it needs a real control, not the raw JSON blob.

	function namespaceRowHtml(prefix, uri) {
		return '<div class="sel-ns-row">' +
			'<input class="sel-ns-prefix" type="text" value="' + escapeAttr(prefix || '') + '" placeholder="prefix" />' +
			' <input class="sel-ns-uri" type="text" value="' + escapeAttr(uri || '') + '" placeholder="urn:example:ns" />' +
			' ' + removeSvg('sel-ns-remove', '14') +
			'</div>';
	}

	function namespaceEditorHtml(ns) {
		var rows = '';
		for (var p in ns) {
			if (ns.hasOwnProperty(p)) rows += namespaceRowHtml(p, ns[p]);
		}
		return '<label>Namespaces <span class="hint">(prefix → URI; required for a namespaced XPath)</span></label>' +
			'<div class="sel-ns-rows">' + rows + '</div>' +
			'<span class="add-btn sel-ns-add" title="Add namespace">+ add namespace</span>';
	}

	// Read the namespace rows back into a prefix -> URI object. Rows missing
	// either half are dropped (an unnamed prefix cannot resolve anything).
	function readNamespaces($row) {
		var ns = {};
		$row.find('.sel-ns-row').each(function() {
			var prefix = $.trim($(this).find('.sel-ns-prefix').val() || '');
			var uri = $.trim($(this).find('.sel-ns-uri').val() || '');
			if (prefix && uri) ns[prefix] = uri;
		});
		return ns;
	}

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
		// state.html is loaded into four panels (#State, #Gateway, #Ingress,
		// #Egress), so a row index alone is not a unique DOM id — scope the
		// datalist ids by the owning panel's tag.
		var scope = (cell && cell.value) ? cell.value.tagName : 'State';
		for (var i = 0; i < selectors.length; i++) {
			$c.append(selectorRowHtml(selectors[i], i, scope));
		}
	}

	function selectorRowHtml(el, idx, scope) {
		var type = (el.getAttribute('type') || 'attribute').toLowerCase();
		if (selectorTypes().indexOf(type) < 0) type = 'attribute';
		var id = el.getAttribute('id') || '';
		var attribute = el.getAttribute('attribute') || '';
		var pattern = el.getAttribute('pattern') || '';
		var expression = el.getAttribute('expression') || '';
		var extra = el.getAttribute('extra') || '';

		var typeOptions = selectorTypes().map(function(opt) {
			return '<option' + (opt === type ? ' selected' : '') + '>' + opt + '</option>';
		}).join('');

		var isRegex = (type === 'regex');
		var attrLabel = ATTRIBUTE_LABELS[type];
		var hideAttr = attrLabel ? '' : ' style="display:none;"';
		var hideRegex = isRegex ? '' : ' style="display:none;"';
		// allInstances is an AttributeSelector field only; namespaces an
		// XmlSelector one. Both are real model fields, not `extra` passengers.
		var isAttribute = (type === 'attribute');
		var isXml = (type === 'xml');
		var allInstances = (el.getAttribute('allInstances') === 'true');
		var namespaces = parseExtraObj(el.getAttribute('namespaces'));
		// Pseudo-headers are only meaningful where `attribute` names a source:
		// the attribute and regex selectors. JsonPath/XPath/SDP take their own
		// syntax there.
		var attrListId = 'sel-src-list-' + (scope || 'State') + '-' + idx;
		var attrList = (isAttribute || isRegex) ? ' list="' + attrListId + '"' : '';
		// Table selectors get a dedicated grid editor; their data still rides in
		// the `extra` blob, but the raw textarea is hidden and used only as the
		// sync sink. Other types: the blob carries what the form doesn't model
		// (XML namespaces, future fields), shown only when present.
		var isTable = (type === 'table');
		var tableObj = isTable ? (parseExtraObj(extra).table || {}) : null;
		var tableBlock = isTable
			? '<div class="sel-field sel-table">' + tableEditorHtml(tableObj) + '</div>'
			: '';
		// Show the raw blob only when it actually carries something. XML
		// namespaces used to live here; they have their own editor now, so xml
		// no longer forces it open. Never for table — the grid owns that data.
		var hideExtra = (!isTable && extra) ? '' : ' style="display:none;"';

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
					'<input class="sel-attribute" type="text" value="' + escapeAttr(attribute) + '"' + attrList + ' />' +
					((isAttribute || isRegex) ? pseudoHeaderDatalist(attrListId) : '') +
				'</div>' +

				(isAttribute ?
				'<div class="sel-field sel-allinstances-field">' +
					'<label class="sel-check">' +
						'<input class="sel-allinstances" type="checkbox"' + (allInstances ? ' checked' : '') + ' /> ' +
						'Read every instance of a repeating header' +
						infoTipHtml('About repeating headers',
							'Joins all instances so a <b>matches</b> condition is true if <i>any</i> instance matches. Off = first instance only.') +
					'</label>' +
				'</div>' : '') +

				(isXml ?
				'<div class="sel-field sel-ns-field">' + namespaceEditorHtml(namespaces) + '</div>' : '') +

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
			var type = $row.find('.sel-type').val();
			items.push({
				type: type,
				id: $row.find('.sel-id').val(),
				attribute: $row.find('.sel-attribute').val(),
				pattern: $row.find('.sel-pattern').val(),
				expression: $row.find('.sel-expression').val(),
				// Only read the type-specific controls when that type is
				// rendered — switching type re-renders, and a stale hidden
				// control must not write into the new subclass.
				allInstances: (type === 'attribute') && $row.find('.sel-allinstances').is(':checked'),
				namespaces: (type === 'xml') ? readNamespaces($row) : null,
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
			// Omitted when false / empty, matching the model's NON_DEFAULT and
			// NON_NULL inclusion so round-tripped configs stay minimal.
			if (item.allInstances) el.setAttribute('allInstances', 'true');
			if (item.namespaces && Object.keys(item.namespaces).length > 0) {
				el.setAttribute('namespaces', JSON.stringify(item.namespaces));
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

		// XML namespaces: add a prefix → URI row (blank; persists once filled).
		$(document).off('click.flowSel', '.sel-ns-add').on('click.flowSel', '.sel-ns-add', function() {
			$(this).closest('.sel-ns-field').find('.sel-ns-rows').append(namespaceRowHtml('', ''));
			return false;
		});

		// XML namespaces: remove a row.
		$(document).off('click.flowSel', '.sel-ns-remove').on('click.flowSel', '.sel-ns-remove', function() {
			$(this).closest('.sel-ns-row').remove();
			saveStateSelectors();
			return false;
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
		// Fill the closed-value selects from the model before reading the cell
		// into them: the options in transition.html are only a static fallback
		// for a failed /fsmarMeta fetch. Region and routeModifier keep a blank
		// first option — unset means "container default" (NEUTRAL / ROUTE).
		var method = cell.getAttribute('label') || 'INVITE';
		var methods = window.flowMeta.methods();
		// A canvas rename (F2) can set any label, including an in-dialog method
		// the router will never see. Show it rather than silently displaying
		// INVITE — the validator reports it as an error on export.
		if (methods.indexOf(method) < 0) {
			methods = [method].concat(methods);
		}
		window.flowMeta.fillSelect($('#transition-method'), methods, false);
		window.flowMeta.fillSelect($('#transition-region'), window.flowMeta.regions(), true);
		// NO_ROUTE is never a useful choice here: with routes the container
		// ignores them, without routes it is the default anyway. Not offered —
		// but an imported value stays visible rather than silently rewriting
		// to blank (the validator flags it on export).
		var modifiers = window.flowMeta.routeModifiers().filter(function(m) {
			return m !== 'NO_ROUTE';
		});
		var curModifier = cell.getAttribute('routeModifier') || '';
		if (curModifier && modifiers.indexOf(curModifier) < 0) {
			modifiers = [curModifier].concat(modifiers);
		}
		window.flowMeta.fillSelect($('#transition-route-modifier'), modifiers, true);

		$('#transition-method').val(method);
		$('#transition-when').val(cell.getAttribute('when') || '');
		$('#transition-seq').val(cell.getAttribute('seq') || '');
		$('#transition-txid').val(cell.getAttribute('txId') || '');
		$('#transition-subscriber').val(cell.getAttribute('subscriber') || '');
		$('#transition-region').val(cell.getAttribute('region') || '');
		$('#transition-route-modifier').val(cell.getAttribute('routeModifier') || '');
		var target = cell.target;
		$('#transition-next').val(target ? (target.getAttribute('label') || '') : '');
		renderRoutes(cell);

		// An egress node owns its own routes, so for an egress target we point
		// at the node instead of offering a second place to set them. For every
		// other target the editor is shown: routes on an app-to-app hop are NOT
		// pointless — AppRouter passes them to createRouterInfo alongside the
		// resolved app (AppRouter.java:381-385, Transition.java:207-213), which
		// is the JSR-289 "invoke this app AND push these Route headers" shape.
		var targetIsEgress = flowUtils.isExitCloud(target);
		$('#transition-egress-note').css('display', targetIsEgress ? '' : 'none');
		$('#transition-routes-section').css('display', targetIsEgress ? 'none' : '');
		if (targetIsEgress) {
			$('#transition-vgw-section').hide();
		} else {
			populateTransitionVgw(cell, target);
		}
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

		// Trunk picker: stamp/replace ;vgw=<name> on this transition's routes. With
		// no route yet there is nothing to stamp, so seed the documented form —
		// sip:${To.user}@<app>;vgw=<name> — rather than leaving the pick inert.
		$(document).off('change.flowTxVgw', '#transition-vgw').on('change.flowTxVgw', '#transition-vgw', function() {
			var cell = window.flowSelectedCell;
			if (!cell || !cell.value) return;
			var vgw = $(this).val();
			if (vgw && !$('#transition-routes .route-row').length) {
				var target = cell.target;
				var app = (target && target.getAttribute('label')) || '';
				cell.value.appendChild(cell.value.ownerDocument.createElement('route'));
				renderRoutes(cell);
				$('#transition-routes .route-uri').val('sip:${To.user}@' + app);
			}
			$('#transition-routes .route-uri').each(function() {
				$(this).val(setVgwParam($(this).val(), vgw));
			});
			saveRoutes();
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
		bindDispatch();
		bindStateSelectors();
		bindTransition();
		bindFlowModel();
	});

	return {
		loadFromCell: loadFromCell,
		initView: initView
	};

})();
