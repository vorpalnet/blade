# Phone

A browser softphone for the BLADE WebRTC gateway, served at `/blade/phone`. Sign in and
place or receive calls from a browser tab — no desk phone, no SIP client install.

## How it works

The page serves the UI and the client library (`assets/blade-webrtc.js`), which speaks JSON
events over a WebSocket to the WebRTC gateway service on the **engine** tier. Nothing here
talks to SIP or to a media server. Browser-to-browser calls run media peer-to-peer; calls to
a phone number are anchored at the gateway. Ringback, DTMF tones, and the level meters are
synthesized with Web Audio, so no audio assets ship.

## Identity

The app's only server side is identity, and it exists because the page and the gateway are
not on the same server. The container's FORM login establishes who the user is *here*; the
gateway is on another host and port, so neither the `BLADEADMINSESSION` cookie nor an
`Authorization` header (which the browser WebSocket API cannot set) reaches it.

So the app mints a short-lived signed token instead:

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET api/v1/session` | FORM | Who you are and what this deployment allows |
| `POST api/v1/token[?aor=]` | FORM, four roles | Mint a token for the signed-in user |
| `GET api/v1/jwks.json` | **none** | Public signing keys, fetched by the engine tier |

The JWKS is deliberately open — the gateway fetches it from a host where it has no admin
session, exactly as it would fetch an IdP's. Its `web.xml` carve-out is an *exact* path
pattern, not a prefix, so `api/v1/token` stays behind the login.

**The token names the address, and the gateway honors nothing else.** Whatever address is
registered, the browser cannot claim one it was not issued — that is fixed regardless of the
settings below.

Whether you may *ask* for a particular address is a separate question, and `allowChosenAddress`
answers it. It defaults to **on**, because a browser-to-browser call needs two addresses and
most deployments have exactly one operator account: with it off, the app cannot be tested or
demonstrated without creating realm users. With it on, an authenticated administrator can be
issued a token for any `user@host`. What survives either way is that the caller must be signed
in and hold a BLADE role, and that the token's subject is always the real username — so
`webrtc` logs who actually registered even when the address they took is someone else's name.

Turn it off to bind each person to exactly one address, `<username>@<aorDomain>`; the page's
address field then goes read-only to match.

## Configuration

Edited in the [Configurator](../configurator/README.md) like any other app
(`blade-phone.json`).

| Setting | Meaning |
|---|---|
| `gateway` | WebSocket URL of the `webrtc` service. Blank means the page guesses the single-box lab case. |
| `aorDomain` | Appended to the username to form the default address others dial. |
| `allowChosenAddress` | Whether a user may be issued a token for an address other than their default. On by default — see above. |
| `stunServer` | Offered to the browser. Required, not optional — browsers publish `<uuid>.local` mDNS candidates rather than private addresses. |
| `jwt.issuer` / `jwt.audience` | Must match the same fields in the gateway's `webrtc.json`, or every browser is refused. |
| `jwt.ttlSeconds` | Token lifetime. It is presented once, immediately; keep it short. |

The signing key is generated at startup and never persisted — see `JwtIssuerConfig` for why
that is sufficient rather than a shortcut.

## Related modules

- [proto/webrtc](../../proto/webrtc/README.md) — the gateway this phone authenticates to
- [Portal](../portal/README.md) — the launcher deck this app's card appears on
- [SECURITY.md](../../SECURITY.md) §2a — the token design in full
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-phone</artifactId>
```
