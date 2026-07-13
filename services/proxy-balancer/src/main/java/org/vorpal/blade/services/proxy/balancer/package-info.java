/// This package provides a SIP load balancer that distributes calls across
/// tiered pools of named endpoints, with per-tier selection strategies and
/// per-node endpoint health.
///
/// ## Key Components
///
/// - [ProxyBalancerServlet] - Main servlet; b2bua dispatch, ping startup, health MBean
/// - [InviteCallflow] - Plan selection, strategy ordering, health filter, fan-out, failover, relay
/// - [org.vorpal.blade.services.proxy.balancer.config.BalancerConfig] - The v3 config: endpoint registry, plans, health knobs
/// - [EndpointHealth] - One endpoint's health as THIS node sees it (up / down / backoff)
/// - [OptionsPingCallflow] - Self-rescheduling OPTIONS ping cycle, started per node at deploy
/// - [EndpointHealthMBean] - Publishes this node's health view for the Balancer admin app
/// - [ProxyBalancerSettingsManager] - Seeds health from the registry, validates tier references
///
/// ## Configuration model
///
/// Endpoints are defined ONCE in the top-level registry
/// ([org.vorpal.blade.services.proxy.balancer.config.Endpoint]: `uri`,
/// `weight`, `enabled` to drain, `ping` to opt out of pings), keyed by NAME —
/// the stable identity health tracking and the dashboard use. Plans are
/// arrays of [org.vorpal.blade.services.proxy.balancer.config.Tier]s keyed
/// by request-URI host: exact match, longest `*.suffix` wildcard, then `"*"`.
///
/// ## Architecture
///
/// The service is a forking B2BUA on the v3 framework
/// ([org.vorpal.blade.framework.v3.B2buaServlet]). Each tier resolves its
/// endpoint names, drops drained/unhealthy ones, and offers the call by
/// strategy: `parallel` races all with `sendRequestsInParallel` (first 2xx
/// wins, losers CANCELed); `serial` hunts in priority order; `random`
/// shuffles; `roundrobin` rotates a per-node offset; `weighted` hunts in
/// smooth weighted round-robin order (deterministic, per-node counter).
/// Tier order is priority order — cheapest-first gives least-cost routing.
/// Failover is response-classified: route-level failures (408, 480, 404,
/// 5xx, 3xx) escalate to the next tier; user-state and auth responses (486,
/// 401/407, 487, all 6xx per RFC 3261 §16.7) are relayed and never escalate,
/// and a caller who CANCELs mid-hunt ends the plan.
///
/// Health has two writers and one reader: the OPTIONS ping cycle (any final
/// response except 408/503 proves the endpoint alive), live call legs (2xx →
/// up; 503 → down with Retry-After or `health.defaultBackoff`), and the
/// routing filter. Every engine node keeps its own independent view — no
/// shared cluster state — and publishes it as
/// `vorpal.blade:Name=proxy-balancer,Type=EndpointHealth[,Cluster=...]`,
/// which the `blade/balancer` admin app aggregates over federated JMX.
///
/// Real 18x ringing from the legs is relayed upstream via the fan-out
/// primitives' per-leg observer, on top of the immediate 180 sent when the
/// fork starts. With `session:passthru` set, the winning leg's Contacts are
/// stitched together and the balancer drops out of the dialog after setup;
/// without it, it stays in the path as a full B2BUA.
///
/// @see ProxyBalancerServlet
/// @see InviteCallflow
/// @see org.vorpal.blade.services.proxy.balancer.config.BalancerConfig
/// @see EndpointHealth
package org.vorpal.blade.services.proxy.balancer;
