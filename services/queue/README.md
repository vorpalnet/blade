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
re-INVITE and the announcement dialog is BYEd. A CANCEL while queued cleans up both dialogs.

When the outbound connect fails, the response decides the call's fate. **Retryable**
failures — 408 (transaction timeout, the unreachable-network case), 480, 486, and 5xx
other than 501 — put the call back at the release end of the queue, so the queue buffers
against a failing or busy destination until it recovers; the retry loop is bounded by the
caller hanging up and by the session expiration. **Definitive** failures — the rest of
4xx and all of 6xx — remove the call from the queue and end both dialogs: a call that can
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
| `ringPeriod` | Seconds between 180 re-sends while queued |
| `ringDuration` | Seconds to ring before answering the caller with the announcement or media; 0 answers at once |
| `announcement` | SIP URI of an announcement/MOH server (optional; omit for ringback-only queues) |
| `media` | A prompt or music the queue plays itself on the JSR-309 media server, looped while the caller waits; takes the place of `announcement` |
| `destinationHolds` | With `media`: the destination keeps its own line (presence, for a skill), so each caller is sent on as soon as they hear the media, not on a tick |

A queue with `media` needs a media server. `driverName` and `driverProperties`, at the top of the
configuration, name the JSR-309 driver and pass it its settings, as the player's do; the driver
ships in the WAR (the Gryphon build of this app carries one). The queue answers the caller from
the media server, and on release moves them to the destination with an offerless re-INVITE, so
the destination is always offered the caller's media and needs no SDP of its own.

With `destinationHolds`, the order turns around: the destination is asked first, with an INVITE
that carries no offer and may wait there for minutes, and the caller hears the media meanwhile.
When someone answers, the caller is re-INVITEd with their offer.

The sample config defines `fast` / `medium` / `slow` queues and shows both a hash map and
a prefix map steering dialed numbers to queues. Edit and publish through the
[Configurator](../../admin/configurator/README.md).

## Related modules

- [services/hold](../hold/README.md) — parking a single dialog, when you don't need queue semantics
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the B2BUA base and async primitives in use
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-queue</artifactId>
```
