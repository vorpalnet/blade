package org.vorpal.blade.applications.console.config;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.vorpal.blade.framework.v2.config.SchemaTitle;

/// Settings for the Configurator admin app.
///
/// `autoPublish` controls the file-system watcher behavior the standalone
/// `watcher` WAR also provides: when `true`, on-disk edits to
/// `./config/custom/vorpal/*.json` are auto-published to live services
/// via JMX. When `false`, operators must explicitly Save + Publish
/// through the UI.
///
/// The flag is live — [ConfiguratorSettingsManager] starts and stops the
/// watcher thread from its `initialize()` hook on every reload, so the
/// Auto-publish toggle in the UI takes effect immediately, no redeploy.
@SchemaTitle("Configurator")
public class ConfiguratorSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected boolean autoPublish;

	@JsonPropertyDescription("When true, on-disk *.json edits under ./config/custom/vorpal/ are auto-published to live services via JMX (the same behavior the standalone watcher WAR provides). When false, only explicit Save + Publish through the UI applies changes. Takes effect immediately when toggled.")
	public boolean isAutoPublish() {
		return autoPublish;
	}

	public void setAutoPublish(boolean autoPublish) {
		this.autoPublish = autoPublish;
	}
}
