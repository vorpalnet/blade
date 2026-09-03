# services/gateway — SIP trunk gateway

A BLADE v3 service that **registers with upstream SIP trunks and originates outbound calls
onto them** — the PSTN front door out of BLADE.

Modernized from the 2020 `vorpal-blade-gateway` (recovered from an SD card): on the current
v3 framework, with a pluggable per-carrier registration technique, timer-driven refresh,
encrypted credentials, and multiple trunks per servlet.

## Model

One `GatewaySipServlet` hosts **N `VirtualGateway`s** — one per outbound trunk. Each trunk:

- points at a carrier `registrarDomain` (with `transport`);
- carries a **`RegistrationStyle`** — the pluggable technique (Jackson-polymorphic, `type`
  discriminator; the same idiom as `v3.configuration.Selector`/`Connector`):
  - **`register-digest`** (`RegisterDigestStyle`) — REGISTER + digest auth on the 401/407
    challenge, kept alive by a recurring SIP servlet timer at `expires − margin`. The
    `password` getter is `@FormLayout(password=true)` and stored encrypted
    (`{CLEARTEXT}`→`{AES}`) by the Configurator. Runtime: `RegisterCallflow`.
  - **`ip-auth`** (`IpAuthStyle`) — IP-allowlisted carriers (Twilio/BYOC) that need no
    REGISTER; `newRegistrar()` returns null.
  - **new carriers** = one `@JsonSubTypes.Type` line + a subclass.
- optionally names an **`outboundInterface`** — multi-homed engines only, to originate that
  trunk's REGISTER and INVITEs from a specific local SIP channel. Leave it unset on a
  single-interface engine: the container's own interface is used, and the channel's
  `public-address` sets the Contact.

## Outbound only

The gateway app sits on the **outbound** path. Inbound carrier INVITEs never reach it: the
carrier's INVITE lands on the engine's SIP channel and FSMAR routes it straight to the
answering app, so a trunk needs no inbound arrival-interface or source matching here. The
registration's job for the inbound direction is just to give the carrier a reachable Contact
(the SIP channel `public-address`); FSMAR sorts inbound calls by the called number.

- **Outbound** (BLADE → PSTN): **FSMAR owns the policy** — it picks the trunk (dial-plan /
  conditions) and routes the INVITE here, naming the trunk in the **Route URI**
  (`;vgw=<name>`, since FSMAR can only push Route headers). This app owns the **mechanism**:
  rewrite the Request-URI to the carrier, From to the trunk identity, and (multi-homed only)
  pin the outbound interface, then forward to the carrier.

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

## Scope boundary

- **Outbound-INVITE digest auth is not implemented.** Registered (post-REGISTER) and ip-auth trunks
  accept outbound INVITEs from the authenticated source, so it's rarely needed — and it can't live in
  `callStarted`: the stock B2BUA bridge propagates a carrier `401/407` to the caller
  (`InitialInvite.processContinue`, `v2/b2bua/InitialInvite.java`). Answering the challenge
  needs a re-auth-aware outbound dialog (a gateway `InitialInvite` variant mirroring
  `RegisterCallflow.onResponse`: `createRequest(response,"INVITE")` + `addAuthHeader` + loop guard).
  Add it when a target carrier re-challenges INVITEs.

## Build / test

```bash
./mvnw -pl services/gateway -o test       # unit tests
./mvnw -pl services/gateway -o package    # skinny WAR: target/gateway.war
```

Discovered and built with the other services by `build.sh`. Context-root `gateway`; deploy unit
`gateway.war`. Deploy it with the REST or wlst engine via `deploy.sh` (see the repo `DEPLOYING.md`).

Deploy-time only (OCCAS): REGISTER/digest/timer behavior and the outbound path end-to-end
(`callStarted` rewrite, `setOutboundInterface`, the `getPoppedRoute` `vgw` read) need the
container's `SipFactory`/channels and a live FSMAR route.
