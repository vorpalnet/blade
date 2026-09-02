# Tuning

Javadocs: `/blade/javadoc/tuning/` on the Admin Portal

The OCCAS performance dashboard at `/blade/tuning`: edit JVM heap and GC, SIP protocol
timers, WebLogic work-manager constraints, server thread pools, and cluster topology over
JMX — live reads and writes against the running domain, with one-click "Recommended"
presets that encode high-CPS heuristics.

## What it covers

Each section of the UI maps to a REST resource under `/blade/tuning/api/v1/`: JVM
arguments and GC profiles, SIP timers, work managers, per-server tuning, cluster and
Coherence settings, JDBC pools, SNMP, kernel tunables (read through the framework's
`KernelProbe`), and domain mode. It also drives the node lifecycle operations — drain,
resume, and restart of individual servers — for rolling maintenance.

Most knobs live where WebLogic keeps them: in the domain's own MBeans. The app's config
file (`./config/custom/vorpal/blade-tuning.json`) persists only what WebLogic has no home
for: named JVM profiles and which target each one is assigned to.

## JVM profiles, targets and the baseline

A target is a ServerStart owner in config.xml: a static server (the AdminServer, engine0)
or the engine server template. Dynamic engines are not targets; they boot from the template.
Applying a profile overlays it knob by knob onto the target's `ServerStart.Arguments`, and
the page shows that diff as a preview before anything is written.

Two files beside the config answer "what was it before?": `blade-tuning-baseline.json`,
pinned the first time the app sees the domain (what install.sh wrote) and rewritten only by
an explicit re-baseline, and `blade-tuning-history.json`, rewritten with the live state
before every write, with the last twenty kept in `.versions/`. Either can be written back
to a target verbatim from the page, ClassPath included. The ClassPath is editable per
target for the same reason: it is the field whose loss produces the least obvious failure,
a server that boots with no SIP container.

| Endpoint (under `api/v1/jvm`) | Does |
|---|---|
| `GET /targets` | every target with its live ServerStart and baseline status |
| `GET` / `POST /baseline` | read / re-pin the baseline |
| `GET /history`, `GET /history/{id}` | the retained pre-write snapshots |
| `POST /preview` | the per-target diff an apply would make, without an edit session |
| `POST /apply` | overlay each assigned profile; returns the same diff |
| `POST /restore` | `{source: "baseline" \| id, targets: [...]}`, written back verbatim |
| `PUT /targets/{name}` | `{classPath}` |

## A JMX subtlety

WebLogic binds the edit-tree JNDI entry (`java:comp/env/jmx/edit`) conditionally at
deployment time, and this WAR can deploy while the AdminServer is still booting — the
symptom is "reads work, writes fail." The app documents this and falls back to locating
the MBeanServer that owns the `ConfigurationManagerMBean` directly.

## Related modules

- [admin/logs](../logs/README.md) and [admin/metrics](../metrics/README.md) — observe what the tuning changes do
- [admin/portal](../portal/README.md) — hosts the launcher card
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-tuning</artifactId>
```
