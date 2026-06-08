/// A scenario-driven SIP load generator and test UAC built on the BLADE
/// framework. The Test UAC is the counterpart to the Test UAS — together
/// they form a complete SIP testing suite: synthesized load on one side,
/// scriptable endpoints and transformations on the other. No SIPp required.
///
/// ## Key Components
///
/// - [UserAgentClientServlet] - thin leaf over the framework's
///   [TesterServlet][org.vorpal.blade.framework.v3.tester.TesterServlet];
///   wires the load engine, metrics, and the `TesterControl` JMX MBean, and
///   preserves the legacy top-level `template` behavior on the softphone
///   B2BUA path
/// - [UserAgentClientConfig] - concrete
///   [TesterConfiguration][org.vorpal.blade.framework.v3.tester.TesterConfiguration];
///   accepts the pre-scenario top-level fields for backward compatibility
/// - [LoadTestAPI] - JAX-RS endpoints: start, stop, status, report, reset
///
/// ## Load Generation Architecture
///
/// Each cluster node runs its own
/// [LoadEngine][org.vorpal.blade.framework.v3.tester.LoadEngine] instance,
/// stored on the `ServletContext` — no singleton, no centralized
/// coordinator. **CPS mode** paces calls with a `java.util.Timer` (batched
/// ticks above 1000 CPS); **concurrent mode** fires an initial batch and
/// replenishes via completion callbacks.
///
/// Each generated call runs a
/// [Scenario][org.vorpal.blade.framework.v3.tester.Scenario]: an optional
/// template seeds the INVITE's headers and body (e.g. a SIPREC
/// `application/rs-metadata+xml` part), CRUD rule sets transform requests
/// and responses, and
/// [OriginateCallflow][org.vorpal.blade.framework.v3.tester.OriginateCallflow]
/// validates the final response against `expectFinal`, evaluates
/// assertions, and feeds the per-scenario metrics (latency percentiles,
/// status distribution, pass/fail tallies).
///
/// ## Configuration Precedence
///
/// [LoadRequest][org.vorpal.blade.framework.v3.tester.LoadRequest] fields
/// override the configuration's `originate` defaults; null or empty request
/// fields fall back to config values.
///
/// ## Related Packages
///
/// ### org.vorpal.blade.test.client
/// Single-call REST test client:
/// [TestClientAPI][org.vorpal.blade.test.client.TestClientAPI] provides
/// `POST /api/v1/connect`, bridging one HTTP request to one SIP INVITE and
/// returning every response. Honors scenarios, templates, and inline
/// content.
///
/// ### org.vorpal.blade.framework.tpcc
/// Third-Party Call Control callflows and REST APIs enabling an external
/// application to set up, modify, and tear down SIP calls between two
/// endpoints.
///
/// @see org.vorpal.blade.test.client
/// @see org.vorpal.blade.framework.v3.tester.TesterServlet
/// @see org.vorpal.blade.framework.v3.tester.LoadEngine
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
package org.vorpal.blade.test.uac;
