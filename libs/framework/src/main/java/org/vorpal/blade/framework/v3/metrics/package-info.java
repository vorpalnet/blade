/// Per-app counters, gauges and latency histograms, exposed over JMX so one
/// console can show what every BLADE app on every node is doing.
///
/// **The gap this fills.** Until now there was no structured way to get a number
/// out of a BLADE app. `Snmp` wraps OCCAS's `sendSipAppTrap`, which offers seven
/// severity-keyed OIDs carrying free text — an event channel, not telemetry. So
/// getting numbers to an NMS meant writing them into a log line and having
/// something regex them back out. `ConfigurationMonitor.queryApps()` walked the
/// JMX tree correctly and then stopped, because the only thing behind the walk
/// was `SettingsMXBean`, which carries configuration and no numbers. This
/// package is that missing surface.
///
/// **Free for every app.** `SettingsManager` creates a
/// [org.vorpal.blade.framework.v3.metrics.MetricsRegistry] and publishes a
/// [org.vorpal.blade.framework.v3.metrics.MetricsControl] MBean when an app
/// starts, keyed identically to that app's Configuration MBean. Apps get SIP
/// counters from `AsyncSipServlet` without writing any code, and declare their
/// own with a few lines.
///
/// **Two design rules, both load-bearing:**
///
/// 1. **Cardinality is declared, not trusted.**
///    [org.vorpal.blade.framework.v3.metrics.Counter] requires a finite label
///    value set at registration and buckets anything else. Unbounded keys — a
///    Call-ID, a phone number — are what turn in-process metrics into an
///    outage, and memory is the real risk, not CPU.
/// 2. **Histograms use fixed buckets so they merge.** Percentiles do not add up;
///    bucket arrays do. Every node publishes the same boundaries and the admin
///    tier sums them for a true cluster-wide percentile, instead of showing the
///    worst node the way the Test Console currently has to.
///
/// **On cost.** Counting is a
/// [java.util.concurrent.atomic.LongAdder] increment on a handle resolved once
/// at startup — no map lookup, no string building, no allocation on the call
/// path. What is expensive is looking a counter *up* per message, which is why
/// [org.vorpal.blade.framework.v3.metrics.Counter.Series] exists and why the
/// convenience methods are documented as off-hot-path.
///
/// **Scope.** In-memory and node-local. This is not a time-series database: no
/// query language, no dashboards, no retention. Aggregation, history and
/// threshold alerting belong to the admin tier, which reads these MBeans.
package org.vorpal.blade.framework.v3.metrics;
