# Analytics Service

Javadocs: `/blade/javadoc/analytics/` on the Admin Portal

Captures per-call records and metrics from SIP traffic as it flows through the cluster —
call counts, status distributions, and timing — and writes them to a relational database
for reporting and troubleshooting.

## How it works

The service has two halves:

- **The JMS sink.** `AnalyticsEventListener` is a message-driven bean holding the durable
  `analytics-db` subscription on the shared BLADE CloudEvents bus (see
  [services/events](../events/README.md)). It subscribes with no message selector and
  decides per message whether to persist, driven by the event catalog's `persist` flags —
  failing open, so an unknown event type is stored rather than dropped. Rows are written
  through JPA. No key is assigned by the database: every id is a hash of the row's
  natural key, computed before the insert, so two cluster members writing the same call
  agree without consulting each other and a redelivered event collides with its own row.
- **The SIP layer.** `AnalyticsSipServlet` is a passive B2BUA built on the framework's
  `v3.B2buaServlet`. It logs the call lifecycle and owns the service's configuration; it
  does not route.

When the database is down, the listener suspends JMS delivery through JMX rather than
dropping messages, and a health-check timer tests connectivity before resuming — the bus
holds the backlog.

## Configuration

| Setting | Description |
| --- | --- |
| `healthCheckInterval` | Seconds between database health checks while suspended (default `30`) |
| `healthCheckSql` | Probe statement (default `SELECT 1 FROM DUAL`) |
| `domainId` | Stamped on every row as `cluster_name`, so several domains can share one database (defaults to the WebLogic domain name) |

Edit and publish through the [Configurator](../../admin/configurator/README.md).

## Database

The persistence unit is `BladeAnalytics`, bound to the `jdbc/BladeAnalytics` data source.
Schema DDL generation is off by design: `sql/MySQL-database-schema.sql` is the single
source of truth (the Oracle variant is regenerated from it). WLST helpers for provisioning
MySQL, Oracle ADB, and the schema live in `notes/`. (`notes/design.md` is historical
JPA/JMS working notes from the pre-CloudEvents design — read `package-info.java` for the
current architecture.)

## Related modules

- [services/events](../events/README.md) — the producer side of the event bus, and the JMS provisioning script
- [admin/analytics-console](../../admin/analytics-console/README.md) — audits and provisions the JMS/JDBC resources this pipeline depends on
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-analytics</artifactId>
```
