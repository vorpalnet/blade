# BLADE

**B**lended **L**ayer **A**pplication **D**evelopment **E**nvironment

BLADE is an open-source collection of libraries and applications that
aide in the development real-time, audio-visual streaming applications.

The latest documentation can be found here: https://vorpal.net/javadocs/blade/
The company website can be found here: https://vorpal.net

BLADE is built on the Java EE JSR-359 (SIP Servlet) specification
and implemented / tested agains Oracle's OCCAS, a modified version of WebLogic
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

### Libraries

| Module | Description |
| --- | --- |
| Framework | A collection of Java libraries that simplify the creation of SIP Servlets beyond what's provided in JSR-359 |
| FSMAR | Finite State Machine Application Router; chain apps together to build sophisticated services |

### Admin

Deployed to the WebLogic AdminServer as skinny WARs that reference the `vorpal-blade` shared library.

| Module | Context Root | Description |
| --- | --- | --- |
| Console | `/blade` | Navigation shell — sidebar loads every other admin app via iframe |
| Configurator | `/configurator` | Configuration editor with JSON Schema forms, JMX-based schema discovery |
| Flow | `/flow` | FSMAR diagram editor (mxGraph) |
| Tuning | `/tuning` | JVM / SIP / OCCAS tuning knobs |
| File Manager | `/files` | WebSocket-based config file management |
| Explorer | `/explorer` | Experimental EasyUI forms |
| Watcher | `/watcher` | Log/event monitor |
| Javadoc | `/javadoc` | Browsable Javadoc site with UML class diagrams |

### Services

Deployed to the OCCAS cluster via the EAR. Each WAR includes the framework JAR; 3rd-party libraries come from the shared library.

| Module | Description |
| --- | --- |
| ACL | Access Control List; allow or deny calls through the system |
| Analytics | Call detail records and analytics |
| Hold | Music/media on hold |
| Options | Control the behavior of SIP OPTIONS requests |
| Presence | Maintains state of user endpoints |
| Proxy-Balancer | A simple load balancer |
| Proxy-Block | Block/reject calls based on rules |
| Proxy-Registrar | A small, elegant SIP proxy-registrar |
| Proxy-Router | The Reductive Reasoning Router (R3); supports various search algorithms for optimal routing |
| Queue | Call queuing and distribution |
| TPCC | Third-party call control |
| Transfer | Implements REFER for transfer applications |

### Test Applications

Deployed to the cluster alongside production applications. Excluded by the `production` build profile. Together, the Test UAC and Test UAS form a complete SIP load testing tool that replaces SIPp for production performance tuning.

| Module | Description |
| --- | --- |
| [Test B2BUA](test/test-b2bua) | An example B2BUA application |
| [Test UAC](test/test-uac) | SIP load generator and test client; CPS and concurrent modes, REST API for start/stop/status, 1000+ CPS per node |
| [Test UAS](test/test-uas) | Configurable test server; response status/delay/duration via REST API or SIP URI parameters, error map routing |



# Deployment Model

BLADE deploys in **four tiers**, each with its own scope:

```
OCCAS Domain
├── approuter/               ← (1) fsmar.jar         [engine-tier reboot]
├── AdminServer              ← (2) shared library  + (3) admin WARs
└── Cluster (engine tier)    ← (2) shared library  + (4) services EAR
```

See **[DEPLOYMENT.md](DEPLOYMENT.md)** for the full deployment guide, `./deploy.sh` reference, FSMAR install walkthrough, and troubleshooting.

# Project Layout

```
libs/           Libraries
  framework/      BLADE Framework (JAR)
  shared/         WebLogic shared library WAR (3rd-party JARs only)
  fsmar/          Finite State Machine Application Router (fat JAR)
admin/          Admin tools (deployed to AdminServer)
  console/        Admin Console — super-console dashboard (WAR, context: /blade)
  configurator/   Configuration Editor (WAR)
  dev-console/    Dev Console — experimental tools (WAR)
services/       Services (deployed to cluster via EAR)
  acl/            Access Control List
  analytics/      Call detail records and analytics
  hold/           Music/media on hold
  options/        SIP OPTIONS handling
  presence/       User endpoint state
  proxy-balancer/ Load balancer
  proxy-block/    Call blocking
  proxy-registrar/SIP proxy-registrar
  proxy-router/   Reductive Reasoning Router (R3)
  queue/          Call queuing and distribution
  tpcc/           Third-party call control
  transfer/       REFER-based call transfer
test/           Test applications (excluded by production profile)
  test-b2bua/     Example B2BUA
  test-uac/       REST-operated User Agent Client
  test-uas/       Configurable User Agent Server
javadoc/        Javadoc WAR (always built; -Pjavadocs regenerates per-module javadocs)
```

# Compiling

## Prerequisites

1. Java (version depends on target OCCAS platform — see table below)
2. Oracle OCCAS installed locally (8.0, 8.1, 8.2, or 8.3)

## One-Time Setup

### 1. Set the `$OCCAS` environment variable

