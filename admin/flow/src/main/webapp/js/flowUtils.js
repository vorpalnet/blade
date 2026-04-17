// FSMAR Flow Editor — file utilities
//
// Replaces the old aliceUtils.js. Uses a native file input for opening
// mxGraph XML files instead of the old XHR/server-listing approach.

window.flowUtils = (function() {

	// Hardcoded toolbar tooltip map. mxResources/app.txt loading appears to
	// 404 in the deployed environment, so we set title attributes directly on
	// the rendered toolbar img elements after the editor is up. This is keyed
	// by the icon URL fragment (e.g. "save.svg") because the toolbar items
	// don't carry their `as` attribute through to the DOM.
	var tooltipsByIcon = {
		'folder-open.svg':       'Open mxGraph XML file',
		'save.svg':              'Save mxGraph XML (work in progress)',
		'image.svg':             'Export image',
		'cloud-upload.svg':      'Export FSMAR JSON',
		'cloud-download.svg':    'Import FSMAR JSON',
		'mouse-pointer.svg':     'Select tool',
		'hand-paper.svg':        'Pan tool',
		'connector-straight.svg':'Connect (straight transition)',
		'connector-elbow.svg':   'Connect (elbow transition)',
		'object-group.svg':      'Group selection',
		'object-ungroup.svg':    'Ungroup selection',
		'id-badge.svg':          'Add Ingress cloud (incoming calls from outside)',
		'rectangle-landscape.svg':'Add State (BLADE application)',
		'database.svg':          'Add Egress cloud (outgoing calls leaving BLADE)',
		'cut.svg':               'Cut',
		'copy.svg':              'Copy',
		'paste.svg':             'Paste',
		'backspace.svg':         'Delete selection',
		'undo.svg':              'Undo',
		'redo.svg':              'Redo',
		'search.svg':            'Fit to view',
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
		// Serialize the current graph model to mxGraph XML and trigger a
		// browser download. No server roundtrip — bypasses SaveServlet entirely.
		var enc = new mxCodec(mxUtils.createXmlDocument());
		var node = enc.encode(editor.graph.getModel());
		var xml = mxUtils.getXml(node);

		var filename = editor.filename || 'flow.xml';
		filename = prompt('Save as filename:', filename);
		if (!filename) return;
		editor.filename = filename;

		var blob = new Blob([xml], { type: 'application/xml' });
		var url = URL.createObjectURL(blob);
		var a = document.createElement('a');
		a.href = url;
		a.download = filename;
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
		URL.revokeObjectURL(url);
	}

	function selectFile(editor) {
		// Create a hidden file input and trigger it
		var input = document.createElement('input');
		input.type = 'file';
		input.accept = '.xml,.txt,application/xml,text/xml';
		input.style.display = 'none';
		input.onchange = function(e) {
			var file = e.target.files[0];
			if (!file) return;
			var reader = new FileReader();
			reader.onload = function(ev) {
				try {
					var doc = mxUtils.parseXml(ev.target.result);
					var dec = new mxCodec(doc);
					dec.decode(doc.documentElement, editor.graph.getModel());
					editor.filename = file.name;
				} catch (err) {
					mxUtils.alert('Failed to load file: ' + err.message);
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
