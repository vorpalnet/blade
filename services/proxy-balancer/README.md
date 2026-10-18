# Proxy Balancer Service

Javadocs: `/blade/javadoc/proxy-balancer/` on the Admin Portal

A SIP load balancer: distributes initial INVITEs across tiered pools of
downstream endpoints, selected by the request URI's host, skipping endpoints
it believes are down.

## Configuration model (v3)

Endpoints are defined ONCE in a named registry and referenced from tiers;
the name is the endpoint's stable identity for health and the dashboard:

```json
{
  "session": { "passthru": true },
  "health":  { "pingEnabled": true, "pingInterval": 60, "defaultBackoff": 60,
               "failoverOn": [408, 502, 503, 504], "markDownOn": [408, 503] },
  "endpoints": {
    "sbc-east-1": { "host": "10.1.1.10", "transport": "tcp", "weight": 3 },
    "sbc-east-2": { "host": "10.1.1.11", "transport": "tcp" },
    "sbc-west-1": { "host": "10.2.1.10", "transport": "tcp", "enabled": false }
  },
  "plans": {
    "example.co":   [ { "name": "primary",  "strategy": "weighted", "timeout": 15,
                        "endpoints": ["sbc-east-1", "sbc-east-2"] },
                      { "name": "fallback", "strategy": "serial",   "timeout": 15,
                        "endpoints": ["sbc-west-1"] } ],
    "*.example.co": [ ],
    "*":            [ ]
  }
}
```

- **Plan selection**: exact request-URI host, else the longest matching
  `*.suffix` wildcard key, else `"*"`. Non-SIP request URIs (tel:) go to the
  default. No plan → 404.
- **Endpoint fields**: the address is structured, not a free-typed SIP URI —
  `scheme` (`sip`/`sips`, default `sip`), `transport` (`udp`/`tcp`, default
  `udp`), `host` (required), `port` (blank = SIP default, 5060/5061). `weight`
  (for `weighted`); `enabled: false` drains the endpoint (no new calls, still
  monitored); `ping: false` opts it out of the OPTIONS cycle;
  `pingRequire2xx: true` marks an endpoint as a BLADE engine — only a 2xx
  ping marks it up (a booting container's error responses must not enroll a
  half-started node). `user` and
  `uriParams` are optional escape hatches (a real SBC endpoint needs neither;
  `uriParams` exists so a test-harness target can still carry literal
  `status=501;delay=2`-style parameters).
- A plan is an ordered array of tiers — a failed tier fails over to the next.

## How calls are routed

`InviteCallflow` executes the plan as a forking B2BUA. Per-tier **strategies**:

| Strategy | Behavior |
|---|---|
| `parallel` | race all endpoints — first 2xx wins and the rest are CANCELed; a 2xx that arrives after that is ACKed and ended with a BYE; a 6xx ends the race; if every branch fails, a definite failure (not in `failoverOn`) answers over a retryable one; only the first branch to offer early media has its SDP relayed |
| `serial` | hunt one at a time in listed (priority) order |
| `random` | hunt in random order — equal-cost distribution |
| `roundrobin` | hunt from a per-node rotating offset (bounded short-window skew) |
| `weighted` | hunt in smooth weighted round-robin order — deterministic, per-node; over any `totalWeight` consecutive calls each endpoint gets exactly `weight` first attempts |

Tier order is priority order — for **least-cost routing**, list the cheapest
tier first. Failover is response-classified by `health.failoverOn` (default
408, 502, 503, 504): those move the call to the tier's next endpoint, then to
the next tier. Everything else (404, 480, 486, 500, a 401/407 challenge, 3xx,
6xx) goes straight back to the caller, because every endpoint would answer it
the same way, and asking each one multiplies the traffic of a call that cannot
complete just when traffic is the problem. Within a hunting tier, an endpoint
with no final response by the tier `timeout` is cancelled and the hunt moves
on regardless. A caller
who CANCELs mid-hunt ends the plan — late dialog failures cannot launch new
tiers. The last tier's failure is relayed, except that a 503 reaches the
caller as a 500 without its Retry-After, and nothing routable anywhere is a
500: a 503 from this engine would tell the caller's load balancer that the
engine is overloaded, when it was a route that refused.
Tier `timeout` defaults to 180s in the schema (a serial hunt's timer drives
it, so it must never be 0). In-dialog requests are relayed by the b2bua
callflows inherited from the v3 `B2buaServlet`.

## Endpoint health

Every engine node keeps its **own independent** health map keyed by endpoint
name (no shared cluster state), with two writers:

- **OPTIONS pings** — every `health.pingInterval` seconds the node pings
  every registry endpoint (minus `ping: false`). ANY final response except
  408/503 proves the box alive → UP (a 405 dislikes OPTIONS, it isn't dead);
  the container-generated 408 (silence) or a 503 → DOWN (overloaded,
  draining, or starting — the endpoint said so). With `pingRequire2xx: true`
  (a BLADE engine) only a 2xx marks UP. Publishing a config
  reschedules the cycle immediately, so a changed `pingInterval` or
  `pingEnabled` applies at once — no redeploy, no waiting out the old
  interval. An unpingable endpoint (malformed URI) is marked DOWN with an
  "unpingable" note and never stops the rest of the cycle.
- **Live call dialogs** — a 2xx marks its endpoint UP; a response in
  `health.markDownOn` (default 503 and 408) marks it DOWN with a timed backoff
  from `Retry-After`, else `health.defaultBackoff` (0 = down until a ping
  revives it). A 408 counts only when nothing came back and the container made
  it: that 408 has no sender address. A 408 the endpoint sent itself proves it
  alive, so the call moves on and the endpoint stays in service. Other
  responses (486, 603, other 4xx) are ignored.

Routing skips drained and DOWN endpoints when building a tier's forks; a
tier with nothing routable is skipped. Publishing a new configuration resets
the map (all UP) until re-marked.

Each node publishes its view as
`vorpal.blade:Name=proxy-balancer,Type=EndpointHealth[,Cluster=...]`; the
[dashboard](../../admin/dashboard/README.md) reads every node's view over
federated JMX and shows each endpoint on its Traffic map: answering, not
answering pings, or refusing calls with 503, and on which engines, with the
calls it was offered.

## B2BUA vs. proxy drop-out

Whether the balancer stays in the call is configuration, not code:

- `session:passthru = true` (the sample default): the caller's INVITE is
  proxied, without Record-Route, to the tier's endpoints. One dialog runs from
  caller to callee, and the ACK, re-INVITEs and BYE never reach OCCAS. The
  balancer only routes, and its health tracking and tier failover work as
  below.
- `session:passthru = false`: the balancer stays in the dialog as a full B2BUA.

Under passthru the container does the forking, so a few things follow RFC 3261
proxy rules instead of the B2BUA behavior above. Every branch's ringing and
early media reach the caller, with no immediate 180 of our own. Every 2xx is
forwarded, and a caller that gets two answers ACKs and ends the second, as
RFC 3261 section 13.2.2.4 requires of it. A 503 still reaches the caller as a
500: the container converts it, as RFC 3261 section 16.7 asks of a proxy. A
definite failure still stops a serial hunt, and a retryable one still moves on
to the next endpoint and then the next tier. Each parallel tier gets its own
`timeout`, however long the tiers before it took.

As a B2BUA, real 18x ringing from the dialogs is relayed upstream (per-dialog
observer on the fan-out primitives), on top of an immediate 180 sent when the
fork starts so the caller hears ringback before any dialog responds.

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-proxy-balancer</artifactId>
```
