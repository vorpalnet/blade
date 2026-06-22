# Deploying BLADE

BLADE deploys in **four tiers**, each with its own scope. Once you see the picture, the artifact-to-where mapping is obvious and the rest is mechanics.

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
| `vorpal-blade-library-fsmar.jar` | FSMAR | Current (formerly "FSMAR 3") |

OCCAS loads its SPI entry (`META-INF/services/javax.servlet.sip.ar.spi.SipApplicationRouterProvider`) at boot. The original **FSMAR 2** is retired (`retired/fsmar2/`, excluded from the standard build) and is no longer shipped.

- **Artifact:** `dist/<ver>-<build>/vorpal-blade-library-fsmar.jar` — a fat JAR with every dependency bundled in.
- **Goes to:** `$DOMAIN_HOME/approuter/` on every engine-tier host. (Newer OCCAS versions support an `approuter/lib/` subdirectory for dependency JARs, but because BLADE ships FSMAR as fat JARs you drop the single files directly into `approuter/`.)
- **Activation:** configure OCCAS to use the chosen FSMAR via the admin console — the exact navigation changes between OCCAS versions, so check the OCCAS docs for your version.
- **Takes effect after:** engine-tier server restart (not AdminServer).
- **Why it's different:** FSMAR is code that runs *inside OCCAS itself*, not an application deployed on top of it. It cannot be hot-updated or targeted the way WARs/EARs can.

### 2. Shared library — `vorpal-blade-library-shared.war`

A WebLogic shared library with `Extension-Name: vorpal-blade`, containing every 3rd-party JAR that BLADE needs (Jackson, Swagger, JSON Schema, etc.). Every other BLADE application (admin and services) references it via `<library-ref>` in `weblogic.xml` instead of bundling its own copies.

- **Artifact:** `dist/<ver>-<build>/vorpal-blade-library-shared.war`
- **Goes to:** **Both** AdminServer **and** the engine cluster — because both admin apps and services apps reference it.
- **Why it's deployed twice:** WebLogic shared libraries are scoped to deployment targets. An admin app on AdminServer can only resolve a library that's also deployed to AdminServer, and the same goes for the cluster.
- **Updating:** bumping a 3rd-party version requires one shared-library redeploy, not a rebuild of every service.

### 3. Admin apps — `dist/<ver>/admin/blade-admin.ear`

Management tools that run **only on AdminServer**, packaged as a single EAR (`blade-admin.ear`) so the whole admin tier deploys in one step. Each bundled WAR is self-contained exactly as it deploys standalone — it carries the framework jar and references the `vorpal-blade` shared library via its own `weblogic.xml`. The EAR is a packaging convenience over those WARs; it bundles no libraries itself. `./build.sh` also drops the individual WARs in `dist/<ver>/admin/` so a single app can be redeployed on its own during testing; `deploy.sh` deploys the EAR. The bundled web modules and their (unchanged) context-roots:

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
- **EAR vs. individual WAR:** `deploy.sh ... admin` deploys `blade-admin.ear` (the whole tier). For a quick single-app test, redeploy that one WAR (from `dist/<ver>/admin/` or its exploded `target/` dir) — each is independently deployable.
- **Watcher is retired.** The headless config auto-publish shim (`blade-watcher.war`) moved to `retired/watcher/` and is no longer built or shipped — the Configurator's auto-publish covers the same need. (It was always standalone-only, never in the EAR.)

### 4. Services + test apps — individual WARs in `dist/<ver>/services/`

