# Events Console

One place for BLADE messaging, served at `/blade/events`. Three tools in one console:

- **Event catalog** — declare the domain's event types; the catalog lives at
  `config/custom/vorpal/events.json` and is written through the framework's
  `VersionedFileStore`, exactly the mechanism the [Flow editor](../flow/README.md) uses
  for `fsmar.json`, so there is no second publish path to maintain.
- **Code designer** — generate producer and consumer source from an event declaration.
- **JMS administration** — the WebLogic resources that carry the events: destinations,
  quotas, durable subscriptions, depths, and consumers.

## Per-operation authorization

A servlet security-constraint can't tell a read from a delete, so `web.xml` gates the app
coarsely and the real decision is made per operation:

| Operation | Roles |
| --- | --- |
| Provision, create, tune | Deployer, Admin |
| Pause, resume | Operator, Deployer, Admin |
| Delete, purge, browse message bodies | Admin only |

Browsing message bodies is Admin-only for the same reason a tap is: bodies carry
call-correlated data, and reading them is a wiretap on production traffic. Every such read
is logged with the calling principal. Destructive operations are refused on protected
destinations regardless of role.

## Configuration

`./config/custom/vorpal/events-console.json`:

| Setting | Description |
| --- | --- |
| `allowDestructiveOperations` | Default `true`. Off leaves the console read-write for creation and tuning but removes every irreversible action |
| `protectedDestinations` | JNDI names no destructive operation may touch (the sample protects the event bus topic) |

(The directory is `events-console` rather than `events` for the same `build.sh`
name-collision reason as [analytics-console](../analytics-console/README.md); the WAR,
context-root, and portal card are all just "events.")

## Related modules

- [services/events](../../services/events/README.md) — the event bus these resources carry
- [services/analytics](../../services/analytics/README.md) — the bus's biggest consumer
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-events-console</artifactId>
```
