# BLADE Framework — v3 API

Javadocs: package `org.vorpal.blade.framework.v3` — browse at `/blade/javadoc/framework/` on the Admin Portal

v3 is the actively developed API line of the BLADE framework. The [v2 line](../v2/README.md)
is frozen and maintained for existing applications; both extend a small version-neutral
baseline package (`org.vorpal.blade.framework`), so the two generations live side by side
in one JAR. A v2 application migrates with a one-line base-class swap — everything else is
inherited unchanged.

The lambda callflow model is the same one v2 taught you: entire SIP conversations as
readable, top-to-bottom code, with automatic state serialization for cluster failover
(see the [v2 callflow guide](../v2/callflow/README.md) if the model is new to you).
v3 adds two things v2 can't carry, plus a config-first routing model.

## Call tracing

`Callflow`
and `AsyncSipServlet`
fire trace events at the exact spots the v2 logger draws its ASCII sequence-diagram arrows.
Each outbound `CallStep`
pins the concrete callflow class, the method that ran, and the line number of the
`sendRequest`/`sendResponse` call, together with the raw SIP message. A stable session id
(`X-Vorpal-Session`) lets steps recorded by different applications in a routed chain be
stitched back into one end-to-end timeline — the artifact that answers "which app in the
string misbehaved."

Tracing is off by default; a disarmed call costs one boolean read per event. Arm it
programmatically (`Callflow.enableTrace`) or by rule over JMX from the
[Callflow Viewer](../../../../../../../../../../admin/callflow/README.md). The node side lives in
`diagnostics`:
`TraceLog` keeps a bounded per-application ring buffer, `TraceRule` matches which calls to
capture, and `CallTraceAggregator` merges the per-app buffers into one `CallTrace`.
A trace session is "arm, reproduce, disarm" — nothing is persisted.

## Passthru: leave the call after routing it

A forwarding callflow (an initial INVITE in, an initial INVITE out) can route a call and then
leave it. With `session.passthru` set in the application's configuration, the caller's INVITE
is proxied to the destinations the callflow chose, so a single dialog runs from caller to
callee and the ACK, re-INVITEs and BYE never reach the application. With it off, the same
callflow is a full B2BUA. An operator picks one or the other per network, without a code
change.

**Writing a callflow that works both ways.** Build the outbound requests as for a B2BUA and
send them with `sendRequest`, `sendRequestsInSerial` or `sendRequestsInParallel`. Under
passthru each request becomes one destination of the proxy, and the callbacks receive the
same responses they would as a B2BUA: the per-dialog observer sees every response from every
destination, and the completion callback gets the answer or the failure. What changes is who
answers the caller. Under passthru it is the container, so the callflow checks `isPassthru()`
before ringing, relaying or answering the caller itself:

```java
if (!isPassthru()) {
    sendResponse(aliceRequest.createResponse(180)); // our own ringback, B2BUA only
}

sendRequestsInParallel(timeout, bobRequests, (bobResponse) -> {
    if (isPassthru()) {
        return; // the caller already has this response
    }
    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    copyContentAndHeaders(bobResponse, aliceResponse);
    sendResponse(aliceResponse, (aliceAck) -> sendAcknowledgement(aliceAck, bobResponse));
}, dialogObserver, retryable);
```

`proxy-balancer` and `proxy-registrar` are written this way.

**Serial and parallel.** A serial hunt tries one destination at a time; the `advance` rule
still decides which failures move on and which end the hunt. A parallel race tries them all;
the first answer wins and the others are cancelled. Each batch gets its `timeout`.

**Trying more destinations before the caller hears a failure.** Send the next batch from
inside the completion callback, as `proxy-balancer` does for its next tier. The caller then
hears from the new batch instead of the failure. It has to happen inside the callback: once
the callback returns without sending anything, the failure goes to the caller. A lookup that
cannot finish there (a database query, a REST call) needs the B2BUA mode. Nothing can follow
an answer, a 6xx, or the caller's CANCEL. Each destination is tried at most once per call: a
later batch skips the ones an earlier batch already tried.

**What the caller sees** follows RFC 3261's proxy rules where they differ from a B2BUA:

- If two destinations answer at the same moment, the caller gets both answers and ends the
  second one, as every SIP user agent must.
- Ringing and early media from every destination reach the caller.
- A 503 reaches the caller as a 500.
- The caller's CANCEL cancels every destination still ringing.

**When not to use it.** After setup nothing of the call reaches the application, so a
callflow that needs to stay in the dialog (hold, transfer, recording) must run without
passthru.

## Final responses without ceremony

`CallflowResponse`
builds a final response fluently:

```java
return new CallflowResponse(486, "Busy Here")
        .addHeader("Retry-After", "120");
```

```java
return new CallflowResponse(200)
        .setContent(sdp, "application/sdp")
        .onAck(ack -> sipLogger.fine(ack, "answered"));
```

## The config-first routing model

v3 routing is driven by configuration, not code: an enrichment pipeline fills a `Context`,
then a single `Routing` strategy produces a `Route`. Every configuration class carries a
JSON `type` discriminator, so the [Configurator](../../../../../../../../../../admin/configurator/README.md)
can render the whole model as generated forms. The
[Framework library README](../../../../../../../../README.md) documents this model package
by package; the reference consumer is the [iRouter](../../../../../../../../../../services/irouter/README.md)
service.

## Package map

| Package | What's in it |
| --- | --- |
| `v3` | `Callflow`, `AsyncSipServlet`, `B2buaServlet`, `CallflowResponse`, `CallStep` |
| `configuration` | The config model root: `RouterConfiguration`, `Context`, `SettingsManager`, resolvable types |
| `configuration.connectors / selectors / translations / routing / auth / expressions / trie` | The routing model's building blocks — see the [Framework library README](../../../../../../../../README.md) |
| `crud` | Rule-driven SIP message transformation: Regex, XPath, JsonPath and SDP operations |
| `diagnostics` | The tracing spine's node side: `TraceLog`, `TraceRule`, `CallTrace` |
| events | CloudEvents 1.0 over JMS: `EventBus`, `EventPublisher`, the event catalog |
| `fsmar` | The FSMAR configuration model (the App Router engine ships in [libs/fsmar](../../../../../../../../../fsmar/README.md)) |
| `irouter` | `IRouterServlet` — the base the iRouter service extends |
| `media` | JSR-309 media verbs in lambda style: `MediaCallflow`, hold/resume/mute callflows |
| metrics | Per-application counters, gauges and histograms, read over JMX |
| `probe` | `KernelProbe` — read-only kernel tunable inspection for the Tuning health check |
| `security` | Admin-tier JWT single sign-on: `JwtAuthFilter`, `JwtValidator`, `AdminRole` |
| `source` | `CallflowRegistry` and per-app source inventory backing the Callflow Viewer |
| `tester` | The SIP load-test harness used by [test-uac](../../../../../../../../../../test/test-uac/README.md) and [test-uas](../../../../../../../../../../test/test-uas/README.md) |
| analytics, sdp | Thin v3 faces over their frozen v2 counterparts, so application imports stay `v3.*` |

## See also

- [v2 API](../v2/README.md) — the frozen line, and the best introduction to the callflow model
- [Framework library](../../../../../../../../README.md) — module overview, dependencies, Maven coordinates
- [BLADE](../../../../../../../../../../README.md) — project home