The production SIP applications themselves (ACL, Analytics, Hold, Proxy-Registrar, Proxy-Router, etc.) deploy as **individual WARs**, one per service — there is **no services EAR**. (The old `blade-cluster.ear` was dropped: OCCAS 8.3 gives no visibility into what's deployed inside an EAR, and the services tier has no equivalent of the admin portal to enumerate its apps. Per-WAR deployment makes each a distinctly-named, visible WebLogic deployment. The EAR aggregator is parked in `retired/cluster/`.) Each WAR is self-contained exactly as it deploys standalone: framework JAR inside + the `vorpal-blade` shared-library reference in its own `weblogic.xml`. `./build.sh` drops every service WAR in `dist/<ver>/services/` (filenames match the context-root: `hold.war`, `proxy-router.war`).

- **Artifacts:** every WAR under `dist/<ver>-<build>/services/` (no EAR).
- **Goes to:** the **cluster only** (engine tier). `deploy.sh ... services` loops the WARs, deploying each under its own name (`hold`, `proxy-router`, …) — visible individually in WebLogic.
- **Why cluster only:** services handle live SIP traffic; AdminServer doesn't.
- **Test apps deploy here too** (promoted to production 2026-06-05 as live-diagnostics tools): `test-uac`, `test-uas`, `test-b2bua`, plus `context`, are ordinary service WARs in `dist/<ver>/services/`.

## Quick start

```bash
./build.sh production                 # produce dist/<ver>-<build>/{admin,services}/
cp build-profiles/deploy/production.secret.example \
   build-profiles/deploy/production.secret
chmod 600 build-profiles/deploy/production.secret
$EDITOR build-profiles/deploy/production.secret        # fill in wls.password
$EDITOR build-profiles/deploy/production.conf          # adminurl, user, approuter.dir

./deploy.sh production shared --dry-run                       # sanity check
./deploy.sh production shared                                 # WebLogic shared library
./deploy.sh production admin    AdminServer                   # blade-admin.ear → AdminServer
./deploy.sh production services BEA_ENGINE_TIER_CLUST         # service WARs → your cluster
./deploy.sh production fsmar                                  # FSMAR jars → approuter/
```

After the FSMAR step, **restart the engine tier** so the new `fsmar.jar` is picked up.

## `./deploy.sh` reference

```
./deploy.sh <env> <subdir> [target] [action] [--build VER] [--dry-run]
```

`<subdir>` is what you'd run for that step — it maps directly to `dist/<ver>/<subdir>/`. `<target>` is the WebLogic deploy target name. `<action>` defaults to `deploy`.

| Invocation | Effect |
|---|---|
| `./deploy.sh production shared` | WebLogic shared library → both AdminServer and the cluster (target read from `wls.targets.both` in the conf) |
| `./deploy.sh production admin AdminServer` | `dist/<ver>/admin/blade-admin.ear` to AdminServer |
| `./deploy.sh production services BEA_ENGINE_TIER_CLUST` | every WAR in `dist/<ver>/services/` (all services + test apps) to your cluster |
| `./deploy.sh production fsmar` | `cp`/`scp` FSMAR jars to `approuter/` (path from `approuter.dir` / `ssh.host` in the conf) |
| `./deploy.sh production admin AdminServer undeploy` | Tear down every admin app from AdminServer |
| `./deploy.sh production services BEA_ENGINE_TIER_CLUST status` | `list-apps` against WebLogic |
| `./deploy.sh production admin AdminServer --build 2.9.5-320` | Pin to a specific dist build (default: newest) |
| `./deploy.sh production admin AdminServer --dry-run` | Print what would happen; run nothing |

The target argument is **required** for `admin` and `services` — no defaults, so there's no surprise about which WebLogic target gets hit. `shared` and `fsmar` derive their target from the conf file or from disk paths, so they don't take one.

Each WAR is registered in WebLogic with `name = basename(war)` — so `blade-configurator.war` becomes the app `blade-configurator`. Admin-tier WARs are prefixed `blade-` (e.g. `blade-tuning`, `blade-crud`, `blade-analytics`) so their app names never collide with the like-named services-tier WARs (`hold`, `proxy-router`, …), whose filenames still match their context-root's last segment. The context-root itself is unchanged by the WAR name — `blade-configurator.war` still deploys at `/blade/configurator` — so bookmarks, portal cards, and config-file names are unaffected.

### Deploy profiles

Every environment gets two files under `build-profiles/deploy/`:

| File | Status | Contains |
|---|---|---|
| `<env>.conf` | **committed** | Hostname, WebLogic user, target names, approuter path, SSH host, build profile selection |
| `<env>.secret` | **gitignored** | `wls.password=…` only |
| `<env>.secret.example` | **committed** | Template for `.secret`, never contains a real password |

Four independent safeguards keep secrets out of git:

1. Top-level `.gitignore`: `build-profiles/deploy/*.secret` with `!*.secret.example` negation.
2. Nested `build-profiles/deploy/.gitignore` with the same rules (belt and suspenders).
3. `deploy.sh` runs `git check-ignore` on the secret file before using it, and refuses if it's trackable.
4. `deploy.sh` warns if a secret file is not mode 600.

Password sourcing priority (highest wins):

1. `BLADE_WLS_PASSWORD` environment variable
2. `build-profiles/deploy/<env>.secret`
3. Interactive prompt (with an offer to save into `<env>.secret`)

## FSMAR install walkthrough

This is the one tier that isn't a WebLogic deployment, so it's worth spelling out.

1. Build: `./build.sh production` produces the fat JAR in `dist/<ver>-<build>/`:
   - `vorpal-blade-library-fsmar.jar` — FSMAR (the App Router)
2. Put the JAR in the OCCAS domain's `approuter/` directory on every engine-tier host:
   ```
   $DOMAIN_HOME/approuter/vorpal-blade-library-fsmar.jar
   ```
   `./deploy.sh <env> fsmar` copies it from `dist/` via `scp` (if `ssh.host` is set in the profile) or `cp` (local domain).
3. Configure OCCAS to use FSMAR via the admin console. The exact navigation and field names change between OCCAS versions — check the OCCAS docs for your version.
4. Restart the engine tier (not AdminServer). Node Manager or a rolling restart works.
5. On first startup, FSMAR writes a sample config into the OCCAS `_samples` directory (same place every other BLADE app drops its samples). Copy it alongside your other BLADE app configs, rename, and edit — see `libs/fsmar/README.md` for the JSON schema.

The JARs can be updated in place and re-activated by a rolling engine-tier restart; hot updates are not supported because the JAR is loaded into the OCCAS Application Router at server startup.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `NoClassDefFoundError` on an admin app | Shared library not deployed to AdminServer | `./deploy.sh <env> shared` |
| `NoClassDefFoundError` on a service in the cluster | Shared library not deployed to the cluster | `./deploy.sh <env> shared` (`wls.targets.both` covers both) |
| `ClassCastException: class X cannot be cast to class X` | Two copies of a class are visible — usually because a service WAR bundles a JAR that's also in the shared library | Rebuild the service WAR; check its `pom.xml` excludes 3rd-party JARs via `packagingExcludes` |
| `Target required for 'admin'` from `deploy.sh` | The target arg is mandatory for `admin`/`services` — no default | `./deploy.sh <env> admin AdminServer` (or whatever your admin target is called) |
| `REFUSING: <env>.secret is not gitignored` | The secret file is tracked by git | Add to `.gitignore` and `git rm --cached <env>.secret` |
| FSMAR config changes ignored | Engine tier not restarted | Rolling restart of the engine tier |
| Service deployed to wrong target | Wrong target passed on the command line | Re-run with the correct target; the conf file no longer defaults a target for the generic admin/services tiers |

## Appendix: artifact-to-target map

This is regenerated on every build as `dist/<ver>-<build>/DEPLOYMENT.txt`. The static view:

**Dist root (libraries):**

| Artifact | Tier | Target | Purpose |
|---|---|---|---|
| `vorpal-blade-library-fsmar.jar` | fsmar | `approuter/` | SIP application router (reboot engine tier) |
| `vorpal-blade-library-shared.war` | shared-lib | AdminServer + cluster | WebLogic shared library (3rd-party JARs) |
| `vorpal-blade-library-framework.jar` | framework | bundled in WARs | BLADE framework library (not deployed directly) |

**`dist/<ver>/admin/`** (deploy to AdminServer):

| Artifact | Context |
|---|---|
| `blade-portal.war` | `/blade/portal` (unified admin shell) |
| `blade-redirect.war` | `/blade` (302s to /blade/portal/) |
| `blade-configurator.war` | `/blade/configurator` |
| `blade-flow.war` | `/blade/flow` |
| `blade-tuning.war` | `/blade/tuning` |
| `blade-logs.war` | `/blade/logs` |
| `blade-crud.war` | `/blade/crud-editor` |
| `blade-javadoc.war` | `/blade/javadoc` |

**`dist/<ver>/services/`** (deploy to the cluster):

| Artifact | Notes |
|---|---|
| `<service>.war` | one WAR per SIP service (`hold.war`, `proxy-router.war`, etc.) — context-root matches filename |
| `test-uac.war`, `test-uas.war`, `test-b2bua.war` | test apps (cluster, same as services) |
