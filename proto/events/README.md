# BLADE Event Bus (`proto/events`)

A JMS **pub/sub** bus that carries **CloudEvents 1.0** from any producer to any
number of consuming BLADE apps. This is the "events for other apps to consume"
half of BLADE 3.0 — the sibling of the analytics queue, built for fan-out.

```
  Gumball attendant (Python)              BLADE (WebLogic / OCCAS engine tier)
  ┌────────────────────┐   HTTP POST     ┌──────────────────────────────────────┐
  │ ASR→extract→gate→  │  CloudEvents    │  EventIngestResource  (JAX-RS)        │
  │ confirm→emit_task  ├────────────────►│    POST /events/api/v1/events         │
  └────────────────────┘  application/   │            │                          │
        (unchanged)       cloudevents+   │            ▼  EventPublisher          │
                          json           │     jms/BladeEventBusTopic  (UDT)     │
                                         │        │            │                 │
                                         │        ▼            ▼                 │
                                         │  CalendarEventListener   (your app)   │
                                         │   (durable MDB)          (another MDB) │
                                         └──────────────────────────────────────┘
```

## Why it's a Topic carrying JSON (and analytics isn't)

The analytics destination is a `UniformDistributedQueue` of Java-serialized
`ObjectMessage`s, because it has exactly **one** consumer (the DB writer) that is
itself a BLADE app. This bus is different on both axes, deliberately:

- **Topic, not Queue.** "Other apps consume" is an open, growing set of
  subscribers — pub/sub. A `UniformDistributedTopic` gives every subscribing app
  its own copy. (A Queue would make two apps fight over each message.)
- **CloudEvents JSON `TextMessage`, not `ObjectMessage`.** A JSON envelope is
  language-neutral, so a consumer need not share BLADE's classpath — and it's the
  exact envelope the attendant already emits, so the producer is unchanged.

The `type` and `subject` CloudEvent attributes are copied into JMS string
properties (`eventType`, `eventSubject`) so consumers filter with **message
selectors** without deserializing the body. `subject` is the Vorpal session id —
the same per-call correlation key BLADE already matches on.

## Layout

| File | Role |
|---|---|
| `framework/v3/events/CloudEvent.java` | the CloudEvents 1.0 envelope (in the framework jar) |
| `framework/v3/events/EventPublisher.java` | topic publisher — JSON `TextMessage`, per-thread session |
| `framework/v3/events/EventBus.java` | canonical JNDI names + node-local publisher facade |
| `EventBusStartup.java` | `@WebListener` — registers config + installs the publisher |
| `EventIngestResource.java` | `POST /events/api/v1/events` — HTTP→JMS ingress |
| `CalendarEventListener.java` | reference consumer MDB (durable topic subscription) |
| `EventBusSettings.java` | Configurator-editable config (`source`) |
| `notes/configure-events-jms.py` | WLST — provisions the JMS resources |

The JNDI names live in **one** place (`EventBus.CONNECTION_FACTORY_JNDI` /
`EventBus.TOPIC_JNDI`) and are referenced everywhere, including the MDB's
`@MessageDriven(mappedName = EventBus.TOPIC_JNDI)` — legal because a `String`
constant is a compile-time constant. (Analytics scattered its names across five
places; this doesn't.)

## Deploy

1. **Provision the JMS resources** (once per domain), same pattern as analytics:
   ```bash
   export WL_USER=weblogic WL_PASS=... WL_ADMIN=t3://<admin>:7001
   # engine-tier cluster name; defaults to BEA_ENGINE_TIER_CLUST. Set this on
   # domains (e.g. ashburn) whose engine cluster is named differently —
   # find it with ls('/Clusters') in wlst.
   export BLADE_ENGINE_CLUSTER=<your-engine-cluster>
   $MW_HOME/oracle_common/common/bin/wlst.sh notes/configure-events-jms.py
   ```
   Creates `BladeEventBusFileStore`, `BladeEventBusJMSServer`, the system module,
   the connection factory `jms/BladeEventBusConnectionFactory`, and the
   `UniformDistributedTopic` `jms/BladeEventBusTopic`, targeted at that cluster.
   The script is idempotent — safe to re-run.

2. **Build + deploy the WAR** (proto apps build by hand — excluded from the
   default build, per the repo convention):
   ```bash
   ./mvnw -pl proto/events -am package
   # deploy proto/events/target/events.war to the engine tier
   ```

3. **Point the attendant at it** — no producer code change, just the sink URL:
   ```bash
   ATTENDANT_SINK=http://<engine>:<port>/events/api/v1/events
   ```

## Verify

```bash
curl -i -X POST http://<engine>:<port>/events/api/v1/events \
  -H 'Content-Type: application/cloudevents+json' \
  -d '{"specversion":"1.0","type":"net.vorpal.attendant.meeting.scheduled",
       "source":"/gumball/attendant","subject":"demo-1",
       "data":{"who":"Sarah","when_text":"next Tuesday at 3"}}'
# => 202 Accepted, {"id":"..."}
```

The server log then shows the reference consumer picking it off the topic:
```
CalendarEventListener: would create appointment with Sarah at next Tuesday at 3 [event id=..., subject=demo-1]
```

## Before it leaves `proto/`

- **Auth on the ingress.** The `POST` is currently **unauthenticated** (the demo
  producer is a trusted in-network sidecar). Add a `security-constraint` on
  `/api/v1/*` (BASIC, `authenticated-users`) in `web.xml` as `services/analytics`
  and `services/context` do, or terminate mTLS at the ingress.
- **Consumer idempotency.** A durable subscriber can see a redelivery; dedupe on
  the CloudEvent `id` (exposed as the `eventId` JMS property) before acting.
- **Promote to `services/`** and add the `WlsResourceProvisioner`/`WlsResourceAudit`
  in-console "fix/verify" pair the analytics console has, so provisioning isn't
  WLST-only.
