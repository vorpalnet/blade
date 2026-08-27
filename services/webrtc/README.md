# BLADE WebRTC Gateway

## Browser-to-SIP communication

The WebRTC Gateway lets a web browser take part in an ordinary phone call. On the browser
side it speaks a simple JSON protocol over a WebSocket; on the network side it speaks
standard SIP signaling. The gateway sits in the middle and translates between the two. When
two browsers call each other, their audio flows directly between them and no media server is
needed — deploy the gateway on its own and browser-to-browser calling works. A media server
is brought in only when a call reaches a traditional phone, or when something downstream
(recording, transcription, and the like) needs access to the audio.

```
browser  ⇄  [ WebRTC Gateway ]  ⇄  phone network
```

## Why this matters

Oracle offered browser calling once before, as WebRTC Session Controller, and later
discontinued it. The BLADE gateway brings that capability back on OCCAS, without the three
things that made the original hard to live with:

- **No custom scripting.** With WSC, every deployment had to hand-write and maintain the
  code that translated between the browser and SIP. Here that translation is built into the
  gateway — written and tested once, the same for everyone.
- **No locked-in media server.** WSC handed the media plane to the ASC, an end-of-life
  product that took copious, poorly-documented configuration to get working at all. This
  gateway reaches the media server through a vendor-neutral interface instead — the choice
  of media server stays open, and no vendor is named in the product.
- **A current browser client.** The old client was written against browser features that no
  longer exist. The BLADE softphone is built to today's standards.

## Every call is a real SIP call

The signaling always runs over SIP, even when both parties are browsers. A
browser-to-browser call goes through the same routing, logging, and analytics as any call
between two phones. An operator monitors and manages these calls with the tools they already
have — the browser on one end changes nothing about how the call is handled on the network.

What varies from one call to the next is the path the audio takes. One setting, `mediaMode`
in `webrtc.json`, controls it:

- **Relay** — the two browsers send audio straight to each other, with no media server in
  the middle. This is all a browser-to-browser call needs.
- **Anchor** — the audio passes through a media server. Required when one end is a regular
  phone, which cannot handle the browser's encrypted media, or when a system downstream
  needs to reach the audio for recording, conferencing, or transcription.
- **Auto** — the default. The gateway relays when both ends are browsers and anchors when a
  phone is on the call, deciding for each call on its own.

## Relayed calls are private end to end

When two browsers relay audio directly to each other, the call is encrypted the whole way
and no one in the middle — not even the gateway — can listen in. The two browsers perform
their DTLS handshake directly with each other and derive their SRTP keys from a master
secret that never appears on the signaling path; RFC 8827 is designed to keep it that way.
The gateway only ever forwards their fingerprints, so it holds nothing that could decrypt a
single packet.

The same is true for anything downstream. Recording, transcription, and monitoring each
belong to a separate service, and none of them can reach a relayed call either, because the
audio never leaves the two browsers. Anchoring the call on a media server is the only way to
put its audio somewhere another system can use — which is why the decision lives here even
though those features do not.

A relayed call can still be moved onto a media server after the fact, with a re-INVITE: the
gateway forwards the new offer to the browser and relays its answer back. That switch costs
an ICE restart and a fresh DTLS handshake, so the callers hear a brief gap. A deployment
that needs a call's audio available without that interruption sets `mediaMode` to anchor
from the start and accepts the cost of a media server on every call. That trade-off is the
whole reason the setting exists.

## Re-INVITEs are handled on both media paths

During a call, the far end often sends a new offer — to place the call on hold, take it off
hold, refresh the session, or switch codecs. The gateway watches for these from the moment
the call is answered and keeps watching for the life of the call, since they can arrive more
than once.

How it responds depends on which media path the call is using:

- **Anchored** — the new offer concerns only the network dialog, between the far end and the
  media server. The media server answers it directly and the browser is never involved,
  because those two sides were negotiated independently to begin with. If the refresh
  carries no SDP, the media server supplies the answer and reads the far end's reply from
  the ACK.
