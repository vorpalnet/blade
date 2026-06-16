// FSMAR Flow Editor — file utilities
//
// Replaces the old aliceUtils.js. Uses a native file input for opening
// files instead of the old XHR/server-listing approach. The native file
// format is FSMAR 3 JSON (diagram layout embedded); legacy mxGraph XML
// diagrams still open for backward compatibility.

window.flowUtils = (function() {

	// Hardcoded toolbar tooltip map. mxResources/app.txt loading appears to
	// 404 in the deployed environment, so we set title attributes directly on
	// the rendered toolbar img elements after the editor is up. This is keyed
	// by the icon URL fragment (e.g. "save.svg") because the toolbar items
	// don't carry their `as` attribute through to the DOM.
	var tooltipsByIcon = {
		'folder-open.svg':       'Open / Load FSMAR — choose a file, paste JSON, or load the live config / sample',
		'save.svg':              'Save / Export FSMAR — download, copy, or publish to the live config (with validation)',
		'image.svg':             'Export image',
		'simulate.svg':          'Route Simulator — simulate, replay, and live traffic overlay',
		'braces.svg':            'Live JSON view — read-only peek at the FSMAR 3 config for the current diagram',
		'mouse-pointer.svg':     'Select tool',
		'hand-paper.svg':        'Pan tool',
		'connect.svg':           'Connect — draw a transition between states',
		'object-group.svg':      'Group selection',
		'object-ungroup.svg':    'Ungroup selection',
		'auto-layout.svg':       'Auto-position: re-arrange the whole diagram left-to-right and center it',
		'cloud-download.svg':    'Add Ingress (SBC/trunk/carrier) — a real entry state; set its Source match to classify inbound traffic. Matchless = the default ingress (null).',
		'cloud-upload.svg':      'Add Egress (exit) — where the call leaves OCCAS. Draw a transition INTO it; set its route URIs, and draw a line back to a state for a return-to-origin (ROUTE_BACK) exit.',
		'rectangle-landscape.svg':'Add State (BLADE application)',
		'cut.svg':               'Cut',
		'copy.svg':              'Copy',
		'paste.svg':             'Paste',
		'backspace.svg':         'Delete selection',
		'undo.svg':              'Undo',
		'redo.svg':              'Redo',
		'search.svg':            'Fit to view (auto-centers the diagram)',
		'search-plus.svg':       'Zoom in',
		'search-minus.svg':      'Zoom out',
		'clipboard-check.svg':   'Toggle properties panel',
		'question-circle.svg':   'Help',
		'cog.svg':               'Show properties dialog'
	};

	function applyToolbarTooltips() {
		// Walk all img elements inside the toolbar containers and set title
		// based on icon filename. Retry briefly in case the toolbar renders
		// after this runs.
		var tries = 0;
		function attempt() {
			var imgs = document.querySelectorAll('.mxToolbarItem, .mxToolbarMode');
			if (imgs.length === 0 && tries++ < 20) {
				setTimeout(attempt, 200);
				return;
			}
			imgs.forEach(function(img) {
				var src = img.getAttribute('src') || '';
				var basename = src.split('/').pop();
				if (tooltipsByIcon[basename]) {
					img.setAttribute('title', tooltipsByIcon[basename]);
				}
			});
		}
		attempt();
	}

	function saveFile(editor) {
		// Single-artifact save: the FSMAR 3 JSON (with its diagram layout
		// embedded) is the only file format — the same file the router runs.
		// Built by the export servlet from the current model, then downloaded.
		window.flowFsmar.getConfigJson(editor, function(json) {
			var filename = editor.filename || 'fsmar3.json';
			if (!/\.json$/i.test(filename)) {
				filename = 'fsmar3.json';
			}
			filename = prompt('Save as filename:', filename);
			if (!filename) return;
			editor.filename = filename;

			var blob = new Blob([json], { type: 'application/json' });
			var url = URL.createObjectURL(blob);
			var a = document.createElement('a');
			a.href = url;
			a.download = filename;
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
			URL.revokeObjectURL(url);
		});
	}

	function selectFile(editor) {
		// Create a hidden file input and trigger it. FSMAR 3 JSON is the
		// native format; legacy mxGraph XML diagrams (sniffed by leading '<')
		// still decode directly so old saved files remain openable.
		var input = document.createElement('input');
		input.type = 'file';
		input.accept = '.json,.xml,application/json,application/xml,text/xml';
		input.style.display = 'none';
		input.onchange = function(e) {
			var file = e.target.files[0];
			if (!file) return;
			var reader = new FileReader();
			reader.onload = function(ev) {
				var text = ev.target.result;
				var first = String(text).replace(/^\s+/, '').charAt(0);
				if (first === '<') {
					// Legacy mxGraph XML diagram
					try {
						var doc = mxUtils.parseXml(text);
						var dec = new mxCodec(doc);
						dec.decode(doc.documentElement, editor.graph.getModel());
						editor.filename = file.name;
					} catch (err) {
						mxUtils.alert('Failed to load file: ' + err.message);
					}
				} else {
					window.flowFsmar.importJsonText(editor, text, function(ok) {
						if (ok) editor.filename = file.name;
					});
				}
			};
			reader.readAsText(file);
			document.body.removeChild(input);
		};
		document.body.appendChild(input);
		input.click();
	}

	return {
		selectFile: selectFile,
		saveFile: saveFile,
		applyToolbarTooltips: applyToolbarTooltips
	};

})();
