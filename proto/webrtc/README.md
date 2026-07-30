# BLADE WebRTC Gateway (proto)

Puts a browser on one end of a SIP call. JSON events over a WebSocket to the browser,
ordinary SIP to the network. Two browsers call each other with no media server at all; a
media server joins when the call reaches the PSTN, or when someone hits record.

```
browser ──wss://──> [ blade-webrtc ] ──SIP──> network
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

## Media is optional

Two browsers calling each other need **no media server at all**. Their SDP is relayed verbatim,
candidates are forwarded both ways, and the media flows peer-to-peer. Deploy this WAR on its
own and browser-to-browser works.

A media server becomes necessary the moment either of two things is true:

- **One end is not a browser.** A phone cannot speak ICE or DTLS-SRTP, so browser↔PSTN is
  always anchored.
- **Somebody wants the media.** Recording, conferencing, transcription, scoring, intercept.

`MediaMode` makes that a per-deployment policy (`auto` / `relay` / `anchor`) rather than a
code path, mirroring `session.passthru` in `v3/Callflow.java:49`, where the same callflow
runs as a dropped-out proxy or a full B2BUA depending on config.

## Recording a peer-to-peer call is a re-key, not a tap

This is the constraint that shapes everything above. In a relayed call the two browsers
complete a DTLS handshake **directly with each other** and derive their SRTP keys from a
master secret the signaling path never sees — RFC 8827 is built so it cannot. The gateway
forwarded fingerprints and nothing more, so it cannot decrypt a single packet.

So pressing record does not attach a listener. It re-offers **both** legs from the media
server (`call.update`), each browser answers, and the media server becomes a legitimate DTLS
endpoint on each — at which point it can mix, record and transcribe. Each leg does an ICE
restart and a fresh handshake, so there is a brief audible gap.

A deployment that would rather never have that gap sets `mediaMode: anchor` and pays for a
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
| `session.connect` | `session.ready` |
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

**ICE direction depends on the mode.** In a *relayed* call both ends trickle: both are
browsers, both support it natively, and there is no server in the middle whose gathering
anyone is waiting on. In an *anchored* call the gateway trickles to the browser but the
browser sends one complete offer or answer, because browsers only send candidates
incrementally when told the far side can take them, and the media server is not told so. The
gateway signals which applies per call with a `trickle` flag on `call.incoming` /
`call.progress`.

**Outbound calls answer the browser during alerting, not at pickup.** `call.progress`
carries the gateway's SDP answer, so the browser has a media path while the far end is
still ringing. Deferring it to `call.established` would mean no ringback and no carrier
early media — the call would be silent until the moment it connected.

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
  driver verbatim; see `GryphonDriver` for the keys the Kurento plane understands
  (`kurento.ws.url`, `stun.address`, `stun.port`, `turn.url`, `external.ipv4`,
  `network.interfaces`).
- **The driver jar has to be visible to this WAR.** Skinny-WAR policy keeps everything but
  the framework out of `WEB-INF/lib` and `DriverManager` only finds what the classloader can
  see, so `gryphon-jsr309` (and `gryphon-media`) must be deployed into the `blade-shared`
  shared library or the domain's `lib/` — the same deployment story as the driver behind
  `proto/player`. Without it the servlet logs "no JSR-309 driver registered" and says so
  plainly: browser-to-browser still works, PSTN and recording do not.

## Status

Built and unit-tested:

| Piece | State |
|---|---|
| `SignalProtocol` — the event vocabulary | done |
| `SignalEndpoint` — `@ServerEndpoint`, registration, event dispatch | done |
| `BrowserRegistry` — node-local socket bindings | done, 7 tests |
| `BrowserSignals` — browser event → callflow continuation, under the SAS lock | done |
| `InboundToBrowser` — network calls a browser, both SDP directions | done |
| `OutboundFromBrowser` — browser calls the network (3PCC) | done, 6 tests |
| `BrowserToBrowser` — peer-to-peer relay + escalation to recording | done |
| `MediaMode` — relay/anchor policy | done, 3 tests |
| `call.update` renegotiation | done, 5 protocol tests |
| JSR-309 driver: `generateSdpOffer` / `processSdpAnswer` / WebRTC legs | done, 17 tests |
| `WebrtcServlet` — SIP entry, provider install | done |
| `blade-webrtc.js` — browser client | done, syntax-checked |
| `phone.html` — softphone page | done |
| `MediaCallflow.generateOffer` / `answerWithLateMedia` | done |
| Kurento `WebRtcEndpoint` facade (`gryphon-media`) | done, 10 tests |

The page is served from the WAR itself: **`/blade/webrtc/phone.html`**. It defaults the
gateway URL to this host, so registering and dialling needs no configuration beyond a STUN
server.

**Not yet built** — real gaps, not polish:

1. **Cross-node delivery.** `BrowserRegistry` is node-local by necessity, but a browser's
   WebSocket and its inbound INVITE land on independently chosen engines. Today
   `InboundToBrowser` rejects with `480` when the browser is not on this node — correct,
   but it means the gateway only works end-to-end on a single engine. The fix is to fan
   browser-directed events over the existing `v3.events.EventBus` JMS topic and let the
   node holding the socket deliver. Outbound calls are unaffected, since the browser that
   originates is by definition on the node handling it.
2. **Authentication.** `session.connect` claims an address with no credential check. Any
   connected browser can claim any address, including one already in use.
3. **`call.dtmf` and `call.accept`** are declared and accepted but not yet acted on — DTMF
   needs an RFC 4733 or INFO path on the media leg.
4. **No `.jschema` settings file.** Media-plane configuration is read from servlet context
   parameters rather than a BLADE settings object, so it is not editable from the
   Configurator, and `MediaMode` is not yet wired to a config key.
5. **Recording captures one leg, not the mix.** Escalation now completes: both legs are
   re-offered, answered, and joined (`GryphonNetworkConnection.join` wires the Kurento
   `connect` in each direction). But `GryphonRecorder` connects a single
   `NetworkConnection` to its `RecorderEndpoint`, and `GryphonMediaSession.createMediaMixer`
   still throws — so a two-party recording gets one side of the conversation. Mixing needs
   the Kurento `Composite` hub, which is the largest single item left. Everything up to and
   including "the media server is now in the media path of both browsers" works.

## Build

```bash
./blade/mvnw -f blade/proto/webrtc/pom.xml package
```

Skinny WAR: `WEB-INF/lib` carries only `vorpal-blade-library-framework.jar`. `javax.websocket`
comes from the inherited `javaee-api` (provided); WebLogic supplies Tyrus at runtime.
