# Proxy Balancer Service

[Javadocs](https://vorpal.net/javadocs/blade/proxy-balancer)

A SIP load balancer: distributes initial INVITEs across tiered pools of
downstream endpoints, selected by the request URI's host, skipping endpoints
it believes are down.

## Configuration model (v3)

Endpoints are defined ONCE in a named registry and referenced from tiers;
the name is the endpoint's stable identity for health and the dashboard:

```json
{
  "session": { "passthru": true },
  "health":  { "pingEnabled": true, "pingInterval": 60, "defaultBackoff": 60 },
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
  monitored); `ping: false` opts it out of the OPTIONS cycle. `user` and
  `uriParams` are optional escape hatches (a real SBC endpoint needs neither;
  `uriParams` exists so a test-harness target can still carry literal
  `status=501;delay=2`-style parameters).
- A plan is an ordered array of tiers — a failed tier fails over to the next.

## How calls are routed

`InviteCallflow` executes the plan as a forking B2BUA. Per-tier **strategies**:

| Strategy | Behavior |
|---|---|
| `parallel` | race all endpoints with `sendRequestsInParallel` — first 2xx wins, losers are CANCELed |
| `serial` | hunt one at a time in listed (priority) order |
| `random` | hunt in random order — equal-cost distribution |
| `roundrobin` | hunt from a per-node rotating offset (bounded short-window skew) |
| `weighted` | hunt in smooth weighted round-robin order — deterministic, per-node; over any `totalWeight` consecutive calls each endpoint gets exactly `weight` first attempts |

Tier order is priority order — for **least-cost routing**, list the cheapest
tier first. Failover is response-classified: route-level failures (408 /
timeout, 480, 404, 5xx, 3xx) escalate to the next tier; user-state and auth
responses (486 busy, 401/407 auth, 487 canceled, all 6xx per RFC 3261 §16.7)
are relayed to the caller and never touch a more expensive tier. A caller
who CANCELs mid-hunt ends the plan — late leg failures cannot launch new
tiers. The last tier's failure is relayed; nothing routable anywhere → 503.
Tier `timeout` defaults to 180s in the schema (a serial hunt's timer drives
it, so it must never be 0). In-dialog requests are relayed by the b2bua
callflows inherited from the v3 `B2buaServlet`.

## Endpoint health

Every engine node keeps its **own independent** health map keyed by endpoint
name (no shared cluster state), with two writers:

- **OPTIONS pings** — every `health.pingInterval` seconds the node pings
  every registry endpoint (minus `ping: false`). ANY final response except
  408/503 proves the box alive → UP (a 405 dislikes OPTIONS, it isn't dead);
  the container-generated 408 (silence) or a 503 → DOWN. Publishing a config
  reschedules the cycle immediately, so a changed `pingInterval` or
  `pingEnabled` applies at once — no redeploy, no waiting out the old
  interval. An unpingable endpoint (malformed URI) is marked DOWN with an
  "unpingable" note and never stops the rest of the cycle.
- **Live call legs** — a 2xx marks its endpoint UP; a **503** marks it DOWN
  with a timed backoff from `Retry-After`, else `health.defaultBackoff`
  (0 = down until a ping revives it). User responses (486, 603, other 4xx)
  are ignored.

Routing skips drained and DOWN endpoints when building a tier's forks; a
tier with nothing routable is skipped. Publishing a new configuration resets
the map (all UP) until re-marked.

Each node publishes its view as
`vorpal.blade:Name=proxy-balancer,Type=EndpointHealth[,Cluster=...]`; the
**Balancer admin app** (`proto/balancer`, context root `blade/balancer`)
aggregates all nodes over federated JMX into a per-node dashboard.

## B2BUA vs. proxy drop-out

The v2 `Proxy` API is gone; path presence is decided by configuration:

- `session:passthru = true` (the sample default) — after call setup the
  framework stitches the endpoints' Contacts together and removes OCCAS from
  the route set; in-dialog traffic flows directly between the endpoints. The
  balancer becomes a routing-only touch point.
- `session:passthru = false` — the balancer remains in the dialog as a full
  B2BUA.

Provisional responses: real 18x ringing from the legs is relayed upstream
(per-leg observer on the fan-out primitives), on top of an immediate 180 sent
when the fork starts so the caller hears ringback before any leg responds.

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-proxy-balancer</artifactId>
```
