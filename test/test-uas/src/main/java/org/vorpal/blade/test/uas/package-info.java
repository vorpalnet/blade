/// A test B2BUA application that acts as a User Agent Server, accepting inbound
/// SIP requests and dispatching them to configurable callflows. This module
/// simulates various UAS behaviors for testing SIP services, including normal
/// call handling, re-INVITE processing, REFER-based transfers, and error responses.
///
/// ## Key Components
///
/// - [UasServlet] - the main SIP servlet extending {@code B2buaServlet} and implementing {@code B2buaListener}, dispatching requests to test callflows
///
/// ## Request Dispatching
///
/// ### UasServlet
/// [UasServlet] is annotated with {@code @WebListener}, {@code @SipApplication(distributable = true)},
/// {@code @SipServlet(loadOnStartup = 1)}, and {@code @SipListener}. It overrides
/// {@code chooseCallflow()} to route requests based on SIP method:
///
/// - **INVITE** (initial) - routed to [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite] for new call setup
/// - **INVITE** (mid-dialog) - routed to [TestReinvite][org.vorpal.blade.test.uas.callflows.TestReinvite] for session modification
/// - **CANCEL, INFO, BYE** - routed to [TestOkayResponse][org.vorpal.blade.test.uas.callflows.TestOkayResponse] for simple 200 OK handling
/// - **All other methods** - routed to [TestNotImplemented][org.vorpal.blade.test.uas.callflows.TestNotImplemented] for 501 responses
///
/// ### Configuration
/// On startup, the servlet initializes a
/// [SettingsManager][org.vorpal.blade.framework.v2.config.SettingsManager] with
/// [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig] and logs the
/// current configuration.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.test.uas.callflows]
/// SIP callflow implementations providing a variety of response behaviors for testing
/// purposes. Includes [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite] for
/// initial INVITE handling with configurable delay and status codes,
/// [TestReinvite][org.vorpal.blade.test.uas.callflows.TestReinvite] for mid-dialog
/// re-INVITE with blackhole SDP, and
/// [TestRefer][org.vorpal.blade.test.uas.callflows.TestRefer] for REFER-based call
/// transfer scenarios with NOTIFY handshaking. Simple response handlers
/// [TestOkayResponse][org.vorpal.blade.test.uas.callflows.TestOkayResponse] and
/// [TestNotImplemented][org.vorpal.blade.test.uas.callflows.TestNotImplemented] cover
/// BYE/CANCEL/INFO and unsupported methods respectively.
///
/// ### [org.vorpal.blade.test.uas.config]
/// Configuration classes defining error response mappings, SIP header definitions, and
/// state management with human-readable delay parsing.
/// [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig] maps phone numbers to
/// SIP error response codes, while
/// [TestUasConfigSample][org.vorpal.blade.test.uas.config.TestUasConfigSample] provides
/// default sample mappings. [TestUasState][org.vorpal.blade.test.uas.config.TestUasState]
/// supports configurable delays with time-unit suffixes and per-state SIP header lists.
///
/// @see org.vorpal.blade.test.uas.callflows
/// @see org.vorpal.blade.test.uas.config.TestUasConfig
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaListener
package org.vorpal.blade.test.uas;
