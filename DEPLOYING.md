# Deploying BLADE

`./deploy.sh` pushes built artifacts to a running server. This is the third of the
three stages — see [INSTALLING.md](INSTALLING.md) for standing up the server and
[BUILDING.md](BUILDING.md) for producing the artifacts.

| Tool | Job |
|---|---|
| `./install.sh` | Stand up the server. |
| `./build.sh` | Compile the artifacts into `dist/`. |
| **`./deploy.sh`** | Push the artifacts to a running server. |

BLADE deploys in **four tiers**, each with its own scope; the artifact-to-target map
falls out of it.

```
OCCAS Domain
├── approuter/               ← (1) fsmar.jar  [engine-tier reboot]
├── AdminServer              ← (2) shared library  + (3) blade-admin.ear
└── Cluster (engine tier)    ← (2) shared library  + (4) services/*.war
```

The shared library appears in two rows on purpose: it is **deployed to both AdminServer and the cluster**, because both sets of apps reference it.

## The four tiers

### 1. FSMAR — `fsmar.jar`

**Not a WebLogic deployment.** FSMAR is the *Finite State Machine Application Router*, loaded by OCCAS before any servlet application sees a SIP message. It lives in a special OCCAS-specific directory (`$DOMAIN_HOME/approuter/`) and is activated in the OCCAS admin console, not in the WebLogic deployments view.

BLADE ships **one FSMAR library** as a fat JAR:

| Artifact | Library | Status |
|---|---|---|
| `blade-fsmar.jar` | FSMAR | Current (formerly "FSMAR 3") |

OCCAS loads its SPI entry (`META-INF/services/javax.servlet.sip.ar.spi.SipApplicationRouterProvider`) at boot. The original **FSMAR 2** is retired (`retired/fsmar2/`, excluded from the standard build) and is no longer shipped.

- **Artifact:** `dist/<ver>-<build>/blade-fsmar.jar` — a fat JAR with every dependency bundled in.
- **Goes to:** `$DOMAIN_HOME/approuter/` on every engine-tier host. (Newer OCCAS versions support an `approuter/lib/` subdirectory for dependency JARs, but because BLADE ships FSMAR as fat JARs you drop the single files directly into `approuter/`.)
- **Activation:** configure OCCAS to use the chosen FSMAR via the admin console — the exact navigation changes between OCCAS versions, so check the OCCAS docs for your version.
- **Takes effect after:** engine-tier server restart (not AdminServer).
- **Why it's different:** FSMAR is code that runs *inside OCCAS itself*, not an application deployed on top of it. It cannot be hot-updated or targeted the way WARs/EARs can.

### 2. Shared library — `blade-shared.war`

A WebLogic shared library with `Extension-Name: blade-shared`, containing every 3rd-party JAR that BLADE needs (Jackson, Swagger, JSON Schema, etc.). Every other BLADE application (admin and services) references it via `<library-ref>` in `weblogic.xml` instead of bundling its own copies.

- **Artifact:** `dist/<ver>-<build>/blade-shared.war`
- **Goes to:** **Both** AdminServer **and** the engine cluster — because both admin apps and services apps reference it.
- **Why it's deployed twice:** WebLogic shared libraries are scoped to deployment targets. An admin app on AdminServer can only resolve a library that's also deployed to AdminServer, and the same goes for the cluster.
- **Updating:** bumping a 3rd-party version requires one shared-library redeploy, not a rebuild of every service.

### 3. Admin apps — `blade-admin.ear` (root) / `dist/<ver>/admin/` (loose)

Management tools that run **only on AdminServer**. The whole-tier EAR sits at the dist root (`dist/<ver>/blade-admin.ear`); the same apps ship loose under `dist/<ver>/admin/`. Deploy the EAR in one step, or one WAR at a time — your call, per deploy. Each bundled WAR is self-contained exactly as it deploys standalone — it carries the framework jar and references the `blade-shared` shared library via its own `weblogic.xml`. The EAR is a packaging convenience over those WARs; it bundles no libraries itself. The bundled web modules and their (unchanged) context-roots:

