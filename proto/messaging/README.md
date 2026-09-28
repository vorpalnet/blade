# Messaging

Chat rooms over SIP MESSAGE (RFC 3428). A room is a SIP address, `sip:<room>@<domain>`. A MESSAGE
to it is a post; the room numbers it, stores it, and sends it to the other members as MESSAGEs of
its own. A meeting's chat is the room named for the meeting, so chat lives beside the meeting, not
inside it.

Because every post and every delivery is an ordinary SIP request, chat goes where calls go: through
the App Router, past the registrar, visible to the same tracing and routing as any call. A browser
reaches it through the WebRTC gateway; a SIP endpoint that speaks MESSAGE reaches it directly.

## What the room vouches for

The body is the sender's. The room stores it with its Content-Type and reads it only for its text,
when it knows the format (below); browsers put a `message/cpim` (RFC 3862) wrapper around plain
text. Nothing in the body is taken for identity. What the room vouches for rides
in headers it alone sets on every delivered MESSAGE: the room, the sender's address, the sender's
name, the room's sequence number and the time it accepted the post (`X-Chat-Room`, `X-Chat-From`,
`X-Chat-Name`, `X-Chat-Seq`, `X-Chat-Time`, and `X-Chat-To` on a private message).

The sender is the network's word, never the body's: the `P-Asserted-Identity` from a trusted hop
(the gateway sets it from the signed-in browser), else the From. Only a member may post.

## Formats: sent as posted, or rewritten for the member

The room reads the formats it knows, `message/cpim` and `text/plain`, into the message's text, and
writes either back out. A member that takes the sender's format, or has not said what it takes, is
sent the body as posted. A member that takes only another format the room writes gets the message
rewritten into it, with the envelope from the room: a CPIM body written by the room names the
sender the network asserted. A format the room does not know is stored and relayed as posted, only
to members that take it.

A member's formats come from two places. The application that adds it to the room may declare them
(`accept` on the membership event; meetings declares CPIM, then plain text). A member nobody spoke
for tells the room itself: RFC 3428 has a receiver that cannot take a body answer `415` with an
`Accept` list, and the room keeps that list for the address and sends the message again in a format
from it, once.

## Who is in a room

The application that owns the room says so. A meeting publishes an `org.vorpal.blade.messaging.member`
event on the event bus when it lets a participant in and when they leave. Admission, not answer, so
a guest in the waiting room can neither read nor post. The room applies what it is told and keeps
no second list of its own.

A newcomer is sent the room's newest stored messages (`replayLimit`, 200 by default). A page drops a
sequence number it already has, so a page that rejoins after a lost connection catches up with no
separate history call.

## Where messages are stored

One object per message, named by its sequence, the way a live transcript is stored: an archive that
refuses to rewrite an object still takes a conversation that grows. The store is found at runtime
through the framework's `MessageArchive`. The Gryphon overlay (`gryphon/messaging-node`) ships the
OCI implementation, which writes `messages/<room>/<sequence>.json` in the recordings bucket. With no
archive installed the service relays live, stores nothing, sends a newcomer no history, and says so
once in the log.

A room's in-memory state expires after `roomExpiresMinutes` of quiet. Its messages stay, and a room
opened again continues their numbering.

## Deploying

`messaging.war` (context root `messaging`) on the engine tier, with the event bus on in
`messaging.json` (`events.enabled`, on in the sample), since membership arrives on it. The browser
side needs the WebRTC gateway, whose own bus must be on as well.

Two App Router routes, which are yours to write:

- an initial MESSAGE to a room's address goes to `messaging`;
- a MESSAGE that `messaging` originates to a member's address goes to `proxy-registrar`, which forks
  it to the member's registered devices (a browser's device is the gateway).

## Build

```
./mvnw -pl proto/messaging -am package                               # messaging.war, no store
./blade/mvnw -f gryphon/pom.xml -pl messaging-node -am package       # with the OCI store (from the workspace root)
```
