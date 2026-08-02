# Options — SIP OPTIONS Keep-Alive Responder

Answers inbound SIP OPTIONS pings — the keep-alive and health probes upstream elements
(SBCs, load balancers, monitors) send to confirm the node is alive — with configurable
response headers (`Accept`, `Allow`, `User-Agent`, …) from `options.json`.

Because the OPTIONS answer is what a SIP-aware load balancer keys its routing on, this app
is also the node's **health mouthpiece**. It answers `503` instead of `200 OK` in two
situations:

| Signal | Trigger | Response |
|---|---|---|
| Overload | OCCAS overload protection is actively rejecting traffic (`unavailableWhenOverloaded: true`; see `EngineOverload`) | `503 Service Unavailable` + `Retry-After: <overloadRetryAfter>` |
| **Administrative drain** | Operator set `Drained=true` on this node's Drain MBean | `503 Draining` + `Retry-After: <drainRetryAfter>` (omitted when 0) |

Either way, a load balancer that pings each engine individually stops offering NEW calls to
this node; established dialogs continue. BLADE's own `proxy-balancer` implements the other
side of the protocol: its OPTIONS ping cycle marks an endpoint down on 503 (sticky until a
later ping succeeds) and up on any other final response.

## Administrative drain (the Drain MBean)

The drain switch is **runtime state, not configuration** — a per-JVM JMX attribute, never a
file. It resets to false on restart, so a bounced engine rejoins the pool as soon as the
app is up, and a forgotten drain cannot outlive the JVM it was set on.

ObjectName (per engine, mirroring the app's Configuration MBean):

```
vorpal.blade:Name=options,Type=Drain[,Cluster=<cluster>]
```

Attributes: `Drained` (read/write boolean), `DrainedSinceMillis` (read-only; 0 when not
drained). Each drain/resume transition logs one WARNING-level line as an audit trail.

From the AdminServer, WebLogic's Domain Runtime MBean Server federates every engine's
instance and tags each with `Location=<serverName>` — the same walk the Metrics and
Configuration consoles use — so one WLST session can drain any engine:

```
connect('admin', '<password>', 't3://adminserver:7001')
domainRuntime()
# one specific engine:
mbean = ObjectName('vorpal.blade:Name=options,Type=Drain,Cluster=engines,Location=engine1')
mbs.setAttribute(mbean, Attribute('Drained', Boolean(true)))
```

(`mbs` is WLST's connected MBeanServer connection; jconsole or a direct t3 connection to
the engine's runtime MBean server works equally well.)

## Rolling-bounce workflow

1. Set `Drained=true` on the target engine.
2. Wait at least one load-balancer ping interval (proxy-balancer defaults to 60s) — the
   node answers `503 Draining` and drops out of rotation for new calls.
3. Wait for the node to go **quiet**: OCCAS's own `SipServerRuntime` MBean (same
   `Location=<server>` federation) reports `PeriodCountSipThroughput` — zero for a couple
   of periods means the engine is processing nothing. Active session counts do **not**
   need to reach zero: dialogs are cluster-replicated and fail over; throughput is the
   safe-to-bounce signal.
4. Bounce the engine. The flag dies with the JVM; the first successful ping after the app
   redeploys puts the node back in rotation.

Three ways to run this workflow:

- **Tuning console** (`blade/tuning`, Servers table): a Drain column per server — state,
  session/throughput numbers, a "quiet — safe to restart" badge, and Drain / Resume
  buttons next to the existing Restart button.
- **`misc/rolling-restart.py`** (WLST): the whole cycle unattended, engine by engine —
  drain → wait ping interval → wait quiet → force-shutdown + Node-Manager start. Skips
  any engine with no Drain MBean rather than restarting it undrained.
- **By hand** over WLST/jconsole, as above.

Third-party SBCs speak the same OPTIONS protocol but apply their own monitor policy —
verify per deployment that a 503 ping response is treated as out-of-service (most
SIP-aware balancers can be configured to).

## Testing

- `../../mvnw -f pom.xml test` — JUnit (`DrainControlTest`: state transitions plus a real
  JMX round-trip against the platform MBeanServer).
- `test/uac-options.sh` — SIPp OPTIONS probe against a running node: expect `200 OK`
  normally, `503 Draining` while drained.
