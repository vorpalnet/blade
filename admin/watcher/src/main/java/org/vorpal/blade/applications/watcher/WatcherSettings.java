package org.vorpal.blade.applications.watcher;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

/// Settings for the legacy `watcher` compatibility shim.
///
/// Currently a single toggle: `enabled`. When `false`, the WAR stays
/// deployed but the file-system watcher thread is never started — useful
/// for temporarily silencing auto-publish during a maintenance window
/// without an undeploy. Read once at startup; flipping the flag requires
/// the AdminServer to be restarted (or the WAR redeployed).
@JsonSchemaTitle("Watcher")
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
