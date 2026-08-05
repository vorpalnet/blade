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
file (`./config/custom/vorpal/tuning.json`) persists only what WebLogic has no home for —
named JVM profiles and their per-node assignments.

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
