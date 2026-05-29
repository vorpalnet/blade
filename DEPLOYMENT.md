# Deploying BLADE

BLADE deploys in **four tiers**, each with its own scope. Once you see the picture, the artifact-to-where mapping is obvious and the rest is mechanics.

```
OCCAS Domain
├── approuter/               ← (1) fsmar.jar / fsmar3.jar  [engine-tier reboot]
├── AdminServer              ← (2) shared library  + (3) admin/*.war
└── Cluster (engine tier)    ← (2) shared library  + (4) services/*.war
```

The shared library appears in two rows on purpose: it is **deployed to both AdminServer and the cluster**, because both sets of apps reference it.

## The four tiers

### 1. FSMAR — `fsmar.jar` / `fsmar3.jar`

**Not a WebLogic deployment.** FSMAR is the *Finite State Machine Application Router*, loaded by OCCAS before any servlet application sees a SIP message. It lives in a special OCCAS-specific directory (`$DOMAIN_HOME/approuter/`) and is activated in the OCCAS admin console, not in the WebLogic deployments view.

BLADE ships **two independent FSMAR libraries** as separate fat JARs:

| Artifact | Library | Status |
|---|---|---|
| `vorpal-blade-library-fsmar.jar`  | FSMAR 2 | Legacy — retained for backward compatibility |
| `vorpal-blade-library-fsmar3.jar` | FSMAR 3 | Current — the future of FSMAR |

