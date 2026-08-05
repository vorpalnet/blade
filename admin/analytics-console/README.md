# Analytics Console

Javadocs: `/blade/javadoc/analytics-console/` on the Admin Portal

Audits the WebLogic resources the analytics pipeline depends on — JMS Server, Connection
Factory, Distributed Queue, JDBC Data Source — over JMX, provisions what's missing, and
generates sample call data so dashboards can be exercised without live SIP traffic.
Served at `/blade/analytics`.

## Naming, deliberately

Three names that look inconsistent are each load-bearing:

- The **directory** is `analytics-console`, not `analytics`, because `build.sh` discovers
  modules by bare directory name — two `analytics` directories would share one skip flag.
- The **WAR and context-root** are `blade-analytics` / `blade/analytics`, so the portal
  card and URL read naturally.
- The **config file** is `./config/custom/vorpal/analytics-console.json` — deliberately
  distinct from the analytics *service's* own config.

## What it does

- **Audit** — walks the domain's JMS and JDBC configuration and reports what the pipeline
  needs versus what exists.
- **Provision** — creates the missing JMS resources with one click.
- **Sample data** — generates synthetic call records into the pipeline for dashboard
  testing.

## Related modules

- [services/analytics](../../services/analytics/README.md) — the pipeline this console fronts
- [services/events](../../services/events/README.md) — the event bus carrying the records
- [admin/events-console](../events-console/README.md) — general JMS administration beyond the analytics slice
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-analytics-console</artifactId>
```
