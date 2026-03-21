/// # Proxy Framework Package
///
/// This package provides a comprehensive framework for building SIP proxy functionality
/// within the Vorpal Blade framework v2. It enables sophisticated call routing, load
/// balancing, and failover capabilities through a tiered proxy architecture.
///
/// ## Core Components
///
/// - [ProxyPlan] - Main orchestrator that defines and executes proxy routing strategies
/// - [ProxyTier] - Represents a tier of proxy targets with specific routing policies
/// - [ProxyInvite] - Encapsulates SIP INVITE request handling within the proxy context
/// - [ProxyCancel] - Manages SIP CANCEL request processing for ongoing proxy operations
///
/// ## Architecture Overview
///
/// The proxy framework operates on a tiered approach where each [ProxyTier] contains
/// multiple proxy targets. The [ProxyPlan] coordinates the execution across tiers,
/// implementing various routing algorithms such as:
///
/// - Sequential routing (trying targets one after another)
/// - Parallel routing (forking to multiple targets simultaneously)
/// - Load balancing across available targets
/// - Failover mechanisms for high availability
///
/// ## Usage Example
///
/// ```java
/// // Create a proxy plan with multiple tiers
/// ProxyPlan plan = new ProxyPlan();
/// 
/// // Add primary tier with load balancing
/// ProxyTier primaryTier = new ProxyTier();
/// primaryTier.addTarget("sip:server1@example.com");
/// primaryTier.addTarget("sip:server2@example.com");
/// plan.addTier(primaryTier);
/// 
/// // Add backup tier for failover
/// ProxyTier backupTier = new ProxyTier();
/// backupTier.addTarget("sip:backup@example.com");
/// plan.addTier(backupTier);
/// 
/// // Execute the proxy plan
/// plan.execute(sipRequest);
/// ```
///
/// @see ProxyPlan
/// @see ProxyTier
/// @see ProxyInvite
/// @see ProxyCancel
package org.vorpal.blade.framework.v2.proxy;
