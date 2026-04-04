/// # SIP Proxy Framework
///
/// This package provides a comprehensive framework for building SIP proxy applications
/// with support for multi-tier routing, failover mechanisms, and flexible routing policies.
///
/// ## Core Components
///
/// - [ProxyServlet] - Abstract base servlet that extends [org.vorpal.blade.framework.v2.AsyncSipServlet] for proxy applications
/// - [ProxyListener] - Callback interface for customizing proxy routing decisions and handling responses
/// - [ProxyPlan] - Defines routing plans with multiple tiers, endpoints, and contextual information
/// - [ProxyTier] - Represents individual routing tiers with parallel or serial execution modes and configurable timeouts
/// - [ProxyInvite] - Callflow implementation for handling INVITE request proxying through configured tiers
/// - [ProxyCancel] - Callflow implementation for CANCEL request handling (no-op as container handles automatically)
///
/// ## Routing Architecture
///
/// The framework uses a hierarchical tier-based routing system where each [ProxyPlan] contains
/// multiple [ProxyTier] objects. Each tier can operate in `PARALLEL` or `SERIAL` mode,
/// allowing for sophisticated routing strategies including load balancing and failover.
/// Tiers support configurable timeouts and can contain multiple SIP URI endpoints.
///
/// ## Usage Pattern
///
/// Applications extend [ProxyServlet] and implement the [ProxyListener] interface
/// to provide custom routing logic. The servlet automatically routes INVITE and
/// CANCEL requests through the appropriate callflow implementations based on the
/// SIP method type. The [ProxyInvite] callflow executes the routing plan while
/// [ProxyCancel] defers to container-managed CANCEL processing.
///
/// ## Key Features
///
/// - Serializable routing plans and tiers for persistence and clustering support
/// - Copy constructors for safe plan modification and reuse
/// - Null-safe API design with defensive programming practices
/// - Integration with the Vorpal Blade callflow framework
/// - Support for arbitrary context objects in routing plans
///
/// @see ProxyServlet
/// @see ProxyListener
/// @see ProxyPlan
/// @see ProxyTier
/// @see ProxyInvite
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
package org.vorpal.blade.framework.v2.proxy;
