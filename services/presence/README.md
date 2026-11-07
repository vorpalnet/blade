# Presence Service

Javadocs: `/blade/javadoc/presence/` on the Admin Portal

Agent presence and skill routing, in SIP alone.

- **Agents publish their skills, and whether they are Ready, with PUBLISH** (RFC 3903, a PIDF
  body, RFC 3863): one tuple per skill, `<tuple id="skill-spanish">`, open when Ready for it. A
  browser phone does it with its skills field and Agent button, through the WebRTC gateway; a desk
  phone publishes itself. No list of skills or agents is configured: a skill exists once an agent
  publishes it, and an agent leaves it by no longer publishing it.
- **Anyone may watch an agent with SUBSCRIBE** and is sent a NOTIFY on every change: Ready, Not
  Ready, ringing, busy, and each skill. A browser phone watches itself, so its Agent button shows
  what presence decided, such as Not Ready after a call it let ring.
- **A call to a skill's address** (`sip:spanish@…`) goes to the agent Ready for that skill who
  has been idle longest; a skill nobody has published is refused `404`. While nobody is free the caller is told `182 Queued` and waits, oldest first.
  An agent who does not answer is set Not Ready and the call goes to the next.

Presence stays in each call as a B2BUA, so it knows when an agent is busy and when free again
without anything outside SIP telling it. It reaches the agent's device through the registrar, so
a browser held by any engine rings.

## Configuration

`presence.json`, edited through the [Configurator](../../admin/configurator/README.md):

```json
{
  "noAnswerSeconds": 15,
  "queuedRefreshSeconds": 60,
  "maxWaitSeconds": 600,
  "maxExpiresSeconds": 3600
}
```

Only timers: how long an agent rings, how often a waiting caller is sent `182`, how long a call
may wait, and the longest publication or subscription granted.

The App Router sends presence the PUBLISH and SUBSCRIBE requests, and the INVITEs for skill
addresses; presence's own INVITE to an agent goes on to the registrar.

## Related modules

- [Proxy registrar](../proxy-registrar/README.md): finds the agent's device
- [WebRTC gateway](../webrtc/README.md): publishes for browser phones
- [BLADE](../../README.md): project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-presence</artifactId>
```