- **Relayed** — here the browser owns both ends of the media, so the new offer is passed to
  it as a `call.update` message and the browser's answer becomes the `200 OK`. This is also
  how a far-side escalation, such as an upgrade to video, reaches the browser. One case is
  refused: a re-INVITE with no SDP asks the gateway to produce an offer on its own, and in a
  relayed call there is no media server to build one from — a browser cannot be told to
  offer on demand — so the gateway rejects it with a `488`.

## A caller who hangs up while ringing

If the caller gives up while the browser is still ringing, the call arrives as a SIP CANCEL.
The gateway stops the ringing, releases any media, notifies the browser, and records the
call as abandoned — but it deliberately sends no response of its own. The container handles
that transaction itself: it answers the CANCEL and terminates the original INVITE with a
`487`. Were the application to answer as well, a second final response would go out on the
wire, so the framework suppresses the duplicate.

## DTMF travels over signaling, not audio

When the browser sends a digit, the gateway turns it into a SIP `INFO` message carrying
`application/dtmf-relay` and sends it to the far end. It does not inject a tone into the
audio stream — none of the JSR-309 media drivers behind the framework generate tones, so
that route isn't available. Carrying the digit over signaling has a useful side effect,
though: DTMF works identically on both media paths. A relayed call has no media session to
inject a tone into anyway, and the INFO rides the SIP dialog either way.

## Why not SIP over WebSocket?

RFC 7118 defines SIP over WebSocket, and it works — but OCCAS does not implement it. The
JSR-359 interfaces are present in the API jar, yet the container behind them offers no
WebSocket transport; its only network channels are `sip` and `sips`. A browser therefore
cannot register as a SIP user agent against this server, no matter how the application is
written.

That constraint happens to match the design we would have chosen anyway. A browser that only
needs to answer "a call is arriving — do you want it?" has no reason to manage SIP dialogs,
Via headers, registration refreshes, or transaction state. Keeping all of that on the
server, and giving the browser a simple JSON protocol, is the cleaner split.

## The protocol

The browser and the gateway talk in CloudEvents 1.0 envelopes over a single WebSocket, using
the subprotocol `blade.webrtc.v1`. There are fourteen event types, all defined in
`SignalProtocol`:

| browser → gateway | gateway → browser |
|---|---|
| `session.connect` (`aor` + `token`) | `session.ready` |
| `call.offer` | `call.incoming` |
| `call.answer` | `call.progress` |
| `call.hangup` | `call.established` |
| `call.dtmf` | `call.connected` |
| `ice.candidate` (both directions) | `call.ended` |
| | `call.update` |
| | `signal.error` |

Every type is named `scope.verb`, in lower case. `signal.error` is the exception to the two
scopes, because an error can belong either to a call or to the session as a whole; its
`subject` field says which — set to a call when the error is about a call, absent when it
concerns the socket itself.

**Two verbs were defined early and then deliberately removed.** `call.accept` was meant to
accept a call without providing SDP, but there is no SIP message it could honestly produce —
both answer paths build the `200 OK` from an SDP that only the browser has, and `call.answer`
does the job properly a step later. `call.record` was a browser asking the gateway to record
the call, but recording belongs to a separate service; the only thing the gateway decides on
its behalf is whether the call's media is anchored at all, and that is the `mediaMode`
setting, not an event.

**Answered and connected are two separate events, because SIP answers a call in three
messages, not two.** `call.established` corresponds to the `200 OK`; `call.connected`
corresponds to the `ACK` that completes the handshake, and never arrives before it. The
split follows the framework's own call lifecycle (`CALL_ANSWERED` then `CALL_CONNECTED`), and
it exists because that third message carries information worth surfacing. `call.connected`
reports a `negotiated` flag, which is false when the far end answered without SDP and nothing
was applied to the media dialog — a call that is up for signaling but silent for media. Reported
as a plain `call.established`, as it was before, such a call looked perfectly healthy.

