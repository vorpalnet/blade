# Context Service

Javadocs: `/blade/javadoc/context/` on the Admin Portal

Captures the raw inbound SIP headers of each call and exposes them for REST lookup and
mutation while the call is in progress. The motivating case: a cloud provider's trunk has
scrubbed or rewritten headers, and a downstream system needs to know what the call
actually arrived with.

## How it works

`ContextServlet` is a passive B2BUA built on the framework's `v3.B2buaServlet`. On
`callStarted` it snapshots every header name and value of the initial request into the
call's application session; the other lifecycle callbacks do nothing. External systems
then read or rewrite that snapshot over REST, keyed by a BLADE Selector value such as the
Call-ID.

## REST API

Base path: `/context/v1`. BASIC auth, role `authenticated-users`.

| Method and path | Does |
| --- | --- |
| `GET {key}` | The full header map for the matching call |
| `GET {key}/{name}` | One header value |
| `PUT {key}/{name}` | Replace one header value (text/plain) |
| `POST {key}` | Bulk-merge a JSON map of headers |
| `DELETE {key}/{name}` | Remove one header |

`{key}` is resolved to the live session through the framework's session-key index; an
unmatched key returns 404.

## Configuration

The config class extends the framework's `RouterConfig`, so the knobs are the standard
logging, session, and selector settings. The sample config defines the two selectors that
become valid `{key}` values:

- `callIdSelector` — the `Call-ID` header
- `appTxIdSelector` — `X-App-Tx-Id`, an application-assigned correlator

Add or change selectors in the [Configurator](../../admin/configurator/README.md).

## Related modules

- [services/analytics](../analytics/README.md) and [services/events](../events/README.md) — the other service-tier machine-to-machine APIs; all three share the BASIC-auth pattern
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-context</artifactId>
```
