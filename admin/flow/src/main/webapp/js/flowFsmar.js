// FSMAR Flow Editor — JSON export/import driver
//
// Bridges the mxGraph editor model to the FsmarExportServlet / FsmarImportServlet.
// Export: serializes the current model XML, POSTs to the export servlet,
//         shows the returned FSMAR 3 JSON in a window with a download button.
// Import: prompts for FSMAR 3 JSON, POSTs to the import servlet, replaces
//         the editor model with the returned mxGraph XML.

window.flowFsmar = (function() {

	function exportToJson(editor) {
		var enc = new mxCodec(mxUtils.createXmlDocument());
		var node = enc.encode(editor.graph.getModel());
		var xml = mxUtils.getXml(node);

		var req = new mxXmlRequest('fsmarExport', 'xml=' + encodeURIComponent(xml));
		req.send(function(resp) {
			if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
				showJsonDialog(resp.getText());
			} else {
				mxUtils.alert('Export failed: ' + resp.getStatus() + ' ' + resp.getText());
			}
		});
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

	function showJsonDialog(json) {
		var div = document.createElement('div');
		div.style.padding = '10px';
		div.style.fontFamily = 'monospace';

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
		importFromJson: importFromJson
	};

})();
