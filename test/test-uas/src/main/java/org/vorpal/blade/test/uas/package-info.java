/// A SIP test server built on the BLADE framework that answers incoming calls
/// with configurable response behavior. The Test UAS is the counterpart to the
/// Test UAC — together they form a complete SIP
/// load testing tool for production call center performance tuning.
///
/// ## Key Components
///
/// - [UasServlet] - main SIP servlet extending `B2buaServlet` and implementing
///   `B2buaListener`, dispatching incoming requests to test callflows based on
///   SIP method
///
/// ## Response Behavior
///
/// Response parameters are resolved in order of precedence:
///
/// 1. **Error map** — phone number match overrides status code
/// 2. **Request URI parameters** — `?status=`, `?delay=`, `?duration=` per-call overrides
/// 3. **REST API configuration** — runtime defaults set via `PUT /api/v1/config/*`
/// 4. **Configuration file** — startup defaults from `config/custom/vorpal/test-uas.json`
///
/// This layered approach lets the calling UAC control UAS behavior per-call by
/// encoding parameters in the request URI (e.g.
/// `sip:target@uas.test;status=503;delay=2s;duration=60s`), while the REST API
/// provides a control plane for test orchestration.
///
/// ## Request Dispatching
///
/// [UasServlet] overrides `chooseCallflow()` to route requests:
///
/// - **INVITE** (initial) — [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite]
///   with configurable status, delay, duration, error map lookup, and SDP content
/// - **INVITE** (mid-dialog) — [TestReinvite][org.vorpal.blade.test.uas.callflows.TestReinvite]
///   with blackhole SDP for media hold simulation
/// - **CANCEL, INFO, BYE** — [TestOkayResponse][org.vorpal.blade.test.uas.callflows.TestOkayResponse]
///   for simple 200 OK handling
/// - **All other methods** — [TestNotImplemented][org.vorpal.blade.test.uas.callflows.TestNotImplemented]
///   for 501 responses
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.test.uas.callflows]
/// SIP callflow implementations providing configurable response behaviors.
/// [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite] is the primary
/// callflow for load testing — it reads config defaults, checks URI parameter
/// overrides and the error map, applies optional delay via `scheduleTimer()`,
/// sends the response with SDP (for 2xx), and schedules auto-BYE for controlled
/// call teardown. Also includes [TestRefer][org.vorpal.blade.test.uas.callflows.TestRefer]
/// for REFER-based transfer testing with NOTIFY handshaking.
///
/// ### [org.vorpal.blade.test.uas.config]
/// Configuration classes defining response defaults, error mappings, and state
/// management. [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig]
/// provides `defaultStatus`, `defaultDelay`, `defaultDuration`, `sdpContent`,
/// and `errorMap` with computed duration getters for human-readable parsing.
///
/// ### [org.vorpal.blade.test.uas.api]
/// JAX-RS REST API for runtime configuration management.
/// [TestUasAPI][org.vorpal.blade.test.uas.api.TestUasAPI] provides GET/PUT
/// endpoints for modifying response behavior without redeployment.
///
/// @see org.vorpal.blade.test.uas.callflows
/// @see org.vorpal.blade.test.uas.config
/// @see org.vorpal.blade.test.uas.api
/// @see org.vorpal.blade.test.uas.config.TestUasConfig
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaListener
package org.vorpal.blade.test.uas;