`call.connected` also declares an `sdp` field that is reserved and currently unset on every
path. The ACK can legitimately carry SDP — in late media, the caller's answer arrives there —
so the field is declared now to describe the message honestly and leave room for that case.
Clients should tolerate its absence, which is the normal situation today.

`call.update` is what makes mid-call escalation possible: a fresh SDP offer for a call that
is already established. Without it, a call's media path could never change after setup. It is
the same mechanism WSC's mobile SDKs exposed as `Call.update()` for upgrading audio to video,
and BLADE already has the SIP equivalent in its B2BUA re-INVITE and hold callflows.

**ICE candidates are not trickled; the SDP is complete in both directions.** A browser sends
its entire offer or answer once candidate gathering has finished, and the gateway forwards
the SDP whole. On the anchored path this is because the media server does not accept trickled
candidates; on the relayed path it is because the SDP crosses a SIP network whose forks and
B2BUA hops have nowhere to carry mid-call candidates. Sending complete SDP also removes a
class of ordering bugs from the client. (The `ice.candidate` type remains in the protocol —
the gateway may trickle its own candidates toward the browser on anchored calls.)

**On an anchored outbound call, the browser is answered while the far end is still ringing**,
not at pickup. `call.progress` carries the gateway's SDP answer, so the browser has a working
media path during alerting; waiting until `call.established` would mean no ringback tone and
no early media from the carrier. On the relayed path there is no local answer to give early,
so the far end's SDP is forwarded as it arrives — on `call.progress` when an 18x response
carries it, otherwise on `call.established` — and only once, because a browser cannot apply a
second answer.

## Authenticating a browser

A WebSocket arriving at the gateway says nothing on its own about who is on the other end. It
comes from a page served by a different server on a different port, so no admin session
cookie reaches this tier, and the browser's WebSocket API cannot attach an `Authorization`
header to the handshake. Left unaddressed, "who are you?" would be answered by whatever the
client claimed — which is how a gateway ends up placing calls for anyone who can reach its
port.

So `session.connect` carries a signed token alongside the address it wants to claim, and
`BrowserAuthenticator` decides whether to honor it. The configuration lives in
`WebrtcSettings.jwt` (in `webrtc.json`) and is an ordinary `JwtAuthConfig` — the same fields
that would describe Okta or Entra. Pointing the gateway at a corporate identity provider
instead of the bundled phone app is a configuration change with no code behind it. Today the
token issuer is [admin/phone](../../admin/phone/README.md), which authenticates the user
against the WebLogic realm before minting one.

**The address comes from the token, not from the request.** The token names the single
address its holder is allowed to bind; a browser asking for any other address is refused, not
quietly corrected. Checking only the signature would let any signed-in employee register as a
colleague and take their calls — a hijack carried out by a fully authenticated user.

**Authentication fails closed.** If no configuration is loaded, or the configuration cannot
be used — a blank or unreachable `jwksUri` — the browser is refused and the log names the
setting to fix. "We could not read the rule" must never be treated as "there is no rule." The
one way to open the gate is explicit: setting `jwt.enabled = false` lets any browser claim
any address, and when it is set, the service logs a SEVERE message at startup and reports
`authenticated: false` in `session.ready`, so the phone shows the open state on screen rather
than burying it in a log.

The shipped sample sets `enabled = true` with a blank `jwksUri` on purpose: a half-configured
deployment gets a gateway that refuses to work, rather than one that works and is wide open.

## The gateway REGISTERs on the browser's behalf

`BrowserRegistry` is just the socket table — a record of which browsers currently hold a live
WebSocket on *this particular node*. The network's actual location service is
`proxy-registrar`, and browsers appear in it because the gateway speaks SIP on their behalf,
the same arrangement a session border controller uses. On a successful `session.connect`,
`BrowserRegistration` sends a genuine REGISTER (with From and To both set to the AOR, so the
registrar files it under the address the browser claimed). The contact it registers is
**routable** — this engine's own SIP interface, carrying the container's targeting
parameters, bound to a long-lived application session for that browser:

