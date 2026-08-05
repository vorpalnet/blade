# Options — SIP OPTIONS Keep-Alive Responder

Answers inbound SIP OPTIONS pings — the keep-alive and health probes upstream elements
(SBCs, load balancers, monitors) send to confirm the node is alive — with configurable
response headers (`Accept`, `Allow`, `User-Agent`, …) from `options.json`.

Because the OPTIONS answer is what a SIP-aware load balancer keys its routing on, this
app is also the node's **health mouthpiece**. Across the node's whole lifecycle it tells
the load balancer the truth:

```
503 Starting  →  200 OK  →  503 Draining
   (booting)     (in service)   (operator drain)
```

It answers `503` instead of `200 OK` in three situations:

| Signal | Trigger | Response |
|---|---|---|
| **Boot gate** | The server has not yet reached RUNNING — deployments still in progress (`unavailableUntilRunning: true`; see `ServerReady`) | `503 Starting` |
| **Overload** | OCCAS overload protection is actively rejecting traffic (`unavailableWhenOverloaded: true`; see `EngineOverload`) | `503 Service Unavailable` + `Retry-After: <overloadRetryAfter>` |
| **Administrative drain** | Operator set `Drained=true` on this node's Drain MBean | `503 Draining` + `Retry-After: <drainRetryAfter>` (omitted when 0) |

In every case, a load balancer that pings each engine individually stops offering NEW
calls to this node; established dialogs continue (session state is cluster-replicated
and fails over). The reason phrases (`Starting` / `Draining`) exist for the human reading
a trace — load balancers treat all three the same.

## Configuration (`options.json`)

- Response headers: `accept`, `acceptLanguage`, `allow`, `supported`, `userAgent`,
  `allowEvents`.
- `unavailableUntilRunning` — boot gate on/off (sample: `true`; absent = off). Requires
  the WebLogic runtime MBeans on the platform MBean server (the default); set `false`
  if a node never leaves Starting.
- `unavailableWhenOverloaded` / `overloadRetryAfter` — mirror OCCAS overload protection
  into the health check.
- `drainRetryAfter` — seconds advertised on the drain 503; `0` omits the header (the
  default — BLADE's own proxy-balancer treats a ping 503 as sticky-down until a ping
  succeeds, so a backoff hint adds nothing there).

The drain **switch** is deliberately NOT in this file — it is runtime state, not
configuration (see below).

## Boot gate: deploy this app FIRST

OCCAS accepts SIP traffic while applications are still deploying, and the App Router
routes those early calls through a partial chain (apps not yet deployed bypass as
virtual states) — not an error, a silently wrong call path. The gate holds the load
balancer away until the server reaches **RUNNING**: the end of the deploy phase, when
ALL deployments have been processed. That definition needs no app list, and deliberately
does not fight the virtual-state retirement model — an app deliberately stopped after
boot does not re-close the gate (drain is the tool for a live node). The gate is a
latch: once RUNNING has been observed it stays open for the life of the JVM.

For the gate to own the answer for the whole boot window, the options app must deploy
before everything else. Nothing in BLADE sets deployment order (all apps sit at
WebLogic's default 100), so set options low once per domain (WLST):

```
connect('admin', '<password>', 't3://adminserver:7001')
edit(); startEdit()
cd('/AppDeployments/options')
cmo.setDeploymentOrder(10)
save(); activate()
```

Caveat: a full **undeploy** deletes the AppDeployment (and its order) — re-run the
snippet after recreating the deployment. A normal update/redeploy preserves it. The few
seconds before options itself deploys are covered on the load-balancer side by
`pingRequire2xx` (see below).

## Administrative drain (the Drain MBean)

The drain switch is **runtime state, not configuration** — a per-JVM JMX attribute,
never a file. It resets to false on restart, so a bounced engine rejoins the pool as
soon as it is ready again, and a forgotten drain cannot outlive the JVM it was set on.

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
   `Location=<server>` federation) reports `PeriodCountSipThroughput` — zero for a
   couple of periods means the engine is processing nothing. Active session counts do
   **not** need to reach zero: dialogs are cluster-replicated and fail over; throughput
   is the safe-to-bounce signal.
4. Bounce the engine. The flag dies with the JVM; the boot gate answers `503 Starting`
   through the deploy phase, and the node rejoins on its first `200 OK` ping.

Three ways to run this workflow:

- **Tuning console** (`blade/tuning`, Servers table): a Drain column per server — state,
  session/throughput numbers, a "quiet — safe to restart" badge, and Drain / Resume
  buttons next to the existing Restart button.
- **`misc/rolling-restart.py`** (WLST): the whole cycle unattended, engine by engine —
  drain → wait ping interval → wait quiet → force-shutdown + Node-Manager start. Skips
  any engine with no Drain MBean rather than restarting it undrained.
- **By hand** over WLST/jconsole, as above.

## The load-balancer side

BLADE's own `proxy-balancer` implements the other half of the protocol: its OPTIONS ping
cycle marks an endpoint DOWN on 503 (sticky until a later ping succeeds) or 408, and UP
on any other final response — or, with `pingRequire2xx: true` on the endpoint, ONLY on a
2xx. Set `pingRequire2xx` for endpoints that are BLADE engines: a healthy engine always
affirms with 200, and without the flag a booting container's error responses (before
this app deploys) would enroll a half-started node.

Third-party SBCs speak the same OPTIONS protocol but apply their own monitor policy —
verify per deployment that a 503 ping response is treated as out-of-service (most
SIP-aware balancers can be configured to).

## Testing

- `../../mvnw -f pom.xml test` — JUnit: `DrainControlTest` (state transitions plus a
  real JMX round-trip against the platform MBeanServer) and `ServerReadyTest` (the boot
  latch, driven through a fake `ServerRuntime` MBean).
- `test/uac-options.sh` — SIPp OPTIONS probe against a running node: expect `200 OK`
  in service, `503 Starting` during boot, `503 Draining` while drained.
