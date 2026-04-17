# BLADE FSMAR 3

The next-generation FSMAR — **F**inite **S**tate **M**achine **A**pplication **R**outer.

FSMAR 3 is a complete rewrite of FSMAR 2 on top of the BLADE v3 configuration system (`org.vorpal.blade.framework.v3.configuration`). It shares no code with FSMAR 2 — the two are independent libraries that ship as separate fat JARs. FSMAR 3 is the future; FSMAR 2 is retained only for backward compatibility with existing deployments and will eventually be phased out.

## What is FSMAR?

FSMAR uses state memory and pattern matching to route SIP traffic between applications in a *SIP Servlet Application Server*. Think of it as a router for audio/video streaming microservices. This allows developers to write tiny snippets of code (SIP Servlets) and string them together in an intelligent manner. The FSMAR knows who you are, where you've been, and where you're going.

## What's new in 3?

- Built on the v3 configuration framework — `RequestSelector` / `SelectorGroup` replace the ad-hoc per-header comparison lists used by FSMAR 2
- Flatter, more expressive JSON schema: `defaultApplication` + `states` map keyed by previous application name (with `"null"` for initial requests)
- First-class integration with the BLADE Flow editor (visual diagram of the state machine, round-tripped through `FsmarExport` / `FsmarImport` servlets in `admin/flow`)
- Dedicated log files (same as FSMAR 2)
- Dynamic config reloads (same as FSMAR 2)

## How does it work?

FSMAR 3 is not a WebLogic deployment — it's a fat JAR that lives in the OCCAS domain's `approuter/` directory and is activated via the OCCAS admin console. See the **FSMAR install walkthrough** in [DEPLOYMENT.md](../../DEPLOYMENT.md#fsmar-install-walkthrough) for the full procedure.

For automated installs, use `./deploy.sh <env> fsmar` from the repository root, which copies `vorpal-blade-library-fsmar3.jar` to the configured `approuter.dir` (locally or over SSH).

On first startup, FSMAR 3 writes a sample config into the OCCAS `_samples` directory alongside samples from every other BLADE app. Copy it into place, rename, and edit.

**Note:** FSMAR 2 (`vorpal-blade-library-fsmar.jar`) and FSMAR 3 (`vorpal-blade-library-fsmar3.jar`) both install to `approuter/`, but only one is activated at a time in the OCCAS admin console. Pick the version you want and configure OCCAS to use its `SipApplicationRouterProvider` SPI entry.

## Tutorial

FSMAR 3 is a finite state machine. Each *state* represents a SIP Servlet application. As messages flow through the system, they *transition* between states when a *trigger* (SIP method) matches a *selector group* (condition). The transition may fire an *action* that modifies routing region, subscriber URI, or pushed routes.

Here's the breakdown:

* **Previous State** — the origin of the message (`"null"` if it originated from an external system)
* **Trigger** — a SIP method, like `INVITE`, `REGISTER`, `SUBSCRIBE`
* **Condition** — a `SelectorGroup` of `RequestSelector`s evaluated against the incoming request
* **Action** — additional steps to take during the transition (region, subscriber URI, pushed routes)
* **Next State** — the destination application

## JSON Shape

```json
{
  "defaultApplication": "mediarouter",
  "states": {
    "null": {
      "triggers": {
        "INVITE": {
          "transitions": [
            {
              "id": "INV-1",
              "next": "b2bua",
              "condition": { "...": "SelectorGroup" },
              "action":    { "originating": "From", "route": ["sip:proxy1"] }
            }
          ]
        }
      }
    }
  }
}
```

See `AppRouterConfigurationSample.java` in this module for the full auto-generated sample, and `org.vorpal.blade.framework.v3.configuration.RequestSelector` for the condition DSL.

## Don't Overthink It

FSMAR can only route the first message in a dialog. For instance, it can route an `INVITE` through multiple applications, but it cannot tamper with the flow of subsequent in-dialog messages like `200 OK`, `ACK`, `INFO`, or `BYE`. Those follow their natural course through the system.

FSMAR also cannot modify a SIP message. If that's your goal, write a SIP Servlet application — check out the BLADE framework's B2BUA or PROXY APIs.

## Seamless Upgrades and Versioned Applications

FSMAR tracks applications as they are deployed and undeployed. New calls are routed to updated applications while existing calls continue through the previous versions until they complete naturally. FSMAR also supports dynamic configuration changes, so you never need to reboot a server or drop a call to update routing rules. FSMAR maintains a per-call copy of the configuration, so in-flight calls aren't affected by config edits.

Remember to update the `Weblogic-Application-Version` entry in your application's `META-INF/MANIFEST.MF` for seamless upgrades to work.
