/// The BLADE framework: a SIP Servlet (JSR-359) application framework for Oracle
/// OCCAS whose lambda-based callflows express an entire SIP conversation as
/// readable, top-to-bottom code.
///
/// Callflow state is serialized automatically so a conversation survives node
/// failover in a distributed cluster — replacing the traditional scattered
/// event-handler model that reads like a choose-your-own-adventure book. The
/// framework also supplies the JSON-Schema-driven configuration system
/// (`SettingsManager` and the `Configuration` hierarchy), B2BUA primitives,
/// proxy and transfer helpers, and structured per-application logging.
///
/// Two API generations live side by side: the frozen `v2` line and the
/// actively developed `v3` line.
package org.vorpal.blade.framework;
