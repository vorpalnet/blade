# Transfer Service

Javadocs: `/blade/javadoc/transfer/` on the Admin Portal

Implements SIP call transfer (RFC 3515 REFER) in blind and attended (consultative) styles,
chosen per call by configuration, plus a REST API for externally triggered transfers.
B2BUA-based: every phone keeps one dialog with the service, never sees a REFER, and is told
whom to talk to by re-INVITEs.

## How a REFER is handled

`TransferServlet` extends the framework's `v3.B2buaServlet`; initial INVITEs run the
framework's `TransferInitialInvite`, and a REFER goes through the selector/translation
machinery:

- A translation with a **`statusCode`** attribute rejects the REFER with that response —
  the configured way to refuse transfers from a given source.
- Otherwise the translation's **`style`** attribute picks the callflow: `blind` (the
  framework's `BlindTransfer`) or `attended` (its `v3.transfer.AttendedTransfer`).
  `conference` is not implemented yet. A REFER whose `Refer-To` carries `Replaces` is
  attended, whatever the style.
- No `style` → the config's `defaultTransferStyle`.
- No matching translation at all → the REFER is processed with the default style only if
  `transferAllRequests` is true; otherwise it passes through untransferred.

`preserveInviteHeaders` / `preserveReferHeaders` name the headers copied onto the
forwarded requests (the sample preserves `Referred-By`).

### Attended transfer

Alice is the customer, Bob the agent transferring her, Carol the agent he transfers her to.
While Carol rings, Alice and Bob stay connected. When Carol answers, Alice is parked and Bob
talks to Carol. When Bob hangs up on Carol, Alice is connected to her. If Carol declines or
never answers, nothing changes for Alice and Bob. If Carol hangs up before Alice reaches her,
Alice and Bob are reconnected so Bob can try again. If Bob hangs up while Carol still rings,
the transfer finishes as a blind one; if Carol then leaves before Alice reaches her, Alice
goes back to the number she first dialed, where a new agent answers. If Alice hangs up, Bob
and Carol are hung up too.

## Finding the call: session selectors

The REST API addresses live calls by key, and the sample config shows the pattern:
session selectors that index each call by a correlation header — the vendor call-id
headers contact centers already stamp on their traffic (the sample uses a `Cisco-Gucid`
selector; the test configs show the same for other platforms' UUID headers). Any header
the selector captures becomes a valid `sessionKey`.

## REST API

Base path: `/transfer/resources/v1`.

| Method and path | Does |
| --- | --- |
| `GET session/{key}` | Examine a live call's session variables |
| `POST transfer` | Trigger a transfer: `{style, sessionKey, dialogKey?, target, notification}` |

`style: attended` runs the attended transfer; Bob completes it by hanging up on Carol, as
with a REFER. `conference` still runs as blind.

## Notifications

Every transfer reports its events as they happen: `transferRequested`, `transferInitiated`,
`consultationStarted` (attended only: Carol answered and is talking to Bob),
`transferCompleted`, `transferDeclined`, `transferAbandoned`. The service publishes each on
the event bus as its own type, `org.vorpal.blade.services.transfer.<event>`, declared in
`WEB-INF/blade-events.json`, so any application can subscribe. Analytics already records
five of them through the framework's own transfer events; it stores `consultationStarted`
against the call. A REST request's `notification.style` picks how its caller hears them:

| `notification.style` | The caller gets |
| --- | --- |
| absent, or `async` | The POST waits and answers once, with the outcome |
| `none` | `202` at once, nothing more |
| `callback` | `202` at once; every event POSTed to the webhook named by `callbackName` |

**The waited answer comes early for an attended transfer.** It answers `transferCompleted`
when Carol picks up, because the consultation that follows can outlast any HTTP timeout.
Everything else (the webhook, the event socket and the bus) says `transferCompleted` only
when Alice is connected to Carol, and `consultationStarted` when Carol picks up. If Carol
hangs up instead, those report `transferDeclined`; the waited caller has already been told
otherwise. This is the one place the service reports ahead of the facts.

Webhooks are named in the config's `webhooks` section (`url`, optional `headers` such as
`Authorization`, optional `events` filter). `defaultWebhook` names one that gets the events
of every transfer no REST request named a webhook for, such as a REFER's.

A client that would rather hold a socket open connects to `wss://<host>/transfer/events`
(the same front door as the REST API, with its credentials) and gets one JSON frame per
event. The service pings every socket every 30 seconds, so a proxy never closes one
for being quiet between transfers. `?sessionKey=` (a
session key or Vorpal-ID) or `?uid=` (a REST request's `notification.uid`) narrows the
stream. Any engine will do: the events travel the bus to every engine's sockets.

## Event bus

The service publishes transfer lifecycle events — requested, initiated, completed,
declined, abandoned — onto the BLADE CloudEvents bus, and ships its own durable
subscriber (`subscriptionName = "transfer"`) as the worked example of two applications
each receiving their own copy of one event: the same events also land in
[services/analytics](../analytics/README.md) under its `analytics-db` subscription. See
[services/events](../events/README.md) for the bus itself.

## Related modules

- [services/events](../events/README.md) — the event bus carrying the transfer events
- [services/analytics](../analytics/README.md) — persists them
- [Framework v2 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v2/README.md) — home of the transfer callflows (`v2.transfer`)
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-transfer</artifactId>
```
