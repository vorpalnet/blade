# BLADE WebRTC Gateway (proto)

Puts a browser on one end of a SIP call. JSON events over a WebSocket to the browser,
ordinary SIP to the network. Two browsers call each other with no media server at all; a
media server joins when the call reaches the PSTN, or when someone hits record.

```
browser ──wss://──> [   webrtc   ] ──SIP──> network
                     SignalEndpoint
                     InboundToBrowser   ← the SIP↔browser translation, compiled
                            │
                            └── JSR-309 (MediaCallflow) — only when anchored
                                     └── WebRTC leg ⇄ RTP leg
                                         ICE + DTLS-SRTP terminated here
```

## Why this exists, and why it is shaped this way

Oracle shipped this idea once, as WebRTC Session Controller, and stopped. Three things
about that are worth keeping in mind, because they drove the design:

1. **The customer wrote the translation.** Every SIP verb in each direction needed a
   hand-written Groovy criterion, authored and compiled inside a WebLogic console. Turning
   an inbound `INVITE` into "a call is arriving" was fifteen lines of scripting that each
   deployment maintained, and it had to be revised whenever browsers changed their SDP.
   Here that layer is `InboundToBrowser` — compiled, shipped, one copy.
2. **The media plane was a licensed appliance.** Which media server terminates DTLS is the
   kind of decision that gets made on commercial terms rather than technical ones, so the
   media plane is reached through **JSR-309** and named nowhere in this WAR. A browser leg
   is requested with `MediaConfigs.WEBRTC`; whichever driver is installed honours it or
   throws.
3. **The client rotted.** Its JavaScript still feature-detects `webkitGetUserMedia` and
   uses `URL.createObjectURL(stream)`, both long gone from browsers.

## Signaling is always SIP; only the media varies

Every call is a SIP INVITE through the App Router — browser-to-browser included. There is
no WebSocket-only shortcut: a call between two tabs on the same engine still traverses
FSMAR and the location service like any other call on the network, so it is routable,
loggable and visible to analytics. What `MediaMode` decides (`webrtc.json`, default
`AUTO`) is only what SDP that INVITE carries:

- **Pass-through (RELAY)** — the browser's own offer rides the INVITE body untouched and
  the far answer comes back untouched, so two browsers key DTLS directly to each other and
  need **no media server at all**. Deploy this WAR on its own and browser-to-browser works.
- **Anchored (ANCHOR)** — a media server's SDP rides the INVITE; required the moment one
  end is not a browser (a phone cannot speak ICE or DTLS-SRTP) or somebody wants the media
  (recording, conferencing, transcription, scoring, intercept).
- **AUTO** — relay when the dialed target is a browser on this node; otherwise anchor when
  a media server is installed and relay when none is.

The caller side resolves that policy blind — it cannot know what will answer. The
answering side needs no policy at all: an arriving offer with a DTLS fingerprint
(`a=fingerprint:`) is from a WebRTC endpoint and passes through; one without is from a
phone or trunk and anchors. This mirrors `session.passthru` in `v3/Callflow.java:49`,
where the same callflow runs as a dropped-out proxy or a full B2BUA depending on config.

## Recording a peer-to-peer call is a re-key, not a tap

This is the constraint that shapes everything above. In a relayed call the two browsers
complete a DTLS handshake **directly with each other** and derive their SRTP keys from a
master secret the signaling path never sees — RFC 8827 is built so it cannot. The gateway
forwarded fingerprints and nothing more, so it cannot decrypt a single packet.

So recording a pass-through call means re-offering **both** legs from the media server
(`call.update`), each browser answering, and the media server becoming a legitimate DTLS
endpoint on each — at which point it can mix, record and transcribe. Each leg does an ICE
restart and a fresh handshake, so there is a brief audible gap. That escalation is
**designed but not currently implemented** — it existed for the retired WebSocket-relay
path and has to be rebuilt across the two SIP legs of a pass-through call (see the gap
list). Today, a deployment that wants recording sets `mediaMode: anchor` and pays for a
media server on every call. That trade is the whole reason the setting exists.

## Not SIP over WebSocket

