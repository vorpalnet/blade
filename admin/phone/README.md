# Phone

A browser softphone for the BLADE WebRTC gateway, served at `/blade/phone`. Sign in, pick
a name, and place or receive calls from a browser tab — no desk phone, no SIP client
install.

## How it works

This is a static web app: no Java, no framework dependency, no server-side state. It
serves the UI and the client library (`assets/blade-webrtc.js`), which speaks JSON events
over a WebSocket to the WebRTC gateway service on the **engine** tier — the AdminServer
only hands the browser the page. Browser-to-browser calls run media peer-to-peer; calls to
a phone number are anchored at the gateway. Ringback, DTMF tones, and the level meters are
synthesized with Web Audio, so no audio assets ship.

The gateway address is a field on the page; the default assumes the single-box lab case
(same host, engine HTTP port) and is meant to be edited anywhere else.

## Configuration

None on the server. With no settings class, the app appears on the
[Portal](../portal/README.md) deck as a bare card.

## Related modules

- [proto/webrtc](../../proto/webrtc/README.md) — the WebRTC gateway service this phone talks to
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-phone</artifactId>
```
