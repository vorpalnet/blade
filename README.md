# BLADE

**B**lended **L**ayer **A**pplication **D**evelopment **E**nvironment

tl;dr...
1) install Java (JDK 11 for OCCAS 8.1, JDK 21 for OCCAS 8.3)
2) download & install OCCAS (with WebLogic and OCCAS patches)
3) run `./install-occas.sh /path/to/occas` (one-time setup, auto-detects version)
4) run `./build.sh` to build the EAR (produces `vorpal-blade-services-full.ear`)
5) deploy the EAR to OCCAS
6) install (and configure) the FSMAR — it chains apps together into callflows
7) nothing will work, everything will fail. check the logs in `<domain>/servers/engine(?)/logs/vorpal/<app>.log`

If this sounds insane, yes, it is!
You can find more instructions at https://vorpal.net

BLADE is a development framework and collection of pre-built
applications built on the Java EE JSR-359 (SIP Servlet) specification.

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

The key innovation: **callflow state is automatically serialized.** The `Callflow` class implements `Serializable`, so the lambda callbacks and all local variables they close over (`aliceRequest`, `bobRequest`, etc.) are transparently persisted into SIP session memory by the OCCAS container. In a distributed cluster, if a node fails mid-call, the callflow resumes on another node with all its state intact — without the developer writing a single `setAttribute()` or `getAttribute()` call.

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

Deployed to the WebLogic AdminServer as standalone WARs.

| Module | Context Root | Description |
| --- | --- | --- |
| Console | `/blade` | Admin dashboard; sidebar loads configurator and other tools via iframe |
| Configurator | `/vorpal-blade-admin-configurator` | Configuration editor with JSON Schema forms, JMX-based schema discovery |
| Dev Console | `/vorpal-blade-admin-dev-console` | Experimental tools: mxGraph diagrams, EasyUI, old JSON editor |
| Javadoc | `/vorpal-blade-javadoc` | Browsable Javadoc site with UML class diagrams |

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

Install the OCCAS JARs into your local Maven repository. The script auto-detects the WebLogic and OCCAS versions from the installation directory:

```bash
./install-occas.sh /path/to/occas
```

Example output:

```
Installing OCCAS JARs from: /home/jetty/occas-8.3
  WebLogic version: 14.1.2
  OCCAS version:    8.3
```

This only needs to be run once per OCCAS version. If you switch OCCAS versions, re-run the script pointing to the new installation.

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

All deployable artifacts are copied to `dist/<version>-<build>/`:

```
dist/<version>-<build>/
  vorpal-blade-services-<profile>.ear    # EAR named after build profile (deploy to engine targets)
  vorpal-blade-library-framework.jar     # Framework library
  vorpal-blade-library-shared.war        # WebLogic shared library (alternative to EAR)
  vorpal-blade-library-fsmar.jar         # FSMAR (copy to OCCAS approuter lib/)
  vorpal-blade-admin-console.war         # Admin Console (deploy to AdminServer, context: /blade)
  vorpal-blade-admin-configurator.war    # Admin Configurator (deploy to AdminServer)
  vorpal-blade-admin-dev-console.war     # Dev Console (deploy to AdminServer, experimental)
  <profile>.conf                         # Build profile used for this build
  <platform>.conf                        # Platform profile used for this build
```

- **FSMAR JAR** must be installed manually into the OCCAS approuter `lib/` folder.
- **Admin WARs** are standalone (include all dependency JARs) and are deployed separately to AdminServer.
- On a successful build, any previous `dist/` directories are automatically zipped and the directories removed. The current build's directory is left unzipped.
- On a failed build, the current build's `dist/` directory is deleted to prevent incomplete artifacts.

### Deployment Options

There are two ways to deploy BLADE applications to the OCCAS cluster:

**Option 1: EAR deployment (recommended)**
Deploy the EAR (e.g. `vorpal-blade-services-production.ear`) to the cluster. The EAR bundles all service WARs — each WAR includes the framework JAR, and 3rd-party libraries are provided by the shared library. No JARs in the EAR `lib/`. This is the simplest approach — one artifact, one deployment.

**Option 2: Shared library + individual WARs**
Deploy `vorpal-blade-library-shared.war` as a WebLogic shared library (contains 3rd-party JARs only), then deploy individual application WARs separately. Each WAR includes the framework JAR and references the shared library for 3rd-party dependencies in their `weblogic.xml`:

```xml
<library-ref>
    <library-name>vorpal-blade</library-name>
</library-ref>
```

This approach allows you to deploy, update, or remove individual applications without redeploying the entire suite. Individual WARs are self-sufficient (framework included) and only need the shared library for 3rd-party dependencies. This is also the required approach for customers building their own BLADE applications — deploy the shared library once, then deploy your custom WARs alongside it.

## Build Number

Each `./build.sh` invocation auto-increments a build number stored in `build.number` (git-ignored). The number is embedded in every artifact's `MANIFEST.MF` as `Implementation-Version: <version>-<build>` (e.g. `2.9.3-67`). This ensures WebLogic sees a change on every build, enabling graceful redeployment even when the version hasn't changed.

## Build Profiles

The `build.sh` script accepts one or more **module profiles** (which apps to build), an optional **platform** (which OCCAS/Java version to target), and optional Maven arguments.

Each profile produces its own EAR named `vorpal-blade-services-<profile>.ear`. When multiple profiles are specified, all required modules are built once, then each profile's EAR is packaged separately.

```bash
./build.sh                              # full build, OCCAS 8.1 (defaults)
./build.sh production                   # vorpal-blade-services-production.ear
./build.sh production occas-8.2         # production services, OCCAS 8.2
./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
./build.sh production minimal           # two EARs: production + minimal
./build.sh production clean package     # with explicit Maven goals
./build.sh -- -Pjavadocs                # full build with extra Maven flags
```

Module profiles (`build-profiles/*.conf`):

| Profile | Description |
| --- | --- |
| `full` | All 15 service and test modules (default) → `vorpal-blade-services-full.ear` |
| `production` | 12 production services, no test apps → `vorpal-blade-services-production.ear` |
| `minimal` | Just proxy-registrar and proxy-router → `vorpal-blade-services-minimal.ear` |

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

## Deploy to WebLogic/OCCAS

Manage the EAR on a running WebLogic/OCCAS server:

```bash
./build.sh -- verify -Pdeploy   -Dwls.password=secret   # deploy (or update)
./build.sh -- verify -Pundeploy -Dwls.password=secret   # remove
./build.sh -- verify -Pstop     -Dwls.password=secret   # stop without removing
./build.sh -- verify -Pstart    -Dwls.password=secret   # start a stopped app
```

The defaults are `t3://localhost:7001`, user `weblogic`, and target `AdminServer`. Override with `-Dwls.adminurl=...`, `-Dwls.user=...`, `-Dwls.targets=...`.

## Eclipse

Import as **Existing Maven Projects** (File > Import > Maven > Existing Maven Projects) and point at the repository root. Eclipse will discover all modules from their `pom.xml` files.
