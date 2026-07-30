# BLADE Event Bus — engine tier (`services/events`)

The runtime half of the BLADE event bus: a **CloudEvents 1.0** ingress, the
**event catalog**, and the JMS publishers that put events on their destinations.
The designer and the JMS administration console are the other half, on the admin
tier, in `admin/events-console`.

```
  Gumball attendant (Python)              BLADE (OCCAS engine tier)
  ┌────────────────────┐   HTTP POST     ┌──────────────────────────────────────┐
  │ ASR→extract→gate→  │  CloudEvents    │  EventIngestResource  (JAX-RS)       │
  │ confirm→emit_task  ├────────────────►│    POST /events/api/v1/events        │
  └────────────────────┘  application/   │            │                         │
        (unchanged)       cloudevents+   │            ▼  EventValidator         │
                          json           │      schema from the catalog         │
                                         │            │                         │
                                         │            ▼  EventPublisher         │
                                         │     the destination the catalog says │
                                         │        │            │                │
                                         │        ▼            ▼                │
                                         │   your consumer   another consumer   │
                                         └──────────────────────────────────────┘
```

## The catalog is the point

An event used to be four hand-written things that nothing checked against each
other:

| Thing | Where it lived |
|---|---|
| the destination | a WLST script, run by hand, once per domain |
| the publisher | a JNDI constant |
| the consumer | an MDB with eight activation properties and a **hand-typed** message selector |
| the payload shape | nowhere — `CloudEvent.data` was a raw `JsonNode` and the consumer reached in by string key |

A typo in that selector was a silent no-op that looked exactly like "no events
yet". Now one declaration in `EventCatalog` is the source of truth, and the
selector, the payload class, the schema, the consumer skeleton and the
destination are all derived from it.

## What runs here

| Class | Role |
|---|---|
| `EventBusStartup` | `@WebListener` — the lifecycle |
| `EventCatalogSettingsManager` | registers the catalog; installs a publisher per destination, re-diffing on every reload |
| `EventIngestResource` | `POST /events/api/v1/events` — HTTP→JMS |
| `EventValidator` | checks a payload against its type's schema |
| `EventCatalogSample` | the catalog a fresh install starts from |

The model (`EventCatalog`, `EventType`, `EventField`) and the code generator
(`EventSourceGenerator`) are framework-side, in `libs/framework/.../v3/events/` —
both tiers need them, and the framework jar is the only one a skinny WAR carries.

## Validation

`EventCatalog.validation` has three settings, and the middle one is why it is not
a boolean:

- **`OFF`** — publish without checking.
- **`WARN`** — check, log the failing field, publish anyway. *Run here while
  proving a new schema against live traffic.*
- **`REJECT`** — check, return 400 naming the failing field, do not publish.

Switching validation on against production traffic is the change nobody dares
make, because a mistake in the catalog starts rejecting real events. `WARN` makes
it safe: turn it on, watch for a day, see whether the schema or the producer is
wrong, then move to `REJECT`.

`rejectUnknownTypes` is separate: it decides whether an event type absent from
the catalog is an error or just untyped traffic. Leave it off while producers are
still being onboarded.

## Config

Published as `config/custom/vorpal/events.json`, edited from the Events console
or the Configurator, reloaded by the engine tier without a redeploy — the
ordinary `SettingsManager` path. Adding or removing a destination takes effect on
the next reload; publishers for destinations that did not change are left alone.

## Deploy

1. **Provision the JMS resources.** The Events console can create them, or use
   the WLST fallback:
   ```bash
   export WL_USER=weblogic WL_PASS=... WL_ADMIN=t3://<admin>:7001
   # engine-tier cluster name; defaults to BEA_ENGINE_TIER_CLUST.
   export BLADE_ENGINE_CLUSTER=<your-engine-cluster>
   $MW_HOME/oracle_common/common/bin/wlst.sh notes/configure-messaging-jms.py
   ```
   One script now provisions **all** BLADE messaging — the analytics queue and
   the event-bus topic — in one stack: one file store, one JMS server, one
   system module, one subdeployment, both connection factories, and both
   destinations with a quota each. It **adopts** an existing `BladeAnalytics*`
   stack rather than renaming it (WebLogic cannot rename a JMS resource, and
   destroy-and-recreate would orphan the file store), so it is safe on a domain
   the old analytics script already provisioned. Every JNDI name is unchanged.
   Idempotent — safe to re-run.

2. **Build and deploy.** `events` rides the standard build and lands in
   `dist/<ver>-<build>/services/events.war`, deployed on its own like every
   other service.

3. **Point the producer at it** — no code change, just the sink URL and
   credentials:
   ```bash
   ATTENDANT_SINK=http://<engine>:<port>/events/api/v1/events
   ```

## The ingress requires authentication

`POST /api/v1/*` is behind BASIC auth with role `authenticated-users`, mapped to
`users` in `weblogic.xml` — the same pattern `services/analytics` uses. This was
deliberately open while the app lived in `proto/` and the only producer was a
trusted in-network sidecar. It publishes onto a cluster-wide bus that downstream
apps act on, so that could not survive promotion.

```bash
curl -i -u weblogic:<password> -X POST \
  http://<engine>:<port>/events/api/v1/events \
  -H 'Content-Type: application/cloudevents+json' \
  -d '{"specversion":"1.0","type":"net.vorpal.attendant.meeting.scheduled",
       "source":"/gumball/attendant","subject":"demo-1",
       "data":{"who":"Sarah","when_text":"next Tuesday at 3"}}'
# => 202 Accepted, {"id":"..."}
```

## The live tap

`GET /api/v1/tap?type=…&subject=…&seconds=20&max=100` watches events as they are
published, without disturbing anything else consuming them.

It opens a **non-durable** subscriber: on a distributed topic every subscriber
gets its own copy, so a non-durable one sees what is published while connected,
retains nothing, creates no subscription state, and takes nobody else's copy.
Deliberately not `createDurableSubscriber`, which would leave persistent state
accruing messages long after the browser tab closed.

It runs here rather than in the console because a tap needs a real JMS consumer,
and the connection factory is targeted at the engine cluster.

Queues are not tappable — a consuming tap would steal messages from the real
consumer. Browse them from the console instead; that reads what is at rest and
takes nothing.

**It is a wiretap, and it is gated as one.** `/api/v1/tap` requires `Admin` or
`Operator` while the ingress takes any authenticated caller; fields an event type
marks in `sensitiveFields` are masked before anything is returned; every tap is
logged with its principal and selector; and the window is capped server-side at
60 seconds and 500 messages regardless of what the caller asks for.

## Still open

- **Consumer idempotency.** A durable subscriber can see a redelivery. The
  generated consumer says so in a comment, but deduping on the CloudEvent `id`
  (exposed as the `eventId` JMS property) is the consumer's job.