| Source module | WAR (in EAR) | Context root | Purpose |
|---|---|---|---|
| `admin/portal` | `blade-portal.war` | `/blade/portal` | Unified admin shell — left rail hosts every other admin app via iframe |
| `admin/redirect` | `blade-redirect.war` | `/` | Default web app; 302s bare `/blade` to `/blade/portal/` |
| `admin/api` | `blade-api.war` | `/blade/api` | Scalar-based OpenAPI explorer |
| `admin/configurator` | `blade-configurator.war` | `/blade/configurator` | JSON Schema-based config editor, JMX-backed |
| `admin/crud-editor` | `blade-crud.war` | `/blade/crud-editor` | CRUD service config editor |
| `admin/flow` | `blade-flow.war` | `/blade/flow` | Visual FSMAR diagram editor (mxGraph) |
| `admin/tuning` | `blade-tuning.war` | `/blade/tuning` | JVM / SIP / OCCAS tuning knobs |
| `admin/logs` | `blade-logs.war` | `/blade/logs` | Cluster log tail viewer |
| `admin/analytics-console` | `blade-analytics.war` | `/blade/analytics` | Analytics admin endpoints (distinct from the analytics cluster service) |
| `admin/javadoc` | `blade-javadoc.war` | `/blade/javadoc` | Browsable Javadoc with UML diagrams |

- **Why AdminServer only:** admin apps expose management endpoints; deploying them to the cluster would expose those endpoints on every engine node and duplicate state.
- **EAR or loose WAR:** deploy `blade-admin.ear` for the whole tier, or a single `blade-*.war` from `dist/<ver>/admin/` for a one-app test. Both are in the same directory.
- **Watcher is retired.** The headless config auto-publish shim (`blade-watcher.war`) moved to `retired/watcher/` and is no longer built or shipped — the Configurator's auto-publish covers the same need. (It was always standalone-only, never in the EAR.)

### 4. Services + test apps — `blade-services.ear` / `blade-test.ear` (root), loose in `services/` and `test/`

The SIP service applications (Analytics, Hold, Proxy-Registrar, Gateway, etc.). The whole-tier EARs `blade-services.ear` and `blade-test.ear` sit at the dist root; the same apps ship loose under `dist/<ver>/services/` and `dist/<ver>/test/`. Deploy whichever suits you:

- **Loose WARs** — one WebLogic deployment per service (`hold`, `gateway`, …), each **individually visible** in Remote Console with its own state, start/stop and targeting. This is the model to reach for when you want per-service control (Remote Console cannot see inside an EAR).
- **The EAR** — the whole tier in one deployment, when a single unit is more convenient than a longer per-WAR loop.

Each WAR is self-contained exactly as it deploys standalone: framework JAR inside + the `blade-shared` shared-library reference in its own `weblogic.xml` (filenames match the context-root: `hold.war`, `gateway.war`).

- **Goes to:** the **cluster only** (engine tier). Services handle live SIP traffic; AdminServer doesn't.
- **Test apps** (promoted to production 2026-06-05 as live-diagnostics tools): `test-uac`, `test-uas`, `test-b2bua` in `dist/<ver>/test/`.

## Quick start

```bash
./build.sh                     # dev build -> flat dist/  (--prod -> dist/<rev>-<build>/)
./deploy.sh production           # no profile yet? deploy.sh builds ~/.blade/production/profile.conf interactively
./deploy.sh production --all     # deploy the whole build, in dependency order
```

`--all` deploys the shared library first, then the admin EAR, the service WARs, and
the test EAR — the order that makes every WAR's `<library-ref>` (≥ spec-version 3.0)
resolve before the WARs that reference it. It reads the WebLogic target names from
the profile and stops the batch at once if the AdminServer is unreachable.
`./deploy.sh production --all undeploy` reverses it.

For finer control, deploy **one artifact at a time** — name the exact file and its
target, and `deploy.sh` does exactly that:

```bash
./deploy.sh production blade-shared.war   AdminServer,cluster1 --library   # shared library → both
./deploy.sh production blade-fsmar.jar    --approuter                      # FSMAR jar into approuter/
./deploy.sh production blade-admin.ear    AdminServer                      # admin tier as one EAR ...
./deploy.sh production blade-portal.war   AdminServer                      #   ... or a single admin WAR
./deploy.sh production blade-services.ear cluster1                         # services tier as one EAR ...
./deploy.sh production gateway.war        cluster1                         #   ... or one loose service WAR
```

After changing the FSMAR jar, **restart the engine tier** so each engine re-fetches
the App Router from the admin.

## `./deploy.sh` reference

```
./deploy.sh <env> [<file> [target]] [action] [--all] [--library|--approuter] [--name NAME] [--build VER] [--dry-run]
```

