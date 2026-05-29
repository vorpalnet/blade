package org.vorpal.blade.applications.console.mxgraph;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Flow admin app (FSMAR callflow editor). Currently
/// exposes only the inherited `name` / `tagline` / `description` metadata
/// fields (rendered on the BLADE Admin Portal launcher card). Add
/// app-specific knobs here later.
public class FlowSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
