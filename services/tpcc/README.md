# Third-Party Call Control (TPCC) Service

Javadocs: `/blade/javadoc/tpcc/` on the Admin Portal

Lets an external application set up and control SIP calls between other parties — RFC
3725 third-party call control, driven entirely over REST. The controller creates a
session, creates dialogs to each party, and connects them; the service does the SIP.

## The call model (RFC 3725 Flow I)

Each dialog is created with an **offerless INVITE**, so the answering party's 200 OK
carries the real SDP offer. The ACK answers with an RFC 3264 `a=inactive` body (built by
the same framework code the [Hold service](../hold/README.md) uses), parking the party's
media. `connect` then bridges two parked dialogs: an offerless re-INVITE to dialog A produces
a fresh offer, which is passed to dialog B, and the answers flow back — standard 3PCC
bridging, no media server involved.

## REST API

Base path: `/tpcc/api/v1`. Long-running operations are asynchronous — the HTTP response
resumes when the SIP transaction completes.

| Method and path | Does |
| --- | --- |
| `POST session` | Create a control session; returns `{"sessionId"}`. Optional body sets expiry and attributes |
| `POST dialog/{sessionId}` | Create a dialog: `{localParty, remoteParty, requestUri?}` — sends the offerless INVITE |
| `GET dialog/{sessionId}` | List the session's dialogs by dialog id |
| `GET dialog/{sessionId}/{dialogId}` | One dialog's properties |
| `PUT dialog/{sessionId}/{dialogId}` | Set attributes on a dialog |
| `PUT dialog/{sessionId}/{a}/connect/{b}` | Bridge two dialogs |
| `DELETE dialog/{sessionId}/{dialogId}` | BYE a dialog |

`sessionId` is the SIP application-session key (minted by `POST session`); `dialogId` is
one dialog's Vorpal dialog id. Roadmap noted in the source: per-dialog hold/mute (the framework
callflows exist), and a one-shot `POST /call {from, to}`.

**Deployment note:** as shipped, the API's `web.xml` carries no auth-constraint — unlike
the other service-tier APIs (context, events), which use BASIC auth. Put authentication
in front of it before exposing it beyond a lab.

## Configuration

Nothing service-specific — the framework's standard `logging` and `session` blocks,
edited through the [Configurator](../../admin/configurator/README.md).

## Related modules

- [services/hold](../hold/README.md) — the shared inactive-answer parking callflow
- [services/context](../context/README.md) and [services/events](../events/README.md) — the other service-tier machine APIs
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-tpcc</artifactId>
```
