/// A SIP load generation tool and test UAC built on the BLADE framework.
///
/// This module generates outbound SIP calls at scale, supporting two modes:
/// **CPS mode** (fire calls at a target calls-per-second rate) and
/// **concurrent mode** (maintain N active calls with callback-driven replenishment).
/// Designed for 1000+ CPS per node in an OCCAS cluster, with each node operating
/// independently via REST commands.
///
/// ## Key Components
///
/// - [UserAgentClientServlet] - main SIP servlet extending `B2buaServlet`, injects
///   configured headers into outbound requests and notifies the load generator
///   on call lifecycle events (`callCompleted`, `callDeclined`)
/// - [UserAgentClientConfig] - configuration with header map, address patterns
///   (`fromAddressPattern`, `toAddressPattern`, `requestUriTemplate`), call
///   `duration`, and `sdpContent`
/// - [LoadGenerator] - per-node load generation engine using `java.util.Timer`
///   for CPS pacing; concurrent mode is purely callback-driven with no timer
/// - [LoadCallflow] - per-call lifecycle handler extending `ClientCallflow`
///   (INVITE -> ACK -> auto-BYE timer -> completion notification)
/// - [LoadTestAPI] - JAX-RS REST endpoints for start, stop, and status
/// - [LoadTestRequest] - request model specifying mode, CPS/concurrent targets,
///   address patterns, duration, max calls, and header overrides
/// - [LoadTestStatus] - response model with state, counters, and elapsed time
///
/// ## Load Generation Architecture
///
/// Each cluster node creates its own [LoadGenerator] instance, stored on the
/// `ServletContext`. There is no singleton or centralized coordinator. The REST
/// API sends a start command which resolves effective parameters (merging
/// [LoadTestRequest] overrides with [UserAgentClientConfig] defaults), resets
/// counters, and begins generating calls.
///
/// In **CPS mode**, a `java.util.Timer` fires at the target rate. At high CPS
/// (above 1000), multiple calls are batched per timer tick. In **concurrent mode**,
/// the initial batch of calls is fired immediately, and [LoadGenerator#onCallCompleted]
/// triggers replacement calls as each one finishes.
///
/// Each generated call runs through [LoadCallflow], which sends the INVITE, ACKs
/// on 2xx, and schedules an auto-BYE via `Callflow.scheduleTimer()` tied to the
/// `SipApplicationSession`. When the BYE completes, [UserAgentClientServlet#callCompleted]
/// notifies the generator to update counters and replenish if in concurrent mode.
///
/// ## Configuration Precedence
///
/// [LoadTestRequest] fields override [UserAgentClientConfig] defaults. Null or
/// empty fields in the request fall back to config values. Headers are merged —
/// request headers override config headers with the same name.
///
/// ## Related Packages
///
/// ### org.vorpal.blade.test.client
/// A SIP test client with a REST API for initiating individual outbound SIP calls.
/// [TestClientAPI][org.vorpal.blade.test.client.TestClientAPI] provides
/// `POST /api/v1/connect` bridging HTTP REST requests to SIP INVITE sessions
/// with asynchronous response handling. Data models include
/// [MessageRequest][org.vorpal.blade.test.client.MessageRequest],
/// [MessageResponse][org.vorpal.blade.test.client.MessageResponse], and
/// [MessageSession][org.vorpal.blade.test.client.MessageSession].
///
/// ### org.vorpal.blade.framework.tpcc
/// Third-Party Call Control callflows and REST APIs enabling an external application
/// to set up, modify, and tear down SIP calls between two endpoints.
/// [ThirdPartyCallControl][org.vorpal.blade.framework.tpcc.ThirdPartyCallControl]
/// exposes `POST /api/v1/tpcc` with OpenAPI annotations and asynchronous HTTP
/// response handling.
///
/// @see org.vorpal.blade.test.client
/// @see org.vorpal.blade.framework.tpcc
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.callflow.ClientCallflow
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.test.uac;
