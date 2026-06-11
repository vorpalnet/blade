package org.vorpal.blade.applications.console.config;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/configurator.json` on first deployment when
/// no operator-supplied file is present.
///
/// Default `autoPublish=false`: changes go live only through the UI's
/// explicit Save + Publish flow. Customers whose ops scripts drop edited
/// `*.json` files on disk and expect them to publish automatically can
/// flip the Auto-publish toggle on — or deploy the standalone `watcher`
/// WAR (`admin/watcher`) instead.
public class ConfiguratorSettingsSample extends ConfiguratorSettings {
	private static final long serialVersionUID = 1L;

	public ConfiguratorSettingsSample() {
		this.autoPublish = false;
	}
}
