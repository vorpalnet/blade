package org.vorpal.blade.applications.console.config;

/// Default values shipped with the WAR, written to
/// `_samples/blade-configurator.json.SAMPLE` on every deploy.
/// [ConfiguratorSettingsManager] copies that file to
/// `blade-configurator.json` when no domain file exists, so the UI's
/// settings (the Auto-publish toggle) always have a file to save into.
///
/// Default `autoPublish=true`: a saved `*.json` goes live without a Publish
/// step. Operators who want changes staged until an explicit Publish flip the
/// Auto-publish toggle off.
public class ConfiguratorSettingsSample extends ConfiguratorSettings {
	private static final long serialVersionUID = 1L;

	public ConfiguratorSettingsSample() {
		this.autoPublish = true;
	}
}
