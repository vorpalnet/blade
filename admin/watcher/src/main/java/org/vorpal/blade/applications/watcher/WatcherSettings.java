package org.vorpal.blade.applications.watcher;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// Settings for the headless `watcher` auto-publish shim.
///
/// Currently a single toggle: `enabled`. When `false`, the WAR stays
/// deployed but the file-system watcher thread is never started — useful
/// for temporarily silencing auto-publish during a maintenance window
/// without an undeploy. Read once at startup; flipping the flag requires
/// the AdminServer to be restarted (or the WAR redeployed).
@SchemaAbout(
		name = "Watcher",
		tagline = "Headless Configuration Monitor",
		description = "Backend-only service that watches the BLADE configuration directory and pushes JSON file changes into the matching SettingsMXBean. Kept for backward compatibility with deployments that don't run the Configurator.")
public class WatcherSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected boolean enabled;

	@JsonPropertyDescription("When true, the watcher auto-publishes config file changes via JMX. When false, the WAR is deployed but inert. Read at startup; redeploy required to apply changes.")
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
