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
| **Administrative drain** | Operator set `Drained=true` on this node's Drain MBean | `503 Draining` + `Retry-After: <drainRetryAfter>` (omitted when 0) |
| **Queue pressure** | The `wlss.transport` or `wlss.timer` queue is at `queuePressurePercent` (default 80) of its capacity; see `QueuePressure` | `503 Busy (transport 86%)`: the fuller queue and how full |
| **Slowing** | 90% of the last second's SIP messages took half of `overload.refuseMillis` (default 50 ms) or more; see `OverloadMonitor` | `503 Busy (latency 60 ms)` |

In every case, a load balancer that pings each engine individually stops offering NEW
calls to this node; established dialogs continue (session state is cluster-replicated
and fails over). The reason phrases (`Starting` / `Draining` / `Busy (...)`) say why to the human
reading a trace, and BLADE's proxy-balancer shows them on the dashboard's Traffic map; load
balancers treat them all the same.

The app also publishes both queues, depth and capacity, as
`vorpal.blade:Name=options,Type=SipQueues`, and the dashboard's Servers panel shows the fuller
one for every engine.

Queue pressure is the early warning. When either queue reaches 100% of its capacity,
OCCAS answers every request `503 Server Busy` itself, calls and pings alike, and the
options app never sees the ping. Answering `503 Busy` at 80% moves new calls elsewhere
while the node can still finish the work it has.

## Overload: refusing new calls before the queue fills

A busy ping only steers the load balancers that ping. Traffic sent straight to an engine,
by an SBC for example, keeps coming until the queue fills, and then OCCAS refuses
everything, including the BYEs that would have drained it. So the options app also
decides when its engine should refuse new calls, and hands that verdict to OCCAS overload
protection.

It decides on one number: how long the engine takes over a SIP message, from reading it
off the socket to finishing with it, which the container measures for every message:

```
 50 ms   pings answer 503 Busy          load balancers send new calls elsewhere
100 ms   503 Server busy to new calls   for traffic no ping steers; calls already up carry on
```

The first line is half of `refuseMillis` (100), and it is also where a refusing engine takes
new calls again, so below it the engine is healthy on both counts. The aim is that a pinging
load balancer moves calls away at the first line, and the second is never reached. That
holds when the ping interval is short next to how fast the engine slows: proxy-balancer
pings every 60 seconds by default, so set its `pingInterval` to a few seconds for engines.
A healthy engine takes a few milliseconds. The wait in the queue is part of the number, so
there is nothing to tune to the hardware: a faster engine works the same queue off sooner.
An engine with messages waiting and none finishing counts as slow.

Two pieces put the verdict in front of the container, once per domain:

```
./deploy.sh <env> blade-overload.jar --domain-lib   # into <domain>/lib, then to each engine host
./install.sh <env> overload                         # names its collector in sipserver.xml
```

then restart the AdminServer and the engines. Without them the app still decides and the
dashboard still shows the verdict, worded as "nothing refuses new calls". After that,
`refuseMillis` is ordinary configuration: change it in the Configurator and the next reading
uses it.

It also warns before calls slow about the usual causes: garbage collection taking a quarter
of the time, an old generation that stays full after collection, and call-state lock
failures. Warnings never refuse a call.

Each change shows on the dashboard: under Issues (a refusal as a problem, a cause as a
warning, each linked to this configuration), in its notifications, in analytics with the
other operations events, and on the Servers panel, where the engine reads `refusing` and its
hover gives the readings.

## Configuration (`options.json`)

- Response headers: `accept`, `acceptLanguage`, `allow`, `supported`, `userAgent`,
  `allowEvents`.
- `unavailableUntilRunning` — boot gate on/off (sample: `true`; absent = off). Requires
  the WebLogic runtime MBeans on the platform MBean server (the default); set `false`
  if a node never leaves Starting.
- `queuePressurePercent` — percent of work-manager queue capacity at which pings turn
  `503 Busy`. Absent = 80; `0` turns it off.
- `overload` — `refuseMillis` (100; `0` turns refusing off), and the warnings
  `gcWarnPercent` (25), `heapWarnPercent` (85), `lockFailuresWarnPerMinute` (10). Absent =
  these defaults.
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
  real JMX round-trip against the platform MBeanServer), `ServerReadyTest` (the boot
  latch, driven through a fake `ServerRuntime` MBean), `QueuePressureTest`, and
  `OverloadVerdictTest` (the latency percentile, the two lines, a stall, and when a warning
  raises and clears).
- `test/uac-options.sh` — SIPp OPTIONS probe against a running node: expect `200 OK`
  in service, `503 Starting` during boot, `503 Draining` while drained.
