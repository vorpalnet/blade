package org.vorpal.blade.applications.console.config;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// Settings for the Configurator admin app.
///
/// `autoPublish` controls the file-system watcher: when `true` (the default),
/// on-disk edits to `./config/custom/vorpal/*.json` are auto-published to live
/// services via JMX and the UI hides its Publish button. When `false`,
/// operators must explicitly Save + Publish through the UI.
///
/// The flag is live. [ConfiguratorSettingsManager] starts and stops the
/// watcher thread from its `initialize()` hook on every reload, so the
/// Auto-publish toggle in the UI takes effect immediately, no redeploy.
///
/// The field initializer is `true`, not just the sample's value, because a
/// `blade-configurator.json` that omits the key deserializes through the
/// no-arg constructor.
@SchemaAbout(
		name = "Configurator",
		tagline = "Schema-Driven Configuration Editor",
		description = "Edit every deployed BLADE service's configuration through a form generated live from its JSON Schema. Targets domain, cluster, and per-server scopes; tracks version history; round-trips changes to live JMX MBeans without an application restart.")
public class ConfiguratorSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected boolean autoPublish = true;
	protected AiSettings ai = new AiSettings();

	@JsonPropertyDescription("When true (the default), on-disk *.json edits under ./config/custom/vorpal/ are auto-published to live services via JMX, and the Publish button is hidden. When false, only explicit Save + Publish through the UI applies changes. Takes effect immediately when toggled.")
	public boolean isAutoPublish() {
		return autoPublish;
	}

	public void setAutoPublish(boolean autoPublish) {
		this.autoPublish = autoPublish;
	}

	@JsonPropertyDescription("The AI config assistant: describe a configuration in plain language and Claude drafts it against the app's JSON Schema, as a proposal for review. Disabled by default.")
	public AiSettings getAi() {
		return ai;
	}

	public void setAi(AiSettings ai) {
		this.ai = (ai != null) ? ai : new AiSettings();
	}
}
