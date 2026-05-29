# Test User Agent Server (UAS)

[Javadocs](https://vorpal.net/javadocs/blade/test-uas)

A SIP test server built on the BLADE framework. It sits at the end of a call
path and does one of two things, chosen per-call from the initial INVITE's
Request-URI — letting developers and administrators mock up SIP scenarios
without standing up SIPp. It is the counterpart to the
[Test UAC](../test-uac).

## Two modes

The mode is inferred from the initial INVITE's Request-URI — there is **no
configuration toggle**:

| Request-URI carries… | Mode | Behavior |
|---|---|---|
| none of `status`/`delay`/`refer` | **Strip-and-forward (B2BUA)** | Forward the INVITE to its Request-URI, stripping a multipart (e.g. SIPREC) body down to just its `application/sdp` part |
| `status` and/or `delay` | **Endpoint (UAS)** | Answer the call locally per the parameters |
| `refer` | **Endpoint (transfer)** | Answer, then REFER the caller to the target |

The chosen mode is stamped on the application session, so in-dialog requests
(re-INVITE, BYE, …) follow the same path as the initial INVITE.

```text
initial INVITE
      │
      ├─ Request-URI has ?refer=…           ──▶  TestRefer    (answer, then transfer)
      ├─ Request-URI has ?status= or ?delay= ──▶  TestInvite   (answer locally)
      └─ none of the above                   ──▶  B2buaServlet (strip multipart → SDP, forward)
```

### Strip-and-forward

When the INVITE has none of the endpoint parameters, the UAS forwards it to the
Request-URI as a B2BUA. SIPREC and other multipart bodies are reduced to their
`application/sdp` part so a plain softphone can parse what it receives. This is
the "sit in the path and clean up the body" role.

### Endpoint

When the INVITE carries `status`, `delay`, or `refer`, the UAS answers locally.

| Parameter | Meaning |
|---|---|
| `status` | SIP response code to send (default `200`). A 2xx answer carries a blackhole/mute SDP (`c=0.0.0.0`, `a=inactive`). Any other code is sent bare. |
| `delay` | For an answered (2xx) call, how long to keep it up before sending `BYE`. `0`/absent means no auto-teardown. |
| `refer` | Answer with 200 OK, then send a REFER to this address (transfer test). |

`delay` accepts a **bare integer (milliseconds)** or a value with an
`ms`/`s`/`m`/`h` suffix:

| Value | Meaning |
|---|---|
| `5000` | 5 seconds |
| `5s` | 5 seconds |
| `500ms` | half a second |
| `2m` | 2 minutes |
| `1h` | 1 hour |

#### Examples

```
sip:bob@uas.test;status=200            ; answer 200 with muted SDP, no teardown
sip:bob@uas.test;status=200;delay=5s   ; answer 200, send BYE after 5 seconds
sip:bob@uas.test;delay=5000            ; same as above (status defaults to 200)
sip:bob@uas.test;status=404            ; reject immediately with 404 Not Found
sip:bob@uas.test;status=503            ; reject immediately with 503
sip:bob@uas.test;refer=sip:carol@…     ; answer, then transfer the caller to Carol
```

To make a specific number return an error (the old `errorMap` use case), dial
it with `;status=404` rather than configuring a mapping.

## Callflow Architecture

| Trigger | Callflow | Behavior |
|---|---|---|
| INVITE with `status`/`delay` (initial) | **TestInvite** | Answer with the configured status; 2xx → blackhole SDP + optional auto-BYE |
| INVITE with `refer` (initial) | **TestRefer** | Answer, then REFER with NOTIFY handshaking |
| re-INVITE (endpoint dialog) | **CallflowHold** *(framework)* | Blackhole hold answer (media hold simulation) |
| BYE, CANCEL, INFO (endpoint dialog) | **TestOkayResponse** | 200 OK |
| *(other, endpoint dialog)* | **TestNotImplemented** | 501 Not Implemented |
| INVITE with no endpoint params | *(B2buaServlet)* | Strip multipart → SDP and forward |

## Configuration

Behavior comes from the Request-URI, so there are no app-specific settings.
The only configuration is the inherited logging and session parameters,
managed by [SettingsManager](https://vorpal.net/javadocs/blade/framework) at
`config/custom/vorpal/test-uas.json` and adjustable via the Configurator GUI or
JMX. The sample seeds FINEST logging and a 60-second session expiration.

## Admin tooling (portal & Configurator)

test-uas registers a `SettingsManager` MBean
(`vorpal.blade:Name=test-uas,Type=Configuration`) like every BLADE app, so it
participates in the admin tooling:

- **Configurator** — test-uas appears in the Configurator's app list (it
  discovers apps by their generated `_schemas/test-uas.jschema`). The dynamic
  form lets operators edit the logging level and session expiration. The
  `name`/`tagline`/`description` set in `TestUasConfigSample` give it a real
  identity in the generated `test-uas.json.SAMPLE` rather than a bare slug.
- **Portal launcher deck** — test-uas is **not** a deck card, and that's by
  design. The deck only lists admin GUI apps under `blade/*` context-roots
  (Configurator, Portal, Tuning, Logs, …). test-uas is a SIP service with a
  flat `test-uas` context-root and no browser UI, so it stays off the deck. Its
  `name`/`tagline`/`description` metadata still satisfies the portal's metadata
  contract (read over JMX via `SettingsMXBean.getCurrentJson()`) — it simply
  isn't joined to a launcher card.

## Dependencies

- **`org.vorpal.blade:vorpal-blade-library-framework`** — BLADE framework
  providing `B2buaServlet`, `Callflow`, `CallflowHold`, `SettingsManager`, and
  SIP container integration

## Related Modules

- [**test/test-uac**](../test-uac) — Test User Agent Client (call generator)
- [**test/test-b2bua**](../test-b2bua) — Test Back-to-Back User Agent
- [**libs/framework**](../../libs/framework) — Base framework

## Deployment

Deploys as a WAR to Oracle OCCAS with the `vorpal-blade` shared library:

- **Context root**: `test-uas`
- **WebLogic shared library**: `vorpal-blade` (specification version 2.0)

SIPp scenarios for exercising both modes live in [`testing/`](testing).

## License

Part of the Vorpal Blade SIP Servlet Container project.
