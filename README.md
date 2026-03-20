# Vorpal:BLADE

**B**lended **L**ayer **A**pplication **D**evelopment **E**nvironment

tl;dr...
1) install Java 11 (from Oracle)
2) download & install OCCAS
2.1) install weblogic patches
2.2) install occas patches
3) run `./install-occas.sh /path/to/occas-8.1` (one-time setup)
4) run `./build.sh` to build the EAR
5) deploy the EAR to OCCAS
6) install (and configure) the fsmar, it will allow you to string apps together during a callflow
7) nothing will work, everything will fail. check the logs in `<domain>/servers/engine(?)/logs/vorpal/<app>.log`

If this sounds insane, yes, it is!
You can find more instructions at https://vorpal.net

BLADE is a development framework and collection of pre-built
applications build on the Java EE JSR-359 (SIP Servlet) specification.

What makes BLADE so great?

It comes with a framework library that features:
* Support for lambda expressions to simplify callflow design and readability
* Prebuilt callflows for common use cases, B2BUA, Transfer & Proxy
* Application templates
* Support for dynamic configuration files
* Improved logging capabilities

It also comes with a carrier-grade Finite State Machine Application Router, the FSMAR.

### Libraries

| Module | Description |
| --- | --- |
| Framework | A collection of Java libraries that simplify the creation of SIP Servlets beyond what's provided in JSR-359 |
| FSMAR | Finite State Machine Application Router; chain apps together to build sophisticated services |

### Admin

Deployed to the WebLogic AdminServer as standalone WARs.

| Module | Description |
| --- | --- |
| Blade Console | Admin console for managing and monitoring BLADE applications |
| Configurator | Web-based configuration editor for application settings |
| Javadoc | Browsable Javadoc site with UML class diagrams (built with `-Pjavadocs`) |

### Services

Deployed to the OCCAS cluster as skinny WARs inside the EAR.

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

Deployed to the cluster alongside production applications. Excluded by the `production` build profile.

| Module | Description |
| --- | --- |
| Test B2BUA | An example B2BUA application |
| Test UAC | A REST-operated User Agent Client |
| Test UAS | A test User Agent Server; controllable through SIP Request-URI parameters |



# Project Layout

```
libs/           Libraries
  framework/      Vorpal:BLADE Framework (JAR)
  shared/         WebLogic shared library (WAR)
  fsmar/          Finite State Machine Application Router (fat JAR)
admin/          Admin tools (deployed to AdminServer)
  console/        Admin Console (WAR)
  configurator/   Configuration Editor (WAR)
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
javadoc/        Javadoc WAR (built with -Pjavadocs)
```

# Compiling

## Prerequisites

1. Java 11 (from Oracle)
2. Oracle OCCAS 8.1 installed locally

## One-Time Setup

Install the OCCAS JARs into your local Maven repository:

```bash
./install-occas.sh /path/to/occas-8.1
```

## Build

```bash
./build.sh
```

## Output

All deployable artifacts are copied to `dist/<version>-<build>/`:

```
dist/<version>-<build>/
  vorpal-blade-services.ear        # EAR (deploy to engine targets)
  vorpal-blade-library-framework.jar   # Framework library
  vorpal-blade-library-shared.war      # WebLogic shared library (alternative to EAR)
  vorpal-blade-library-fsmar.jar       # FSMAR (copy to OCCAS approuter lib/)
  vorpal-blade-admin-console.war       # Admin Console (deploy to AdminServer)
  vorpal-blade-admin-configurator.war  # Admin Configurator (deploy to AdminServer)
```

- **FSMAR JAR** must be installed manually into the OCCAS approuter `lib/` folder.
- **Admin WARs** are standalone (include all dependency JARs) and are deployed separately to AdminServer.

### Deployment Options

There are two ways to deploy BLADE applications to the OCCAS cluster:

**Option 1: EAR deployment (recommended)**
Deploy `vorpal-blade-services.ear` to the cluster. The EAR bundles all service WARs (skinny), the framework JAR, and third-party JARs in `lib/`. This is the simplest approach — one artifact, one deployment.

**Option 2: Shared library + individual WARs**
Deploy `vorpal-blade-library-shared.war` as a WebLogic shared library, then deploy individual application WARs separately. Applications reference the shared library in their `weblogic.xml`:

```xml
<library-ref>
    <library-name>vorpal-blade</library-name>
</library-ref>
```

This approach allows you to deploy, update, or remove individual applications without redeploying the entire suite. It is also the required approach for customers building their own BLADE applications — deploy the shared library once, then deploy your custom WARs alongside it.

## Build Number

Each `./build.sh` invocation auto-increments a build number stored in `build.number` (git-ignored). The number is embedded in every artifact's `MANIFEST.MF` as `Implementation-Version: <version>-<build>` (e.g. `2.9.3-67`). This ensures WebLogic sees a change on every build, enabling graceful redeployment even when the version hasn't changed.

## Build Profiles

The `build.sh` script accepts one or more **module profiles** (which apps to build) and an optional **platform** (which OCCAS/Java version to target). When multiple profiles are specified, the script compiles all modules once, then assembles a separate EAR for each profile (e.g. `vorpal-blade-services-production.ear`, `vorpal-blade-services-minimal.ear`).

```bash
./build.sh                              # full build, OCCAS 8.1 (defaults)
./build.sh production                   # production EAR, OCCAS 8.1
./build.sh production minimal           # two EARs in one build
./build.sh production occas-8.2         # production EAR, OCCAS 8.2
./build.sh production minimal occas-8.2 # two EARs, OCCAS 8.2
./build.sh production clean package     # with explicit Maven goals
./build.sh -- -Pjavadocs                # full build with extra Maven flags
```

Module profiles (`build-profiles/*.conf`):

| Profile | Description |
| --- | --- |
| `full` | All 16 service and test modules (default) |
| `production` | 13 production services, no test apps |
| `minimal` | Just proxy-registrar and proxy-router |

Platform profiles (`build-profiles/platforms/*.conf`):

| Platform | Java Version |
| --- | --- |
| `occas-8.0` | JDK 8 |
| `occas-8.1` | JDK 11 (default) |
| `occas-8.2` | JDK 17 |
| `occas-8.3` | JDK 21 (Beta) |

To create a custom module profile, copy an existing `build-profiles/*.conf` file and edit it. Add or remove project directory names to control which modules are included.

## Javadocs

Javadoc generation is off by default. To build with Javadocs:

```bash
./build.sh -- -Pjavadocs
```

This uses the [UML Doclet](https://github.com/talsma-ict/umldoclet) to generate class diagrams (SVG) alongside the standard Javadoc HTML. All module javadocs are bundled into a deployable WAR:

```
javadoc/target/javadoc-<version>.war
```

Deploy this WAR to the AdminServer to browse javadocs at `/javadoc`. The index page links to each module's javadoc automatically — no build changes needed when adding new modules.

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