They share no code and install side-by-side in `approuter/`. Only one is activated at a time via the OCCAS admin console (the SPI entry in the activated JAR's `META-INF/services/javax.servlet.sip.ar.spi.SipApplicationRouterProvider` is what OCCAS loads).

- **Artifact:** `dist/<ver>-<build>/vorpal-blade-library-fsmar.jar` and/or `vorpal-blade-library-fsmar3.jar` — fat JARs with every dependency bundled in.
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

### 3. Admin apps — `dist/<ver>/admin/*.war`

Management tools that run **only on AdminServer**. Each is a skinny WAR (no JARs in `WEB-INF/lib/`) that `<library-ref>`s the shared library. WAR filenames match the context-root so an operator looking at `/configurator` in a browser sees `configurator.war` on disk.

| Source module | WAR filename | Context root | Purpose |
|---|---|---|---|
| `admin/portal` | `portal.war` | `/blade/portal` | Unified admin shell — left rail hosts every other admin app via iframe |
| `admin/redirect` | `blade-redirect.war` | `/blade` | 302s bare `/blade` to `/blade/portal/` |
| `admin/configurator` | `configurator.war` | `/blade/configurator` | JSON Schema-based config editor, JMX-backed |
| `admin/flow` | `flow.war` | `/blade/flow` | Visual FSMAR diagram editor (mxGraph) |
| `admin/tuning` | `tuning.war` | `/blade/tuning` | JVM / SIP / OCCAS tuning knobs |
| `admin/logs` | `logs.war` | `/blade/logs` | Cluster log tail viewer |
| `admin/watcher` | `watcher.war` | `/blade/watcher` | Log/event monitor |
| `admin/crud-editor` | `crud-editor.war` | `/blade/crud-editor` | CRUD service config editor |
| `admin/javadoc` | `javadoc.war` | `/blade/javadoc` | Browsable Javadoc with UML diagrams |

- **Why AdminServer only:** admin apps expose management endpoints; deploying them to the cluster would expose those endpoints on every engine node and duplicate state.

### 4. Services + test apps — `dist/<ver>/services/*.war`

The production SIP applications themselves (ACL, Analytics, Hold, Proxy-Registrar, Proxy-Router, etc.) plus the test apps (`test-uac`, `test-uas`, `test-b2bua`). Each WAR bundles the framework JAR and references the shared library for 3rd-party code. WAR filenames match the context-root (`hold.war`, `proxy-router.war`, `test-uac.war`).

- **Artifacts:** every WAR under `dist/<ver>-<build>/services/`
- **Goes to:** the **cluster only** (engine tier).
- **Why cluster only:** services handle live SIP traffic; AdminServer doesn't.
- **Test apps live here too:** they target the cluster just like real services — no need for a separate tier.
- **EAR (currently disabled):** the historical packaging was a single `vorpal-blade-services-<profile>.ear`. While that's offline (see `services/pom.xml`), services are deployed as individual WARs.

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
./deploy.sh production admin    AdminServer                   # all admin/*.war → AdminServer
./deploy.sh production services BEA_ENGINE_TIER_CLUST         # all services/*.war → your cluster
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
| `./deploy.sh production admin AdminServer` | Every `dist/<ver>/admin/*.war` to AdminServer |
| `./deploy.sh production services BEA_ENGINE_TIER_CLUST` | Every `dist/<ver>/services/*.war` (services + test apps) to your cluster |
| `./deploy.sh production fsmar` | `cp`/`scp` FSMAR jars to `approuter/` (path from `approuter.dir` / `ssh.host` in the conf) |
| `./deploy.sh production admin AdminServer undeploy` | Tear down every admin app from AdminServer |
| `./deploy.sh production services BEA_ENGINE_TIER_CLUST status` | `list-apps` against WebLogic |
| `./deploy.sh production admin AdminServer --build 2.9.5-320` | Pin to a specific dist build (default: newest) |
| `./deploy.sh production admin AdminServer --dry-run` | Print what would happen; run nothing |

The target argument is **required** for `admin` and `services` — no defaults, so there's no surprise about which WebLogic target gets hit. `shared` and `fsmar` derive their target from the conf file or from disk paths, so they don't take one.

Each WAR is registered in WebLogic with `name = basename(war)` — so `configurator.war` becomes the app `configurator`. The deploy app name matches the context-root, which matches the WAR filename, which matches what you see in the browser.

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

1. Build: `./build.sh production` produces two fat JARs in `dist/<ver>-<build>/`:
   - `vorpal-blade-library-fsmar.jar` — FSMAR 2 (legacy)
   - `vorpal-blade-library-fsmar3.jar` — FSMAR 3 (current)
2. Put the JAR(s) in the OCCAS domain's `approuter/` directory on every engine-tier host:
   ```
   $DOMAIN_HOME/approuter/vorpal-blade-library-fsmar.jar
   $DOMAIN_HOME/approuter/vorpal-blade-library-fsmar3.jar
   ```
   `./deploy.sh <env> fsmar` copies whichever JARs are present in `dist/` via `scp` (if `ssh.host` is set in the profile) or `cp` (local domain). Having both on disk is fine — OCCAS only loads the one activated in its admin console.
3. Configure OCCAS to use the chosen FSMAR via the admin console. The exact navigation and field names change between OCCAS versions — check the OCCAS docs for your version.
4. Restart the engine tier (not AdminServer). Node Manager or a rolling restart works.
5. On first startup, FSMAR writes a sample config into the OCCAS `_samples` directory (same place every other BLADE app drops its samples). FSMAR 2 and FSMAR 3 use distinct sample-file names so they don't collide. Copy the appropriate sample alongside your other BLADE app configs, rename appropriately, and edit — see `libs/fsmar/README.md` (v2) or `libs/fsmar3/README.md` (v3) for the JSON schema.

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
| `vorpal-blade-library-fsmar.jar` | fsmar | `approuter/` | SIP application router — v2 legacy (reboot engine tier) |
| `vorpal-blade-library-fsmar3.jar` | fsmar | `approuter/` | SIP application router — v3 (reboot engine tier) |
| `vorpal-blade-library-shared.war` | shared-lib | AdminServer + cluster | WebLogic shared library (3rd-party JARs) |
| `vorpal-blade-library-framework.jar` | framework | bundled in WARs | BLADE framework library (not deployed directly) |

**`dist/<ver>/admin/`** (deploy to AdminServer):

| Artifact | Context |
|---|---|
| `portal.war` | `/blade/portal` (unified admin shell) |
| `blade-redirect.war` | `/blade` (302s to /blade/portal/) |
| `configurator.war` | `/blade/configurator` |
| `flow.war` | `/blade/flow` |
| `tuning.war` | `/blade/tuning` |
| `logs.war` | `/blade/logs` |
| `watcher.war` | `/blade/watcher` |
| `crud-editor.war` | `/blade/crud-editor` |
| `javadoc.war` | `/blade/javadoc` |

**`dist/<ver>/services/`** (deploy to the cluster):

| Artifact | Notes |
|---|---|
| `<service>.war` | one WAR per SIP service (`hold.war`, `proxy-router.war`, etc.) — context-root matches filename |
| `test-uac.war`, `test-uas.war`, `test-b2bua.war` | test apps (cluster, same as services) |
