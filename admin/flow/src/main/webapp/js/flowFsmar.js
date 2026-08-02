// FSMAR Flow Editor — JSON export/import driver
//
// Bridges the mxGraph editor model to the FsmarExportServlet / FsmarImportServlet.
// Export: serializes the current model XML, POSTs to the export servlet,
//         shows the returned FSMAR 3 JSON in a window with a download button.
// Import: prompts for FSMAR 3 JSON, POSTs to the import servlet, replaces
//         the editor model with the returned mxGraph XML.

window.flowFsmar = (function() {

	// ----- publish target (domain / cluster / server) -------------------------
	//
	// SettingsManager merges domain -> _clusters/<c>/ -> _servers/<s>/, so a
	// narrower file overrides the broader one. Almost every deployment only
	// ever uses the domain file, so that is the default everywhere and the
	// overlay choices carry a warning: this editor exports a COMPLETE config,
	// and a complete config in an overlay overrides the domain wholesale — that
	// cluster stops picking up domain changes until the overlay is deleted.
	// (Overlays merge per-field, so a hand-written partial overlay is a
	// different, subtler thing; the editor cannot produce one.)

	var DEFAULT_TARGET = 'domain';

	// The target is session state, not a per-dialog control: whichever config
	// you loaded is the one you publish back to. Two independent pickers would
	// let you load a cluster overlay in the import dialog and then publish it
	// domain-wide from the export dialog, because that dialog's picker had
	// reset to the default — reading and writing different scopes in two
	// clicks. Both pickers share this instead.
	var currentTarget = DEFAULT_TARGET;

	// Builds a labelled <select> of publish targets and hands it back with the
	// row to insert. `onChange` fires with the selected target object.
	// Falls back to a domain-only list if /fsmarTargets is unreachable, so the
	// dialogs still work on an older deployment.
	function buildTargetPicker(onChange) {
		var row = document.createElement('div');
		row.style.margin = '0 0 6px';
		row.style.flexShrink = '0';

		var label = document.createElement('span');
		label.textContent = 'Configuration: ';
		label.style.fontSize = '11px';
		row.appendChild(label);

		var select = document.createElement('select');
		select.style.fontSize = '11px';
		row.appendChild(select);

		var note = document.createElement('div');
		note.style.fontSize = '10.5px';
		note.style.marginTop = '3px';
		note.style.display = 'none';
		row.appendChild(note);

		var targets = [];

		function selected() {
			for (var i = 0; i < targets.length; i++) {
				if (targets[i].id === select.value) return targets[i];
			}
			return { id: DEFAULT_TARGET, type: 'domain', displayName: 'Domain (all servers)' };
		}

		function refreshNote() {
			var t = selected();
			if (t.type === 'domain') {
				note.style.display = 'none';
			} else {
				note.style.display = '';
				note.textContent = 'Overrides the domain configuration for this '
					+ t.type + ' only. It will stop inheriting later domain changes.';
			}
		}

		select.onchange = function() {
			currentTarget = select.value;
			refreshNote();
			if (onChange) onChange(selected());
		};

		function render() {
			select.innerHTML = '';
			for (var i = 0; i < targets.length; i++) {
				var opt = document.createElement('option');
				opt.value = targets[i].id;
				opt.textContent = targets[i].displayName
					+ (targets[i].exists === false ? ' — none yet' : '');
				select.appendChild(opt);
			}
			// Carry the session's target across dialogs, falling back to the
			// domain if it has gone away since (a server stopped, say).
			select.value = currentTarget;
			if (!select.value) {
				select.value = DEFAULT_TARGET;
				currentTarget = DEFAULT_TARGET;
			}
			refreshNote();
		}

		targets = [{ id: DEFAULT_TARGET, type: 'domain', displayName: 'Domain (all servers)' }];
		render();

		flowRequest('fsmarTargets', null, 'GET', function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				try {
					var parsed = JSON.parse(resp.getText());
					if (parsed && parsed.targets && parsed.targets.length) {
						targets = parsed.targets;
						render();
					}
				} catch (e) {
					// keep the domain-only fallback
				}
			}
		});

		return {
			row: row,
			target: function() { return selected().id; },
			targetInfo: selected
		};
	}

	// ----- publish diff -------------------------------------------------------
	//
	// The editor models the routing topology; logging/analytics/events and any
	// future root block ride through untouched only if the live config was
	// loaded first. Publishing something built from a sample therefore drops
	// them. FsmarDiffServlet does the comparison; this renders it.
	//
	// Operations are named in words (added / removed / changed), never carried
	// by color alone.

	function fetchDiff(target, json, done) {
		flowRequest('fsmarDiff',
				'target=' + encodeURIComponent(target) + '&json=' + encodeURIComponent(json),
				'POST', function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				try {
					done(JSON.parse(resp.getText()));
					return;
				} catch (e) {
					done(null, 'unreadable response');
					return;
				}
			}
			done(null, resp.getStatus() + ' ' + resp.getText());
		});
	}

	function diffSummary(diff) {
		var bits = [];
		if (diff.removed) bits.push(diff.removed + ' removed');
		if (diff.added) bits.push(diff.added + ' added');
		if (diff.changed) bits.push(diff.changed + ' changed');
		return bits.length ? bits.join(', ') : 'no differences';
	}

	var DIFF_LABEL = { REMOVED: 'removed', ADDED: 'added', CHANGED: 'changed' };

	function showDiff(diff, info) {
		var div = document.createElement('div');
		div.style.padding = '8px';
		div.style.height = '100%';
		div.style.boxSizing = 'border-box';
		div.style.overflow = 'auto';
		div.style.fontSize = '11px';

		var head = document.createElement('div');
		head.style.marginBottom = '6px';
		head.innerHTML = '<b>' + escapeHtml(info.displayName) + '</b> &mdash; '
			+ escapeHtml(diff.path);
		div.appendChild(head);

		if (!diff.targetExists) {
			var none = document.createElement('div');
			none.textContent = 'Nothing published here yet — publishing creates this file.';
			div.appendChild(none);
		} else if (diff.identical) {
			var same = document.createElement('div');
			same.textContent = 'Identical to the live configuration.';
			div.appendChild(same);
		} else {
			var sum = document.createElement('div');
			sum.style.marginBottom = '6px';
			sum.textContent = diffSummary(diff)
				+ (diff.truncated ? ' (showing the first ' + diff.entries.length + ')' : '');
			div.appendChild(sum);

			if (diff.removedRootKeys && diff.removedRootKeys.length) {
				var warn = document.createElement('div');
				warn.style.margin = '0 0 8px';
				warn.style.padding = '6px';
				warn.style.border = '1px solid var(--vorpal-slate-200, #ccc)';
				warn.innerHTML = '<b>Removes live top-level settings:</b> '
					+ escapeHtml(diff.removedRootKeys.join(', '))
					+ '. These are edited in the Configurator, not here — load the live'
					+ ' configuration first if you meant to keep them.';
				div.appendChild(warn);
			}

			var table = document.createElement('table');
			table.style.borderCollapse = 'collapse';
			table.style.width = '100%';
			table.innerHTML = '<thead><tr>'
				+ '<th style="text-align:left; padding:2px 6px 2px 0;">Change</th>'
				+ '<th style="text-align:left; padding:2px 6px 2px 0;">Where</th>'
				+ '<th style="text-align:left; padding:2px 6px 2px 0;">Live</th>'
				+ '<th style="text-align:left; padding:2px 0;">After publish</th>'
				+ '</tr></thead>';
			var tbody = document.createElement('tbody');
			for (var i = 0; i < diff.entries.length; i++) {
				var e = diff.entries[i];
				var tr = document.createElement('tr');
				tr.innerHTML =
					'<td style="padding:2px 6px 2px 0; white-space:nowrap;"><b>'
						+ escapeHtml(DIFF_LABEL[e.op] || e.op) + '</b></td>'
					+ '<td style="padding:2px 6px 2px 0; font-family:monospace;">'
						+ escapeHtml(e.path) + '</td>'
					+ '<td style="padding:2px 6px 2px 0; font-family:monospace;">'
						+ escapeHtml(e.from === undefined ? '—' : e.from) + '</td>'
					+ '<td style="padding:2px 0; font-family:monospace;">'
						+ escapeHtml(e.to === undefined ? '—' : e.to) + '</td>';
				tbody.appendChild(tr);
			}
			table.appendChild(tbody);
			div.appendChild(table);
		}

		var wnd = new mxWindow('Compare with live', div, 80, 80, 720, 420, true, true);
		wnd.setMaximizable(true);
		wnd.setScrollable(true);
		wnd.setResizable(true);
		wnd.setClosable(true);
		wnd.setVisible(true);
	}

	function escapeHtml(s) {
		return String(s === undefined || s === null ? '' : s)
			.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
	}

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

	// Public entry point for loading a config into the editor. Detects a legacy
	// FSMAR 2 configuration (the old `previous` map, no v3 `states`) and converts
	// it to FSMAR 3 first — the editor only ever models, and saves, FSMAR 3.
	function importJsonText(editor, json, onDone) {
		var parsed = null;
		try {
			parsed = JSON.parse(json);
		} catch (e) {
			// Malformed JSON — let importFsmar3Text's servlet report it.
		}
		if (parsed && parsed.previous && typeof parsed.previous === 'object'
				&& !Array.isArray(parsed.previous) && !parsed.states) {
			convertFsmar2ThenImport(editor, json, onDone);
			return;
		}
		importFsmar3Text(editor, json, onDone);
	}

	// Imports an FSMAR 3 config: the servlet converts it to mxGraph XML (honoring
	// stored diagram placements), then — when the config carried no diagram
	// section at all — the bundled hierarchical layout ranks the graph left to
	// right, ingress through states to egress, so a bare config still renders as
	// a readable callflow.
	function importFsmar3Text(editor, json, onDone) {
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

	// Converts a legacy FSMAR 2 config to FSMAR 3 (server-side, via the framework
	// Fsmar2Converter), shows what changed plus any review items, then loads the
	// converted FSMAR 3 into the editor on confirmation.
	function convertFsmar2ThenImport(editor, fsmar2Json, onDone) {
		flowRequest('fsmarConvert', 'fsmar2=' + encodeURIComponent(fsmar2Json), 'POST', function(resp) {
			if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
				mxUtils.alert('FSMAR 2 conversion failed: ' + resp.getStatus() + ' ' + resp.getText());
				if (onDone) onDone(false);
				return;
			}
			var result;
			try {
				result = JSON.parse(resp.getText());
			} catch (e) {
				mxUtils.alert('Conversion response was not valid JSON.');
				if (onDone) onDone(false);
				return;
			}
			showConversionDialog(result, function() {
				importFsmar3Text(editor, result.json, onDone);
			}, function() {
				if (onDone) onDone(false);
			});
		});
	}

	// Conversion summary: counts, the converted FSMAR 3 JSON (read-only preview),
	// and the converter's warnings — REVIEW items (fail-closed conditions that
	// can never fire) shown as errors, NOTE items as info, by text label first so
	// they read correctly without relying on color. Load applies it; Cancel bails
	// before anything touches the diagram.
	function showConversionDialog(result, onProceed, onCancel) {
		var div = dialogBody();

		var summary = document.createElement('div');
		summary.style.flexShrink = '0';
		summary.style.marginBottom = '6px';
		summary.innerHTML = '<b>Converted FSMAR 2 &rarr; FSMAR 3.</b> '
			+ (result.states || 0) + ' state(s), ' + (result.transitions || 0)
			+ ' transition(s), ' + (result.selectors || 0) + ' selector(s). '
			+ 'The editor saves FSMAR 3.';
		div.appendChild(summary);

		// Reuse the validation findings renderer: REVIEW -> error, NOTE -> info.
		var findings = { errors: [], warnings: [], infos: [] };
		(result.warnings || []).forEach(function(w) {
			if (w.indexOf('REVIEW') === 0) {
				findings.errors.push(w);
			} else if (w.indexOf('NOTE') === 0) {
				findings.infos.push(w);
			} else {
				findings.warnings.push(w);
			}
		});
		var fdiv = document.createElement('div');
		fdiv.style.flexShrink = '0';
		fdiv.innerHTML = findingsHtml(findings);
		div.appendChild(fdiv);

		if (result.needsReview) {
			var warn = document.createElement('div');
			warn.style.flexShrink = '0';
			warn.style.margin = '4px 0';
			warn.style.color = '#a00';
			warn.innerHTML = '<b>Some conditions could not be converted faithfully</b> and were set '
				+ 'to never fire (fail closed). Review the items above and fix them before this '
				+ 'goes live.';
			div.appendChild(warn);
		}

		var label = document.createElement('div');
		label.innerHTML = '<b>Converted FSMAR 3 JSON (preview):</b>';
		label.style.flexShrink = '0';
		label.style.margin = '6px 0 4px';
		div.appendChild(label);

		var textarea = dialogTextarea();
		textarea.value = result.json || '';
		textarea.readOnly = true;
		div.appendChild(textarea);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';
		btnDiv.style.flexShrink = '0';

		var loadBtn = document.createElement('button');
		loadBtn.textContent = 'Load into editor';
		loadBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			onProceed();
		};
		btnDiv.appendChild(loadBtn);

		var cancelBtn = document.createElement('button');
		cancelBtn.textContent = 'Cancel';
		cancelBtn.style.marginLeft = '6px';
		cancelBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			if (onCancel) onCancel();
		};
		btnDiv.appendChild(cancelBtn);

		div.appendChild(btnDiv);

		var wnd = dialogWindow('Convert FSMAR 2 → FSMAR 3', div);
		wnd.setClosable(true);
		wnd.setVisible(true);
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

		// Where "Save to fsmar" writes. Defaults to the domain; overlays only
		// appear if their directory exists on this domain.
		var picker = buildTargetPicker();
		picker.row.style.marginTop = '6px';
		div.appendChild(picker.row);

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

		// Writes the fsmar.json of the selected target on AdminServer — the same
		// file a Configurator save writes; the engine SettingsManager reloads
		// it live. Overwrites the running config, hence the confirm(), which
		// names the target so a cluster/server publish can't be a slip.
		var pubBtn = document.createElement('button');
		pubBtn.textContent = 'Save to fsmar';
		pubBtn.style.cssFloat = 'left';
		pubBtn.title = 'Publish to the live fsmar configuration';
		pubBtn.onclick = function() {
			var info = picker.targetInfo();
			// Check what this would change before asking. The editor only
			// models part of the config, so publishing something built from a
			// sample can drop live root blocks (logging/analytics/events) that
			// were never on screen — name them in the prompt rather than
			// letting them vanish quietly.
			pubBtn.disabled = true;
			status.style.color = '';
			status.textContent = 'Checking what would change…';
			fetchDiff(picker.target(), textarea.value, function(diff) {
				pubBtn.disabled = false;
				status.textContent = '';
				var prompt = 'Overwrite the live fsmar configuration for '
					+ info.displayName + '?';
				if (diff && diff.targetExists) {
					if (diff.identical) {
						prompt = 'No differences from the live configuration for '
							+ info.displayName + '. Publish anyway?';
					} else {
						prompt += '\n\n' + diffSummary(diff);
						if (diff.removedRootKeys && diff.removedRootKeys.length) {
							prompt += '\n\nThis REMOVES top-level settings that are live now:\n  '
								+ diff.removedRootKeys.join(', ')
								+ '\n\nThose are edited in the Configurator, not here. Load the'
								+ ' live configuration first if you meant to keep them.';
						}
					}
				} else if (diff) {
					prompt = 'Nothing has been published to ' + info.displayName
						+ ' yet. Create it?';
				}
				if (!confirm(prompt)) {
					return;
				}
				doPublish();
			});
		};

		function doPublish() {
			pubBtn.disabled = true;
			status.textContent = 'Publishing…';
			flowRequest('fsmarPublish',
					'target=' + encodeURIComponent(picker.target())
						+ '&json=' + encodeURIComponent(textarea.value),
					'POST', function(resp) {
				pubBtn.disabled = false;
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					var r = {};
					try { r = JSON.parse(resp.getText()); } catch (e) { /* show without detail */ }
					status.style.color = '#060';
					status.textContent = 'PUBLISHED to ' + (r.displayName || 'fsmar') + ': '
						+ (r.path || 'fsmar.json')
						+ (r.bytes ? ' (' + r.bytes + ' bytes)' : '');
					// Work is now saved to the live config — no unsaved edits.
					if (window.flowDirty) window.flowDirty.clear();
				} else {
					status.style.color = '#a00';
					status.textContent = 'FAILED: ' + resp.getStatus() + ' ' + resp.getText();
				}
			});
		}
		btnDiv.appendChild(pubBtn);

		// The same comparison the publish prompt runs, on demand and in full —
		// for when you want to read the change rather than be warned about it.
		var diffBtn = document.createElement('button');
		diffBtn.textContent = 'Compare with live';
		diffBtn.style.cssFloat = 'left';
		diffBtn.style.marginLeft = '6px';
		diffBtn.title = 'Show what publishing would change at the selected target';
		diffBtn.onclick = function() {
			diffBtn.disabled = true;
			status.style.color = '';
			status.textContent = 'Comparing…';
			fetchDiff(picker.target(), textarea.value, function(diff, error) {
				diffBtn.disabled = false;
				if (!diff) {
					status.style.color = '#a00';
					status.textContent = 'COMPARE FAILED: ' + error;
					return;
				}
				status.textContent = '';
				showDiff(diff, picker.targetInfo());
			});
		};
		btnDiv.appendChild(diffBtn);

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
	// A State's state id (the JSON states-map key): its `stateId` attribute,
	// else its label (the app name). Two States can share a label but have
	// distinct ids, so the id — not the label — is what edges and the FSM key
	// on. An ingress has no separate id: its NAME (label) is the state id, so
	// gateways always key by label (matching FsmarExportServlet).
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
			return (m && m.length > 0) ? (v.getAttribute('label') || 'null') : 'null';
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
			// Named ingress (has a match) = its own state, keyed by its label;
			// default = null.
			var m = cell.getAttribute('match');
			return '/states/' + ((m && m.length > 0)
					? (cell.getAttribute('label') || 'null') : 'null');
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
			+ 'or load the live config / sample. A legacy FSMAR 2 config is '
			+ 'converted to FSMAR 3 on import.';
		label.style.marginBottom = '6px';
		label.style.flexShrink = '0';
		div.appendChild(label);

		var textarea = dialogTextarea();
		div.appendChild(textarea);

		// Which configuration "Load live fsmar" reads. Same picker as the export
		// dialog; the sample is domain-level only and ignores it.
		var picker = buildTargetPicker();
		picker.row.style.marginTop = '6px';
		div.appendChild(picker.row);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';
		btnDiv.style.flexShrink = '0';

		// Open a local file (FSMAR 3 JSON, a legacy FSMAR 2 config — converted on
		// import — or a legacy mxGraph XML diagram). Loads straight into the editor
		// and closes the dialog — the textarea path is for paste / live / sample.
		var fileBtn = document.createElement('button');
		fileBtn.textContent = 'Choose file…';
		fileBtn.style.cssFloat = 'left';
		fileBtn.title = 'Open an FSMAR 3 JSON file (FSMAR 2 configs and legacy XML diagrams also open)';
		fileBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			window.flowUtils.selectFile(editor);
		};
		btnDiv.appendChild(fileBtn);

		// Pulls the running config (config/custom/vorpal/fsmar.json) into the
		// textarea — the everyday loop is Load live fsmar → edit → Save to
		// fsmar, no files involved.
		var liveBtn = document.createElement('button');
		liveBtn.textContent = 'Load live fsmar';
		liveBtn.style.cssFloat = 'left';
		liveBtn.title = 'Fill the textarea with the live fsmar configuration';
		liveBtn.onclick = function() {
			flowRequest('fsmarPublish?target=' + encodeURIComponent(picker.target()),
					null, 'GET', function(resp) {
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					textarea.value = resp.getText();
				} else {
					mxUtils.alert('Load failed: ' + resp.getStatus() + ' ' + resp.getText());
				}
			});
		};
		btnDiv.appendChild(liveBtn);

		// The canonical sample (_samples/fsmar.json.SAMPLE, generated from
		// AppRouterConfigurationSample) — single source of truth; no JS copy
		// to drift from it.
		var exampleBtn = document.createElement('button');
		exampleBtn.textContent = 'Load sample';
		exampleBtn.style.cssFloat = 'left';
		exampleBtn.style.marginLeft = '6px';
		exampleBtn.title = 'Fill the textarea with the canonical fsmar sample (_samples/fsmar.json.SAMPLE)';
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
