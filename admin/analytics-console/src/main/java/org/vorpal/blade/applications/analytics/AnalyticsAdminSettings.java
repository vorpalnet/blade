package org.vorpal.blade.applications.analytics;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Analytics admin app. Currently exposes only the inherited
/// `name` / `tagline` / `description` metadata fields (rendered on the BLADE
/// Admin Portal launcher card). Add app-specific knobs here later — e.g.
/// expected JNDI names for the analytics JMS/DB resources, default refresh
/// intervals for the status panels.
public class AnalyticsAdminSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
