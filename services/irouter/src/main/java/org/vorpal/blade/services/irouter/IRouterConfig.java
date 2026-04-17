package org.vorpal.blade.services.irouter;

import java.io.Serializable;

/// Service-level alias for the framework's [org.vorpal.blade.framework.v3.configuration.IRouterConfig].
///
/// The iRouter has no service-specific config beyond what the
/// framework provides (`adapters`, `tables`, `defaultTreatment`),
/// so this is a thin pass-through.
public class IRouterConfig
		extends org.vorpal.blade.framework.v3.configuration.IRouterConfig
		implements Serializable {
	private static final long serialVersionUID = 1L;
}
