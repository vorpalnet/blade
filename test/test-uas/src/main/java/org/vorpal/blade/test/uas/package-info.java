/// A SIP test server built on the BLADE framework. The Test UAS is the
/// counterpart to the Test UAC — together they form a complete SIP testing
/// suite: synthesized load on one side, scriptable endpoints and
/// transformations on the other. No SIPp required.
///
/// ## Scenario-driven
///
/// [UasServlet] extends the framework's
/// [TesterServlet][org.vorpal.blade.framework.v3.tester.TesterServlet], which
/// resolves a [Scenario][org.vorpal.blade.framework.v3.tester.Scenario] for
/// every initial INVITE:
///
/// - `;scenario=<name>` Request-URI parameter, or a translation-plan match
/// - the classic shorthands — `;status=486`, `;delay=5s`, `;refer=sip:…` —
///   which synthesize an ephemeral answer scenario (fully backward
///   compatible with existing test scripts)
/// - otherwise the built-in default: **strip-and-forward** — the call is
///   forwarded B2BUA-style with any multipart (e.g. SIPREC) body stripped
///   down to its `application/sdp` part so a plain softphone can parse it
///
/// Example endpoint call: `sip:target@uas.test;status=200;delay=5s` answers
/// with a muted (blackhole) SDP and sends `BYE` after 5 seconds.
///
/// Configured scenarios (in `test-uas.json`, edited in the Configurator) can
/// script multi-step response sequences with per-step delays and reason
/// phrases, REFER transfers, auto-teardown, and CRUD rule-set message
/// transformations — see
/// [TestUasConfigSample][org.vorpal.blade.test.uas.config.TestUasConfigSample]
/// for worked examples.
///
/// ### [org.vorpal.blade.test.uas.config]
/// [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig] — a
/// concrete [TesterConfiguration][org.vorpal.blade.framework.v3.tester.TesterConfiguration].
///
/// @see org.vorpal.blade.test.uas.config
/// @see org.vorpal.blade.framework.v3.tester.TesterServlet
/// @see org.vorpal.blade.framework.v3.tester.ScriptedAnswer
package org.vorpal.blade.test.uas;
