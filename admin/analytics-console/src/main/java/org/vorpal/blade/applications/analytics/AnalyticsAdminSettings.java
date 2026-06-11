package org.vorpal.blade.applications.analytics;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Analytics admin app. Currently exposes only the inherited
/// `name` / `tagline` / `description` metadata fields (rendered on the BLADE
/// Admin Portal launcher card). Add app-specific knobs here later — e.g.
/// expected JNDI names for the analytics JMS/DB resources, default refresh
/// intervals for the status panels.
@SchemaAbout(
		name = "Analytics",
		tagline = "Pipeline Status & Config",
		description = "Audit the WebLogic resources the analytics pipeline depends on — JMS Server, Connection Factory, Distributed Queue, JDBC Data Source — and (in upcoming releases) create what's missing and visualize what's flowing through.")
public class AnalyticsAdminSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