RFC 7118 exists and works, but **OCCAS 8.1 does not implement it**. The JSR-359 API jar
ships `SipWebSocketContext`, `Flow` and `FlowListener`, but `wlss.jar` — 933 classes, the
actual SIP container — contains no reference to any of them, and the only network-channel
protocol literals present are `sip` and `sips`. So a browser cannot register as a SIP UA
against this server regardless of how the application is written.

That settles it, and it is also the design we would pick: a browser answering "a call is
arriving, do you want it?" has no need to own dialogs, Via headers, registration refreshes
or transaction state.

## The protocol

CloudEvents 1.0 envelopes, subprotocol `blade.webrtc.v1`. Thirteen types, all in
`SignalProtocol`:

| browser → gateway | gateway → browser |
|---|---|
| `session.connect` (`aor` + `token`) | `session.ready` |
| `call.offer` | `call.incoming` |
| `call.answer` | `call.progress` |
| `call.accept` | `call.established` |
| `call.hangup` | `call.ended` |
| `call.dtmf` | `error` |
| `call.record` | `call.update` |
| `ice.candidate` (both directions) | |

`call.update` is the one that makes escalation possible: a fresh SDP offer for a call that is
already up. Without it a call's media path could never change after setup. It is the same
mechanism WSC's mobile SDKs exposed as `Call.update()` for audio↔video upgrade, and BLADE
already has the SIP analog in `v2/b2bua/Reinvite.java` and `v3/media/CallflowHold`.

**ICE is not trickled — complete SDP both ways.** A browser sends its whole offer or
answer after gathering finishes, candidates included, and the gateway forwards SDP whole.
On the anchored path that is because the media server does not advertise inbound trickle;
on the pass-through path it is because the SDP crosses a SIP fabric whose forks and B2BUA
hops have no channel for mid-flight candidates. Half-trickle also removes an entire class
of ordering bug from the client. (The `ice.candidate` type remains in the protocol; the
gateway may still trickle *its* candidates browser-ward on anchored calls.)

**Anchored outbound calls answer the browser during alerting, not at pickup.**
`call.progress` carries the gateway's SDP answer, so the browser has a media path while
the far end is still ringing. Deferring it to `call.established` would mean no ringback
and no carrier early media. On the pass-through path there is no local answer to give
early: SDP is forwarded as the far end produces it — on `call.progress` when an 18x
carries it, else on `call.established` — and exactly once, because a browser cannot
apply a second answer.

## Authenticating a browser

A socket arriving here says nothing about who is on the other end. It came from a page
served by a different server on a different port, so no admin session cookie reaches this
tier, and the browser WebSocket API cannot attach an `Authorization` header to a handshake.
Left alone, "who are you" is answered by whatever the client typed — which is how a gateway
ends up placing calls for anyone who can reach port 8001.

So `session.connect` carries a signed token beside the address, and `BrowserAuthenticator`
decides. Configuration lives in `WebrtcSettings.jwt` (`webrtc.json`) and is an ordinary
`JwtAuthConfig` — the same fields that would describe Okta or Entra. Pointing this at a
corporate IdP instead of the bundled phone app is a configuration change with no code behind
it. Today the issuer is [admin/phone](../../admin/phone/README.md), which authenticates the
user against the WebLogic realm before minting.

**The address comes from the token, not from the request.** The token names the one address
its holder may bind; a browser asking for a different one is refused rather than quietly
corrected. Checking only the signature would leave every signed-in employee able to register
as a colleague and take their calls — a hijack performed by a fully authenticated user.

**It fails closed.** No configuration loaded, or configuration that cannot be used (a blank
or unreachable `jwksUri`), refuses the browser and names the setting to fix. "We could not
read the rule" must never mean "there is no rule". The one open path is explicit:
`jwt.enabled = false` lets any browser claim any address, and when it is set the service
logs SEVERE at startup and reports `authenticated: false` in `session.ready` so the phone
shows it on screen rather than only in a log.

The shipped sample has `enabled = true` and a blank `jwksUri`, so a half-configured
deployment gets a gateway that does not work rather than one that works and is open.

