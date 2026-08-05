# services/gateway — SIP trunk gateway

A BLADE v3 service that **registers with upstream SIP trunks** so the
platform can send and receive PSTN calls — the **PSTN front door** into BLADE.

Modernized from the 2020 `vorpal-blade-gateway` (recovered from an SD card): on the current
v3 framework, with a pluggable per‑carrier registration technique, timer‑driven refresh,
encrypted credentials, and multiple virtual gateways per servlet.

## Model

One `GatewaySipServlet` hosts **N `VirtualGateway`s** — multi‑homed / multi‑DID /
multi‑tenant. Each virtual gateway:

- advertises **its own Contact IP** (`contactHost`/`contactPort`/`transport`), bound at
  REGISTER time to the matching container SIP outbound interface
  (`SipServletContext.getOutboundInterfaces()` → `SipSession.setOutboundInterface(...)`);
- points at a carrier `registrarDomain`;
- carries a **`RegistrationStyle`** — the pluggable technique (Jackson‑polymorphic, `type`
  discriminator; the same idiom as `v3.configuration.Selector`/`Connector`):
  - **`register-digest`** (`RegisterDigestStyle`) — REGISTER + digest auth on the 401/407
    challenge, kept alive by a recurring SIP servlet timer at `expires − margin`. The
    `password` getter is `@FormLayout(password=true)` and stored encrypted
    (`{CLEARTEXT}`→`{AES}`) by the Configurator. Runtime: `RegisterCallflow`.
  - **`ip-auth`** (`IpAuthStyle`) — IP‑allowlisted carriers (Twilio/BYOC) that need no
    REGISTER; `newRegistrar()` returns null.
  - **new carriers** = one `@JsonSubTypes.Type` line + a subclass.

## FSMAR integration (the call model)

**This app terminates the trunk in both directions.** A trunk on the Flow canvas and a
`VirtualGateway` in this config are the same thing — calls in *and* out pass through here.

- **Inbound** (PSTN → BLADE): the registration makes OCCAS reachable, and the carrier sends to
  the trunk's own **Contact IP** — so the *arrival interface identifies the trunk*
  (`matchesInterface`), with no `;vgw=` present. FSMAR routes the trunk INVITE to this app
  (match on `${localIP}`); the app checks the source against the trunk's `sourceHosts` and
  bridges the call onward. FSMAR then runs again on the second leg (`previousApp = gateway`) and
  routes it to whichever app answers — which never learns which carrier called.
- **Outbound** (BLADE → PSTN): **FSMAR owns the policy** — it picks the trunk (dial‑plan /
  conditions) and routes the INVITE to this app, naming the trunk in the **Route URI**
  (`;vgw=<name>`, since FSMAR can only push Route headers). This app owns the **mechanism**:
  bind the trunk's Contact‑IP outbound interface, rewrite From to the trunk identity, forward to
  the carrier.

The `vgw` param is what tells the two apart: FSMAR sets it going out; a carrier never sets it
coming in.

### A trunk describes both directions

| Field | Direction | Used for |
|---|---|---|
| `registrarDomain` | outbound | where we send calls, via `trunkRequestUri()` |
| `sourceHosts` | inbound | where the carrier signals from, via `matchesSource()` — bare IPs or CIDR blocks; empty accepts any source on the Contact IP |
| `contactHost`/`contactPort` | both | the address the carrier was told to use — and therefore the inbound trunk discriminator |

### Routing inbound trunk calls (FSMAR)

Match the arrival interface, which needs no knowledge of the carrier's source ranges:

```json
"null": {
  "selectors": [ { "type": "attribute", "id": "localIP", "attribute": "localIP" } ],
  "triggers": { "INVITE": { "transitions": [
    { "id": "trunk-in", "when": "${localIP} == '203.0.113.10'", "next": "gateway" } ] } } },
"gateway": {
  "triggers": { "INVITE": { "transitions": [
    { "id": "to-media", "next": "<the app that answers>" } ] } } }
```

`${localIP}`/`${localPort}` are `Selector` pseudo-headers
(`v3.configuration.selectors.Selector`). Match on `${originIP}` instead when several trunks
share one interface.

### Routing convention (how FSMAR names the trunk)

FSMAR routes an outbound call here by targeting a **state whose app is the gateway** and pushing a
**Route** whose URI carries `;vgw=<name>`; the gateway reads it via `getPoppedRoute()`. Builder form:

