/// A vendor-neutral **JSR-309 player/recorder** BLADE service.
///
/// [org.vorpal.blade.services.player.PlayerServlet] answers an inbound call, and
/// [org.vorpal.blade.services.player.PlayerCallflow] anchors the call's media on a 309 media server
/// and plays a prompt/music (optionally recording the caller) using the framework's lambda media verbs
/// ([org.vorpal.blade.framework.v3.media.MediaCallflow]): `offer` → `join` → `play`/`record`.
///
/// The app speaks only `javax.media.mscontrol.*`; it obtains its [javax.media.mscontrol.MsControlFactory]
/// from whichever 309 driver is registered (via the SPI [javax.media.mscontrol.spi.DriverManager]), so
/// nothing here is tied to a particular media server. In the Vorpal deployment that driver is the
/// private Gryphon Kurento driver — but this app would run unchanged on any 309 driver.
package org.vorpal.blade.services.player;
