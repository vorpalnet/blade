package org.vorpal.blade.services.irouter;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.RouterConfiguration;

/// iRouter's concrete configuration — a [RouterConfiguration]
/// parameterized by [RoutingTreatment].
///
/// The iRouter has no service-specific config beyond what the framework
/// provides (`adapters`, `plan`, `defaultRoute`), so this is effectively
/// a type alias that fixes `T = RoutingTreatment` for
/// `SettingsManager<IRouterConfig>` to load from disk.
public class IRouterConfig
		extends RouterConfiguration<RoutingTreatment>
		implements Serializable {
	private static final long serialVersionUID = 1L;
}
