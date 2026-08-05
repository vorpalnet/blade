# Transfer Service

Javadocs: `/blade/javadoc/transfer/` on the Admin Portal

Implements SIP call transfer (RFC 3515 REFER): blind, attended (consultative), and
conference styles, chosen per call by configuration — plus a REST API for
externally-triggered transfers. B2BUA-based, built on the framework's pre-built transfer
callflows.

## How a REFER is handled

`TransferServlet` extends the framework's `v3.B2buaServlet`; initial INVITEs run the
framework's `TransferInitialInvite`, and a REFER goes through the selector/translation
machinery:

- A translation with a **`statusCode`** attribute rejects the REFER with that response —
  the configured way to refuse transfers from a given source.
- Otherwise the translation's **`style`** attribute picks the callflow: `blind`,
  `attended`, or `conference` (the framework's `BlindTransfer`, `AttendedTransfer`,
  `ConferenceTransfer`).
- No `style` → the config's `defaultTransferStyle`.
- No matching translation at all → the REFER is processed with the default style only if
  `transferAllRequests` is true; otherwise it passes through untransferred.

`preserveInviteHeaders` / `preserveReferHeaders` name the headers copied onto the
forwarded requests (the sample preserves `Referred-By`).

## Finding the call: session selectors

The REST API addresses live calls by key, and the sample config shows the pattern:
session selectors that index each call by a correlation header — the vendor call-id
headers contact centers already stamp on their traffic (the sample uses a `Cisco-Gucid`
selector; the test configs show the same for other platforms' UUID headers). Any header
the selector captures becomes a valid `sessionKey`.

## REST API

Base path: `/transfer/v1`.

| Method and path | Does |
| --- | --- |
| `GET session/{key}` | Examine a live call's session variables |
| `POST transfer` | Trigger a transfer: `{style, sessionKey, dialogKey?, target, notification}` |

`notification.style` picks how completion is reported: immediate response, hang until the
final response, a REST callback, or a JMS message.

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
