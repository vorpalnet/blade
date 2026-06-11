package org.vorpal.blade.applications.portal;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Portal-specific settings. Inherits `name` / `tagline` / `description`
/// (the launcher metadata) from [Configuration]; that's all the portal
/// needs since the app list is now discovered dynamically from JMX (see
/// [[portal-card-discovery]]), not authored as a manifest.
///
/// Kept as a class (rather than removed entirely) so the portal continues
/// to register a `vorpal.blade:Name=portal,Type=Configuration` MBean —
/// which makes the portal visible in the Configurator's app dropdown and
/// gives operators a place to override the portal's own card metadata.
@SchemaAbout(
		name = "BLADE Admin Portal",
		tagline = "Unified Administration",
		description = "Launcher for every admin app deployed on this AdminServer. The deck is built live from JMX — any app that registers a SettingsMXBean shows up automatically.")
public class PortalSettings extends Configuration {
	private static final long serialVersionUID = 1L;
}
