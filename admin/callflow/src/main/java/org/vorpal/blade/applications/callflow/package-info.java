/// BLADE Trace — a read-only admin tool that records live SIP call traces across
/// the whole BLADE app chain and pins each message to the source line that sent it.
///
/// Arm a rule (match a header pattern), place the call, and read the recording:
/// every message each app sent and received, merged by `X-Vorpal-Session` into
/// one timeline, drawn as a sequence (ladder) diagram, and shown against the real
/// source — the lambda-based callflow ([org.vorpal.blade.framework.v2.callflow.Callflow])
/// with response handlers as nested [org.vorpal.blade.framework.v2.callflow.Callback]
/// lambdas, read top-to-bottom in one `process(...)` method instead of the scattered
/// `doInvite`/`doResponse`/`doAck` handlers of the traditional JSR-289 model.
///
/// Source is read live over federated JMX from the per-app `v3.source` MBeans, so
/// what you see is byte-identical to the code running in this domain; a captured
/// trace saves as a self-contained HTML snapshot for people without OCCAS access.
/// [CallflowsAPI] serves the source; `TracesAPI` serves the recordings.
///
/// Note: only the DISPLAY identity is "Trace" — the deployment identifiers keep the
/// historical `callflow` name (context-root `blade/callflow`, WAR `blade-callflow`,
/// this package), so no redeploy or config-file migration is needed for the rename.
package org.vorpal.blade.applications.callflow;