`<env>` is a conf name (→ `~/.blade/<env>/profile.conf`, or `build-profiles/deploy/<env>.conf`)
or a path; with no profile there yet, `deploy.sh` builds one interactively, and
`./deploy.sh <env>` with no file just builds or updates it. `<file>` is the exact
artifact — a path, or a bare filename found in the newest `dist/<ver>/` tree
(searched across `lib/ admin/ services/ test/ proto/`). `[target]` is the WebLogic
target (server or cluster name); if omitted, `wls.target` from the conf is used.
`[action]` ∈ `deploy | undeploy | status`, default `deploy`. `--all` deploys (or
undeploys) the whole build in dependency order instead of one named file.

**Engine.** `deploy.sh` runs the deploy through the OCCAS install's own `wlst.sh`
when an OCCAS home is resolvable — so it runs on the AdminServer host, which has no
Maven — and otherwise through the WebLogic Maven plugin.
`BLADE_DEPLOY_ENGINE=wlst|maven` overrides.

| Invocation | Effect |
|---|---|
| `./deploy.sh production --all` | Deploy the whole build in order: shared lib → admin → services → test |
| `./deploy.sh production blade-admin.ear AdminServer` | Deploy the admin EAR to AdminServer |
| `./deploy.sh production gateway.war cluster1` | Deploy one service WAR to the cluster |
| `./deploy.sh production blade-services.ear cluster1` | Deploy the whole services tier as one EAR |
| `./deploy.sh production blade-shared.war cluster1 --library` | Deploy as a WebLogic shared library |
| `./deploy.sh production blade-fsmar.jar --approuter` | Copy the FSMAR jar into `approuter.dir` (no WebLogic) |
| `./deploy.sh production gateway.war cluster1 undeploy` | Undeploy the app named `gateway` |
| `./deploy.sh production blade-admin.ear AdminServer status` | `list-apps` against WebLogic |
| `./deploy.sh production gateway.war cluster1 --build 3.0.5-880` | Take the file from a specific dist build |
| `./deploy.sh production ./dist/3.0.6-908/admin/blade-portal.war AdminServer --dry-run` | Full path; print, change nothing |

The deployment name defaults to the filename without its extension
(`blade-admin.ear` → `blade-admin`, `gateway.war` → `gateway`); override with
`--name`. Admin-tier WARs are prefixed `blade-` so their app names never collide
with the like-named services-tier WARs (`gateway`, `hold`, …). The context-root is
unchanged by the WAR name — `blade-configurator.war` still deploys at
`/blade/configurator`.

### Deploy profiles

One file per environment, `~/.blade/<env>/profile.conf`, shared with `install.sh` and `build.sh` — it holds
the connection and the (encrypted) secrets. `deploy.sh` builds it interactively when
it doesn't exist yet.

| Key | Purpose |
|---|---|
| `wls.adminurl` | AdminServer URL (`t3://…` or `t3s://…`) |
| `wls.user` | WebLogic admin user |
| `admin.password=ENC(…)` | admin password, ENC()-wrapped (offered on first prompt) |
| `wls.target` | *(optional)* default WebLogic target when none is given on the CLI |
| `wls.targets.admin` / `.cluster` / `.both` / `.test` | *(optional)* the target names `--all` deploys each tier to |
| `oracle.home` | *(optional)* OCCAS home for the `wlst` engine (auto-detected on the server) |
| `tls.trust.passphrase` | *(t3s)* passphrase for `~/.blade/<env>/blade-trust.p12`, the CA trust store |
| `approuter.dir` | *(optional)* domain `approuter/` path for `--approuter` |
| `wls.plugin.version` | *(optional)* override the weblogic-maven-plugin version (maven engine) |

Secret safeguards: `deploy.sh` runs `git check-ignore` on the conf if it's inside
the repo and refuses to use a trackable one; a conf under `~/.blade` (outside the
repo) is fine. Password priority (highest wins): `BLADE_WLS_PASSWORD` env var →
`admin.password` in the conf → interactive prompt (with an offer to save it).

## FSMAR install walkthrough

This is the one tier that isn't a WebLogic deployment, so it's worth spelling out.

1. Build: `./build.sh` produces the fat JAR in `dist/` (dev) or `dist/<rev>-<build>/` (prod):
   - `blade-fsmar.jar` — FSMAR (the App Router)
