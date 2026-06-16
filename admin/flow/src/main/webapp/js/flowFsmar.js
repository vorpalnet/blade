// FSMAR Flow Editor — JSON export/import driver
//
// Bridges the mxGraph editor model to the FsmarExportServlet / FsmarImportServlet.
// Export: serializes the current model XML, POSTs to the export servlet,
//         shows the returned FSMAR 3 JSON in a window with a download button.
// Import: prompts for FSMAR 3 JSON, POSTs to the import servlet, replaces
//         the editor model with the returned mxGraph XML.

window.flowFsmar = (function() {

	// Serializes the current diagram and converts it to FSMAR 3 JSON via the
	// export servlet. Shared by the export dialog and the Route Simulator
	// (which simulates the diagram being edited, before anything is saved).
	function getConfigJson(editor, onSuccess, onError) {
		var enc = new mxCodec(mxUtils.createXmlDocument());
		var node = enc.encode(editor.graph.getModel());
		var xml = mxUtils.getXml(node);

		flowRequest('fsmarExport', 'xml=' + encodeURIComponent(xml), 'POST', function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				onSuccess(resp.getText());
			} else {
				(onError || mxUtils.alert)('Export failed: ' + resp.getStatus() + ' ' + resp.getText());
			}
		});
	}

	function exportToJson(editor) {
		getConfigJson(editor, function(json) {
			// Validate before showing — semantic checks the form can't do
			// (when-expression syntax, enum values, unreachable states,
			// likely typos). Findings render above the JSON.
			flowRequest('fsmarValidate', 'json=' + encodeURIComponent(json), 'POST', function(vresp) {
				var findings = null;
				if (vresp.getStatus() >= 200 && vresp.getStatus() < 300) {
					try {
						findings = JSON.parse(vresp.getText());
					} catch (e) { /* show JSON without findings */ }
				}
				showJsonDialog(json, findings);
			});
		});
	}

	// Renders validation findings as a labeled list. Severity is carried by
	// the text label (ERROR/WARNING/INFO) first — color is a supplement, so
	// the list reads correctly for color-blind operators and in print.
	function findingsHtml(findings) {
		if (!findings) return '';
		var rows = [];
		(findings.errors || []).forEach(function(m) {
			rows.push('<div style="color:#a00;"><b>ERROR:</b> ' + escapeHtml(m) + '</div>');
		});
		(findings.warnings || []).forEach(function(m) {
			rows.push('<div style="color:#850;"><b>WARNING:</b> ' + escapeHtml(m) + '</div>');
		});
		(findings.infos || []).forEach(function(m) {
			rows.push('<div style="color:#446;"><b>INFO:</b> ' + escapeHtml(m) + '</div>');
		});
		if (rows.length === 0) {
			return '<div style="margin-bottom:6px;"><b>Validation:</b> no findings.</div>';
		}
		return '<div style="margin-bottom:6px; max-height:140px; overflow-y:auto; ' +
			'border:1px solid #ccc; padding:6px; background:#fffef5; font-size:11px;">' +
			'<b>Validation findings (' + rows.length + '):</b>' + rows.join('') + '</div>';
	}

	function escapeHtml(s) {
		return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
	}

	function importFromJson(editor) {
		showImportDialog(editor, function(json) {
			importJsonText(editor, json);
		});
	}

	// Imports FSMAR 3 JSON text into the editor: servlet converts to mxGraph
	// XML (honoring stored diagram placements), then — when the config carried
	// no diagram section at all — the bundled hierarchical layout ranks the
	// graph left to right, ingress through states to egress, so a bare config
	// still renders as a readable callflow.
	function importJsonText(editor, json, onDone) {
		var hadDiagram = false;
		try {
			var parsed = JSON.parse(json);
			hadDiagram = !!(parsed && parsed.diagram && Object.keys(parsed.diagram).length > 0);
		} catch (e) {
			// Malformed JSON — fall through; the import servlet reports it.
		}
		flowRequest('fsmarImport', 'json=' + encodeURIComponent(json), 'POST', function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				try {
					var doc = mxUtils.parseXml(resp.getText());
					var dec = new mxCodec(doc);
					dec.decode(doc.documentElement, editor.graph.getModel());
					if (!hadDiagram) {
						autoLayout(editor);
					} else {
						// Stored positions are intentional — keep them, but
						// separate any parallel edges (autoLayout would have,
						// but we're not running it) and center the view (the
						// view translate isn't part of the stored layout).
						if (window.flowParallelEdges) {
							window.flowParallelEdges(editor.graph);
						}
						centerView(editor);
					}
					// Freshly loaded from a file — a clean baseline, not edits.
					if (window.flowDirty) window.flowDirty.clear();
					if (onDone) onDone(true);
				} catch (err) {
					mxUtils.alert('Import failed: ' + err.message);
					if (onDone) onDone(false);
				}
			} else {
				mxUtils.alert('Import failed: ' + resp.getStatus() + ' ' + resp.getText());
				if (onDone) onDone(false);
			}
		});
	}

	// Left-to-right hierarchical layout (roots on the west side): matches how
	// a callflow reads — ingress on the left, egress on the right. Also the
	// toolbar's auto-position action. Ends by centering the view.
	//
	// Spacing matters: the defaults (intra 30 / interRank 100) leave no
	// vertical channel between same-column boxes, so skip-edges (e.g. the
	// default state → b2bua, crossing the ingress/screening column) cut
	// through boxes and the long edge labels land on them. Widening the
	// channels lets the layout route those edges between the boxes.
	// disableEdgeStyle stays true (the layout's default) so it routes the
	// edges rather than pinning the elbow style.
	function autoLayout(editor) {
		var graph = editor.graph;
		var layout = new mxHierarchicalLayout(graph, mxConstants.DIRECTION_WEST);
		layout.intraCellSpacing = 60;      // vertical gap between boxes in a column
		layout.interRankCellSpacing = 140; // horizontal gap between columns (label room)
		layout.parallelEdgeSpacing = 20;   // fan apart multiple edges between the same pair
		graph.getModel().beginUpdate();
		try {
			layout.execute(graph.getDefaultParent());
		} finally {
			graph.getModel().endUpdate();
		}
		// Separate genuinely-parallel edges ONCE, after routing — not on every
		// model change (that re-run was clobbering this layout's routing; see
		// app.js). Guard so our own edit doesn't re-trigger anything.
		if (window.flowParallelEdges) {
			window.flowParallelEdges(graph);
		}
		centerView(editor);
	}

	// Centers the diagram in the visible canvas without changing zoom.
	function centerView(editor) {
		editor.graph.center(true, true);
	}

	// Flex-column dialog body that fills the mxWindow content area. The
	// textarea absorbs whatever height the fixed-size rows (findings, label,
	// buttons) leave over, so nothing clips when validation findings are tall
	// — the old fixed 560x380 textarea overflowed the 600x500 window.
	function dialogBody() {
		var div = document.createElement('div');
		div.style.padding = '10px';
		div.style.boxSizing = 'border-box';
		div.style.height = '100%';
		div.style.display = 'flex';
		div.style.flexDirection = 'column';
		div.style.fontFamily = 'monospace';
		return div;
	}

	function dialogTextarea() {
		var textarea = document.createElement('textarea');
		textarea.style.flex = '1 1 auto';
		textarea.style.width = '100%';
		textarea.style.boxSizing = 'border-box';
		textarea.style.minHeight = '120px';
		textarea.style.fontFamily = 'monospace';
		textarea.style.fontSize = '11px';
		textarea.style.resize = 'none';
		return textarea;
	}

	// Sizes against the viewport instead of hardcoding 600x500, centered.
	function dialogWindow(title, div) {
		var w = Math.min(760, Math.max(480, window.innerWidth - 120));
		var h = Math.min(640, Math.max(400, window.innerHeight - 160));
		var x = Math.max(20, Math.round((window.innerWidth - w) / 2));
		var wnd = new mxWindow(title, div, x, 80, w, h, true, true);
		wnd.setResizable(true);
		return wnd;
	}

	function showJsonDialog(json, findings) {
		var div = dialogBody();

		var fdiv = document.createElement('div');
		fdiv.innerHTML = findingsHtml(findings);
		fdiv.style.flexShrink = '0';
		div.appendChild(fdiv);

		var label = document.createElement('div');
		label.innerHTML = '<b>FSMAR 3 JSON:</b>';
		label.style.marginBottom = '6px';
		label.style.flexShrink = '0';
		div.appendChild(label);

		var textarea = dialogTextarea();
		textarea.value = json;
		div.appendChild(textarea);

		// Publish outcome, labeled by text (PUBLISHED/FAILED), not color alone.
		var status = document.createElement('div');
		status.style.marginTop = '6px';
		status.style.fontSize = '11px';
		status.style.flexShrink = '0';
		div.appendChild(status);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';
		btnDiv.style.flexShrink = '0';

		// Writes config/custom/vorpal/fsmar3.json on AdminServer — the same
		// file a Configurator save writes; the engine SettingsManager reloads
		// it live. Overwrites the running config, hence the confirm().
		var pubBtn = document.createElement('button');
		pubBtn.textContent = 'Save to fsmar3';
		pubBtn.style.cssFloat = 'left';
		pubBtn.title = 'Publish to the live fsmar3 configuration (config/custom/vorpal/fsmar3.json)';
		pubBtn.onclick = function() {
			if (!confirm('Overwrite the live fsmar3 configuration?')) {
				return;
			}
			pubBtn.disabled = true;
			status.textContent = 'Publishing…';
			flowRequest('fsmarPublish', 'json=' + encodeURIComponent(textarea.value), 'POST', function(resp) {
				pubBtn.disabled = false;
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					var r = {};
					try { r = JSON.parse(resp.getText()); } catch (e) { /* show without detail */ }
					status.style.color = '#060';
					status.textContent = 'PUBLISHED: ' + (r.path || 'fsmar3.json')
						+ (r.bytes ? ' (' + r.bytes + ' bytes)' : '');
					// Work is now saved to the live config — no unsaved edits.
					if (window.flowDirty) window.flowDirty.clear();
				} else {
					status.style.color = '#a00';
					status.textContent = 'FAILED: ' + resp.getStatus() + ' ' + resp.getText();
				}
			});
		};
		btnDiv.appendChild(pubBtn);

		var dlBtn = document.createElement('button');
		dlBtn.textContent = 'Download FSMAR.json';
		dlBtn.onclick = function() {
			var blob = new Blob([textarea.value], { type: 'application/json' });
			var url = URL.createObjectURL(blob);
			var a = document.createElement('a');
			a.href = url;
			a.download = 'FSMAR.json';
			a.click();
			URL.revokeObjectURL(url);
		};
		btnDiv.appendChild(dlBtn);

		var copyBtn = document.createElement('button');
		copyBtn.textContent = 'Copy';
		copyBtn.style.marginLeft = '6px';
		copyBtn.onclick = function() {
			textarea.select();
			document.execCommand('copy');
		};
		btnDiv.appendChild(copyBtn);

		div.appendChild(btnDiv);

		var wnd = dialogWindow('Save / Export FSMAR', div);
		wnd.setClosable(true);
		wnd.setVisible(true);
	}

	// --- Live JSON peek ----------------------------------------------------
	// Read-only window showing the FSMAR 3 JSON for the diagram as it is
	// being edited: refreshes (debounced) on every model change, so the
	// mapping from boxes-and-arrows to config is visible at a glance. The
	// selected cell's JSON fragment is highlighted and scrolled into view.
	// Toolbar-toggled; closing the window detaches all listeners.
	var jsonView = null;

	// Renders a parsed JSON value as HTML, wrapping every key/element in a
	// span tagged with its path (e.g. /states/null/triggers/INVITE/
	// transitions/0) so highlights can find fragments by path.
	function jsonToHtml(value, path, indent) {
		var pad = new Array(indent + 1).join(' ');
		if (value === null || typeof value !== 'object') {
			return escapeHtml(JSON.stringify(value));
		}
		if (Array.isArray(value)) {
			if (value.length === 0) return '[ ]';
			var items = value.map(function(v, i) {
				var p = path + '/' + i;
				return pad + '  ' + '<span data-path="' + escapeHtml(p) + '">'
					+ jsonToHtml(v, p, indent + 2) + '</span>';
			});
			return '[\n' + items.join(',\n') + '\n' + pad + ']';
		}
		var keys = Object.keys(value);
		if (keys.length === 0) return '{ }';
		var rows = keys.map(function(k) {
			var p = path + '/' + k;
			return pad + '  ' + '<span data-path="' + escapeHtml(p) + '">'
				+ escapeHtml(JSON.stringify(k)) + ' : '
				+ jsonToHtml(value[k], p, indent + 2) + '</span>';
		});
		return '{\n' + rows.join(',\n') + '\n' + pad + '}';
	}

	// Maps the selected cell to the path of its JSON fragment. States map to
	// their states entry, the ingress cloud to states/null, a transition edge
	// to its exact array slot. Transition index replicates the export
	// servlet's ordering: same source+method edges sorted by seq (edges
	// without seq last, in model order). Returns null for cells with no JSON
	// home (egress cloud, background).
	// State name an edge's source vertex represents: a plain State by label,
	// a named ingress (Gateway with a match) by its label, the default
	// ingress (matchless Gateway / legacy cloud) as "null".
	// A node's state id (the JSON states-map key): its `stateId` attribute, else
	// its label (the app name). Two States can share a label but have distinct
	// ids, so the id — not the label — is what edges and the FSM key on.
	function stateIdOf(v) {
		return v.getAttribute('stateId') || v.getAttribute('label') || 'null';
	}

	function ingressOrStateName(v) {
		if (!v || !v.value || !v.value.tagName) return 'null';
		var tag = v.value.tagName;
		if (tag === 'State') return stateIdOf(v);
		if (tag === 'Gateway') {
			// An egress is never a transition source; treat it as no state.
			if (v.getAttribute('role') === 'egress') return 'null';
			var m = v.getAttribute('match');
			return (m && m.length > 0) ? stateIdOf(v) : 'null';
		}
		return 'null';
	}

	function pathForCell(graph, cell) {
		if (cell == null || cell.value == null || !cell.value.tagName) return null;
		var tag = cell.value.tagName;
		// An egress exit node isn't a state — nothing to simulate from it.
		if (cell.getAttribute('role') === 'egress') return null;
		if (tag === 'State') {
			return '/states/' + stateIdOf(cell);
		}
		if (tag === 'Gateway' || tag === 'Ingress') {
			// Named ingress (has a match) = its own state; default = null.
			var m = cell.getAttribute('match');
			return '/states/' + ((m && m.length > 0) ? stateIdOf(cell) : 'null');
		}
		if (tag !== 'Transition' || cell.source == null || cell.source.value == null) {
			return null;
		}
		var src = cell.source;
		var srcName = ingressOrStateName(src);
		var method = cell.value.getAttribute('label') || 'INVITE';
		var siblings = [];
		for (var i = 0; i < (src.edges ? src.edges.length : 0); i++) {
			var e = src.edges[i];
			if (e.source === src && e.value != null && e.value.tagName === 'Transition'
					&& (e.value.getAttribute('label') || 'INVITE') === method) {
				siblings.push(e);
			}
		}
		siblings.sort(function(a, b) {
			var sa = parseInt(a.value.getAttribute('seq'), 10);
			var sb = parseInt(b.value.getAttribute('seq'), 10);
			if (isNaN(sa)) sa = Number.MAX_SAFE_INTEGER;
			if (isNaN(sb)) sb = Number.MAX_SAFE_INTEGER;
			return sa - sb;
		});
		var idx = siblings.indexOf(cell);
		if (idx < 0) return null;
		return '/states/' + srcName + '/triggers/' + method + '/transitions/' + idx;
	}

	function toggleJsonView(editor) {
		if (jsonView != null) {
			jsonView.destroy(); // DESTROY event runs the cleanup below
			return;
		}

		var graph = editor.graph;
		var pre = document.createElement('pre');
		pre.style.cssText = 'margin:0; padding:10px; height:100%; box-sizing:border-box;'
			+ ' overflow:auto; font-size:11px; font-family:monospace; background:#fff;'
			+ ' position:relative;';

		var w = Math.min(460, Math.max(320, Math.round(window.innerWidth * 0.3)));
		var h = Math.max(300, window.innerHeight - 220);
		var x = Math.max(20, window.innerWidth - w - 40);
		var wnd = new mxWindow('FSMAR JSON — live, read-only', pre, x, 80, w, h, true, true);
		wnd.setResizable(true);
		wnd.setClosable(true);

		var selectedPath = null;
		var highlighted = null;

		var applyHighlight = function(scroll) {
			if (highlighted != null) {
				highlighted.style.background = '';
				highlighted.style.outline = '';
				highlighted.style.fontWeight = '';
				highlighted = null;
			}
			if (selectedPath == null) {
				console.debug('[jsonView] selection has no JSON fragment');
				return;
			}
			var spans = pre.querySelectorAll('span[data-path]');
			for (var i = 0; i < spans.length; i++) {
				if (spans[i].getAttribute('data-path') === selectedPath) {
					highlighted = spans[i];
					// Heavy outline + weight, not color alone.
					highlighted.style.background = '#ffe28a';
					highlighted.style.outline = '2px solid #444';
					highlighted.style.fontWeight = 'bold';
					if (scroll) {
						pre.scrollTop = Math.max(0, highlighted.offsetTop - 60);
					}
					break;
				}
			}
			console.debug('[jsonView] path=' + selectedPath
					+ ' matched=' + (highlighted != null)
					+ ' spans=' + spans.length);
		};

		var timer = null;
		var refresh = function() {
			if (timer != null) {
				clearTimeout(timer);
			}
			timer = setTimeout(function() {
				timer = null;
				getConfigJson(editor, function(json) {
					try {
						pre.innerHTML = jsonToHtml(JSON.parse(json), '', 0);
					} catch (e) {
						pre.textContent = json;
					}
					applyHighlight(false); // re-tag after re-render, keep scroll
				}, function(err) {
					// Mid-edit states (e.g. a dangling edge being drawn) are
					// not exportable — say why instead of going blank.
					pre.textContent = 'Not exportable right now:\n\n' + err;
					highlighted = null;
				});
			}, 400);
		};

		var selectionListener = function() {
			selectedPath = pathForCell(graph, graph.getSelectionCell());
			applyHighlight(true);
		};

		var model = graph.getModel();
		model.addListener(mxEvent.CHANGE, refresh);
		graph.getSelectionModel().addListener(mxEvent.CHANGE, selectionListener);
		wnd.addListener(mxEvent.DESTROY, function() {
			model.removeListener(refresh);
			graph.getSelectionModel().removeListener(selectionListener);
			if (timer != null) {
				clearTimeout(timer);
			}
			jsonView = null;
		});

		jsonView = wnd;
		wnd.setVisible(true);
		refresh();
		selectionListener(); // pick up any pre-existing selection
	}

	function showImportDialog(editor, callback) {
		var div = dialogBody();

		var label = document.createElement('div');
		label.innerHTML = '<b>Open a config:</b> choose a file, paste JSON below, '
			+ 'or load the live config / sample.';
		label.style.marginBottom = '6px';
		label.style.flexShrink = '0';
		div.appendChild(label);

		var textarea = dialogTextarea();
		div.appendChild(textarea);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';
		btnDiv.style.flexShrink = '0';

		// Open a local file (FSMAR 3 JSON, or a legacy mxGraph XML diagram).
		// Loads straight into the editor and closes the dialog — the textarea
		// path is for paste / live / sample.
		var fileBtn = document.createElement('button');
		fileBtn.textContent = 'Choose file…';
		fileBtn.style.cssFloat = 'left';
		fileBtn.title = 'Open an FSMAR 3 JSON file (legacy XML diagrams also open)';
		fileBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			window.flowUtils.selectFile(editor);
		};
		btnDiv.appendChild(fileBtn);

		// Pulls the running config (config/custom/vorpal/fsmar3.json) into the
		// textarea — the everyday loop is Load live fsmar3 → edit → Save to
		// fsmar3, no files involved.
		var liveBtn = document.createElement('button');
		liveBtn.textContent = 'Load live fsmar3';
		liveBtn.style.cssFloat = 'left';
		liveBtn.title = 'Fill the textarea with the live fsmar3 configuration';
		liveBtn.onclick = function() {
			flowRequest('fsmarPublish', null, 'GET', function(resp) {
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					textarea.value = resp.getText();
				} else {
					mxUtils.alert('Load failed: ' + resp.getStatus() + ' ' + resp.getText());
				}
			});
		};
		btnDiv.appendChild(liveBtn);

		// The canonical sample (_samples/fsmar3.json.SAMPLE, generated from
		// AppRouterConfigurationSample) — single source of truth; no JS copy
		// to drift from it.
		var exampleBtn = document.createElement('button');
		exampleBtn.textContent = 'Load sample';
		exampleBtn.style.cssFloat = 'left';
		exampleBtn.style.marginLeft = '6px';
		exampleBtn.title = 'Fill the textarea with the canonical fsmar3 sample (_samples/fsmar3.json.SAMPLE)';
		exampleBtn.onclick = function() {
			flowRequest('fsmarPublish?sample=1', null, 'GET', function(resp) {
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					textarea.value = resp.getText();
				} else {
					mxUtils.alert('Load failed: ' + resp.getStatus() + ' ' + resp.getText());
				}
			});
		};
		btnDiv.appendChild(exampleBtn);

		var importBtn = document.createElement('button');
		importBtn.textContent = 'Import';
		importBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			callback(textarea.value);
		};
		btnDiv.appendChild(importBtn);

		var cancelBtn = document.createElement('button');
		cancelBtn.textContent = 'Cancel';
		cancelBtn.style.marginLeft = '6px';
		cancelBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
		};
		btnDiv.appendChild(cancelBtn);

		div.appendChild(btnDiv);

		var wnd = dialogWindow('Open / Load FSMAR', div);
		wnd.setClosable(true);
		wnd.setVisible(true);
	}

	return {
		exportToJson: exportToJson,
		importFromJson: importFromJson,
		importJsonText: importJsonText,
		getConfigJson: getConfigJson,
		autoLayout: autoLayout,
		toggleJsonView: toggleJsonView
	};

})();
