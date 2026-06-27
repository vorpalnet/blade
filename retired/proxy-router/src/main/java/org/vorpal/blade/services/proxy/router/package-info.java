/// This package provides a SIP proxy router that performs request routing based on
/// configurable translation maps. It uses the framework's `RouterConfig` selector and
/// translation plan infrastructure to determine the destination URI for each incoming
/// request.
///
/// ## Key Components
///
/// - [ProxyRouterSipServlet] - Main proxy servlet that applies routing translations to incoming requests
/// - [ProxyRouterConfigSample] - Sample configuration demonstrating selector-based routing with
///   nested translation maps
///
/// ## Architecture
///
/// The service extends `ProxyServlet` from the framework. When a request arrives,
/// [ProxyRouterSipServlet] calls `config.findRoute(request)` on the current `RouterConfig`
/// to evaluate selectors and translation plans. If a matching route is found, it is used
/// as the proxy destination; otherwise, the original Request-URI is used. The resolved
/// URI is added as a single-endpoint `ProxyTier` in the proxy plan.
///
/// ## Detailed Class Reference
///
/// ### ProxyRouterSipServlet
///
/// Main servlet annotated with `@WebListener`, `@SipApplication(distributable=true)`,
/// and `@SipServlet(loadOnStartup=1)`. Extends `ProxyServlet` and maintains a static
/// `SettingsManager<RouterConfig>`. The `proxyRequest` method:
///
/// 1. Retrieves the current `RouterConfig` from the settings manager
/// 2. Calls `findRoute(request)` to evaluate the translation plan against the request
/// 3. Falls back to the original Request-URI if no translation matches
/// 4. Creates a `ProxyTier` with the resolved URI and adds it to the proxy plan
/// 5. Logs the From, To, and resolved URI at INFO level
///
/// The `proxyResponse` method logs the response status at FINER level.
///
/// ### ProxyRouterConfigSample
///
/// A comprehensive sample configuration extending `RouterConfig` demonstrating:
///
/// - **Selectors** -- `caller` extracts the user part from the From header;
///   `dialed` extracts the user part from the To header
/// - **Nested translation maps** -- shows how a single caller entry can contain
///   a sub-map that further routes based on the dialed number
/// - **ConfigHashMap** -- used for exact-match lookups (e.g., caller ID "alice")
/// - **ConfigPrefixMap** -- used for longest-prefix matching on dialed numbers
///   (e.g., "1816555" matches all numbers in that NPA-NXX)
///
/// The sample includes three caller scenarios:
///
/// - `alice` -- has nested dialed-number routing (bob gets carol, carol gets bob)
/// - `18165551234` -- all calls routed to voicemail with dialed-number overrides
///   using prefix matching for area code, NPA, and NXX-level routing
/// - `12795555678` -- exact-match blocking for a specific dialed number
///
/// @see ProxyRouterSipServlet
/// @see [org.vorpal.blade.framework.v2.proxy.ProxyServlet]
/// @see [org.vorpal.blade.framework.v2.config.RouterConfig]
/// @see [org.vorpal.blade.framework.v2.config.Selector]
/// @see [org.vorpal.blade.framework.v2.config.TranslationsMap]
package org.vorpal.blade.services.proxy.router;
