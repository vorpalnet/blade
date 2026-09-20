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
			targetInfo: selected,
			// Lets the file browser drive the picker: clicking a live row is
			// the same statement as choosing its target from the pull-down,
			// and two controls that disagree about where a publish goes is
			// exactly the mistake worth designing out.
			set: function(id) {
				select.value = id;
				if (select.value === id) {
					currentTarget = id;
					refreshNote();
				}
			}
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
				showJsonDialog(json, findings, editor);
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
		showOpenDialog(editor, function(json) {
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
		flowRequest('fsmarImport', 'json=' + encodeURIComponent(json), 'POST', function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				try {
					var doc = mxUtils.parseXml(resp.getText());
					var dec = new mxCodec(doc);
					dec.decode(doc.documentElement, editor.graph.getModel());
					// Always lay the diagram out on open, whether or not the
					// file carried positions. The coordinates in a config came
					// from an earlier auto-layout rather than from anyone
					// arranging boxes, and a stored grid that no longer suits
					// the states in it is what draws arrows through boxes. The
					// redraw button is the position-preserving alternative:
					// same arrows, every box left alone.
					autoLayout(editor);
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
	// ----- redraw the arrows --------------------------------------------------
	//
	// Re-routes every transition and leaves every box where it is.
	//
	// A config that arrives with stored box positions can still draw badly: the
	// waypoints saved with its edges, and any anchor points pinned to a box
	// side, are positions from a layout that no longer holds, so arrows cut
	// across states and parallel transitions stack into one line. Dropping the
	// per-edge routing and re-running the parallel separation fixes the picture
	// without moving a diagram someone arranged on purpose. That is the whole
	// difference from auto-layout, which moves everything.

	// Routing that a config carries, or that an earlier redraw stamped in: saved
	// bend points, anchors pinned to a box face, and the polyline override. All
	// of it is position-dependent, so it is wrong as soon as anything moves, and
	// leaving it in place is what makes a freshly loaded diagram unreadable and
	// what made auto-position draw every arrow out of a single point per box.
	var ROUTING_KEYS = ['exitX', 'exitY', 'entryX', 'entryY', 'exitDx', 'exitDy',
		'entryDx', 'entryDy', 'exitPerimeter', 'entryPerimeter', 'noEdgeStyle', 'edgeStyle'];

	function clearRouting(graph) {
		var model = graph.getModel();
		var cells = model.getDescendants(graph.getDefaultParent());
		model.beginUpdate();
		try {
			for (var i = 0; i < cells.length; i++) {
				var cell = cells[i];
				if (!model.isEdge(cell)) {
					continue;
				}

				var geo = model.getGeometry(cell);
				if (geo && geo.points && geo.points.length) {
					geo = geo.clone();
					geo.points = null;
					model.setGeometry(cell, geo);
				}

				var style = cell.getStyle();
				if (style !== null && style !== undefined) {
					var cleaned = style;
					for (var k = 0; k < ROUTING_KEYS.length; k++) {
						cleaned = mxUtils.setStyle(cleaned, ROUTING_KEYS[k], null);
					}
					if (cleaned !== style) {
						model.setStyle(cell, cleaned);
					}
				}
			}
		} finally {
			model.endUpdate();
		}
	}

	// ----- redraw the arrows --------------------------------------------------
	//
	// Re-routes every transition and leaves every box where it is.
	//
	// A config arrives with box positions but no edge routing, so whatever bend
	// points it carries came from some other layout and draw as tangles. This
	// drops them, lets the stylesheet's router lay each edge out again from the
	// current positions, and fans apart transitions that share both ends.
	//
	// It deliberately does NOT choose which face of a box an edge attaches to.
	// Pinning that per edge reads well in a sketch and badly on a real config:
	// every arrow into a state converges on one point, and the next auto-layout
	// inherits the pins and draws a starburst.
	// ----- arrows that cut through boxes --------------------------------------
	//
	// mxGraph routes each edge as if the canvas were empty, so on a ranked
	// layout an arrow crossing the column passes through whatever sits in
	// between. This walks the path the renderer actually drew and, for any edge
	// that crosses a box, tries routes through the free channels around it.
	//
	// The gate is what makes it safe: a candidate is adopted only when it
	// provably crosses nothing. An edge whose every candidate still crosses is
	// left exactly as the router drew it, so the worst case is the picture we
	// already had rather than a wandering detour that reads worse.

	function vertexRects(model, parent) {
		var rects = [];
		var count = model.getChildCount(parent);
		for (var i = 0; i < count; i++) {
			var cell = model.getChildAt(parent, i);
			if (!model.isVertex(cell)) {
				continue;
			}
			var g = model.getGeometry(cell);
			if (g != null) {
				rects.push({ cell: cell, x: g.x, y: g.y, w: g.width, h: g.height });
			}
		}
		return rects;
	}

	/// Segment/rectangle overlap, as a clip: walk the segment against the four
	/// slabs and see whether any of it survives inside.
	function segmentHitsRect(a, b, r) {
		var t0 = 0;
		var t1 = 1;
		var dx = b.x - a.x;
		var dy = b.y - a.y;
		var checks = [
			{ p: -dx, q: a.x - r.x }, { p: dx, q: (r.x + r.w) - a.x },
			{ p: -dy, q: a.y - r.y }, { p: dy, q: (r.y + r.h) - a.y }
		];
		for (var i = 0; i < checks.length; i++) {
			var p = checks[i].p;
			var q = checks[i].q;
			if (p === 0) {
				if (q < 0) {
					return false;
				}
			} else {
				var t = q / p;
				if (p < 0) {
					if (t > t1) { return false; }
					if (t > t0) { t0 = t; }
				} else {
					if (t < t0) { return false; }
					if (t < t1) { t1 = t; }
				}
			}
		}
		return true;
	}

	/// How many boxes a polyline passes through, ignoring its own two ends.
	function crossingCount(points, rects, source, target) {
		var margin = 6;
		var hits = 0;
		for (var i = 0; i + 1 < points.length; i++) {
			for (var r = 0; r < rects.length; r++) {
				var box = rects[r];
				if (box.cell === source || box.cell === target) {
					continue;
				}
				var inset = { x: box.x - margin, y: box.y - margin,
					w: box.w + margin * 2, h: box.h + margin * 2 };
				if (segmentHitsRect(points[i], points[i + 1], inset)) {
					hits++;
				}
			}
		}
		return hits;
	}

	/// The path as drawn, in model coordinates.
	function drawnPath(graph, edge) {
		var state = graph.view.getState(edge);
		if (state == null || state.absolutePoints == null) {
			return null;
		}
		var scale = graph.view.scale;
		var translate = graph.view.translate;
		var points = [];
		for (var i = 0; i < state.absolutePoints.length; i++) {
			var p = state.absolutePoints[i];
			if (p == null) {
				return null;
			}
			points.push(new mxPoint(p.x / scale - translate.x, p.y / scale - translate.y));
		}
		return (points.length >= 2) ? points : null;
	}

	/// Candidate routes, in the order worth trying: out of the exit face, along
	/// a channel clear of the boxes in between, back into the entry face.
	/// Offsets grow until one clears or we give up on this edge.
	function candidateRoutes(from, to) {
		var routes = [];
		var lead = 22;
		var outX = from.x + ((to.x >= from.x) ? lead : -lead);
		var inX = to.x - ((to.x >= from.x) ? lead : -lead);
		var outY = from.y + ((to.y >= from.y) ? lead : -lead);
		var inY = to.y - ((to.y >= from.y) ? lead : -lead);

		for (var step = 1; step <= 8; step++) {
			var spread = step * 26;
			var above = Math.min(from.y, to.y) - spread;
			var below = Math.max(from.y, to.y) + spread;
			var left = Math.min(from.x, to.x) - spread;
			var right = Math.max(from.x, to.x) + spread;
			routes.push([new mxPoint(outX, from.y), new mxPoint(outX, above),
				new mxPoint(inX, above), new mxPoint(inX, to.y)]);
			routes.push([new mxPoint(outX, from.y), new mxPoint(outX, below),
				new mxPoint(inX, below), new mxPoint(inX, to.y)]);
			routes.push([new mxPoint(from.x, outY), new mxPoint(left, outY),
				new mxPoint(left, inY), new mxPoint(to.x, inY)]);
			routes.push([new mxPoint(from.x, outY), new mxPoint(right, outY),
				new mxPoint(right, inY), new mxPoint(to.x, inY)]);
		}
		return routes;
	}

	function clearCrossings(graph) {
		var model = graph.getModel();
		var parent = graph.getDefaultParent();
		var rects = vertexRects(model, parent);
		var cells = model.getDescendants(parent);

		model.beginUpdate();
		try {
			for (var i = 0; i < cells.length; i++) {
				var edge = cells[i];
				if (!model.isEdge(edge)) {
					continue;
				}
				var source = model.getTerminal(edge, true);
				var target = model.getTerminal(edge, false);
				if (source == null || target == null || source === target) {
					continue;
				}

				var drawn = drawnPath(graph, edge);
				if (drawn == null || crossingCount(drawn, rects, source, target) === 0) {
					continue;
				}

				var from = drawn[0];
				var to = drawn[drawn.length - 1];
				var routes = candidateRoutes(from, to);
				for (var r = 0; r < routes.length; r++) {
					var full = [from].concat(routes[r], [to]);
					if (crossingCount(full, rects, source, target) === 0) {
						var geo = model.getGeometry(edge);
						geo = (geo == null) ? new mxGeometry() : geo.clone();
						geo.points = routes[r];
						model.setGeometry(edge, geo);
						model.setStyle(edge, mxUtils.setStyle(
							mxUtils.setStyle(edge.getStyle() || '', 'noEdgeStyle', 1),
							'rounded', 1));
						break;
					}
				}
			}
		} finally {
			model.endUpdate();
		}
		graph.refresh();
	}

	function redrawEdges(editor) {
		var graph = editor.graph;
		clearRouting(graph);
		if (window.flowParallelEdges) {
			window.flowParallelEdges(graph);
		}
		graph.refresh();
		// Reading the drawn path is why this runs last.
		clearCrossings(graph);
	}

	function autoLayout(editor) {
		var graph = editor.graph;
		// Start from unrouted edges: a pinned anchor left by an earlier redraw
		// (or by hand) survives the layout and drags its arrow to one face.
		clearRouting(graph);
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
		clearCrossings(graph);
		fitView(editor);
	}

	// The part of the canvas nothing is floating over. The toolbar and the
	// inspector are mxWindows drawn ON TOP of the graph container rather than
	// beside it, so the container's own width lies: fitting to it hides boxes
	// behind a panel, which is how `conference` ended up under the inspector.
	function clearArea(graph) {
		var canvas = graph.container.getBoundingClientRect();
		var left = 0;
		var right = 0;
		var windows = document.querySelectorAll('.mxWindow');
		for (var i = 0; i < windows.length; i++) {
			var el = windows[i];
			if (el.offsetParent === null) {
				continue;   // minimized or hidden
			}
			var r = el.getBoundingClientRect();
			if (r.width === 0 || r.height === 0 || r.right < canvas.left || r.left > canvas.right) {
				continue;
			}
			// Which side it hugs decides which margin it eats.
			if ((r.left + r.right) / 2 < (canvas.left + canvas.right) / 2) {
				left = Math.max(left, r.right - canvas.left);
			} else {
				right = Math.max(right, canvas.right - r.left);
			}
		}
		return {
			left: left,
			width: Math.max(160, canvas.width - left - right),
			height: Math.max(120, canvas.height)
		};
	}

	// Scales the whole diagram into that clear area and centers it there.
	// Never magnifies past 100%: a three-box config blown up to fill a monitor
	// looks like a mistake, and the labels are drawn for this size.
	function fitView(editor) {
		var graph = editor.graph;
		var view = graph.view;
		var bounds = graph.getGraphBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
			return;
		}

		var pad = 28;
		var area = clearArea(graph);
		var scale = view.scale;

		// Graph bounds are screen coordinates; undo the current view to get the
		// model rectangle, which is what the new scale has to be computed from.
		var modelX = bounds.x / scale - view.translate.x;
		var modelY = bounds.y / scale - view.translate.y;
		var modelW = bounds.width / scale;
		var modelH = bounds.height / scale;

		var usableW = area.width - pad * 2;
		var usableH = area.height - pad * 2;
		var next = Math.min(usableW / modelW, usableH / modelH);
		next = Math.max(0.25, Math.min(next, 1));

		var offsetX = area.left + pad + (usableW - modelW * next) / 2;
		var offsetY = pad + (usableH - modelH * next) / 2;

		view.scaleAndTranslate(next, offsetX / next - modelX, offsetY / next - modelY);
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

	function showJsonDialog(json, findings, editor) {
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

		// The same list the Open dialog shows, so a save names a file in the
		// place you will go looking for it. Clicking a row fills the name field.
		var saveBrowser = fileBrowser({
			onSelect: function(row) {
				nameInput.value = row.name;
				if (row.kind === 'live') {
					picker.set(row.target);
				}
			}
		});
		saveBrowser.el.style.flex = '0 0 auto';
		saveBrowser.el.style.maxHeight = '150px';
		saveBrowser.el.style.minHeight = '0';
		saveBrowser.el.style.marginTop = '6px';
		div.appendChild(saveBrowser.el);

		// "fsmar.json" is the live configuration's name, so saving under it is
		// how you publish from here — through the same diff and confirmation
		// the Save to fsmar button uses. Any other name writes a file and
		// changes nothing about the running router.
		var nameRow = document.createElement('div');
		nameRow.style.marginTop = '6px';
		nameRow.style.flexShrink = '0';
		var nameLabel = document.createElement('span');
		nameLabel.textContent = 'Save as: ';
		nameLabel.style.fontSize = '11px';
		nameRow.appendChild(nameLabel);
		var nameInput = document.createElement('input');
		nameInput.type = 'text';
		nameInput.style.fontSize = '11px';
		nameInput.style.fontFamily = 'var(--vorpal-font-mono, monospace)';
		nameInput.style.width = '260px';
		nameInput.value = (editor && editor.filename) ? editor.filename : 'demo1.json';
		nameRow.appendChild(nameInput);
		div.appendChild(nameRow);

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
		var saveBtn = document.createElement('button');
		saveBtn.textContent = 'Save';
		saveBtn.style.cssFloat = 'left';
		saveBtn.style.marginRight = '6px';
		saveBtn.title = 'Save this configuration under the name above';
		saveBtn.onclick = function() {
			var name = (nameInput.value || '').trim();
			if (!name) {
				mxUtils.alert('Name the file, for example demo1.json.');
				return;
			}
			if (name.toLowerCase() === 'fsmar.json') {
				// The live file: take the publish path, confirmation and all.
				pubBtn.onclick();
				return;
			}
			saveBtn.disabled = true;
			status.style.color = '';
			status.textContent = 'Saving\u2026';
			flowRequest('fsmarFiles',
					'name=' + encodeURIComponent(name) + '&json=' + encodeURIComponent(textarea.value),
					'POST', function(resp) {
				saveBtn.disabled = false;
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					var r = {};
					try { r = JSON.parse(resp.getText()); } catch (e) { /* show without detail */ }
					status.style.color = '#060';
					status.textContent = 'SAVED ' + (r.name || name)
						+ (r.bytes ? ' (' + r.bytes + ' bytes)' : '');
					if (editor) editor.filename = r.name || name;
					saveBrowser.refresh();
				} else {
					status.style.color = '#a00';
					status.textContent = 'FAILED: ' + resp.getStatus() + ' ' + resp.getText();
				}
			});
		};
		btnDiv.appendChild(saveBtn);

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
			// An exit cloud is never a transition source; treat it as no state.
			if (flowUtils.isExitCloud(v)) return 'null';
			var m = v.getAttribute('match');
			return (m && m.length > 0) ? (v.getAttribute('label') || 'null') : 'null';
		}
		return 'null';
	}

	function pathForCell(graph, cell) {
		if (cell == null || cell.value == null || !cell.value.tagName) return null;
		var tag = cell.value.tagName;
		// An exit cloud isn't a state — nothing to simulate from it.
		if (flowUtils.isExitCloud(cell)) return null;
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

	// ----- saved flows (the file browser) -------------------------------------
	//
	// A saved flow is an ordinary FSMAR 3 file kept under _flows/ (see
	// FlowFiles). The browser lists those beside the live configurations and the
	// generated sample, so "what can I open?" and "where does this go?" are one
	// list instead of four buttons. Nothing in _flows/ is live: opening a flow
	// only fills the canvas, and publishing stays the one act that changes how
	// calls route.
	//
	// Selection is carried by a marker and a heavy left border, never by color.

	function formatBytes(n) {
		if (n == null) return '';
		if (n < 1024) return n + ' B';
		if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' kB';
		return (n / (1024 * 1024)).toFixed(1) + ' MB';
	}

	function formatWhen(ms) {
		return ms ? new Date(ms).toLocaleString() : '';
	}

	// Builds the list. `onChoose` fires on double-click, `onSelect` on a click.
	function fileBrowser(options) {
		options = options || {};

		var el = document.createElement('div');
		el.style.flex = '1 1 auto';
		el.style.minHeight = '140px';
		el.style.overflowY = 'auto';
		el.style.border = '1px solid var(--vorpal-slate-300, #ccc)';
		el.style.borderRadius = 'var(--vorpal-radius-sm, 4px)';
		el.style.background = 'var(--vorpal-surface, #fff)';
		el.style.fontSize = '11px';

		var rows = [];
		var chosen = null;

		function select(row) {
			chosen = row;
			for (var i = 0; i < rows.length; i++) {
				var on = (rows[i].data === row);
				rows[i].el.style.borderLeft = on
					? '4px solid var(--vorpal-purple, #602671)' : '4px solid transparent';
				rows[i].el.style.background = on ? 'var(--vorpal-purple-100, #ece1f0)' : 'transparent';
				rows[i].el.style.fontWeight = on ? '700' : '400';
				rows[i].marker.innerHTML = on ? '&#9656;' : '&#160;';
			}
			if (options.onSelect) options.onSelect(row);
		}

		function heading(text) {
			var h = document.createElement('div');
			h.textContent = text;
			h.style.padding = '6px 8px 3px';
			h.style.fontSize = '10px';
			h.style.letterSpacing = '0.08em';
			h.style.textTransform = 'uppercase';
			h.style.color = 'var(--vorpal-slate-600, #5a6677)';
			h.style.borderTop = rows.length ? '1px solid var(--vorpal-divider, #dde2ec)' : 'none';
			el.appendChild(h);
		}

		function addRow(data, name, meta) {
			var row = document.createElement('div');
			row.style.display = 'flex';
			row.style.alignItems = 'baseline';
			row.style.padding = '3px 8px';
			row.style.cursor = 'pointer';
			row.style.borderLeft = '4px solid transparent';

			var marker = document.createElement('span');
			marker.innerHTML = '&#160;';
			marker.style.width = '10px';
			marker.style.flexShrink = '0';
			row.appendChild(marker);

			var label = document.createElement('span');
			label.textContent = name;
			label.style.fontFamily = 'var(--vorpal-font-mono, monospace)';
			label.style.flex = '1 1 auto';
			row.appendChild(label);

			var detail = document.createElement('span');
			detail.textContent = meta || '';
			detail.style.color = 'var(--vorpal-slate-600, #5a6677)';
			detail.style.flexShrink = '0';
			row.appendChild(detail);

			row.onclick = function() { select(data); };
			row.ondblclick = function() {
				select(data);
				if (options.onChoose) options.onChoose(data);
			};

			el.appendChild(row);
			rows.push({ el: row, marker: marker, data: data });
		}

		// One live row, then the sample, then the library. Config is edited on
		// the admin server and the Configurator pushes it to the engines, so
		// the domain file is THE live configuration — listing a row per cluster
		// and server would be five spellings of the same file. An overlay is
		// still reachable through the Configuration pull-down in the save
		// dialog, which is where that rarer choice belongs.
		function refresh() {
			el.innerHTML = '';
			rows = [];
			chosen = null;

			heading('Live configuration');
			addRow({ kind: 'live', target: DEFAULT_TARGET, name: 'fsmar.json' }, 'fsmar.json',
				'live \u00b7 pushed to the engines');

			heading('Sample');
			addRow({ kind: 'sample', name: 'fsmar.json.SAMPLE' }, 'fsmar.json.SAMPLE', 'generated');

			// Fails soft: an older deployment without the files servlet still
			// lists its live configuration and sample.
			flowRequest('fsmarFiles', null, 'GET', function(fresp) {
				var files = [];
				if (fresp.getStatus() >= 200 && fresp.getStatus() < 300) {
					try {
						files = JSON.parse(fresp.getText()).files || [];
					} catch (e) {
						files = [];
					}
				}
				heading('Saved flows' + (files.length ? '' : ' (none yet)'));
				for (var j = 0; j < files.length; j++) {
					addRow({ kind: 'flow', name: files[j].name }, files[j].name,
						formatBytes(files[j].bytes) + ' \u00b7 ' + formatWhen(files[j].modified));
				}
				if (options.onReady) options.onReady();
			});
		}

		refresh();

		return {
			el: el,
			refresh: refresh,
			selected: function() { return chosen; }
		};
	}

	// Reads whichever row is selected: a live target, the sample, or a flow.
	function loadRow(row, onText) {
		var url;
		if (row.kind === 'live') {
			url = 'fsmarPublish?target=' + encodeURIComponent(row.target);
		} else if (row.kind === 'sample') {
			url = 'fsmarPublish?sample=1';
		} else {
			url = 'fsmarFiles?name=' + encodeURIComponent(row.name);
		}
		flowRequest(url, null, 'GET', function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				onText(resp.getText());
			} else {
				mxUtils.alert('Open failed: ' + resp.getStatus() + ' ' + resp.getText());
			}
		});
	}

	// The Open dialog. Pasting and opening a local file are still here, as
	// buttons, for the cases a server-side list cannot cover.
	function showOpenDialog(editor, callback) {
		var div = dialogBody();

		var label = document.createElement('div');
		label.innerHTML = '<b>Open:</b> a live configuration, the generated sample, or a saved flow. '
			+ 'Double-click a row to open it.';
		label.style.marginBottom = '6px';
		label.style.flexShrink = '0';
		div.appendChild(label);

		function openRow(row) {
			if (!row) {
				mxUtils.alert('Select a file to open.');
				return;
			}
			loadRow(row, function(text) {
				wnd.setVisible(false);
				wnd.destroy();
				if (row.kind !== 'sample') {
					editor.filename = row.name;
				}
				callback(text);
			});
		}

		var browser = fileBrowser({ onChoose: openRow });
		div.appendChild(browser.el);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';
		btnDiv.style.flexShrink = '0';

		var fileBtn = document.createElement('button');
		fileBtn.textContent = 'Choose file\u2026';
		fileBtn.style.cssFloat = 'left';
		fileBtn.title = 'Open a file from this computer (FSMAR 2 configs and legacy XML diagrams also open)';
		fileBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			window.flowUtils.selectFile(editor);
		};
		btnDiv.appendChild(fileBtn);

		var pasteBtn = document.createElement('button');
		pasteBtn.textContent = 'Paste JSON\u2026';
		pasteBtn.style.cssFloat = 'left';
		pasteBtn.style.marginLeft = '6px';
		pasteBtn.title = 'Paste a configuration instead of opening a file';
		pasteBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
			showImportDialog(editor, callback);
		};
		btnDiv.appendChild(pasteBtn);

		var delBtn = document.createElement('button');
		delBtn.textContent = 'Delete';
		delBtn.style.cssFloat = 'left';
		delBtn.style.marginLeft = '6px';
		delBtn.title = 'Delete the selected saved flow. Live configurations are not deleted here.';
		delBtn.onclick = function() {
			var row = browser.selected();
			if (!row || row.kind !== 'flow') {
				mxUtils.alert('Select a saved flow to delete. Live configurations are not deleted here.');
				return;
			}
			if (!confirm('Delete ' + row.name + '?')) {
				return;
			}
			flowRequest('fsmarFiles?name=' + encodeURIComponent(row.name), null, 'DELETE', function(resp) {
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					browser.refresh();
				} else {
					mxUtils.alert('Delete failed: ' + resp.getStatus() + ' ' + resp.getText());
				}
			});
		};
		btnDiv.appendChild(delBtn);

		var openBtn = document.createElement('button');
		openBtn.textContent = 'Open';
		openBtn.onclick = function() {
			openRow(browser.selected());
		};
		btnDiv.appendChild(openBtn);

		var cancelBtn = document.createElement('button');
		cancelBtn.textContent = 'Cancel';
		cancelBtn.style.marginLeft = '6px';
		cancelBtn.onclick = function() {
			wnd.setVisible(false);
			wnd.destroy();
		};
		btnDiv.appendChild(cancelBtn);

		div.appendChild(btnDiv);

		var wnd = dialogWindow('Open FSMAR configuration', div);
		wnd.setClosable(true);
		wnd.setVisible(true);
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
		redrawEdges: redrawEdges,
		fitView: fitView,
		toggleJsonView: toggleJsonView
	};

})();
