# BLADE

**B**lended **L**ayer **A**ppliance **D**evelopment **E**nvironment

What once read like a choose-your-own-adventure book now reads like a poem — open-source callflows for carrier-grade call centers.🗡️ 

BLADE is an open-source collection of libraries and applications that
aid in the development of real-time, audio-visual streaming applications.

The documentation lives in this repository's READMEs — start with the module tables below.
To write a service, read the **[Framework Developer's Guide](DEVELOPING.md)**; to install one, read **[DEPLOYMENT.md](DEPLOYMENT.md)**.
The full API reference (Javadocs with UML diagrams) ships in the product itself, at `/blade/javadoc` on the [Admin Portal](admin/portal/README.md).
The company website can be found here: https://vorpal.net

BLADE is built on the Java EE JSR-359 (SIP Servlet) specification
and implemented / tested against Oracle's OCCAS, a modified version of WebLogic
designed to support the SIP protocol.

## Why BLADE?

Traditional SIP servlet development requires writing dozens of disconnected handler classes — `doInvite()`, `doResponse()`, `doAck()`, `doBye()` — with session state scattered across attributes that the developer must manually save and retrieve. It's like a choose-your-own-adventure book: the call logic jumps between methods, and you have to mentally trace attribute breadcrumbs to reconstruct the conversation flow.

BLADE replaces this with **lambda-based callflows** that read like a poem:

```java
sendRequest(bobRequest, (bobResponse) -> {
    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    sendResponse(aliceResponse, (aliceAck) -> {
        SipServletRequest bobAck = bobResponse.createAck();
        sendRequest(bobAck);
    });
});
```

The entire call flow — INVITE, wait for response, forward it, wait for ACK, forward it — is expressed top-to-bottom in a single method. The nested lambdas mirror the actual SIP message exchange. You can read the code and *see* the call.

The key innovation: **callflow state is automatically serialized.** The `Callflow` class implements `Serializable`, so the lambda callbacks and all local variables they close over (`aliceRequest`, `bobRequest`, etc.) are transparently persisted into SIP session memory by the OCCAS container. In a distributed cluster, if a node fails mid-call, the callflow resumes on another node with all its state intact — without the developer ever knowing.

What once required a complicated collection of Java classes is now a single class. What once read like a choose-your-own-adventure book now reads like a poem.

### Features

* **Lambda-based callflows** — express entire SIP conversations as readable, top-to-bottom code
* **Automatic state serialization** — callflow variables survive failover in distributed clusters
* **Pre-built callflow patterns** — B2BUA, Proxy, and Transfer patterns ready to extend
* **JSON-driven configuration** — dynamic config with JSON Schema validation, hot-reload via JMX
* **SIP-aware logging** — structured logs with sequence diagrams, ANSI color, per-application log files
* **Carrier-grade FSMAR** — Finite State Machine Application Router chains applications together into sophisticated services
* **One shared library** — application WARs carry only BLADE code; every 3rd-party JAR ships in `blade-shared`, so one library update patches the whole suite
* **Graceful overload drain** — an overloaded engine tells the load balancer to stop sending new calls; in-flight calls finish undisturbed
* **Circuit breakers with SNMP traps** — REST/LDAP/JDBC lookups stop dragging calls through timeouts; one trap down and one up per outage, not a storm

### Libraries

| Module | Description |
| --- | --- |
| [Framework](libs/framework/README.md) | A collection of Java libraries that simplify the creation of SIP Servlets beyond what's provided in JSR-359 — see the [v3 API](libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) and [v2 API](libs/framework/src/main/java/org/vorpal/blade/framework/v2/README.md) guides |
| [Shared](libs/shared/README.md) | The `blade-shared` WebLogic shared library — every 3rd-party JAR, centrally managed |
| [FSMAR](libs/fsmar/README.md) | Finite State Machine Application Router; chain apps together to build sophisticated services |

### Admin

Deployed to the WebLogic AdminServer as skinny WARs that reference the `blade-shared` shared library, bundled into one EAR by [apps/admin](apps/admin/README.md).

| Module | Context Root | Description |
| --- | --- | --- |
| [Portal](admin/portal/README.md) | `/blade/portal` | Unified admin shell — a launcher deck built live from JMX; apps appear automatically |
| [Redirect](admin/redirect/README.md) | `/` | 302s bare `/` and `/blade` to `/blade/portal/` |
| [Configurator](admin/configurator/README.md) | `/blade/configurator` | Configuration editor with JSON Schema forms, JMX-based schema discovery |
| [Flow](admin/flow/README.md) | `/blade/flow` | Visual FSMAR callflow editor — design routing as state-machine diagrams |
| [Tuning](admin/tuning/README.md) | `/blade/tuning` | JVM / SIP / OCCAS tuning over live JMX, with recommended presets and node drain/restart |
| [CRUD Editor](admin/crud-editor/README.md) | `/blade/crud-editor` | Translation-table editor with live rule preview |
| [Files](admin/files/README.md) | `/blade/files` | Whitelisted, versioned editing of schema-less domain files |
| [Logs](admin/logs/README.md) | `/blade/logs` | Cluster log tail viewer — merged, filtered, live |
| [Metrics](admin/metrics/README.md) | `/blade/metrics` | Cluster-wide counters, gauges, and latency for every app |
| [API Explorer](admin/api/README.md) | `/blade/api` | Live-discovered OpenAPI reference for every running service |
| [Trace](admin/callflow/README.md) | `/blade/callflow` | Record a call across the app chain, pinned to the source line that sent each message |
| [Phone](admin/phone/README.md) | `/blade/phone` | Browser softphone for the WebRTC gateway |
| [Analytics Console](admin/analytics-console/README.md) | `/blade/analytics` | Audit and provision the analytics pipeline's JMS/JDBC resources |
| [Events Console](admin/events-console/README.md) | `/blade/events` | Event catalog, code designer, and JMS administration |
| [Javadoc](admin/javadoc/README.md) | `/blade/javadoc` | Browsable Javadoc site with UML class diagrams |

### Services

Deployed to the OCCAS cluster as individual WARs — one per service, deliberately no EAR (see [DEPLOYMENT.md](DEPLOYMENT.md)). Each WAR includes the framework JAR; 3rd-party libraries come from the shared library.

| Module | Description |
| --- | --- |
| [Analytics](services/analytics/README.md) | Per-call records and metrics, written to a relational database off the event bus |
| [Context](services/context/README.md) | Captures raw inbound SIP headers; REST lookup and mutation per call |
| [CRUD](services/crud/README.md) | Rule-based SIP message rewriting — headers and bodies (XML/JSON/SDP), no code |
| [Events](services/events/README.md) | The BLADE CloudEvents bus — one pipeline for analytics and integrations |
| [Gateway](services/gateway/README.md) | SIP trunk gateway — registration and routing to carrier trunks |
| [Hold](services/hold/README.md) | Call parking — answers with RFC 3264 inactive SDP until the far end resumes the leg |
| [iRouter](services/irouter/README.md) | Universal, config-driven SIP proxy; two-phase enrichment + routing pipeline edited in the Configurator |
| [Options](services/options/README.md) | SIP OPTIONS handling and node lifecycle signaling |
| [Presence](services/presence/README.md) | SIP/SIMPLE presence endpoint (skeleton — accepts SUBSCRIBE/PUBLISH; NOTIFY fan-out on the roadmap) |
| [Proxy-Balancer](services/proxy-balancer/README.md) | A simple load balancer |
| [Proxy-Block](services/proxy-block/README.md) | Number-based translate-and-forward proxy (deny rules on the roadmap) |
| [Proxy-Registrar](services/proxy-registrar/README.md) | A small, elegant SIP proxy-registrar |
| [Queue](services/queue/README.md) | Call queuing and distribution |
| [TPCC](services/tpcc/README.md) | Third-party call control |
| [Transfer](services/transfer/README.md) | Implements REFER for transfer applications |

### Incubator (`proto/`)

New apps start in `proto/` — they build under the `full` profile but stay out of the everyday builds and the admin EAR until promoted.

| Module | Description |
| --- | --- |
| [ACL](proto/acl/README.md) | Allow or deny calls by remote IP address |
| [Balancer](proto/balancer/README.md) | Load-balancer prototype |
| [Demo](proto/demo/README.md) | The rep-facing demo launcher and matrix hub |
| [Player](proto/player/README.md) | Vendor-neutral JSR-309 media player/recorder |
| [Security](proto/security/README.md) | Admin-tier authentication configuration (JWT SSO) |
| [Test Console](proto/test-console/README.md) | Cluster-wide control surface for the test apps |
| [WebRTC](proto/webrtc/README.md) | WebRTC-to-SIP gateway |

### Test Applications

Deployed to the cluster alongside production applications (or to a standalone test server via [apps/test](apps/test/README.md)). Excluded by the `production` build profile. Together, the Test UAC and Test UAS form a complete SIP load testing tool that replaces SIPp for production performance tuning.

| Module | Description |
| --- | --- |
| [Test B2BUA](test/test-b2bua/README.md) | The reference B2BUA — functional test, template, and debugging aid |
| [Test UAC](test/test-uac/README.md) | SIP load generator and test client; CPS and concurrent modes, REST API for start/stop/status, 1000+ CPS per node |
| [Test UAS](test/test-uas/README.md) | Configurable test server; response status/delay/duration via REST API or SIP URI parameters, error map routing |



# Deployment Model

BLADE deploys in **four tiers**, each with its own scope:

```
OCCAS Domain
├── approuter/               ← (1) fsmar.jar         [engine-tier reboot]
├── AdminServer              ← (2) shared library  + (3) admin WARs
└── Cluster (engine tier)    ← (2) shared library  + (4) service WARs (one each)
```

See **[DEPLOYMENT.md](DEPLOYMENT.md)** for the full deployment guide, `./deploy.sh` reference, FSMAR install walkthrough, and troubleshooting.

# Project Layout

```
libs/           Libraries
  framework/      BLADE Framework (JAR) — baseline + v2 + v3 APIs
  shared/         blade-shared WebLogic shared library (3rd-party JARs only)
  fsmar/          Finite State Machine Application Router (fat JAR → approuter/)
admin/          Admin tools (deployed to AdminServer)
  portal/  redirect/  configurator/  flow/  crud-editor/  files/  tuning/
  logs/  metrics/  api/  callflow/  phone/  analytics-console/
  events-console/  javadoc/
services/       Services (one WAR each, deployed to the cluster)
  analytics/  context/  crud/  events/  gateway/  hold/  irouter/  options/
  presence/  proxy-balancer/  proxy-block/  proxy-registrar/  queue/  tpcc/
  transfer/
apps/           EAR packaging
  admin/          blade-admin.ear — the whole admin tier in one deployable
  test/           blade-test.ear — every service + test app, for a test server
proto/          Incubator — new apps start here (built by the full profile only)
  acl/  balancer/  demo/  player/  security/  test-console/  webrtc/
test/           Test applications (excluded by production profile)
  test-b2bua/     Reference B2BUA
  test-uac/       REST-operated User Agent Client
  test-uas/       Configurable User Agent Server
retired/        Legacy modules kept for reference — not built
  fsmar2/  proxy-router/  watcher/
```

# Compiling

## Prerequisites

1. Java (version depends on target OCCAS platform — see table below)
2. Oracle OCCAS installed locally (8.0, 8.1, 8.2, or 8.3)

## One-Time Setup

### 1. Set the `$MW_HOME` environment variable

`bootstrap.sh` and `build.sh` both look for an `MW_HOME` env var pointing at your OCCAS installation root. `MW_HOME` is the standard Oracle "Middleware Home" convention — the same variable required by OPatch and other Oracle tooling, so setting it once gives you a single source of truth across BLADE builds, patching, and deployment scripts.

Add this to your shell rc (`~/.zshrc`, `~/.bashrc`, etc.):

```bash
export MW_HOME=/path/to/your/occas/install     # e.g. /Users/jeff/Oracle/occas-8.3
```

Both scripts read `$MW_HOME/inventory/registry.xml` to derive the OCCAS and WebLogic versions automatically — you never need to type a version number.

To switch OCCAS versions, point `$MW_HOME` at a different install — no edits to build configs
required. You can keep multiple installs side-by-side (e.g. `/Users/jeff/Oracle/occas-8.1`,
`.../occas-8.3`). For a one-off build against a different version, pass the platform on the
command line instead of re-exporting: `./build.sh default occas-8.1 …` overrides `$MW_HOME` for that run.

> **Whichever way you switch, add `clean`.** Bytecode target is invisible to Maven's up-to-date
> check, so an incremental build after a switch silently ships classes compiled for the old
> target. See [Switching platforms requires `clean`](#switching-platforms-requires-clean).

### 2. Bootstrap OCCAS into your local Maven repo

```bash
./bootstrap.sh                  # uses $MW_HOME
# or
./bootstrap.sh /path/to/occas   # explicit path overrides $MW_HOME
```

Example output:

```
Installing OCCAS JARs from: /home/jetty/occas-8.3
  WebLogic version: 14.1.2
  OCCAS version:    8.3
```

This only needs to be run once per OCCAS version. The artifacts are installed into `~/.m2/repository/com/oracle/occas/` and `~/.m2/repository/com/oracle/weblogic/`, keyed by version. Multiple bootstrapped versions can coexist; the active one is determined at build time by `$MW_HOME` (see "Platform auto-detection" below).

## Build

A build **requires a profile** — BLADE is a development framework, so you name
what to build rather than always building everything:

```bash
./build.sh default        # the base set: every admin app + service, no proto/ incubator
```

Run `./build.sh` with no profile on a terminal and it lists the profiles and
offers to build a new one; without a terminal (CI, another build script) it
exits non-zero naming the choices. `./build.sh --list` shows them; `./build.sh
--init` builds a custom profile interactively (saved under `.conf/`, gitignored).
See **[Build Profiles](#build-profiles)** below. (Clean-only runs need no
profile: `./build.sh clean`.)

### Building Individual Modules

To build a single module without rebuilding the entire project, use `./mvnw -pl <module-path> package`. If the module depends on the framework library, install it first:

```bash
# install framework JAR to local Maven repo (needed if framework code has changed)
./mvnw -pl libs/framework install

# then build the individual module
./mvnw -pl admin/configurator package
./mvnw -pl services/irouter package
./mvnw -pl test/test-b2bua package
```

If the framework hasn't changed since your last full build, you can skip the install step and just build the module directly.

Two things `./mvnw -pl` does **not** do, because it bypasses `build.sh` entirely:

- **It doesn't read the platform profile.** The bytecode target falls back to the parent POM's
  `blade.java.version` default of `11` (`pom.xml`), whatever `$MW_HOME` points at. Java 11
  classes run fine on a newer JRE, so this is harmless by itself — but it leaves `target/`
  holding a mix of targets, and the next non-clean `./build.sh` ships that mix as-is. To match a
  platform, pass it: `./mvnw -pl admin/flow package -Dblade.java.version=21`.
- **It doesn't update `dist/`.** Only `./build.sh` copies artifacts there and regenerates
  `DEPLOYMENT.txt`. Deploy scripts read `dist/`, so a `-pl` build is for compiling and testing,
  not for producing something to deploy.

## Output

Every WAR/JAR built by the active profile is copied to `dist/<version>-<build>/`, organized into tier subdirectories matching where each artifact deploys. Library artifacts and build conf files stay at the root.

```
dist/<version>-<build>/
  blade-framework.jar         # Framework library (bundled in WARs; not deployed)
  blade-shared.war            # WebLogic shared library (admin + cluster)
  blade-fsmar.jar             # FSMAR (copy to OCCAS approuter/)
  blade-admin.ear             # Admin tier → AdminServer. Contains every admin WAR:
                              #   blade-portal.war      → /blade/portal
                              #   blade-redirect.war    → /blade (302s to /blade/portal/)
                              #   blade-configurator.war→ /blade/configurator
                              #   blade-crud.war        → /blade/crud-editor
                              #   blade-flow.war        → /blade/flow
                              #   blade-logs.war        → /blade/logs
                              #   blade-tuning.war      → /blade/tuning
                              #   blade-javadoc.war     → /blade/javadoc
  blade-test.ear              # Test tier → engine0 (test-b2bua, test-uac, test-uas)
  services/
    irouter.war                              # one WAR per service → cluster
    hold.war
    ...
  default.conf                               # build profile used
  occas-<ver>.conf                           # platform profile used
  DEPLOYMENT.txt                             # generated manifest classifying every artifact
```

There is **no `admin/` subdirectory**: the admin tier ships as one EAR, and the test apps ship
as `blade-test.ear` rather than as WARs under `services/`. `DEPLOYMENT.txt` in the same
directory is the authoritative per-artifact tier/target listing for the build you actually ran.

Admin-tier WARs are named `blade-<app>.war` so their WebLogic app names never collide with the like-named services-tier WARs (e.g. admin `blade-crud.war` vs. a service `crud.war`); `blade-configurator.war` still deploys at `/blade/configurator` because the WAR filename and the `<wls:context-root>` are independent. **A SIP servlet application is always named for its context root** — `<finalName>` equals `<wls:context-root>`, with no prefix (`hold.war` → `/hold`, `gateway.war` → `/gateway`). That name is what the container reports to the App Router, so it is the name FSMAR configs target; a prefixed WAR would deploy under a name no FSMAR `next` could resolve. The `blade-` prefix belongs to admin-tier WARs only, and there it doesn't always equal the context — `blade-crud.war` → `/blade/crud-editor`, `blade-analytics.war` → `/blade/analytics`, `blade-test.war` → `/blade/test-console` — those three are deliberately shortened.

- The dist contents are driven by the active build profile (`build-profiles/*.conf`). Stale artifacts from previous builds in unrelated `target/` directories do **not** leak in — only modules listed in the active conf are copied.
- **No services EAR — by design.** Service WARs are copied to `dist/<ver>-<build>/services/` and deploy one at a time. Oracle's Remote Console cannot show the status of an application bundled inside an EAR, so a single `blade-services.ear` left every service individually invisible: you could see the EAR was running, not which service inside it was. Separate WARs cost a longer deploy loop and buy per-service state, start/stop and targeting. The admin and test tiers keep their EARs (`blade-admin.ear`, `blade-test.ear`).
- **FSMAR JAR** must be installed manually into the OCCAS approuter `lib/` folder.
- **Admin WARs** are skinny like service WARs — `WEB-INF/lib` carries only the framework jar; 3rd-party JARs come from the `blade-shared` shared library. They deploy to AdminServer (as `blade-admin.ear`, or individually).
- On a failed build, the current build's `dist/` directory is deleted to prevent incomplete artifacts.

### Skipping the dist copy (dev mode)

The dist copy can get noisy during fast inner-loop development. Two ways to skip it:

```bash
./build.sh default --no-dist     # one-off
export BLADE_SKIP_DIST=1         # sticky for the current shell
```

`--no-dist` on the CLI always wins, so you can opt back in for a single build even with the env var set: just don't pass `--no-dist`. (To force the env var off temporarily, run `BLADE_SKIP_DIST=0 ./build.sh default ...`.)

### Deployment

BLADE deploys in four tiers — shared library, admin apps, services (+ test apps), and FSMAR. The `<env>.conf` profile is the single source of truth (admin URL, per-tier WebLogic targets, engine node list, approuter path, app allowlist); `./deploy.sh <env>` with no tier deploys the **whole environment** in dependency-safe order. See **[DEPLOYMENT.md](DEPLOYMENT.md)** for the full guide. The short version:

```bash
./build.sh default                    # produce dist/<ver>-<build>/
cp build-profiles/deploy/production.conf.example \
   build-profiles/deploy/production.conf               # then edit: adminurl, user, targets, engine.nodes
cp build-profiles/deploy/production.secret.example \
   build-profiles/deploy/production.secret             # fill in wls.password
# (env confs are gitignored — they carry your site's hostnames/IPs)

./deploy.sh production --dry-run      # sanity check the whole environment
./deploy.sh production                # deploy everything, in order: shared → fsmar → admin → services
./deploy.sh production services       # or just one tier (target read from the conf)
```

## Build Number

Each `./build.sh` invocation auto-increments a build number stored in `build.number` (git-ignored). The number is embedded in every artifact's `MANIFEST.MF` as `Implementation-Version: <version>-<build>` (e.g. `3.0.4-848`). This ensures WebLogic sees a change on every build, enabling graceful redeployment even when the version hasn't changed.

## Build Profiles

The `build.sh` script takes exactly one **module profile** (which apps to build — required for any build), an optional **platform** (which OCCAS/Java version to target), and optional Maven arguments.

A profile decides which modules are built, and therefore what lands in `dist/`: the admin tier as `blade-admin.ear`, the test tier as `blade-test.ear`, and each service as its own WAR under `dist/<ver>-<build>/services/`. The canonical profiles are:

| Profile | Builds |
|---|---|
| `default` | The base set — every admin app + service, but **not** the `proto/` incubator apps. |
| `full`    | `default` **plus** the `proto/` incubator apps (built standalone into `dist/proto/`). |
| `minimal` | Core routing only (framework + shared + proxy-registrar). |

Need a different subset? `./build.sh --init` builds one interactively and saves it under `.conf/` (gitignored, yours alone); rebuild it any time by name. `./build.sh --list` shows every profile, canonical and local.

```bash
./build.sh default                      # base set, platform from $MW_HOME
./build.sh default occas-8.2            # base set, OCCAS 8.2 (overrides $MW_HOME)
./build.sh minimal occas-8.3            # core routing, OCCAS 8.3 (overrides $MW_HOME)
./build.sh full                         # base set + proto/ incubator apps
./build.sh default clean package        # with explicit Maven goals
./build.sh default occas-8.1 clean package   # REQUIRED shape when switching platforms
./build.sh --no-javadoc default         # skip javadoc generation (fast dev loop)
./build.sh default -- -Dfoo=bar         # extra Maven flags
```

One profile per invocation: each build is one Maven reactor and produces one `blade-admin.ear` whose contents match that profile, so profiles can't be combined.

### Platform auto-detection

When you don't pass a platform on the command line, `build.sh` resolves it in this order:

1. **`$MW_HOME` env var** (recommended). The script reads `$MW_HOME/inventory/registry.xml` and picks the matching `build-profiles/platforms/occas-X.Y.conf`.
2. **Single bootstrapped version**. If exactly one OCCAS version is installed in `~/.m2/repository/com/oracle/occas/wlss/`, use that.
3. **Hardcoded fallback**: `occas-8.1`.

The chosen source is shown in parentheses in the build header so you can always tell where the platform came from:

```
Platform: occas-8.3 ($MW_HOME)        # from environment
Platform: occas-8.3 (bootstrapped)  # only one bootstrapped, used by elimination
Platform: occas-8.3 (cli)           # passed as a build.sh argument
Platform: occas-8.1 (fallback)      # nothing else worked — printed with a warning
```

If `$MW_HOME` is unset (or points somewhere invalid) **and** you didn't pass a platform on the CLI, `build.sh` prints a warning explaining how to fix it. To silence it, either export `$MW_HOME` in your shell rc or always pass a platform on the command line.

A CLI platform always wins. This is intentional — useful for one-off cross-builds (e.g. you're pointed at OCCAS 8.3 but want to build for 8.1 without re-exporting).

Module profiles (`build-profiles/*.conf`):

| Profile | Description |
| --- | --- |
| `default` | Used when no profile is specified. Builds `framework`, `shared`, `fsmar`, the admin tier, most services, test apps |
| `full` | Every library, admin, service and test module, plus the `proto/` incubator apps |
| `production` | All libraries + admin apps + services (no test apps) |
| `minimal` | `framework` + `shared` + core routing |

Each conf file is a flat list of module directory names. Anything **not** listed is excluded with `-Dskip.<name>`. The four module categories — `libs/`, `admin/`, `services/`, `test/` — are all treated uniformly: any of them can be opted in or out.

> **Note**: most WARs depend on `framework` and `shared` at compile time. If you skip them in a build profile, they must already be installed in your local `~/.m2` from a prior build, or compilation will fail.

Platform profiles (`build-profiles/platforms/*.conf`):

| Platform | Java | WebLogic | OCCAS |
| --- | --- | --- | --- |
| `occas-8.0` | JDK 11 | 14.1.1 | 8.0 |
| `occas-8.1` | JDK 11 (fallback default) | 14.1.1 | 8.1 |
| `occas-8.2` | JDK 17 | 14.1.2 | 8.2 |
| `occas-8.3` | JDK 21 | 14.1.2 | 8.3 |

The platform profile controls the Java compiler target, WebLogic dependency version, and OCCAS SIP API version. These are passed to Maven as `-Dblade.java.version`, `-Dblade.weblogic.version`, and `-Dblade.occas.version`.

### Switching platforms requires `clean`

**Always pass `clean` when you change platform.** Bytecode target is invisible to
Maven's up-to-date check: it compares source and class timestamps, not the version
the class was compiled for. `build.sh` does not clean between runs (deliberately —
see `copy_all_to_dist`), so after switching platforms it recompiles only the sources
you edited and repackages every untouched class at the *previous* target.

```bash
./build.sh default occas-8.1 clean package     # switching from 8.3 to 8.1
```

The build summary is not a safety net here: `Target: Java 11 bytecode` describes what
this run compiles, not what it ships. The symptom lands at deploy time, from WebLogic
rather than from the build:

```
java.lang.UnsupportedClassVersionError: .../FsmarMetaServlet has been compiled by a
more recent version of the Java Runtime (class file version 65.0), this version of
the Java Runtime only recognizes class file versions up to 55.0
```

Class-file 65 is Java 21, 55 is Java 11. To check a suspect artifact without deploying it:

```bash
unzip -p dist/<ver>-<build>/blade-admin.ear blade-flow.war > /tmp/f.war
unzip -p /tmp/f.war WEB-INF/classes/org/vorpal/blade/applications/console/mxgraph/FsmarMetaServlet.class \
  | head -c 8 | xxd -s 6 -l 2      # 0037 = 55 = Java 11; 0041 = 65 = Java 21
```

To create a custom module profile, copy an existing `build-profiles/*.conf` file and edit it. Add or remove project directory names to control which modules are included.

## Javadocs

Javadocs are generated **automatically** whenever the build JDK is **23 or newer** — BLADE's
source uses Java 23+ Markdown `///` doc comments ([JEP 467](https://openjdk.org/jeps/467)), so
the javadoc tool has to come from a JDK that understands them. This is independent of the
bytecode target: docs generate on JDK 23+ while `--release` still compiles to Java 11.

On an older build JDK the docs are skipped with a warning and the build itself is fine. Either
way the build summary says which happened:

```
Javadocs:      generating (-Pjavadocs → admin/javadoc → blade-javadoc.war)
Javadocs:      SKIPPED — needs JDK 23+ (build JDK is 21); admin EAR built without blade-javadoc.war
```

To skip generation deliberately (fast dev loops — the javadoc WAR is ~150 MB and the slowest
module in the build):

```bash
./build.sh default --no-javadoc  # one-off
export BLADE_SKIP_JAVADOC=1      # sticky for the current shell
```

Skipping means the admin EAR assembles **without** `blade-javadoc.war`, so `/blade/javadoc`
404s on that deployment. Passing `-Pjavadocs` by hand still works but is the legacy form; it is
no longer needed.

This uses the [UML Doclet](https://github.com/talsma-ict/umldoclet) to generate class diagrams (SVG) alongside the standard Javadoc HTML, with Vorpal purple branding. All module javadocs are bundled into `blade-javadoc.war`, which ships **inside the admin EAR** — admin WARs are not copied to `dist/` individually:

```
dist/<version>-<build>/blade-admin.ear   →   blade-javadoc.war
```

Deploying the admin EAR serves the javadocs at `/blade/javadoc` (it appears as a card on the portal). The index page links to each module's javadoc automatically — no build changes needed when adding new modules.

### Markdown Javadoc Comments

Javadoc comments can be written using Markdown syntax with `///` triple-slash comments ([JEP 467](https://openjdk.org/jeps/467)). For example:

```java
/// Returns the **session** associated with this request.
///
/// - If `create` is `true`, creates a new session when none exists.
/// - Returns `null` otherwise.
///
/// @param create whether to create a new session
/// @return the session, or `null`
public SipSession getSession(boolean create) { ... }
```

Traditional `/** */` comments remain fully compatible and can coexist with `///` comments — migrate gradually as you see fit.

## Deploy

Use `./deploy.sh <env>` — see **[DEPLOYMENT.md](DEPLOYMENT.md)**. It deploys the whole environment in dependency-safe order (shared → fsmar → admin → services), or one tier at a time; the services tier loops the WARs in `dist/<ver>-<build>/services/`, narrowable with `deploy.services` in the env conf.

## Eclipse

Import as **Existing Maven Projects** (File > Import > Maven > Existing Maven Projects) and point at the repository root. Eclipse will discover all modules from their `pom.xml` files.