`bootstrap.sh` and `build.sh` both look for an `OCCAS` env var pointing at your OCCAS installation root. Add this to your shell rc (`~/.zshrc`, `~/.bashrc`, etc.):

```bash
export OCCAS=/path/to/your/occas/install     # e.g. /Users/jeff/Oracle/occas-8.3
```

This is the **single source of truth** for "which OCCAS am I using right now." Both scripts read `$OCCAS/inventory/registry.xml` to derive the OCCAS and WebLogic versions automatically — you never need to type a version number.

To switch OCCAS versions, just point `$OCCAS` at a different install. No edits to build configs required.

You can keep multiple OCCAS installs side-by-side (e.g. `/Users/jeff/Oracle/occas-8.1`, `.../occas-8.3`) and switch between them by re-exporting `$OCCAS`.

### 2. Bootstrap OCCAS into your local Maven repo

```bash
./bootstrap.sh                  # uses $OCCAS
# or
./bootstrap.sh /path/to/occas   # explicit path overrides $OCCAS
```

Example output:

```
Installing OCCAS JARs from: /home/jetty/occas-8.3
  WebLogic version: 14.1.2
  OCCAS version:    8.3
```

This only needs to be run once per OCCAS version. The artifacts are installed into `~/.m2/repository/com/oracle/occas/` and `~/.m2/repository/com/oracle/weblogic/`, keyed by version. Multiple bootstrapped versions can coexist; the active one is determined at build time by `$OCCAS` (see "Platform auto-detection" below).

## Build

```bash
./build.sh
```

### Building Individual Modules

To build a single module without rebuilding the entire project, use `./mvnw -pl <module-path> package`. If the module depends on the framework library, install it first:

```bash
# install framework JAR to local Maven repo (needed if framework code has changed)
./mvnw -pl libs/framework install

# then build the individual module
./mvnw -pl admin/configurator package
./mvnw -pl services/proxy-router package
./mvnw -pl test/test-b2bua package
```

If the framework hasn't changed since your last full build, you can skip the install step and just build the module directly.

## Output

Every WAR/JAR built by the active profile is copied to `dist/<version>-<build>/`, along with the profile and platform conf files used. Example for the `default` profile:

```
dist/<version>-<build>/
  vorpal-blade-library-framework.jar         # Framework library
  vorpal-blade-library-shared.war            # WebLogic shared library
  vorpal-blade-library-fsmar.jar             # FSMAR (copy to OCCAS approuter lib/)
  vorpal-blade-admin-configurator.war        # Admin Configurator
  vorpal-blade-admin-watcher.war             # Admin Watcher
  vorpal-blade-services-<service>.war        # one WAR per service in the profile
  test-<name>.war                            # test apps if included
  default.conf                               # build profile used
  occas-<ver>.conf                           # platform profile used
  DEPLOYMENT.txt                             # generated manifest classifying every artifact
```

- The dist contents are driven by the active build profile (`build-profiles/*.conf`). Stale artifacts from previous builds in unrelated `target/` directories do **not** leak in — only modules listed in the active conf are copied.
- **EAR (currently disabled)**: the `services/` aggregator no longer produces a `vorpal-blade-services-<profile>.ear`. Services are deployed individually as WARs while the EAR logic is offline. See `services/pom.xml` for the TODO marker.
- **FSMAR JAR** must be installed manually into the OCCAS approuter `lib/` folder.
- **Admin WARs** are standalone (include all dependency JARs) and are deployed separately to AdminServer.
- On a failed build, the current build's `dist/` directory is deleted to prevent incomplete artifacts.

### Skipping the dist copy (dev mode)

The dist copy can get noisy during fast inner-loop development. Two ways to skip it:

```bash
./build.sh --no-dist             # one-off
export BLADE_SKIP_DIST=1         # sticky for the current shell
```

`--no-dist` on the CLI always wins, so you can opt back in for a single build even with the env var set: just don't pass `--no-dist`. (To force the env var off temporarily, run `BLADE_SKIP_DIST=0 ./build.sh ...`.)

### Deployment

BLADE deploys in four tiers — FSMAR, shared library, admin apps, and services EAR. See **[DEPLOYMENT.md](DEPLOYMENT.md)** for the full guide. The short version:

```bash
./build.sh production                 # produce dist/<ver>-<build>/
$EDITOR build-profiles/deploy/production.conf          # set host, targets, paths
cp build-profiles/deploy/production.secret.example \
   build-profiles/deploy/production.secret             # fill in wls.password
./deploy.sh production                # deploy all four tiers
```

## Build Number

Each `./build.sh` invocation auto-increments a build number stored in `build.number` (git-ignored). The number is embedded in every artifact's `MANIFEST.MF` as `Implementation-Version: <version>-<build>` (e.g. `2.9.3-67`). This ensures WebLogic sees a change on every build, enabling graceful redeployment even when the version hasn't changed.

## Build Profiles

The `build.sh` script accepts one or more **module profiles** (which apps to build), an optional **platform** (which OCCAS/Java version to target), and optional Maven arguments.

Each profile produces its own EAR named `vorpal-blade-services-<profile>.ear`. When multiple profiles are specified, all required modules are built once, then each profile's EAR is packaged separately.

