package org.vorpal.blade.applications.console.config;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

/// Settings for the Configurator admin app.
///
/// `autoPublish` controls the same file-system watcher behavior the
/// legacy `watcher` WAR provides: when `true`, on-disk edits to
/// `./config/custom/vorpal/*.json` are auto-published via JMX. When
/// `false`, operators must explicitly Save + Publish through the UI.
/// Read once at startup; flipping the flag requires the AdminServer to
/// be restarted (or the WAR redeployed).
///
/// This is a transitional bridge so customers running the legacy
/// `watcher` can move to the Configurator without losing the
/// auto-publish behavior their ops scripts depend on.
@JsonSchemaTitle("Configurator")
public class ConfiguratorSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected boolean autoPublish;

	@JsonPropertyDescription("When true, on-disk *.json edits under ./config/custom/vorpal/ are auto-published via JMX (the legacy watcher behavior). When false, only explicit Save + Publish through the UI applies changes. Read at startup; redeploy required to apply changes.")
	public boolean isAutoPublish() {
		return autoPublish;
	}

	public void setAutoPublish(boolean autoPublish) {
		this.autoPublish = autoPublish;
	}
}
