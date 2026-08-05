# Trace (Callflow Viewer)

Javadocs: `/blade/javadoc/callflow/` on the Admin Portal

Record a live SIP call across the whole BLADE app chain: arm a rule, place the call, read
the recording — every message each app sent and received, drawn as a ladder diagram and
pinned to the exact source line that emitted it. The answer to "which app in the chain
misbehaved," down to the line — and shareable as a self-contained HTML snapshot.

The display identity is **Trace**; the deployment identity keeps the historical `callflow`
name (WAR `blade-callflow.war`, context-root `blade/callflow`), so the rename required no
redeploy or config migration.

## How it works

The engine side is the framework's tracing spine (see the
[v3 API README](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md)):
every app records `CallStep`s into a bounded per-app ring buffer, correlated across apps
by the stable `X-Vorpal-Session` id. This app is the reader:

- **Arming fans out** — arm/disarm/clear reach every app's Trace MBean in the domain, so
  the rule catches the call wherever it lands.
- **Trace reads sweep** every federated MBean instance — a call's steps live on whichever
  nodes handled it — and merge into one timeline.
- **Source reads pin to one node** (all copies are identical). The source shown is read
  live from each app's own inventory MBean, so it is byte-identical to the deployed code —
  and an app only answers for classes its own scan inventoried, so no browser-supplied
  path ever reaches a filesystem lookup.

## Configuration

`./config/custom/vorpal/callflow.json`:

| Setting | Description |
| --- | --- |
| `sourceServer` | Which node serves source reads (e.g. an engine carrying no call traffic). Unset = first server. Re-read live on every request. |

## Related modules

- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the tracing spine (`v3.diagnostics`, `v3.source`)
- [admin/logs](../logs/README.md) — the coarser view, when a log line is enough
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-callflow</artifactId>
```