```java
b2buaCallee.getTrigger("INVITE").createTransition("gateway")   // next = a state with app "gateway"
    .setId("offnet-via-gateway")
    .setWhen("${To.user} matches '\\+?1[2-9]\\d{9}'")
    .setSubscriber("To")
    .setRoutes(new String[] { "sip:${To.user}@gateway;vgw=flowroute-primary" });
```

In the Flow editor this is the **transition into the gateway state**, whose *Virtual gateway*
dropdown writes the `;vgw=` param onto the pushed Route. It belongs on the arrow, not on a cloud:
a cloud is where the call leaves OCCAS with no further application invoked, so a `;vgw=` there
would reach nothing. `<name>` must match a `VirtualGateway.name` in this app's config (the
dropdown is populated from it via `/gatewayVgws`, which the editor queries for whichever app the
transition targets).

## Status

- **Phase 1 (done):** config model + pluggable styles + N‑virtual‑gateway startup registration +
  `RegisterCallflow` (contact‑IP bind, digest challenge w/ loop guard, timer refresh, `Expires:0`
  de‑register).
- **Phase 2a (done):** outbound bridge — `GatewaySipServlet` extends `B2buaServlet`; `callStarted`
  reads `;vgw=<name>` (from the popped Route), looks up the `VirtualGateway`, rewrites Request‑URI
  → carrier, From → trunk identity (`outboundIdentity()`), binds the Contact‑IP outbound interface,
  and rejects an unknown `vgw` (404).
- **Phase 2b (done):** FSMAR routing convention documented — a transition into the gateway state
  carrying a `;vgw=<name>` Route (see *Routing convention* above).
- **Phase 2c (done):** admin Flow GUI — `GatewayVgwsServlet` (`/gatewayVgws`) exposes the gateway
  app's vg names; the *Virtual gateway* dropdown (`transition.html` + `flowTasks.js`) writes
  `;vgw=<name>` onto the Route pushed by the transition into the gateway state. It sits on the
  transition rather than on a cloud because only a pushed Route reaches `getPoppedRoute()`.
- **Phase 3 (done):** inbound termination — `callStarted` splits by direction
  (`bridgeToTrunk` / `bridgeFromTrunk`); `findGatewayByArrival` identifies the trunk from the
  arrival interface; `sourceHosts` + `matchesSource()` (CIDR via the `ipaddress` library, the same
  subnet math as the FSMAR `insubnet` operator) reject calls from anywhere else with a `403`.
  Adds `localIP`/`localPort` to `Selector` so FSMAR can express "arrived on this trunk".
  Supersedes the earlier design in which the gateway app sat outside the inbound path.
- **Verified:** `GatewayConfigTest` (JUnit) — style round‑trip + `defaultImpl`, `newRegistrar`,
  masked password, distinct Contact IPs, contact‑interface matching, trunk Request‑URI build,
  per‑style outbound identity, plus the inbound direction: CIDR/bare-IP `sourceHosts` matching,
  empty and null `sourceHosts` accepting any source, a malformed entry being skipped, and a
  pre-`sourceHosts` config still loading and still accepting its carrier. **12/12 pass**;
  skinny WAR packages. `admin/flow` compiles (90/90).
- **Deploy‑time only (OCCAS):** REGISTER/digest/timer behavior; the outbound path end‑to‑end
  (`callStarted` rewrite, `setOutboundInterface`, `getPoppedRoute` `vgw` read); and the Flow GUI
  dropdown — these need the container's `SipFactory`/channels, a live FSMAR route, and a browser.

## Scope boundary

- **Outbound‑INVITE digest auth is not implemented.** Registered (post‑REGISTER) and ip‑auth trunks
  accept outbound INVITEs from the authenticated source, so it's rarely needed — and it can't live in
  `callStarted`: the stock B2BUA bridge propagates a carrier `401/407` to the caller
  (`InitialInvite.processContinue`, `v2/b2bua/InitialInvite.java:197‑206`). Answering the challenge
  needs a re‑auth‑aware outbound leg (a gateway `InitialInvite` variant mirroring
  `RegisterCallflow.onResponse`: `createRequest(response,"INVITE")` + `addAuthHeader` + loop guard),
  or a challenge hook on the framework's `InitialInvite`. Add it when a target carrier re‑challenges
  INVITEs.

## Build / test

```bash
./mvnw -pl services/gateway -o test       # unit tests (framework 3.0.4 must be installed)
./mvnw -pl services/gateway -o package    # skinny WAR: target/gateway.war
```
Registered in the reactor via the `!skip.gateway` profile (root pom → `services/gateway`) and
listed in the `default`/`production`/`full` build profiles, so it builds and deploys with the
other services (`deploy.services=*`). Context‑root `gateway`; deploy unit `gateway.war`.
