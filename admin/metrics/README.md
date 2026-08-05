# Metrics

What every BLADE application on every node is actually doing — calls, outcomes, handler
latency, and whatever counters an app declares — aggregated across the cluster at
`/blade/metrics`.

## How aggregation works

The app queries every BLADE `Metrics` MBean in the domain over the federated JMX
connection and merges per-node values by one rule set:

- **Counters and gauges sum** — each node reports its share.
- **Histograms sum their bucket arrays, never their percentiles.** Every node publishes
  identical bucket boundaries, so the p95 shown is the true cluster p95, not the worst
  node's figure.

Apps declare their metrics through the framework's `v3.metrics` package
(`MetricsRegistry`: counters, gauges, histograms), which publishes them over JMX with no
HTTP surface on the engines.

## Configuration

`./config/custom/vorpal/metrics.json`:

| Setting | Description |
| --- | --- |
| `refreshSeconds` | Dashboard refresh interval (default `10`, floor `2`) — each refresh is one JMX read per app per node |

## Related modules

- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the `v3.metrics` package apps instrument with
- [admin/tuning](../tuning/README.md) — turn observations into settings
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-metrics</artifactId>
```