2. Put the JAR in the OCCAS domain's `approuter/` directory on every engine-tier host:
   ```
   $DOMAIN_HOME/approuter/blade-fsmar.jar
   ```
   `./deploy.sh <env> blade-fsmar.jar --approuter` copies it from `dist/` into `approuter.dir`. Only the admin needs the file — each engine fetches the App Router from the admin over the internal management channel at activation and caches it locally, so a single copy into the admin's `approuter/` reaches the whole cluster.
3. Configure OCCAS to use FSMAR via the admin console. The exact navigation and field names change between OCCAS versions — check the OCCAS docs for your version.
4. Restart the engine tier (not AdminServer). Node Manager or a rolling restart works.
5. On first startup, FSMAR writes a sample config into the OCCAS `_samples` directory (same place every other BLADE app drops its samples). Copy it alongside your other BLADE app configs, rename, and edit — see `libs/fsmar/README.md` for the JSON schema.

The JARs can be updated in place and re-activated by a rolling engine-tier restart; hot updates are not supported because the JAR is loaded into the OCCAS Application Router at server startup.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `NoClassDefFoundError` on an admin app | Shared library not deployed to AdminServer | `./deploy.sh <env> blade-shared.war AdminServer --library` |
| `NoClassDefFoundError` on a service in the cluster | Shared library not deployed to the cluster | `./deploy.sh <env> blade-shared.war <cluster> --library` |
| `ClassCastException: class X cannot be cast to class X` | Two copies of a class are visible — usually because a service WAR bundles a JAR that's also in the shared library | Rebuild the service WAR; check its `pom.xml` excludes 3rd-party JARs via `packagingExcludes` |
| Redeploy fails with `CharConversionException` (e.g. `Unconvertible UTF-8 character beginning with 0x8b`) | A *redeploy* re-reads the app's on-server staged descriptor, and an earlier deploy left a bad one there — your freshly built WAR is clean UTF-8, so a *fresh* deploy is fine | Undeploy first, then deploy: `./deploy.sh <env> <app>.war <target> undeploy` then deploy. For the whole build, `./deploy.sh <env> --all undeploy` then `--all`. |
| `No target given and no wls.target in …` | No target on the CLI and no `wls.target` in the conf | Name the WebLogic target, or set a default `wls.target` in `<env>.conf` |
| `REFUSING: <env>.conf is not gitignored` | A conf inside the repo is tracked by git | Move it to `~/.blade/`, or gitignore it |
| `--all` aborts: "the AdminServer is unreachable" | Can't connect — server down, or the t3s trust store missing | Start the AdminServer; for t3s, ensure `~/.blade/<env>/blade-trust.p12` and `tls.trust.passphrase` are present |
| FSMAR config changes ignored | Engine tier not restarted | Rolling restart of the engine tier |

## Appendix: artifact-to-target map

This is regenerated on every build as `dist/<ver>-<build>/DEPLOYMENT.txt`. The static view:

The **whole-tier EARs sit at the dist root**; the loose artifacts sit in per-tier folders. Deploy an EAR from the root, or a loose WAR from its folder.

**Dist root** — the whole-tier EARs:

| Artifact | Target | Notes |
|---|---|---|
| `blade-admin.ear` | AdminServer | the whole admin tier in one EAR |
| `blade-services.ear` | cluster | the whole services tier in one EAR |
| `blade-test.ear` | engine0 | the test apps in one EAR |

**`dist/<ver>/lib/`** (libraries):

| Artifact | Deploy as | Target | Purpose |
|---|---|---|---|
| `blade-shared.war` | `--library` | AdminServer + cluster | WebLogic shared library (3rd-party JARs) |
| `blade-fsmar.jar` | `--approuter` | `approuter/` | SIP application router (restart engine tier) |
| `blade-framework.jar` | *(not deployed)* | bundled in WARs | BLADE framework library |

**Per-tier folders** — the same apps, loose, for single-app deploys:

| Folder | Target | Contents |
|---|---|---|
| `admin/` | AdminServer | `blade-*.war` admin apps |
| `services/` | cluster | `<service>.war` (`gateway.war`, `hold.war`, …) — individually visible in Remote Console; context-root matches filename |
| `test/` | engine0 | `test-uac.war` / `test-uas.war` / `test-b2bua.war` |
| `proto/` | ad-hoc | incubator WARs (built by the `full` profile). **No proto EAR** — proto is a grab-bag; deploy its WARs individually. |
