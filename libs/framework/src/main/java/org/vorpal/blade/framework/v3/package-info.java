/// BLADE framework version 3: the config-first routing model.
///
/// v3 replaces the v2 `RouterConfig` / `TranslationsMap` machinery with
/// a two-phase pipeline:
///
/// 1. An ordered list of [org.vorpal.blade.framework.v3.configuration.connectors.Connector]s
///    that enrich a shared per-call [org.vorpal.blade.framework.v3.configuration.Context]
///    by reading from SIP messages, REST APIs, JDBC data sources, LDAP
///    directories, static maps, or in-memory translation tables.
/// 2. A single polymorphic [org.vorpal.blade.framework.v3.configuration.routing.Routing]
///    that reads the now-enriched Context and produces a concrete
///    [org.vorpal.blade.framework.v3.configuration.routing.Route]
///    (destination SIP URI + outbound INVITE headers).
///
/// The entire model is JSON-driven. Every concrete type — Connector,
/// Selector, Authentication, Routing — is polymorphic via a `type`
/// discriminator so the Configurator form editor can render a dropdown
/// and reshape its form when the user changes type. Operators build
/// routing behavior by editing JSON; Java provides the implementations.
///
/// v2 remains frozen and supported via a separate package tree. v3 is
/// where new routing work happens.
///
/// @see org.vorpal.blade.framework.v3.configuration
package org.vorpal.blade.framework.v3;