## Location service — the gateway REGISTERs on the browser's behalf

`BrowserRegistry` is only the socket table: which browsers hold a live WebSocket on
*this node*. The network's location service is `proxy-registrar`, and browsers appear
in it because this gateway speaks SIP for them — the SBC arrangement. On a successful
`session.connect`, `BrowserRegistration` sends a real REGISTER (From = To = the AOR,
so the registrar files it under the address the browser claimed) whose contact is
**routable** — this engine's own SIP interface carrying the container's `encodeURI`
targeting parameters, bound to a long-lived per-browser application session:

```
Contact: <sip:alice@172.16.32.129:5060;transport=tcp;sipappsessionid=<prefix>:<callId>:webrtc;wlsscid=…>
```

That header is the whole inbound routing story. The registrar stores it verbatim and
forks an inbound INVITE with it as the Request-URI; the container recognizes its own
targeting parameters, hands the App Router `SipTargetedRequestInfo(ENCODED_URI,
"webrtc", …)`, and the FSMAR's targeted branch dispatches the fork into the
registration's session on this app **before the state machine runs**. No FSMAR
transition names this application for inbound calls — the REGISTER said everything,
which is what a registrar's contact is for. (The app-originated REGISTER itself still
consults the AR normally, so a `webrtc` state routing REGISTER to `proxy-registrar`
remains ordinary deployment routing.)

Because the contact names the registering engine, a fork in a cluster is *delivered
to the node holding the WebSocket* — the contact routes to the node, which is exactly
right for a socket that cannot replicate.

On socket close or error the gateway sends `Expires: 0`. A page reload never tears
down its successor's binding: the superseded socket's unregister returns null, and
the replacement's re-REGISTER finds the same by-key session, produces the identical
contact string, and refreshes the same binding. Registration is best-effort — a
REGISTER failure logs a WARNING and the browser keeps its session.

One call at a time per browser follows from the session-per-AOR shape: inbound
targeted calls land on the registration's session, so a second simultaneous INVITE
for the same AOR would collide with the first's continuations. A browser tab is a
one-call phone; this is the honest shape, not a limitation.

There is no refresh timer yet: browsers re-register on every reconnect, and a tab
left open longer than `registerExpiresSeconds` (settings, default 3600) simply ages
out of the location service until it reconnects — its socket, and browser-to-browser
calling, are unaffected. When the timer becomes worth building, the gateway service's
trunk registration (`services/gateway/RegisterCallflow`) is the proven pattern: arm
`startTimer` in the 2xx callback, resolve the session by a stashed id.

## Late media

An INVITE with no SDP is handled, and it is the case the prior art dropped. The media
server offers in the `200 OK` and the caller's answer is read out of the `ACK` —
`MediaCallflow.answerWithLateMedia`, on `SdpPortManager.generateSdpOffer()`. No new machinery
was needed: `Callflow.sendResponse` already delivers the ACK to a continuation.

## Deployment requirements

- **A reachable STUN or TURN server is mandatory, not optional.** Since Chrome 76 browsers
  publish `<uuid>.local` mDNS host candidates instead of private addresses; without a
  server-reflexive path there is frequently no routable candidate pair. This is the most
  likely cause of "works on a laptop, fails on OCI".
- **Set the media node's public address.** On OCI the instance cannot see its own public
  IP, so the provider must be given `external.ipv4` or it will advertise an unreachable
  candidate.
  Both apply only to anchored calls; a relayed browser-to-browser call needs neither.
- Media-plane settings are passed as servlet context parameters and handed to the JSR-309
  driver verbatim; see the JSR-309 media controller driver's documentation for the keys it
  understands (the media server's WebSocket URL, `stun.address`, `stun.port`, `turn.url`,
  `external.ipv4`, `network.interfaces`).
- **The driver jar has to be visible to this WAR.** Skinny-WAR policy keeps everything but
  the framework out of `WEB-INF/lib` and `DriverManager` only finds what the classloader can
  see, so the JSR-309 media controller driver jars must be deployed into the `blade-shared`
  shared library or the domain's `lib/` — the same deployment story as the driver behind
  `proto/player`. Without it the servlet logs "no JSR-309 driver registered" and says so
  plainly: browser-to-browser still works, PSTN and recording do not.

