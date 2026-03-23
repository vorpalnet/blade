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
/// ### Routing Modes
///
/// - **PARALLEL** - All endpoints in a tier are tried simultaneously
/// - **SERIAL** - Endpoints in a tier are tried sequentially in order
///
/// ## Implementation Pattern
///
/// Applications extend [ProxyServlet] and implement the [ProxyListener] interface
/// to provide custom routing logic. The servlet automatically routes requests through
/// appropriate callflow implementations:
///
/// - INVITE requests are handled by [ProxyInvite] which executes the routing plan
/// - CANCEL requests are handled by [ProxyCancel] which defers to container processing
///
/// The [ProxyListener] interface provides two key callback methods for customizing
/// proxy behavior: building routing plans for incoming requests and handling responses
/// from proxied destinations.
///
/// ## Key Features
///
/// - **Serializable Components** - Both [ProxyPlan] and [ProxyTier] implement `Serializable` for persistence and clustering
/// - **Copy Constructors** - Safe plan modification and reuse through defensive copying
/// - **Null-Safe Design** - Defensive programming practices throughout the API
/// - **Context Support** - Application-specific context objects can be attached to routing plans
/// - **Framework Integration** - Built on the Vorpal Blade callflow framework
/// - **JSON Support** - Jackson annotations enable serialization of routing configurations
///
/// @see ProxyServlet
/// @see ProxyListener
/// @see ProxyPlan
/// @see ProxyTier
/// @see ProxyInvite
/// @see ProxyCancel
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
package org.vorpal.blade.framework.v2.proxy;
