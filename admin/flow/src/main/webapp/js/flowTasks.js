// FSMAR Flow Editor — property panel bindings
//
// Binds the currently-selected mxGraph cell's XML user object to the visible
// property panel. The cell's wrapper element (State, Transition, FlowModel)
// is the source of truth; panels read on selection change and write back on
// every input change via mxCellAttributeChange for proper undo support.

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

	function getChildElementsOf(el, tagName) {
		var out = [];
		if (!el || !el.childNodes) return out;
		for (var i = 0; i < el.childNodes.length; i++) {
			var c = el.childNodes[i];
			if (c.nodeType === 1 && c.nodeName === tagName) {
				out.push(c);
			}
		}
		return out;
	}

	// ----- node panel (shared by State / Ingress / Egress) ------------------

	// Find the .node-name input inside the panel that matches the cell's tag
	// (e.g. '#State', '#Ingress', '#Egress'). Each panel loads state.html so all
	// three contain a .node-name input — we need to scope to the right one.
	function nodeNameInput(cell) {
		if (!cell || !cell.value) return $();
		return $('#' + cell.value.tagName + ' .node-name');
	}

	function loadNode(cell) {
		nodeNameInput(cell).val(cell.getAttribute('label') || '');
	}

	function bindNode() {
		$(document).off('change.flowNode', '.node-name').on('change.flowNode', '.node-name', function() {
			var cell = window.flowSelectedCell;
			if (cell) {
				window.flowGraph.cellLabelChanged(cell, $(this).val(), false);
			}
		});
	}

	// ----- transition panel --------------------------------------------------

	function loadTransition(cell) {
		$('#transition-method').val(cell.getAttribute('label') || 'INVITE');
		$('#transition-txid').val(cell.getAttribute('txId') || '');
		$('#transition-subscriber').val(cell.getAttribute('subscriber') || '');
		var target = cell.target;
		$('#transition-next').val(target ? (target.getAttribute('label') || '') : '');
		renderSelectors(cell);
		renderRoutes(cell);
	}

	function renderGroups(cell) {
		var $c = $('#transition-groups').empty();
		var groups = getChildElements(cell, 'selectorGroup');
		for (var g = 0; g < groups.length; g++) {
			if (g > 0) $c.append('<div class="or-divider">OR</div>');
			$c.append(groupRowHtml(groups[g], g));
		}
	}

	function groupRowHtml(groupEl, gIdx) {
		var selectors = getChildElementsOf(groupEl, 'selector');
		var selectorHtml = '';
		for (var i = 0; i < selectors.length; i++) {
			selectorHtml += selectorRowHtml(selectors[i], i);
		}
		return '' +
			'<fieldset class="group-row" data-gidx="' + gIdx + '">' +
				'<legend>Group ' + (gIdx + 1) +
					' <i class="fas fa-minus-circle remove-btn remove-group" title="Remove group"></i>' +
				'</legend>' +
				'<div style="margin-bottom: 4px;">' +
					'<i class="fas fa-plus-circle add-btn add-selector" title="Add selector"></i>' +
					' <span class="hint">All selectors must match (AND)</span>' +
				'</div>' +
				'<div class="group-selectors">' + selectorHtml + '</div>' +
			'</fieldset>';
	}

	function renderSelectors(cell) {
		renderGroups(cell);
	}

	// Map a stored 'attribute' string to a UI type + header name.
	// Anything that isn't a recognized special keyword is treated as a header name.
	function attributeToType(attr) {
		if (!attr) return { type: 'Header', name: '' };
		switch (attr) {
			case 'Request-URI': case 'RequestURI': case 'requestURI':
			case 'ruri': case 'Ruri': case 'RURI':
				return { type: 'Request-URI', name: '' };
			case 'body': case 'Body': case 'content': case 'Content':
				return { type: 'Body', name: '' };
			case 'Origin-IP': case 'OriginIP': case 'originIP':
				return { type: 'Origin-IP', name: '' };
			default:
				return { type: 'Header', name: attr };
		}
	}

	// Map UI type + header name back to the canonical 'attribute' string.
	function typeToAttribute(type, name) {
		switch (type) {
			case 'Request-URI': return 'Request-URI';
			case 'Body':        return 'body';
			case 'Origin-IP':   return 'Origin-IP';
			case 'Header':
			default:
				return name || '';
		}
	}

	function selectorRowHtml(el, idx) {
		var id = el.getAttribute('id') || '';
		var mode = (el.getAttribute('type') || 'REGEX').toUpperCase();
		if (mode !== 'REGEX' && mode !== 'JSONPATH' && mode !== 'XPATH') mode = 'REGEX';
		var attribute = el.getAttribute('attribute') || '';
		var pattern = el.getAttribute('pattern') || '';
		var expression = el.getAttribute('expression') || '';
		var t = attributeToType(attribute);

		// Gather <extract name="" path=""/> children
		var extracts = [];
		if (el.childNodes) {
			for (var i = 0; i < el.childNodes.length; i++) {
				var c = el.childNodes[i];
				if (c.nodeType === 1 && c.nodeName === 'extract') {
					extracts.push({
						name: c.getAttribute('name') || '',
						path: c.getAttribute('path') || ''
					});
				}
			}
		}

		var hideHeader = (t.type !== 'Header') ? ' style="display:none;"' : '';
		var hidePattern = (mode !== 'REGEX') ? ' style="display:none;"' : '';
		var hideExtractions = (mode === 'REGEX') ? ' style="display:none;"' : '';

		var sourceOptions = ['Header', 'Request-URI', 'Body', 'Origin-IP'].map(function(opt) {
			return '<option' + (opt === t.type ? ' selected' : '') + '>' + opt + '</option>';
		}).join('');

		var modeOptions = ['REGEX', 'JSONPATH', 'XPATH'].map(function(opt) {
			return '<option' + (opt === mode ? ' selected' : '') + '>' + opt + '</option>';
		}).join('');

		var extractsHtml = extracts.map(extractRowHtml).join('');

		var patternPlaceholder = '(?&lt;user&gt;[^@]+)@(?&lt;host&gt;.*)';

		// Existing selector id is preserved silently via data-id (UI doesn't expose it).
		return '' +
			'<fieldset class="selector-row" data-idx="' + idx + '" data-id="' + escapeAttr(id) + '">' +
				'<legend>Selector ' + (idx + 1) +
					' <i class="fas fa-minus-circle remove-btn remove-selector" title="Remove"></i>' +
				'</legend>' +

				'<div class="sel-field">' +
					'<label>Source</label>' +
					'<select class="sel-type">' + sourceOptions + '</select>' +
				'</div>' +

				'<div class="sel-field sel-header-field"' + hideHeader + '>' +
					'<label>Header name</label>' +
					'<input class="sel-header" type="text" value="' + escapeAttr(t.name) +
						'" placeholder="e.g. From, To, X-Cisco-Gucid" />' +
				'</div>' +

				'<div class="sel-field">' +
					'<label>Mode</label>' +
					'<select class="sel-mode">' + modeOptions + '</select>' +
				'</div>' +

				'<div class="sel-field sel-pattern-field"' + hidePattern + '>' +
					'<label>Pattern <span class="hint">(regex with named groups)</span></label>' +
					'<input class="sel-pattern" type="text" value="' + escapeAttr(pattern) +
						'" placeholder="' + patternPlaceholder + '" />' +
				'</div>' +

				'<div class="sel-field sel-extractions-field"' + hideExtractions + '>' +
					'<label>Extractions ' +
						'<i class="fas fa-plus-circle add-btn add-extract" title="Add extraction"></i>' +
					'</label>' +
					'<div class="extract-list">' + extractsHtml + '</div>' +
				'</div>' +

				'<div class="sel-field">' +
					'<label>Expression <span class="hint">(${name} template)</span></label>' +
					'<input class="sel-expression" type="text" value="' + escapeAttr(expression) +
						'" placeholder="${user}@${host}" />' +
				'</div>' +

			'</fieldset>';
	}

	function extractRowHtml(ex) {
		return '<div class="extract-row">' +
			'<input class="ex-name" type="text" value="' + escapeAttr(ex.name || '') +
				'" placeholder="name" />' +
			'<input class="ex-path" type="text" value="' + escapeAttr(ex.path || '') +
				'" placeholder="$.path  or  //xpath" />' +
			' <i class="fas fa-minus-circle remove-btn remove-extract" title="Remove extraction"></i>' +
			'</div>';
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
			'<input class="route-uri" type="text" value="' + escapeAttr(uri) + '" placeholder="sip:proxy.example.com" />' +
			' <i class="fas fa-minus-circle remove-btn remove-route" title="Remove route"></i>' +
			'</div>';
	}

	function escapeAttr(s) {
		return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
	}

	function collectSelectorFromRow($row) {
		var type = $row.find('.sel-type').val();
		var name = $row.find('.sel-header').val();
		var mode = ($row.find('.sel-mode').val() || 'REGEX').toUpperCase();
		var extractions = [];
		$row.find('.extract-row').each(function() {
			var n = $(this).find('.ex-name').val();
			var p = $(this).find('.ex-path').val();
			if (n || p) extractions.push({ name: n || '', path: p || '' });
		});
		return {
			id: $row.attr('data-id') || '',
			type: (mode === 'REGEX') ? '' : mode,
			attribute: typeToAttribute(type, name),
			pattern: (mode === 'REGEX') ? $row.find('.sel-pattern').val() : '',
			extractions: (mode !== 'REGEX') ? extractions : [],
			expression: $row.find('.sel-expression').val()
		};
	}

	function writeSelectorEl(el, item) {
		if (item.id) el.setAttribute('id', item.id);
		if (item.type) el.setAttribute('type', item.type);
		if (item.attribute) el.setAttribute('attribute', item.attribute);
		if (item.pattern) el.setAttribute('pattern', item.pattern);
		if (item.expression) el.setAttribute('expression', item.expression);
		if (item.extractions && item.extractions.length > 0) {
			var doc = el.ownerDocument;
			for (var k = 0; k < item.extractions.length; k++) {
				var ex = doc.createElement('extract');
				ex.setAttribute('name', item.extractions[k].name);
				ex.setAttribute('path', item.extractions[k].path);
				el.appendChild(ex);
			}
		}
	}

	function saveGroups() {
		var cell = window.flowSelectedCell;
		if (!cell) return;

		var groupItems = [];
		$('#transition-groups .group-row').each(function() {
			var selectors = [];
			$(this).find('.selector-row').each(function() {
				selectors.push(collectSelectorFromRow($(this)));
			});
			groupItems.push({ selectors: selectors });
		});

		setChildElements(cell, 'selectorGroup', groupItems, function(groupEl, groupItem) {
			var doc = groupEl.ownerDocument;
			for (var s = 0; s < groupItem.selectors.length; s++) {
				var selEl = doc.createElement('selector');
				writeSelectorEl(selEl, groupItem.selectors[s]);
				groupEl.appendChild(selEl);
			}
		});
	}

	function saveSelectors() {
		saveGroups();
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
		// Method change updates label
		$(document).off('change.flowTx', '#transition-method').on('change.flowTx', '#transition-method', function() {
			var cell = window.flowSelectedCell;
			if (cell) {
				window.flowGraph.cellLabelChanged(cell, $(this).val(), false);
			}
		});

		// Transition ID
		$(document).off('change.flowTx', '#transition-txid').on('change.flowTx', '#transition-txid', function() {
			setAttr(window.flowSelectedCell, 'txId', $(this).val());
		});

		// Subscriber
		$(document).off('change.flowTx', '#transition-subscriber').on('change.flowTx', '#transition-subscriber', function() {
			setAttr(window.flowSelectedCell, 'subscriber', $(this).val());
		});

		// Add selector group
		$(document).off('click.flowTx', '#add-group').on('click.flowTx', '#add-group', function() {
			var cell = window.flowSelectedCell;
			if (!cell || !cell.value) return;
			var doc = cell.value.ownerDocument;
			var groupEl = doc.createElement('selectorGroup');
			cell.value.appendChild(groupEl);
			renderGroups(cell);
			return false;
		});

		// Remove selector group
		$(document).off('click.flowTx', '.remove-group').on('click.flowTx', '.remove-group', function() {
			$(this).closest('.group-row').remove();
			// Remove OR dividers that are now orphaned
			$('#transition-groups .or-divider').first().remove();
			saveGroups();
			return false;
		});

		// Add selector (within a group)
		$(document).off('click.flowTx', '.add-selector').on('click.flowTx', '.add-selector', function() {
			var cell = window.flowSelectedCell;
			if (!cell || !cell.value) return;
			// Find the group-row this button belongs to, then save+re-render
			var $group = $(this).closest('.group-row');
			var doc = cell.value.ownerDocument;
			var dummyEl = doc.createElement('selector');
			$group.find('.group-selectors').append(selectorRowHtml(dummyEl, $group.find('.selector-row').length));
			saveGroups();
			return false;
		});

		// Remove selector
		$(document).off('click.flowTx', '.remove-selector').on('click.flowTx', '.remove-selector', function() {
			$(this).closest('.selector-row').remove();
			saveGroups();
			return false;
		});

		// Save selector field changes on blur
		$(document).off('change.flowTx', '#transition-groups input').on('change.flowTx', '#transition-groups input', function() {
			saveSelectors();
		});

		// Source dropdown change: toggle header field visibility AND save
		$(document).off('change.flowTx', '#transition-groups .sel-type').on('change.flowTx', '#transition-groups .sel-type', function() {
			var $row = $(this).closest('.selector-row');
			var showHeader = ($(this).val() === 'Header');
			$row.find('.sel-header-field').css('display', showHeader ? '' : 'none');
			saveSelectors();
		});

		// Mode dropdown change: toggle pattern vs extractions field AND save
		$(document).off('change.flowTx', '#transition-groups .sel-mode').on('change.flowTx', '#transition-groups .sel-mode', function() {
			var $row = $(this).closest('.selector-row');
			var isRegex = ($(this).val() === 'REGEX');
			$row.find('.sel-pattern-field').css('display', isRegex ? '' : 'none');
			$row.find('.sel-extractions-field').css('display', isRegex ? 'none' : '');
			saveSelectors();
		});

		// Add extraction row
		$(document).off('click.flowTx', '.add-extract').on('click.flowTx', '.add-extract', function() {
			var $row = $(this).closest('.selector-row');
			$row.find('.extract-list').append(extractRowHtml({ name: '', path: '' }));
			saveSelectors();
			return false;
		});

		// Remove extraction row
		$(document).off('click.flowTx', '.remove-extract').on('click.flowTx', '.remove-extract', function() {
			$(this).closest('.extract-row').remove();
			saveSelectors();
			return false;
		});

		// Save extraction field changes
		$(document).off('change.flowTx', '#transition-groups .extract-row input').on('change.flowTx', '#transition-groups .extract-row input', function() {
			saveSelectors();
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

	function rebuildView(graph) {
		var methods = collectMethods(graph);
		// Drop hidden entries for methods that no longer exist
		for (var key in hiddenMethods) {
			if (methods.indexOf(key) < 0) delete hiddenMethods[key];
		}
		var $list = $('#view-methods').empty();
		if (methods.length === 0) {
			$list.append('<div class="view-empty">No transitions yet</div>');
			return;
		}
		for (var i = 0; i < methods.length; i++) {
			var m = methods[i];
			var checked = hiddenMethods[m] ? '' : ' checked';
			$list.append(
				'<label class="view-item"><input type="checkbox" class="view-method" value="' +
				escapeAttr(m) + '"' + checked + '> ' + escapeAttr(m) + '</label>'
			);
		}
	}

	function applyFilter(graph) {
		var cells = graph.getModel().cells;
		for (var id in cells) {
			var cell = cells[id];
			if (cell && cell.edge && cell.value && cell.value.tagName === 'Transition') {
				var label = cell.getAttribute('label') || '(none)';
				cell.visible = !hiddenMethods[label];
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
		$(document).off('click.flowView', '#view-show-all').on('click.flowView', '#view-show-all', function() {
			hiddenMethods = {};
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
		if (tag === 'State' || tag === 'Ingress' || tag === 'Egress') {
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
		bindTransition();
		bindFlowModel();
	});

	return {
		loadFromCell: loadFromCell,
		initView: initView
	};

})();
