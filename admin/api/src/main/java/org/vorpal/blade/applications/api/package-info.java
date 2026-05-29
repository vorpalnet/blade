/// BLADE Admin **API Explorer** (`blade/api`).
///
/// A standalone admin webapp that renders the OpenAPI documents published by
/// deployed BLADE apps. It discovers candidates live from the AdminServer's
/// DomainRuntime MBean tree, probes each for an OpenAPI document at
/// `/<contextRoot>/resources/openapi.json`, and renders the selected one with
/// [Scalar](https://github.com/scalar/scalar).
///
/// The OpenAPI documents live on the **engine tier** (e.g. `:8001`), not on the
/// AdminServer where this app runs, so the engine base URL is supplied via
/// configuration ([ApiSettings#getEngineBaseUrl]). Specs are streamed back to
/// the browser through a constrained same-origin proxy ([SpecProxyResource]) —
/// the proxy can only ever resolve to `<engineBaseUrl>/<app>/resources/...`,
/// so it is not a general-purpose relay.
///
/// Internal reads go over JMX; REST is only the browser↔server boundary. See
/// memory `[[internal-communication-jmx]]`.
package org.vorpal.blade.applications.api;
