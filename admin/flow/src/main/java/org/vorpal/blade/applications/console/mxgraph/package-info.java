/// Server side of the BLADE Flow editor at `/blade/flow` — a browser-based
/// diagram editor (mxGraph) for FSMAR call-flow configurations.
///
/// The editor renders an FSMAR application-router configuration as a state
/// diagram and lets the user edit it graphically. [FsmarImportServlet]
/// converts FSMAR JSON configuration into the mxGraph XML the canvas
/// renders; [FsmarExportServlet] converts the edited diagram back into
/// FSMAR JSON for publishing. [FlowSettings] carries the app's
/// configuration (registered via the standard SettingsManager so it appears
/// in the Configurator).
package org.vorpal.blade.applications.console.mxgraph;
