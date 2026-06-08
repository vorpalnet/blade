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

		var req = new mxXmlRequest('fsmarExport', 'xml=' + encodeURIComponent(xml));
		req.send(function(resp) {
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
			var vreq = new mxXmlRequest('fsmarValidate', 'json=' + encodeURIComponent(json));
			vreq.send(function(vresp) {
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
		showImportDialog(function(json) {
			var req = new mxXmlRequest('fsmarImport', 'json=' + encodeURIComponent(json));
			req.send(function(resp) {
				if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
					try {
						var doc = mxUtils.parseXml(resp.getText());
						var dec = new mxCodec(doc);
						dec.decode(doc.documentElement, editor.graph.getModel());
					} catch (err) {
						mxUtils.alert('Import failed: ' + err.message);
					}
				} else {
					mxUtils.alert('Import failed: ' + resp.getStatus() + ' ' + resp.getText());
				}
			});
		});
	}

	function showJsonDialog(json, findings) {
		var div = document.createElement('div');
		div.style.padding = '10px';
		div.style.fontFamily = 'monospace';

		var fdiv = document.createElement('div');
		fdiv.innerHTML = findingsHtml(findings);
		div.appendChild(fdiv);

		var label = document.createElement('div');
		label.innerHTML = '<b>FSMAR 3 JSON:</b>';
		label.style.marginBottom = '6px';
		div.appendChild(label);

		var textarea = document.createElement('textarea');
		textarea.value = json;
		textarea.style.width = '560px';
		textarea.style.height = '380px';
		textarea.style.fontFamily = 'monospace';
		textarea.style.fontSize = '11px';
		div.appendChild(textarea);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';

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

		var wnd = new mxWindow('FSMAR Export', div, 100, 100, 600, 500, true, true);
		wnd.setClosable(true);
		wnd.setVisible(true);
	}

	// A small known-good FSMAR 3 config for one-click experimentation. The
	// expressions use the same grammar as AppRouterConfigurationSample (the
	// canonical fsmar3 sample): ==, &&, matches. Demonstrates the service-plan
	// pattern end to end: a table selector CLASSIFIES the caller into a plan
	// (rows operators edit), and a mutually exclusive dispatch family ROUTES
	// by plan (edges architects draw — these render badged, e.g.
	// "INVITE · tier=gold", and appear in the Plans view filter).
	var EXAMPLE_CONFIG = {
		defaultApplication: 'b2bua',
		states: {
			'null': {
				selectors: [
					{ type: 'regex', id: 'From', attribute: 'From',
					  pattern: "sips?:(?<user>[^@]+)@(?<host>[^;>]+)",
					  expression: '${user}@${host}',
					  description: 'Split the From header into ${From.user} / ${From.host}' },
					{ type: 'table', id: 'plan',
					  description: 'Classify the caller into a service plan — rows are operator data',
					  table: {
						keyExpression: '${From.user}',
						translations: {
							alice: { tier: 'gold' },
							bob: { tier: 'silver' }
						}
					  } }
				],
				triggers: {
					INVITE: { transitions: [
						{ id: 'INV-gold', when: "${tier} == 'gold'", next: 'premium-screening' },
						{ id: 'INV-silver', when: "${tier} == 'silver'", next: 'screening' },
						{ id: 'INV-default', next: 'b2bua' }
					] },
					REGISTER: { transitions: [ { id: 'REG-1', next: 'registrar' } ] }
				}
			},
			'premium-screening': {
				triggers: {
					INVITE: { transitions: [
						{ id: 'PSCR-out', next: 'null',
						  routes: ['sip:${From.user}@priority-gateway.example_co.com'] }
					] }
				}
			},
			screening: {
				triggers: {
					INVITE: { transitions: [
						{ id: 'SCR-out', next: 'null',
						  routes: ['sip:${From.user}@gateway.example_co.com'] }
					] }
				}
			},
			b2bua: { triggers: {} },
			registrar: { triggers: {} }
		}
	};

	function showImportDialog(callback) {
		var div = document.createElement('div');
		div.style.padding = '10px';
		div.style.fontFamily = 'monospace';

		var label = document.createElement('div');
		label.innerHTML = '<b>Paste FSMAR 3 JSON:</b>';
		label.style.marginBottom = '6px';
		div.appendChild(label);

		var textarea = document.createElement('textarea');
		textarea.style.width = '560px';
		textarea.style.height = '380px';
		textarea.style.fontFamily = 'monospace';
		textarea.style.fontSize = '11px';
		div.appendChild(textarea);

		var btnDiv = document.createElement('div');
		btnDiv.style.marginTop = '8px';
		btnDiv.style.textAlign = 'right';

		var exampleBtn = document.createElement('button');
		exampleBtn.textContent = 'Load example';
		exampleBtn.style.cssFloat = 'left';
		exampleBtn.title = 'Fill the textarea with a small known-good FSMAR 3 config';
		exampleBtn.onclick = function() {
			textarea.value = JSON.stringify(EXAMPLE_CONFIG, null, 2);
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

		var wnd = new mxWindow('FSMAR Import', div, 100, 100, 600, 500, true, true);
		wnd.setClosable(true);
		wnd.setVisible(true);
	}

	return {
		exportToJson: exportToJson,
		importFromJson: importFromJson,
		getConfigJson: getConfigJson
	};

})();
