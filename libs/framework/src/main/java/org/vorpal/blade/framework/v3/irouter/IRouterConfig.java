package org.vorpal.blade.framework.v3.irouter;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.RouterConfiguration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// iRouter's concrete configuration. The framework's
/// [RouterConfiguration] is now non-generic — its `routing` field carries
/// the polymorphic routing decision — so this is effectively just a
/// named type for `SettingsManager<IRouterConfig>` to load from disk.
///
/// No service-specific fields today; subclasses may add logging overrides
/// or analytics hooks later.
@SchemaAbout(
		name = "Intelligent Router",
		tagline = "Universal Config-Driven SIP Proxy",
		description = "The operator's answer to writing a custom SIP service per customer. A "
				+ "two-phase pipeline — enrichment Connectors, then a single routing decision — "
				+ "turns every routing rule into one JSON config the Configurator edits visually. "
				+ "No Java, no recompile for a new use case.")
public class IRouterConfig
		extends RouterConfiguration
		implements Serializable {
	private static final long serialVersionUID = 1L;
}
