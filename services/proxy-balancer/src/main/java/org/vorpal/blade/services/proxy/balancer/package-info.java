/// This package provides a SIP proxy load balancer that distributes requests across
/// multiple endpoints organized into tiered proxy plans. It supports parallel and serial
/// routing modes, periodic health checking via OPTIONS pings, and dynamic endpoint
/// status tracking.
///
/// ## Key Components
///
/// - [ProxyBalancerServlet] - Main proxy servlet that routes requests based on configured proxy plans
/// - [ProxyBalancerConfig] - Configuration class defining proxy plans, endpoint status, and ping intervals
/// - [ProxyBalancerConfigSample] - Sample configuration with multi-tier parallel and serial routing
/// - [ProxyBalancerSettingsManager] - Settings manager that builds the endpoint status map from proxy plans
/// - [OptionsPingCallflow] - Periodic callflow that sends OPTIONS pings to track endpoint availability
///
/// ## Architecture
///
/// The service extends `ProxyServlet` from the framework to provide tiered proxy routing.
/// When a request arrives, [ProxyBalancerServlet] extracts the host from the Request-URI and
/// looks up a matching `ProxyPlan` from the configuration. Each plan contains ordered tiers
/// of endpoints that can be tried in parallel or serial mode with configurable timeouts.
///
/// Endpoint health is monitored by [OptionsPingCallflow], which sends periodic SIP OPTIONS
/// requests to all configured endpoints and updates their status (up/down) based on the
/// response. This status information can be used to skip unavailable endpoints during routing.
///
/// ## Detailed Class Reference
///
/// ### ProxyBalancerServlet
///
/// Main servlet annotated with `@WebListener`, `@SipApplication(distributable=true)`,
/// and `@SipServlet(loadOnStartup=1)`. Extends `ProxyServlet` and maintains a static
/// `SettingsManager<ProxyBalancerConfig>`. The `proxyRequest` method extracts the host
/// portion of the Request-URI, looks up the corresponding `ProxyPlan`, and copies it
/// into the active proxy plan. The `doResponse` method handles response callbacks by
/// pulling the stored callback from the callflow and invoking it. The `proxyResponse`
/// method logs the response status.
///
/// ### ProxyBalancerConfig
///
/// Configuration class extending `Configuration` and implementing `Serializable`. Defines:
///
/// - `plans` (HashMap of String to ProxyPlan) -- maps hostnames to proxy routing plans
/// - `pingInterval` (Integer) -- seconds between OPTIONS health-check cycles (default 60)
/// - `endpointStatus` (HashMap, `@JsonIgnore`) -- runtime map of endpoint URIs to `EndpointStatus` (up/down)
/// - `tierStatus` (HashMap, `@JsonIgnore`) -- runtime map of tier names to `TierStatus` (available/degraded/unavailable)
/// - `planStatus` (HashMap, `@JsonIgnore`) -- runtime map of plan names to `PlanStatus` (optimized/impaired/faulty)
///
/// Provides fluent `addPlan` and `removePlan` methods for programmatic configuration.
///
/// ### ProxyBalancerConfigSample
///
/// Sample configuration demonstrating a two-tier proxy plan named "test1":
///
/// - **Tier 1** (parallel, 15s timeout) -- two endpoints (`blade1`, `blade2`) tried simultaneously
/// - **Tier 2** (serial, 15s timeout) -- three endpoints (`blade3`, `blade4`, `blade5`) tried in sequence
///
/// Uses `ProxyServlet.getSipFactory()` to create endpoint URIs with test parameters.
///
/// ### ProxyBalancerSettingsManager
///
/// Extends `SettingsManager<ProxyBalancerConfig>` and overrides `initialize` to build
/// the `endpointStatus` map. Iterates over all plans, tiers, and endpoints, extracting
/// the host from each SIP URI and setting the initial status to `EndpointStatus.up`.
///
/// ### OptionsPingCallflow
///
/// Periodic health-check callflow extending `Callflow`. The `process` method schedules
/// a repeating timer (interval from `pingInterval` config) that iterates over all
/// entries in `endpointStatus`, sends an OPTIONS request to each endpoint using the
/// servlet context name as the From address, and updates the status based on the
/// response (200 = up, anything else = down).
///
/// @see ProxyBalancerServlet
/// @see ProxyBalancerConfig
/// @see [org.vorpal.blade.framework.v2.proxy.ProxyServlet]
/// @see [org.vorpal.blade.framework.v2.proxy.ProxyPlan]
/// @see [org.vorpal.blade.framework.v2.proxy.ProxyTier]
package org.vorpal.blade.services.proxy.balancer;
