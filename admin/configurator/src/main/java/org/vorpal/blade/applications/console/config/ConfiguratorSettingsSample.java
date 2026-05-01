package org.vorpal.blade.applications.console.config;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/configurator.json` on first deployment when
/// no operator-supplied file is present.
///
/// Default `autoPublish=true` preserves the legacy `watcher` behavior
/// for customers migrating off `watcher` to the Configurator. Once a
/// customer's ops scripts are on the explicit Save + Publish flow, they
/// can flip this to `false`.
public class ConfiguratorSettingsSample extends ConfiguratorSettings {
	private static final long serialVersionUID = 1L;

	public ConfiguratorSettingsSample() {
		this.autoPublish = true;
	}
}