```
Contact: <sip:alice@…:5060;transport=tcp;sipappsessionid=<prefix>:<callId>:webrtc;wlsscid=…>
```

That contact header is the entire inbound routing story. The registrar stores it verbatim
and, when a call arrives, forks an INVITE with it as the Request-URI. The container recognizes
its own targeting parameters and delivers the call straight into the registration's session,
before the application router's state machine ever runs. **No FSMAR rule needs to name this
application for inbound calls** — the REGISTER already said everything, which is exactly what a
registrar's contact is for. (The outbound REGISTER itself routes normally, so directing a
`webrtc` state's REGISTER to `proxy-registrar` is ordinary deployment routing.)

Because the contact names the specific engine that registered it, a call forked in a cluster
is delivered to the node that holds the WebSocket. This is exactly right for a socket, which
cannot be replicated to other nodes. `BrowserRegistry` being node-local is therefore not a
clustering limitation, and nothing needs to be fanned out: the call, its dialog, and its
media all arrive where the socket already is. When `InboundToBrowser` answers `480` because
the socket is not on this node, it means the binding is stale — the browser has disconnected
and not yet re-registered from wherever it reconnected — and until it does, it is unreachable
from every engine, not only this one.

When a socket closes or errors, the gateway sends `Expires: 0` to remove the binding. A page
reload never tears down its replacement's binding: the old socket's unregister finds nothing
to remove, and the new socket's REGISTER lands on the same keyed session, produces the
identical contact, and refreshes the same binding. Registration is best-effort — a failed
REGISTER logs a warning and the browser keeps its session regardless.

A browser handles one call at a time, which follows naturally from the one-session-per-address
design: inbound calls land on the registration's session, so a second simultaneous call to
the same address would collide with the first. A browser tab is a single-line phone — the
honest shape of the thing, not a restriction bolted on.

Because a registration eventually lapses, a timer on the session re-registers shortly before
it does — at the expiry the registrar actually **granted**, not the one originally requested,
since a registrar is free to shorten a binding and refreshing on the requested value could let
it expire early. The timer stops when the socket does: if the browser is no longer connected
to this node, the refresh cancels itself rather than reasserting a contact that names an
engine which can no longer deliver the call. Letting the binding lapse is the honest outcome;
when the browser returns, it registers again from wherever it lands.

## Late media

An INVITE that arrives with no SDP is handled — the case the earlier product dropped. On this
kind of call the offer and answer run in the opposite order on the network dialog: the media
server puts its offer in the `200 OK`, and the caller's answer is read back out of the `ACK`.

This is the one path where `call.established` and `call.connected` are genuinely far apart in
time, and the only one where the ACK can arrive still owing an answer that it does not
actually carry — which is what `negotiated: false` reports.

**Late media always anchors, and cannot work without a media server.** Having the browser
make the offer instead was considered and rejected: late media is a pattern browsers never
originate, so the caller is always a phone or a trunk, and the answer that comes back in the
`ACK` is plain RTP with no DTLS fingerprint — which a browser will not set up media against.
On a call of this shape, the media server is the only party present that can speak to both
ends. If no media driver is installed, the gateway refuses the call with a `503` and names
the missing driver in the log.

## What a call reports to analytics

A WebRTC call used to be invisible to analytics. The servlet extends `AsyncSipServlet` rather
than `B2buaServlet`, so none of the framework's usual call publishers ran for it, and a
browser call left no trace in any report.

It now publishes the framework's own six call facts — `callStarted`, `callAnswered`,
`callConnected`, `callCompleted`, `callAbandoned`, and `callDeclined` — which
`BladeEventTypes.forEventName` maps onto the canonical `org.vorpal.blade.call.*` event types.
Reusing the existing facts is the whole point: a single subscription picks up browser calls
and phone calls together, with no webrtc-specific special case, and the payload has
`AnalyticsEventMapper`'s shape rather than a second dialect that merely resembles it.

Two things are worth knowing before reading a report:

