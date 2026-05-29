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

		this.about.setName("Configurator")
				.setTagline("Schema-Driven Configuration Editor")
				.setDescription("Edit every deployed BLADE service's configuration through a form generated live from its JSON Schema. Targets domain, cluster, and per-server scopes; tracks version history; round-trips changes to live JMX MBeans without an application restart.");
	}
}
