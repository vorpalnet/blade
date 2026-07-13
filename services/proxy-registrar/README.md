# Proxy Registrar Service

[Javadocs](https://vorpal.net/javadocs/blade/proxy-registrar)

A SIP registrar with call forwarding: REGISTER requests maintain an in-memory
location database, and initial INVITEs are forwarded to the registered contact.

## How it works

- **REGISTER** — `RegisterCallflow` maintains one `SipApplicationSession` per
  account (keyed by the From account name via `@SipApplicationKey`), holding a
  `Registrar` object that tracks contact bindings and their expirations. The
  app session's own expiration is tied to the longest contact expiry, so
  bindings age out with the session.
- **INVITE** — `InviteCallflow` forks the call to **every** registered
  contact with `sendRequestsInParallel`: the first 2xx wins and the framework
  CANCELs the losing legs; if all legs fail, the last error is relayed; the
  `timeout` setting cancels everything and answers 408. Unregistered accounts
  get a 404 unless `proxyOnUnregistered` is set, in which case the INVITE is
  forwarded to its request URI unchanged. In-dialog requests are relayed by
  the b2bua callflows inherited from the v3 `B2buaServlet`.

## B2BUA vs. proxy drop-out

The v2 `Proxy` API is gone. Whether the service stays in the signaling path is
now decided by configuration, not code:

- `session:passthru = true` (the sample default) — after call setup the
  framework stitches the endpoints' Contacts together and removes OCCAS from
  the route set; the ACK and all in-dialog traffic flow directly between the
  endpoints, like a proxy that drops out.
- `session:passthru = false` — the service remains in the dialog as a full
  B2BUA, relaying in-dialog requests (re-INVITE, BYE, etc.).

Provisional responses: the service sends its own 180 Ringing as soon as the
fork starts (so the caller hears local ringback immediately), and real 18x
from the legs — including 183 early media — is relayed upstream via the
fan-out primitive's per-leg observer.

## Configuration

| Setting | Description |
|---|---|
| `allowHeader` | Value for the `Allow` header on REGISTER responses |
| `proxyOnUnregistered` | Forward to the request URI instead of answering 404 when no contact is registered |
| `timeout` | Overall timeout in seconds for the forked INVITEs; on expiry all legs are canceled and the caller gets a 408 |
| `session:passthru` | Drop out of the dialog after setup (see above) |

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-proxy-registrar</artifactId>
```
