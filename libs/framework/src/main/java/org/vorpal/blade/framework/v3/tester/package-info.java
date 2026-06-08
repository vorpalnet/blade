/// # BLADE Tester — enterprise SIP test harness
///
/// One toolkit, two call sources. A *scenario* describes what a test app does
/// with a call; where the call comes from is orthogonal:
///
/// - **originate** — the [LoadEngine] synthesizes calls (SIPp-style load:
///   CPS or concurrent-call pacing) and drives them through
///   [OriginateCallflow].
/// - **answer** — a real or synthetic INVITE is answered locally by
///   [ScriptedAnswer], playing a configured [ResponseScript] (provisional /
///   final sequences with delays, transfers, auto-BYE).
/// - **b2bua** — a real call (softphone, SBC) passes through and is
///   transformed en route.
///
/// All three roles share the same message-transformation engine: the
/// [org.vorpal.blade.framework.v3.crud] `RuleSet → Rule → Operation`
/// pipeline (header create/update/delete, MIME part attach/strip, SDP/XML/
/// JSON path edits, `${variable}` capture and substitution). A scenario
/// names a rule set; the rule set's `event` / `messageType` / `statusRange`
/// filters decide what fires where.
///
/// [TesterConfiguration] extends `CrudConfiguration`, so scenario selection
/// reuses the standard selector / translation-map / plan machinery, and the
/// whole configuration — scenarios included — is edited schema-validated in
/// the BLADE Configurator. Assertions evaluate
/// [org.vorpal.blade.framework.v3.configuration.expressions.Expression]
/// predicates over captured session variables; [TesterMetrics] aggregates
/// per-scenario counters, response-code distributions, and latency buckets,
/// exposed via REST (service tier) and the [TesterMXBean] (admin tier,
/// federated JMX).
package org.vorpal.blade.framework.v3.tester;