```bash
./build.sh                              # full build, platform from $OCCAS
./build.sh production                   # vorpal-blade-services-production.ear
./build.sh production occas-8.2         # production services, OCCAS 8.2 (overrides $OCCAS)
./build.sh minimal occas-8.3            # core routing, OCCAS 8.3 (overrides $OCCAS)
./build.sh production minimal           # two EARs: production + minimal
./build.sh production clean package     # with explicit Maven goals
./build.sh -- -Pjavadocs                # full build with extra Maven flags
```

### Platform auto-detection

When you don't pass a platform on the command line, `build.sh` resolves it in this order:

1. **`$OCCAS` env var** (recommended). The script reads `$OCCAS/inventory/registry.xml` and picks the matching `build-profiles/platforms/occas-X.Y.conf`.
2. **Single bootstrapped version**. If exactly one OCCAS version is installed in `~/.m2/repository/com/oracle/occas/wlss/`, use that.
3. **Hardcoded fallback**: `occas-8.1`.

The chosen source is shown in parentheses in the build header so you can always tell where the platform came from:

```
Platform: occas-8.3 ($OCCAS)        # from environment
Platform: occas-8.3 (bootstrapped)  # only one bootstrapped, used by elimination
Platform: occas-8.3 (cli)           # passed as a build.sh argument
Platform: occas-8.1 (fallback)      # nothing else worked — printed with a warning
```

If `$OCCAS` is unset (or points somewhere invalid) **and** you didn't pass a platform on the CLI, `build.sh` prints a warning explaining how to fix it. To silence it, either export `$OCCAS` in your shell rc or always pass a platform on the command line.

A CLI platform always wins. This is intentional — useful for one-off cross-builds (e.g. you're pointed at OCCAS 8.3 but want to build for 8.1 without re-exporting).

Module profiles (`build-profiles/*.conf`):

| Profile | Description |
| --- | --- |
| `default` | Used when no profile is specified. Builds `framework`, `shared`, `fsmar`, configurator + watcher, most services (no `irouter`/`crud`), test apps. Notably **excludes `fsmar3`**. → `vorpal-blade-services-default.ear` |
| `full` | Every library, admin, service, and test module → `vorpal-blade-services-full.ear` |
| `production` | All libraries + admin apps + services (no test apps) → `vorpal-blade-services-production.ear` |
| `minimal` | `framework` + `shared` + proxy-registrar/proxy-router → `vorpal-blade-services-minimal.ear` |

Each conf file is a flat list of module directory names. Anything **not** listed is excluded with `-Dskip.<name>`. The four module categories — `libs/`, `admin/`, `services/`, `test/` — are all treated uniformly: any of them can be opted in or out.

> **Note**: most WARs depend on `framework` and `shared` at compile time. If you skip them in a build profile, they must already be installed in your local `~/.m2` from a prior build, or compilation will fail.

Platform profiles (`build-profiles/platforms/*.conf`):

| Platform | Java | WebLogic | OCCAS |
| --- | --- | --- | --- |
| `occas-8.0` | JDK 8 | 14.1.1 | 8.0 |
| `occas-8.1` | JDK 11 (default) | 14.1.1 | 8.1 |
| `occas-8.2` | JDK 17 | 14.1.2 | 8.2 |
| `occas-8.3` | JDK 21 | 14.1.2 | 8.3 |

The platform profile controls the Java compiler target, WebLogic dependency version, and OCCAS SIP API version. These are passed to Maven as `-Dblade.java.version`, `-Dblade.weblogic.version`, and `-Dblade.occas.version`.

To create a custom module profile, copy an existing `build-profiles/*.conf` file and edit it. Add or remove project directory names to control which modules are included.

## Javadocs

The javadoc module is always built and its WAR is copied to the `dist/` folder alongside other artifacts. By default it bundles previously generated javadoc output. To regenerate per-module javadocs (requires **JDK 23+** for Markdown comment support via [JEP 467](https://openjdk.org/jeps/467)):

```bash
./build.sh -- -Pjavadocs
```

This uses the [UML Doclet](https://github.com/talsma-ict/umldoclet) to generate class diagrams (SVG) alongside the standard Javadoc HTML, with Vorpal purple branding. All module javadocs are bundled into a deployable WAR:

```
dist/<version>-<build>/vorpal-blade-javadoc.war
```

Deploy this WAR to the AdminServer to browse javadocs at `/javadoc`. The index page links to each module's javadoc automatically — no build changes needed when adding new modules.

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

Use `./deploy.sh <env>` — see **[DEPLOYMENT.md](DEPLOYMENT.md)**. Per-tier Maven profiles (`-Pdeploy`, `-Pundeploy`, `-Pstop`, `-Pstart`) still exist under `services/pom.xml` as the underlying implementation; `deploy.sh` is the user-facing wrapper.

## Eclipse

Import as **Existing Maven Projects** (File > Import > Maven > Existing Maven Projects) and point at the repository root. Eclipse will discover all modules from their `pom.xml` files.
