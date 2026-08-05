# Hold Service

Javadocs: `/blade/javadoc/hold/` on the Admin Portal

A single-leg parking endpoint: route a call leg here and its media goes quiet until the
far end re-INVITEs it somewhere else. No media server is involved — the service answers
with SDP of its own construction.

## How it works

`HoldServlet` is a UAS built on the framework's `v3.AsyncSipServlet`. An INVITE (initial
or re-INVITE) runs the framework's `CallflowHold`, which answers 200 OK with an RFC 3264
**`a=inactive`** answer: one inactive m-line per offered m-line, a real address and a
non-zero port — deliberately not the legacy `c=0.0.0.0` blackhole, so streams stay
recoverable rather than rejected. Offerless re-INVITEs (RFC 4028 session refreshes)
replay the cached SDP byte for byte; `Session-Expires` is echoed with `refresher=uac`
when the caller didn't state one. Multipart offers (SIPREC-style) are handled; the answer
is always plain `application/sdp`.

BYE and CANCEL tear the leg down; any other method gets `405` with an accurate `Allow`
header — a single-leg UAS has no peer to forward to.

There is no "resume" operation here: a parked leg resumes when whoever routed it here
re-INVITEs it with a live offer. The inactive-answer builder is reusable framework code
(`CallflowHold.inactiveAnswerFor`) — the [TPCC service](../tpcc/README.md) uses it to
park newly created dialogs.

## Configuration

Nothing service-specific — only the framework's standard `logging` and `session` blocks,
edited through the [Configurator](../../admin/configurator/README.md).

## Related modules

- [services/tpcc](../tpcc/README.md) — parks its REST-created dialogs with the same framework callflow
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — home of `v3.media` and `CallflowHold`
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-hold</artifactId>
```
