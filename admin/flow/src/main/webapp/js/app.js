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

				// Auto-separate parallel edges between the same pair of nodes
				var parallelLayout = new mxParallelEdgeLayout(editor.graph);
				parallelLayout.spacing = 32;

				editor.graph.addListener(mxEvent.CELL_CONNECTED, function(sender, evt) {
					parallelLayout.execute(editor.graph.getDefaultParent());
				});

				// Also run after model decode (e.g. JSON import) so imported
				// graphs get separated edges immediately.
				editor.graph.getModel().addListener(mxEvent.CHANGE, function() {
					if (!parallelLayout._running) {
						parallelLayout._running = true;
						try {
							parallelLayout.execute(editor.graph.getDefaultParent());
						} finally {
							parallelLayout._running = false;
						}
					}
				});

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
