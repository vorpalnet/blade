# proto/player — JSR-309 player / recorder

A BLADE service that **answers an inbound call, anchors its media on a media server, and plays a
prompt or music** to the caller (optionally recording the caller). The obvious partner for the
`gateway` app: a PSTN DID rings in → FSMAR routes it here → the caller hears audio.

## The point: vendor-neutral JSR-309

This app speaks **only `javax.media.mscontrol.*`** — the standard JSR-309 media API — via the
framework's lambda media verbs ([`MediaCallflow`](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/media/MediaCallflow.java)).
It has **no idea what media server is behind it**. At startup it asks the JSR-309 SPI
(`ServiceLoader` over `javax.media.mscontrol.spi.Driver`) for a driver by name (or the sole
registered one) and installs its factory on `MediaCallflow`. Nothing here is tied to any particular
media server — that lives entirely behind the driver.

In the Vorpal deployment the driver is a **JSR-309 media controller driver**, deployed
alongside as a runtime artifact. The app has **zero compile dependency on the driver** —
swap the driver, swap the media server.

## The callflow

`PlayerCallflow` (extends `MediaCallflow`) reads top-to-bottom:

```
offer(nc, callerSdp, answer -> {          // feed caller SDP to the media server
    sendResponse(200 with answer);        //   → its answer goes in our 200 OK
    on ACK:
        join(mediaGroup, DUPLEX, nc);      // wire player/recorder to the caller dialog
        record(mediaGroup, recordUri, …);  // optional: record the caller
        play(mediaGroup, mediaUri, done -> // play the prompt/music
            loop ? play again : hang up);
});
```

`PlayerServlet` bootstraps the 309 factory in `servletCreated` and dispatches INVITE → `PlayerCallflow`,
BYE/CANCEL → `PlayerBye` (releases the media anchor), INFO → `PlayerInfo` (DTMF). Live media objects
are held in a node-local registry (they aren't serializable); failover rebuilds rather than migrates
the anchor.

### Conference mode

With `conference=true` the same app is an N-party audio bridge: callers to the same dialed user
(`sip:daily@…` → room `daily`) share one `MediaMixer`:

```
offer(nc, callerSdp, answer -> {          // the leg is created on the ROOM's media session
    sendResponse(200 with answer);
    on ACK:
        join(nc, DUPLEX, room.mixer);      // into the mix; N-1 mixing, nobody hears themself
});
```

A `Room` (node-local, like the anchor registry) holds the shared `MediaSession` and mixer; the first
caller opens it, the last BYE closes it. Because the legs share one session, each is bound to its own
caller with `MediaCallflow.bindMediaObject` so its SDP answer continues under the right app session.
Two callers to one room on different cluster nodes get two rooms — steering a room's calls to one
node is routing, not this app.

## Config (`PlayerSettings`)

- `driverName` — 309 driver to use (blank = the sole registered driver).
- `driverProperties` — passed verbatim to the driver (driver-specific keys, e.g. the media server's WebSocket URL).
- `mediaUri` — what to play (any URI the media server can fetch: `file://`, `http://`, `rtsp://`).
  Music, or a TTS prompt pre-rendered to a file.
- `loop` — replay on completion (music-on-answer) vs. play once then hang up.
- `record` / `recordUri` — record the caller's audio.
- `conference` — conference mode (above); `mediaUri` / `loop` / `record` do not apply.

## Status

- **Built:** config model, the `offer → join → play/record` callflow, DTMF over INFO, conference
  mode, BYE teardown, vendor-neutral SPI bootstrap. `PlayerConfigTest` **2/2**; skinny WAR
  (`player.war`, framework jar + the driver jars).
- **Deploy-time (OCCAS + a JSR-309 media controller):** the media path itself — SDP anchor, playback,
  recording, the mix — needs a live media server and is verified there.

## Build / test

```bash
./mvnw -pl proto/player -o test       # config unit tests (framework 3.0.4 installed)
./mvnw -pl proto/player -o package    # skinny WAR: target/player.war
```
Registered via the `!skip.player` profile (root pom → `proto/player`); a proto app, so excluded from
the everyday `default`/`production` builds until promoted.
