# Queue Service

Javadocs: `/blade/javadoc/queue/` on the Admin Portal

Holds inbound calls in a named queue when no downstream resource is available and
releases them at a configured rate. B2BUA-based, so it stays in the dialog for the life
of the queued call.

## How it works

`QueueServlet` extends the framework's `v3.B2buaServlet`. On an initial INVITE, the
standard selector/translation machinery picks a translation; its `queue` attribute names
the queue. A call with no matching translation (or no `queue` attribute) passes through
as an ordinary B2BUA call.

While queued, the caller hears ringing (`180`, re-sent on a configurable interval), and
optionally — after `ringDuration` — gets connected to an announcement server for
music/announcements, using the caller's own SDP. A per-queue timer releases up to `rate`
calls every `period`, oldest first: each released call is B2BUA-connected to its original
destination; a caller parked on the announcement is moved to the agent with an offerless
re-INVITE and the announcement leg is BYEd. A CANCEL while queued cleans up both legs.

When the outbound connect fails, the response decides the call's fate. **Retryable**
failures — 408 (transaction timeout, the unreachable-network case), 480, 486, and 5xx
other than 501 — put the call back at the release end of the queue, so the queue buffers
against a failing or busy destination until it recovers; the retry loop is bounded by the
caller hanging up and by the session expiration. **Definitive** failures — the rest of
4xx and all of 6xx — remove the call from the queue and end both legs: a call that can
never succeed doesn't sit in the queue forever.

Queues are **per node**. BLADE runs no singletons, so each engine drains its own queues;
capacity planning is per-node `rate × nodes`. Depth watermarks are logged per minute,
hour, and day.

## Configuration

`queues` maps queue names to their attributes:

| Setting | Description |
| --- | --- |
| `period` | Milliseconds between release ticks |
| `rate` | Calls released per tick |
| `ringPeriod` | Interval between 180 re-sends while queued |
| `ringDuration` | How long to ring before connecting the caller to the announcement |
| `announcement` | SIP URI of the announcement/MOH server (optional — omit for ringback-only queues) |

The sample config defines `fast` / `medium` / `slow` queues and shows both a hash map and
a prefix map steering dialed numbers to queues. Edit and publish through the
[Configurator](../../admin/configurator/README.md).

## Related modules

- [services/hold](../hold/README.md) — parking a single leg, when you don't need queue semantics
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the B2BUA base and async primitives in use
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-queue</artifactId>
```
