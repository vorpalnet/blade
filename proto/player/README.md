# proto/player — JSR-309 player / recorder

A BLADE service that **answers an inbound call, anchors its media on a media server, and plays a
prompt or music** to the caller (optionally recording the caller). The obvious partner for the
`gateway` app: a PSTN DID rings in → FSMAR routes it here → the caller hears audio.

## The point: vendor-neutral JSR-309

This app speaks **only `javax.media.mscontrol.*`** — the standard JSR-309 media API — via the
framework's lambda media verbs ([`MediaCallflow`](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/media/MediaCallflow.java)).
It has **no idea what media server is behind it**. At startup it asks the JSR-309 SPI
(`DriverManager`) for a driver by name (or the sole registered one) and installs its factory on
`MediaCallflow`. Nothing here is tied to any particular media server — that lives entirely behind the driver.

In the Vorpal deployment the driver is a **JSR-309 media controller driver**, deployed
alongside as a runtime artifact. The app has **zero compile dependency on the driver** —
swap the driver, swap the media server.

## The callflow

`PlayerCallflow` (extends `MediaCallflow`) reads top-to-bottom:

```
offer(nc, callerSdp, answer -> {          // feed caller SDP to the media server
    sendResponse(200 with answer);        //   → its answer goes in our 200 OK
    on ACK:
        join(mediaGroup, DUPLEX, nc);      // wire player/recorder to the caller leg
        record(mediaGroup, recordUri, …);  // optional: record the caller
        play(mediaGroup, mediaUri, done -> // play the prompt/music
            loop ? play again : hang up);
});
```

`PlayerServlet` bootstraps the 309 factory in `servletCreated` and dispatches INVITE → `PlayerCallflow`,
BYE/CANCEL → `PlayerBye` (releases the media anchor). Live media objects are held in a node-local
registry (they aren't serializable); failover rebuilds rather than migrates the anchor.

## Config (`PlayerSettings`)

- `driverName` — 309 driver to use (blank = the sole registered driver).
- `driverProperties` — passed verbatim to the driver (driver-specific keys, e.g. the media server's WebSocket URL).
- `mediaUri` — what to play (any URI the media server can fetch: `file://`, `http://`, `rtsp://`).
  Music, or a TTS prompt pre-rendered to a file.
- `loop` — replay on completion (music-on-answer) vs. play once then hang up.
- `record` / `recordUri` — record the caller's audio.

## Status

- **Built:** config model, the `offer → join → play/record` callflow, BYE teardown, vendor-neutral SPI
  bootstrap. `PlayerConfigTest` **2/2**; skinny WAR (`player.war`, framework jar only).
- **Deploy-time (OCCAS + a JSR-309 media controller):** the media path itself — SDP anchor, playback,
  recording — needs a live media server and is verified there.
- **Not yet:** DTMF collect (`prompt`) and conference (`MediaMixer`) — the driver throws for those.

## Build / test

```bash
./mvnw -pl proto/player -o test       # config unit tests (framework 3.0.4 installed)
./mvnw -pl proto/player -o package    # skinny WAR: target/player.war
```
Registered via the `!skip.player` profile (root pom → `proto/player`); a proto app, so excluded from
the everyday `default`/`production` builds until promoted.
