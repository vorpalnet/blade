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
		'image.svg':             'Export image — download the diagram as a scalable SVG file',
		'print.svg':             'Print Report — open the current routing plan as a printable report (diagram + transitions table)',
		'simulate.svg':          'Route Simulator — simulate, replay, and live traffic overlay',
		'braces.svg':            'Live JSON view — read-only peek at the FSMAR 3 config for the current diagram',
		'mouse-pointer.svg':     'Select tool',
		'hand-paper.svg':        'Pan tool',
		'connect.svg':           'Connect — draw a transition between states',
		'object-group.svg':      'Group selection',
		'object-ungroup.svg':    'Ungroup selection',
		'auto-layout.svg':       'Auto-position: re-arrange the whole diagram left-to-right and center it',
		'cloud.svg':             'Add a cloud — the network outside OCCAS (a carrier, an SBC, anything not BLADE). The arrows say which way traffic runs: draw one OUT of it for calls arriving (set a Source match to classify them; matchless = the default entry), or INTO it for calls leaving (set its route URIs, and draw a line back to a state to return and resume there).',
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

	// Renders the whole graph model into a STANDALONE SVG string via
	// mxImageExport + mxSvgCanvas2D — the same registry-driven pass the editor
	// paints with, so stencils (ingress/egress clouds) and HTML labels
	// (foreignObject) come out identical to the canvas. No server round-trip,
	// no dependence on the live container DOM. Used by the exportImage action
	// (SVG download) and the printable report (inline diagram).
	function graphToSvgString(graph, border) {
		border = (border == null) ? 12 : border;
		var bounds = graph.getGraphBounds();
		var vs = graph.view.scale;
		var w = Math.ceil(bounds.width / vs + 2 * border);
		var h = Math.ceil(bounds.height / vs + 2 * border);

		var svgDoc = mxUtils.createXmlDocument();
		var root = (svgDoc.createElementNS != null)
			? svgDoc.createElementNS(mxConstants.NS_SVG, 'svg')
			: svgDoc.createElement('svg');
		if (root.setAttributeNS != null) {
			root.setAttributeNS('http://www.w3.org/2000/xmlns/', 'xmlns:xlink', mxConstants.NS_XLINK);
		} else {
			root.setAttribute('xmlns', mxConstants.NS_SVG);
			root.setAttribute('xmlns:xlink', mxConstants.NS_XLINK);
		}
		root.setAttribute('width', w + 'px');
		root.setAttribute('height', h + 'px');
		root.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
		root.setAttribute('version', '1.1');
		svgDoc.appendChild(root);

		var group = (svgDoc.createElementNS != null)
			? svgDoc.createElementNS(mxConstants.NS_SVG, 'g')
			: svgDoc.createElement('g');
		root.appendChild(group);

		var svgCanvas = new mxSvgCanvas2D(group);
		svgCanvas.foAltText = '[label]';
		svgCanvas.translate(Math.floor(border - bounds.x / vs), Math.floor(border - bounds.y / vs));
		svgCanvas.scale(1 / vs);

		var imgExport = new mxImageExport();
		imgExport.drawState(graph.getView().getState(graph.model.root), svgCanvas);

		return mxUtils.getXml(root);
	}

	// The exportImage toolbar action: a real SVG download (the stock mxEditor
	// action expected a server-side image servlet and fell back to a bare
	// mxUtils.show popup).
	function downloadSvg(graph, filename) {
		var svg = '<?xml version="1.0" encoding="UTF-8"?>\n' + graphToSvgString(graph);
		var blob = new Blob([svg], { type: 'image/svg+xml' });
		var url = URL.createObjectURL(blob);
		var a = document.createElement('a');
		a.href = url;
		a.download = filename || 'fsmar-diagram.svg';
		document.body.appendChild(a);
		a.click();
		document.body.removeChild(a);
		URL.revokeObjectURL(url);
	}

	// --- Field-help popups (ⓘ icons in the property panels) -------------
	//
	// The long field explanations that used to sit inline in state.html /
	// transition.html / the selector fieldsets ride hidden inside an ⓘ icon
	// (`.info-tip > .info-tip-text`) and show in ONE shared popup. Handlers
	// are delegated from the document because the panels are injected later
	// by jQuery .load() and selector fieldsets are rebuilt on every
	// selection; the popup is position:fixed because the tasks window
	// scrolls and would clip an absolutely-positioned bubble at its edges.
	//
	// Hover/focus shows it transiently; click (or Enter/Space) pins it so
	// the example text inside can be selected and copied; Escape, a click
	// elsewhere, or scrolling dismisses it.

	var infoPop = null;
	var infoPinned = null;
	var infoHideTimer = null;

	function infoPopEl() {
		if (!infoPop) {
			infoPop = document.createElement('div');
			infoPop.id = 'info-tip-pop';
			infoPop.style.display = 'none';
			document.body.appendChild(infoPop);
			// Moving from the icon onto the popup must not dismiss it — the
			// examples in it are meant to be selectable.
			infoPop.addEventListener('mouseenter', cancelInfoHide);
			infoPop.addEventListener('mouseleave', function() {
				if (!infoPinned) scheduleInfoHide();
			});
		}
		return infoPop;
	}

	function cancelInfoHide() {
		if (infoHideTimer) {
			clearTimeout(infoHideTimer);
			infoHideTimer = null;
		}
	}

	function scheduleInfoHide() {
		cancelInfoHide();
		infoHideTimer = setTimeout(function() {
			infoHideTimer = null;
			if (!infoPinned) hideInfoTip();
		}, 250);
	}

	function showInfoTip(tip) {
		var text = tip.querySelector('.info-tip-text');
		if (!text) return;
		cancelInfoHide();
		var pop = infoPopEl();
		pop.innerHTML = text.innerHTML;
		// Measure invisibly at 0,0 so max-width wrapping settles before we
		// clamp the final position to the viewport.
		pop.style.visibility = 'hidden';
		pop.style.display = 'block';
		pop.style.left = '0px';
		pop.style.top = '0px';
		var r = tip.getBoundingClientRect();
		var pw = pop.offsetWidth;
		var ph = pop.offsetHeight;
		var x = Math.max(8, Math.min(r.left - 8, window.innerWidth - pw - 8));
		var y = r.bottom + 6;
		if (y + ph > window.innerHeight - 8) {
			y = Math.max(8, r.top - ph - 6);
		}
		pop.style.left = x + 'px';
		pop.style.top = y + 'px';
		pop.style.visibility = '';
	}

	function hideInfoTip() {
		cancelInfoHide();
		if (infoPinned) {
			infoPinned.classList.remove('info-tip-pinned');
			infoPinned = null;
		}
		if (infoPop) infoPop.style.display = 'none';
	}

	function closestInfoTip(target) {
		return (target && target.closest) ? target.closest('.info-tip') : null;
	}

	function initInfoTips() {
		document.addEventListener('mouseover', function(e) {
			var tip = closestInfoTip(e.target);
			if (tip) showInfoTip(tip);
		});
		document.addEventListener('mouseout', function(e) {
			if (closestInfoTip(e.target) && !infoPinned) scheduleInfoHide();
		});
		document.addEventListener('focusin', function(e) {
			var tip = closestInfoTip(e.target);
			if (tip) showInfoTip(tip);
		});
		document.addEventListener('focusout', function(e) {
			if (closestInfoTip(e.target) && !infoPinned) scheduleInfoHide();
		});
		document.addEventListener('click', function(e) {
			var tip = closestInfoTip(e.target);
			if (tip) {
				// An ⓘ inside a <label> wrapping a checkbox (sel-check) would
				// otherwise toggle the box.
				e.preventDefault();
				e.stopPropagation();
				if (infoPinned === tip) {
					hideInfoTip();
					return;
				}
				if (infoPinned) infoPinned.classList.remove('info-tip-pinned');
				infoPinned = tip;
				tip.classList.add('info-tip-pinned');
				showInfoTip(tip);
			} else if (infoPinned && !(infoPop && infoPop.contains(e.target))) {
				hideInfoTip();
			}
		});
		document.addEventListener('keydown', function(e) {
			if (e.key === 'Escape') {
				hideInfoTip();
				return;
			}
			var tip = closestInfoTip(e.target);
			if (tip && (e.key === 'Enter' || e.key === ' ')) {
				e.preventDefault();
				tip.click();
			}
		});
		// The popup is viewport-anchored; any scroll (the tasks window
		// scrolls internally, so listen in capture) invalidates its position.
		document.addEventListener('scroll', function() {
			if (infoPop && infoPop.style.display !== 'none') hideInfoTip();
		}, true);
	}

	// The node test harness evaluates this file with no DOM; only wire the
	// document listeners in a real browser.
	if (typeof document !== 'undefined') initInfoTips();

	// True when a cloud is where the call LEAVES OCCAS. The palette has one cloud
	// symbol carrying no direction, so the arrows decide: a transition pointing AT
	// the cloud makes it an exit. Its own out-edge is the route-back line, which is
	// why only inbound edges count. Diagrams saved before the two cloud symbols
	// were merged still carry role="egress" and win outright.
	//
	// Same rule as FsmarExportServlet — keep the two in step.
	function isExitCloud(cell) {
		if (!cell || !cell.value || !cell.value.tagName) return false;
		if (cell.getAttribute('role') === 'egress') return true;
		if (cell.value.tagName !== 'Gateway') return false;
		var edges = cell.edges || [];
		for (var i = 0; i < edges.length; i++) {
			if (edges[i].target === cell) return true;
		}
		return false;
	}

	return {
		selectFile: selectFile,
		saveFile: saveFile,
		applyToolbarTooltips: applyToolbarTooltips,
		graphToSvgString: graphToSvgString,
		downloadSvg: downloadSvg,
		isExitCloud: isExitCloud,
		hideInfoTip: hideInfoTip
	};

})();
