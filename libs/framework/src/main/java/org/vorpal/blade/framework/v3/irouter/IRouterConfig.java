package org.vorpal.blade.framework.v3.irouter;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.RouterConfiguration;

/// iRouter's concrete configuration. The framework's
/// [RouterConfiguration] is now non-generic — its `routing` field carries
/// the polymorphic routing decision — so this is effectively just a
/// named type for `SettingsManager<IRouterConfig>` to load from disk.
///
/// No service-specific fields today; subclasses may add logging overrides
/// or analytics hooks later.
public class IRouterConfig
		extends RouterConfiguration
		implements Serializable {
	private static final long serialVersionUID = 1L;
}
