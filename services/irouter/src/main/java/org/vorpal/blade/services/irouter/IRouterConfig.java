package org.vorpal.blade.services.irouter;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.RouterConfiguration;

/// Configuration for the Intelligent Router.
///
/// Extends [RouterConfiguration] with `RoutingTreatment` as the
/// treatment type. Future additions: REST endpoint config, JDBC
/// data source, LDAP connection parameters for routing lookups.
public class IRouterConfig extends RouterConfiguration<RoutingTreatment> implements Serializable {
	private static final long serialVersionUID = 1L;
}