## Status

Built and unit-tested:

| Piece | State |
|---|---|
| `SignalProtocol` — the event vocabulary | done |
| `SignalEndpoint` — `@ServerEndpoint`, registration, event dispatch | done |
| `BrowserRegistry` — node-local socket table | done, 7 tests |
| `BrowserSignals` — browser event → callflow continuation, under the SAS lock | done |
| `InboundToBrowser` — network calls a browser, both SDP directions | done |
| `OutboundFromBrowser` — browser calls the network (3PCC) | done, 6 tests |
| Pass-through media in both callflows (browser↔browser via SIP, media P2P) | done |
| `MediaMode` — media policy, wired to `webrtc.json` | done, 4 tests |
| `call.update` renegotiation | done, 5 protocol tests |
| JSR-309 driver: `generateSdpOffer` / `processSdpAnswer` / WebRTC legs | done, 17 tests |
| `WebrtcServlet` — SIP entry, provider install, settings | done |
| `BrowserAuthenticator` — who may claim which address | done, 12 tests |
| `BrowserRegistration` — REGISTER/deregister on the browser's behalf | done, 5 tests |
| `MediaCallflow.generateOffer` / `answerWithLateMedia` | done |
| JSR-309 media controller WebRTC endpoint facade | done, 10 tests |

This WAR is Java only. It serves no page and ships no script: the browser side —
the client library and the softphone UI — is [admin/phone](../../admin/phone/README.md),
a separate static app on the admin tier that connects here at `/webrtc/signal`. The
split is the point. A gateway that served its own client would keep a second copy of
the protocol in step with this one, and would pin the UI to the engine tier. The UI is
a static page; it can be hosted anywhere.

**Not yet built** — real gaps, not polish:

1. **Cross-node delivery.** `BrowserRegistry` is node-local by necessity, but a browser's
   WebSocket and its inbound INVITE land on independently chosen engines. Today
   `InboundToBrowser` rejects with `480` when the browser is not on this node — correct,
   but it means the gateway only works end-to-end on a single engine. The fix is to fan
   browser-directed events over the existing `v3.events.EventBus` JMS topic and let the
   node holding the socket deliver. Outbound calls are unaffected, since the browser that
   originates is by definition on the node handling it.
2. **`call.dtmf` and `call.accept`** are declared and accepted but not yet acted on — DTMF
   needs an RFC 4733 or INFO path on the media leg.
3. **Driver configuration** is still read from servlet context parameters rather than from
   `WebrtcSettings` (`mediaMode`, `jwt` and `registerExpiresSeconds` *are* settings now;
   the 309 driver's own keys have yet to follow).
4. **Registration refresh timer** — see the Location service section: a tab open longer
   than `registerExpiresSeconds` ages out of the registrar until it reconnects.
5. **Recording escalation of a pass-through call.** `call.record` on a pass-through call
   currently does nothing — the `call.update` re-key implementation was retired with the
   WebSocket-relay path it was written for, and has to be rebuilt across the two SIP legs
   (each gateway leg re-offers its own browser from the media server). Until then,
   recording requires `mediaMode: anchor` from call start. Within an anchored call, the
   remaining media-server gap is unchanged: the driver records a single
   `NetworkConnection`, and mixing two parties needs the JSR-309 media controller's
   compositing hub (`createMediaMixer` still throws).

## Build

```bash
./blade/mvnw -f blade/proto/webrtc/pom.xml package
```

Skinny WAR: `WEB-INF/lib` carries only `vorpal-blade-library-framework.jar`. `javax.websocket`
comes from the inherited `javaee-api` (provided); WebLogic supplies Tyrus at runtime.

## Related modules

- [admin/phone](../../admin/phone/README.md) — the browser softphone that speaks this protocol, and mints the tokens it presents
- [proto/player](../player/README.md) — the vendor-neutral JSR-309 player, same driver deployment story
- [SECURITY.md](../../SECURITY.md) §2a — the token design in full
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-proto-webrtc</artifactId>
```
