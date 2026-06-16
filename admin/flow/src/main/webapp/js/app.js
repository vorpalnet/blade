/*
 * Copyright (c) 2006-2013, JGraph Ltd
 *
 * Defines the startup sequence of the application.
 */
{

	/**
	 * Constructs a new application (returns an mxEditor instance)
	 */
	function createEditor(config)
	{
		var editor = null;
		
		var hideSplash = function()
		{
			// Fades-out the splash screen
			var splash = document.getElementById('splash');
			
			if (splash != null)
			{
				try
				{
					mxEvent.release(splash);
					mxEffects.fadeOut(splash, 100, true);
				}
				catch (e)
				{
					splash.parentNode.removeChild(splash);
				}
			}
		};
		
		try
		{
			if (!mxClient.isBrowserSupported())
			{
				mxUtils.error('Browser is not supported!', 200, false);
			}
			else
			{
				mxObjectCodec.allowEval = true;
				var node = mxUtils.load(config).getDocumentElement();
				editor = new mxEditor(node);
				mxObjectCodec.allowEval = false;
				
				// Adds active border for panning inside the container
				editor.graph.createPanningManager = function()
				{
					var pm = new mxPanningManager(this);
					pm.border = 30;
					
					return pm;
				};
				
				editor.graph.allowAutoPanning = true;
				editor.graph.timerAutoScroll = true;
				editor.graph.setEdgeLabelsMovable(true);

				// Hand tool: drag the background to slide the view. Implemented
				// as an explicit view-translate drag rather than relying on
				// mxPanningHandler's left-button panning — this version tracks
				// raw client coordinates (no feedback loop from the moving
				// view) and consumes the gesture so the rubberband can't fight
				// it. Active only in pan mode (setMode sets the flag we key
				// off) and only on the background, so cell selection in pan
				// mode still works by clicking a cell.
				(function(graph) {
					// Neutralize the built-in left-button panning so the two
					// implementations can't both move the view (double-speed
					// pan). Ctrl+Shift-drag and popup-trigger panning remain.
					var panningHandler = graph.panningHandler;
					panningHandler.isPanningTrigger = function(me)
					{
						var evt = me.getEvent();
						return (mxEvent.isControlDown(evt) && mxEvent.isShiftDown(evt)) ||
							(this.usePopupTrigger && mxEvent.isPopupTrigger(evt));
					};

					var drag = null;
					graph.addMouseListener({
						mouseDown: function(sender, me)
						{
							if (panningHandler.useLeftButtonForPanning &&
									me.getState() == null &&
									mxEvent.isLeftMouseButton(me.getEvent()))
							{
								var t = graph.view.translate;
								drag = {
									x0: mxEvent.getClientX(me.getEvent()),
									y0: mxEvent.getClientY(me.getEvent()),
									tx: t.x, ty: t.y
								};
								graph.container.style.cursor = 'grabbing';
								me.consume();
							}
						},
						mouseMove: function(sender, me)
						{
							if (drag != null)
							{
								var s = graph.view.scale;
								var dx = (mxEvent.getClientX(me.getEvent()) - drag.x0) / s;
								var dy = (mxEvent.getClientY(me.getEvent()) - drag.y0) / s;
								graph.view.setTranslate(drag.tx + dx, drag.ty + dy);
								me.consume();
							}
						},
						mouseUp: function(sender, me)
						{
							if (drag != null)
							{
								drag = null;
								graph.container.style.cursor =
									(panningHandler.useLeftButtonForPanning) ? 'grab' : 'default';
								me.consume();
							}
						}
					});

					// Grab cursor while the hand tool is active.
					var setMode = editor.setMode;
					editor.setMode = function(modename)
					{
						setMode.apply(this, arguments);
						graph.container.style.cursor = (modename == 'pan') ? 'grab' : 'default';
					};
				})(editor.graph);

				// Click-to-place guard. In this mxGraph build a toolbar
				// prototype can reach mxEditor.addVertex with a null geometry
				// (the cloned cell loses it), so addVertex's geo.clone() throws
				// and the node is silently never inserted — clicking a toolbar
				// node then the canvas appears to do nothing. Restore a
				// geometry, sized by node type (State is shorter than the
				// Gateway/cloud boxes), before delegating.
				var origAddVertex = editor.addVertex;
				editor.addVertex = function(parent, vertex, x, y)
				{
					if (vertex != null && this.graph.getModel().getGeometry(vertex) == null)
					{
						var tag = (vertex.value && vertex.value.tagName) || '';
						var h = (tag === 'State') ? 48 : 80;
						vertex.setGeometry(new mxGeometry(0, 0, 120, h));
					}
					return origAddVertex.apply(this, arguments);
				};

				// Updates the window title after opening new files
				var title = document.title;
				var funct = function(sender)
				{
					document.title = title + ' - ' + sender.getTitle();
				};
				
				editor.addListener(mxEvent.OPEN, funct);
				
				// Prints the current root in the window title if the
				// current root of the graph changes (drilling).
				editor.addListener(mxEvent.ROOT, funct);
				funct(editor);
				
				// Displays version in statusbar
				editor.setStatus('BLADE Flow Editor — FSMAR 3');

				// Apply hardcoded tooltips to toolbar buttons (mxResources/app.txt
				// loading is unreliable in the deployed environment, so we set
				// title attributes directly).
				if (window.flowUtils && window.flowUtils.applyToolbarTooltips) {
					window.flowUtils.applyToolbarTooltips();
				}

				// Blur any focused property-panel input when the user clicks on
				// the canvas. Without this, focus stays on the input and the
				// Delete key (and other keyboard shortcuts) get swallowed instead
				// of being routed to mxKeyHandler.
				if (editor.graph && editor.graph.container) {
					mxEvent.addListener(editor.graph.container, 'mousedown', function() {
						if (document.activeElement && document.activeElement !== document.body
								&& typeof document.activeElement.blur === 'function') {
							document.activeElement.blur();
						}
					});
				}

				// Auto-separate parallel edges (multiple edges between the same
				// pair of nodes) by fanning them apart with control points.
				var parallelLayout = new mxParallelEdgeLayout(editor.graph);
				parallelLayout.spacing = 32;

				// Run it on demand, NOT on every model change. Running it on
				// every CHANGE re-fired right after the hierarchical
				// auto-layout's batch and re-set control points on parallel
				// edges, undoing that layout's routing (edges through boxes).
				// Exposed for flowFsmar.autoLayout to call once after routing.
				window.flowParallelEdges = function(graph) {
					parallelLayout.execute(graph.getDefaultParent());
				};

				// Separate edges when the user draws a new one.
				editor.graph.addListener(mxEvent.CELL_CONNECTED, function(sender, evt) {
					window.flowParallelEdges(editor.graph);
				});

				// Track unsaved work for the beforeunload prompt (flowSession.js).
				// Any model change marks the diagram dirty; flowFsmar clears it on
				// publish/import. The initial config load fires CHANGE events
				// synchronously during createEditor, so reset to a clean baseline
				// on the next tick — only the user's own later edits count.
				if (window.flowDirty) {
					editor.graph.getModel().addListener(mxEvent.CHANGE, function() {
						window.flowDirty.set();
					});
					window.setTimeout(function() { window.flowDirty.clear(); }, 0);
				}

				// Shows the application
				hideSplash();
			}
		}
		catch (e)
		{
			hideSplash();

			// Shows an error message if the editor cannot start
			mxUtils.alert('Cannot start application: ' + e.message);
			throw e; // for debugging
		}

		
		console.log('mxClient.basePath: ' + mxClient.basePath);
		
		
		return editor;
	}

}