- **Every fact carries a `dialog` attribute**, either `inbound` or `outbound`. A
  browser-to-browser call is a single call that crosses this application twice — outbound
  through `OutboundFromBrowser`, then back inbound through `InboundToBrowser` by way of the
  location service — and the second dialog inherits the first's `X-Vorpal-ID` correlation id.
  Both dialogs are published under the same correlator, source, and application name, so `dialog` is
  the only attribute that tells them apart.
- **`analytics.enabled` is the switch, not `events.enabled`.** Turning on `events.enabled`
  alone gives a live bus connection that carries no call facts. The shipped sample fills in
  the selectors but leaves the switch off, the same way every other BLADE application samples
  itself.

The browser signaling protocol is a separate channel and keeps its own short, imperative
names. Five of them are commands the client sends — `session.connect`, `call.offer`,
`call.answer`, `call.hangup`, `call.dtmf` — whereas the analytics bus deliberately names facts
rather than commands, so a reverse-DNS name on an imperative would describe it wrongly.
`BladeEventTypes.forEventName` is the sanctioned bridge between the two conventions.

## Deployment requirements

- **A reachable STUN or TURN server is mandatory, not optional.** Since Chrome 76, browsers
  publish `<uuid>.local` mDNS candidates instead of their private addresses, so without a
  server-reflexive path there is often no routable candidate pair at all. This is the most
  likely cause of a gateway that "works on a laptop but fails on OCI."
- **Set the media node's public address.** On OCI an instance cannot see its own public IP,
  so the media provider must be given `external.ipv4` explicitly, or it will advertise a
  candidate no one can reach. Both of these requirements apply only to anchored calls — a
  relayed browser-to-browser call needs neither.
- **Media-plane settings live in `webrtc.json` under `driverProperties`** and are passed to
  the JSR-309 driver verbatim; see the driver's own documentation for the keys it accepts
  (the media server's WebSocket URL, `stun.address`, `stun.port`, `turn.url`, `external.ipv4`,
  `network.interfaces`). `driverName` selects among drivers when more than one is installed,
  and can be left blank in the usual single-driver case. These are read **once, at
  deployment** — the media factory is built at startup and is not rebuilt when configuration
  is republished, so a change here requires a redeploy.
- **The driver jar must be visible to this WAR.** Skinny-WAR policy keeps everything but the
  framework out of `WEB-INF/lib`, and `DriverManager` only finds what the classloader can see,
  so the JSR-309 driver jars have to be deployed into the `blade-shared` shared library or the
  domain's `lib/` directory — the same deployment story as the driver behind `proto/player`.
  Without it, the servlet logs "no JSR-309 driver registered": browser-to-browser calls still
  work, but calls to phones do not.

## The browser client is a separate app

This WAR is Java only — it serves no page and ships no script. The browser side, both the
client library and the softphone UI, is [admin/phone](../../admin/phone/README.md): a separate
static app on the admin tier that connects to this gateway at `/webrtc/signal`. Keeping them
apart is deliberate. A gateway that served its own client would carry a second copy of the
protocol to keep in sync, and would tie the UI to the engine tier. As a static page, the
softphone can be hosted anywhere.

The bundled phone app is one worked example of a WebRTC client, not the only way to build one.
The protocol is plain JSON over a WebSocket, so a customer can build their own client against
it — in JavaScript for the browser, or natively for iOS or Android.

## Build

```bash
./blade/mvnw -f blade/services/webrtc/pom.xml package
```

Skinny WAR: `WEB-INF/lib` carries only `vorpal-blade-library-framework.jar`. `javax.websocket`
comes from the inherited `javaee-api` (provided); WebLogic supplies Tyrus at runtime.

## Related modules

- [admin/phone](../../admin/phone/README.md) — the browser softphone that speaks this protocol, and mints the tokens it presents
- [proto/player](../../proto/player/README.md) — the vendor-neutral JSR-309 player, same driver deployment story
- [SECURITY.md](../../SECURITY.md) §2a — the token design in full
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-webrtc</artifactId>
```
