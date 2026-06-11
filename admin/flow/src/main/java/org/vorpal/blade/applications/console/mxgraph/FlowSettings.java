package org.vorpal.blade.applications.console.mxgraph;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Flow admin app (FSMAR callflow editor). Currently
/// exposes only the inherited `name` / `tagline` / `description` metadata
/// fields (rendered on the BLADE Admin Portal launcher card). Add
/// app-specific knobs here later.
@SchemaAbout(
		name = "Flow",
		tagline = "FSMAR Callflow Editor",
		description = "Design SIP callflows visually as FSMAR state machines. Drag-and-drop trigger / task / transition nodes; round-trips FSMAR XML for execution by the framework's lambda callflow engine.")
public class FlowSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
