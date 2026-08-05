package org.vorpal.blade.admin.irouter;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// Settings for the iRouter Editor admin app. Currently exposes only the
/// inherited metadata fields (rendered on the BLADE Admin Portal launcher
/// card). Add app-specific knobs here later as the app grows.
@SchemaAbout(
		name = "iRouter Editor",
		tagline = "Pipeline & Routing with Live Dry-Run",
		description = "Author the iRouter's enrichment pipeline and routing decision, and dry-run "
				+ "any SIP request against the unsaved draft — see the exact Route (forward, "
				+ "redirect target, or final response) before you publish.")
public class IRouterEditorSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
