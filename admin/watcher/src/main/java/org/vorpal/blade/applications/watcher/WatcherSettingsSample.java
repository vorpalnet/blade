package org.vorpal.blade.applications.watcher;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/watcher.json` on first deployment when no
/// operator-supplied file is present.
public class WatcherSettingsSample extends WatcherSettings {
	private static final long serialVersionUID = 1L;

	public WatcherSettingsSample() {
		this.enabled = true;

		this.about.setName("Watcher")
				.setTagline("Headless Configuration Monitor")
				.setDescription("Backend-only service that watches the BLADE configuration directory and pushes JSON file changes into the matching SettingsMXBean. Kept for backward compatibility with deployments that don't run the Configurator.");
	}
}
