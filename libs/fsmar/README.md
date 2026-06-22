# BLADE FSMAR

The next-generation FSMAR — **F**inite **S**tate **M**achine **A**pplication **R**outer.

This is BLADE's FSMAR, built on the v3 configuration system (`org.vorpal.blade.framework.v3.configuration`). Its config **model** lives in the framework (`org.vorpal.blade.framework.v3.fsmar`); this module holds the App Router runtime + the `approuter/` SPI registration. The original **FSMAR 2** is a separate, retired library (`retired/fsmar2/`, excluded from the standard build) — it shares no code with this one and will eventually be removed.

## What is FSMAR?

FSMAR uses state memory and pattern matching to route SIP traffic between applications in a *SIP Servlet Application Server*. Think of it as a router for audio/video streaming microservices. This allows developers to write tiny snippets of code (SIP Servlets) and string them together in an intelligent manner. The FSMAR knows who you are, where you've been, and where you're going.

## Capabilities

- **Data-driven routing** on the v3 configuration framework: per-state *selectors* extract named values from any part of the message (headers, URIs, parameters, even the body), and transitions fire on `when` expressions over those values — replacing FSMAR 2's per-header comparison lists
- **Route construction from extracted values**: routes are `${}`-templated (`"sip:${To.user}@registrar"`), so one transition handles what used to take one rule per subscriber
- **Capture-and-carry**: extracted values accumulate across the whole call-path (carried in the JSR-289 `stateInfo`, replicated cluster-wide) — a later state can route on something captured at ingress, before intermediate apps rewrote the message
- **Tiering as data**: a `table` selector classifies calls through a translation table (exact / longest-prefix / range keys) — gold/silver/bronze customers are table rows an operator edits, not routing logic. Conditions just test `${tier} == 'gold'`
- **Condition operators**: `==`, `!=`, ordering, `&&`/`||`, plus `matches` (full-string regex) and `contains`
- **Pseudo-variables** published every hop: `${method}`, `${requestUri}`, `${directive}`, `${previousApp}`, `${hour}`, `${dayOfWeek}`, and `${hash100}` — a stable per-call 0–99 bucket, so `${hash100} < 5` canaries ~5% of calls to a new application version
- **Routing observability**: a FINER trace of every transition evaluated (and why it did or didn't fire), plus JMX metrics at `org.vorpal.blade:type=Fsmar3,name=metrics` — per-transition hit counts, default-application fallbacks, undeployed bypasses, cycle detections
- **Route Simulator** (in the Flow editor, `blade/flow`): run a synthetic request — or paste a real INVITE — through the diagram *being edited* and watch the routing path animate hop by hop: selectors extracting values, every transition's `when` shown FIRED / no-match, `${}` routes resolved. Pseudo-variables are overridable ("simulate Sunday 3 AM", "show the 4% canary bucket") and any application can be marked *undeployed* to explore bypass / cycle / fallback behavior before deploying anything
- **Call capture & replay**: arm `captureNextCalls(n)` on the metrics MBean and the engine records full routing traces (the same format the simulator emits) for the next n real calls — then replay them on the Flow editor diagram. Capture is opt-in; disarmed cost is one atomic read per request
- **Live heat overlay**: the Flow editor polls per-transition hit counts across every engine and renders them on the diagram edges — count labels, stroke width scaled by traffic share
- **Config validation on load**: malformed `when` expressions are flagged SEVERE (they'd otherwise never match, silently); transitions targeting undeployed applications get a WARNING
- Optional JSR-289 `region` per transition (`ORIGINATING` / `TERMINATING`; default `NEUTRAL`) for third-party apps that branch on `request.getRegion()`
- Flatter, more expressive JSON schema: `defaultApplication` + `states` map keyed by previous application name (with `"null"` for initial requests)
- Full BLADE Flow editor integration (`admin/flow`): visual authoring of the state machine, semantic validation, plan-dispatch generation, simulation and replay
- Dedicated log files (same as FSMAR 2)
- Dynamic config reloads (same as FSMAR 2)

## How does it work?

FSMAR is not a WebLogic deployment — it's a fat JAR that lives in the OCCAS domain's `approuter/` directory and is activated via the OCCAS admin console. See the **FSMAR install walkthrough** in [DEPLOYMENT.md](../../DEPLOYMENT.md#fsmar-install-walkthrough) for the full procedure.

For automated installs, use `./deploy.sh <env> fsmar` from the repository root, which copies `vorpal-blade-library-fsmar.jar` to the configured `approuter.dir` (locally or over SSH).

On first startup, FSMAR writes a sample config into the OCCAS `_samples` directory alongside samples from every other BLADE app. Copy it into place, rename, and edit.

**Note:** FSMAR installs to `approuter/` as `vorpal-blade-library-fsmar.jar`; OCCAS loads its `SipApplicationRouterProvider` SPI entry at boot. (The retired FSMAR 2 jar is no longer built — see `retired/fsmar2/`.)

## Tutorial

FSMAR is a finite state machine. Each *state* represents a SIP Servlet application. As a message flows through the system, two things happen on entry to each state:

1. The state's **selectors** run, extracting named values from the request into the routing context (e.g. the user-part of the To header becomes `${To.user}`). The context accumulates across the whole call-path — values captured at ingress remain available in every later state, even after intermediate apps rewrite the message.
2. The **trigger** matching the SIP method is consulted, and its **transitions** are evaluated in order. The first transition whose `when` expression matches (or that has no `when`) wins.

Here's the breakdown:

* **Previous State** — the origin of the message (`"null"` if it originated from an external system)
* **Selectors** — extraction rules run on entry to the state: regex named-groups against headers, attribute reads, JSON/XML/SDP body extraction. A selector with id `To` capturing `(?<user>…)` publishes both `${user}` and the namespaced `${To.user}`
* **Trigger** — a SIP method, like `INVITE`, `REGISTER`, `SUBSCRIBE`
* **Transition** — `when` (a condition expression over `${}` context values; omit it for an unconditional match), `next` (the destination application), `subscriber` (which header names the subscriber — the JSR-289 contract), `routes` (`${}`-templated SIP URIs to push), and an optional `routeModifier`

## JSON Shape

```json
{
  "defaultApplication": "b2bua",
  "states": {
    "null": {
      "selectors": [
        { "type": "regex", "id": "To", "attribute": "To",
          "pattern": ".*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*" }
      ],
      "triggers": {
        "INVITE": {
          "transitions": [
            {
              "id": "INV-bob",
              "when": "${To.user} == 'bob'",
              "next": "b2bua",
              "subscriber": "From",
              "routes": [ "sip:${To.user}@special-proxy" ]
            },
            {
              "id": "INV-default",
              "next": "screening",
              "subscriber": "From"
            }
          ]
        }
      }
    }
  }
}
```

See `AppRouterConfigurationSample.java` in this module for the full sample (it demonstrates capture-and-carry across a three-state path: ingress → screening → b2bua → registrar); the selector types live in `org.vorpal.blade.framework.v3.configuration.selectors` and the `when` expression syntax is the same one iRouter uses (`org.vorpal.blade.framework.v3.configuration.expressions`).

## Don't Overthink It

FSMAR can only route the first message in a dialog. For instance, it can route an `INVITE` through multiple applications, but it cannot tamper with the flow of subsequent in-dialog messages like `200 OK`, `ACK`, `INFO`, or `BYE`. Those follow their natural course through the system.

FSMAR also cannot modify a SIP message. If that's your goal, write a SIP Servlet application — check out the BLADE framework's B2BUA or PROXY APIs.

## Seamless Upgrades and Versioned Applications

FSMAR tracks applications as they are deployed and undeployed. New calls are routed to updated applications while existing calls continue through the previous versions until they complete naturally. FSMAR also supports dynamic configuration changes, so you never need to reboot a server or drop a call to update routing rules. FSMAR maintains a per-call copy of the configuration, so in-flight calls aren't affected by config edits.

Remember to update the `Weblogic-Application-Version` entry in your application's `META-INF/MANIFEST.MF` for seamless upgrades to work.
