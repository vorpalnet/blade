# BLADE Release Notes

## 2.9.6 (unreleased)

### Deployment overhaul

BLADE deploys in **four tiers**: FSMAR (`approuter/`), shared library (AdminServer + cluster), admin WARs (AdminServer only), and the services EAR (cluster only). Previously these were conflated in the docs, there was no unified install tool, and `services/pom.xml` defaulted service-EAR deployment to AdminServer — the wrong target.

- **New `DEPLOYMENT.md`** — single source of truth for operators: four-tier mental model with architecture diagram, install sequence, `./deploy.sh` reference, FSMAR OCCAS-console walkthrough, troubleshooting guide, and the artifact-to-target map.
- **New `./deploy.sh`** — profile-driven deployment wrapper. Usage: `./deploy.sh <env> [tier] [--build VER] [--dry-run]`. Handles all four tiers, iterates admin WARs automatically, `cp`/`scp`s FSMAR to `approuter/`, prints a per-tier summary. Supports `undeploy`, `status`, `--dry-run`, and `--build` pinning.
- **New `build-profiles/deploy/` directory** — deployment config lives alongside module and platform profiles.
  - `<env>.conf` (committed) holds connection details, target names, approuter path, and which build profile's EAR to deploy.
  - `<env>.secret` (**gitignored**) holds `wls.password=…` only.
  - `<env>.secret.example` (committed) is a safe template.
- **Secret safeguards** — four independent guards prevent password commits: top-level `.gitignore`, nested `build-profiles/deploy/.gitignore`, `deploy.sh` pre-flight `git check-ignore`, and a mode-600 warning. Password resolution order: `BLADE_WLS_PASSWORD` env var → `<env>.secret` → interactive prompt with save offer.
- **Auto-generated `dist/<ver>-<build>/DEPLOYMENT.txt`** — `build.sh` writes a four-column manifest (Artifact, Tier, Target, Purpose) after every successful build, so operators can see at a glance what goes where.
- **README refreshed** — admin app table updated against 2.9.5 reality (console/configurator/flow/tuning/file-manager/explorer/json-forms/watcher/javadoc), four-tier ASCII diagram added, redundant deploy sections collapsed into `DEPLOYMENT.md` pointers.
- **`libs/fsmar/README.md`** — install steps replaced with a pointer to `DEPLOYMENT.md`; tutorial content kept.

### Breaking change

- **`services/pom.xml` `-Pdeploy` no longer defaults `wls.targets=AdminServer`.** A services EAR belongs on the engine cluster, never on AdminServer, and the old default silently deployed it to the wrong place. The caller must now supply `-Dwls.targets=<cluster>` explicitly, or use `./deploy.sh <env> services` which sets it from the deploy profile. Same applies to `-Pundeploy`, `-Pstop`, `-Pstart`.

## 2.9.5

**Build system: Maven**

This is the first release built entirely with Maven. ANT is no longer supported.
Build with `./build.sh` or directly with `mvn`.

### Deployment Changes

- **Shared library required on AdminServer.** The `vorpal-blade` shared library must now be
  deployed to the AdminServer in addition to the engine cluster. All admin applications reference
  it via `<library-ref>` instead of bundling JARs in each WAR. This significantly reduces WAR
  file sizes and ensures consistent dependency versions across all applications.

- **Configurator replaces the console app.** The standalone configurator application
  (`vorpal-blade-admin-configurator`) has been promoted to the primary configuration tool.
  The old embedded configurator code inside the console has been removed.

### Admin Application Restructuring

The monolithic `dev-console` has been broken into focused, independent applications:

| Application | Context Root | Purpose |
|---|---|---|
| **console** | `/blade` | Navigation shell — links to all other admin apps via sidebar/iframe |
| **flow** | `/flow` | FSMAR configuration editor — visual diagram editing with mxGraph |
| **tuning** | `/tuning` | OCCAS/WebLogic tuning — JVM, SIP timers, thread pools, work managers |
| **file-manager** | `/files` | WebSocket-based configuration file management and monitoring |
| **explorer** | `/explorer` | EasyUI-based experimental UI — endpoints, jstree, ALICE forms |
| **json-forms** | `/forms` | Legacy JSON configuration form editor with multiple JSP iterations |

### Removed

- `admin/configurator/` module — replaced by the standalone configurator
- `admin/dev-console/` module — contents redistributed to flow, file-manager, explorer, json-forms

### Tuning Application (New)

New admin application for tuning OCCAS and WebLogic settings, accessible at `/tuning`.

- **Authentication** — Enable/disable Single Sign-On across all admin apps
- **Node Manager** — View Node Manager configuration per machine
- **JVM Settings** — Per-server heap, metaspace, GC collector, flags, and additional arguments
- **Server Tuning** — Thread pool minimum, socket readers, max message size, connection timeouts
- **SIP Protocol** — RFC 3261 timers (T1/T2/T4, Timer B/F/L/M/N), protocol behavior, feature flags
- **OCCAS Thread Tuning** — All 9 WLSS work managers with editable fair-share, min/max threads, and capacity constraints
- **Cluster** — Cluster topology, member warmup, migratable targets

All settings include descriptive tooltips explaining their purpose and impact.

### SSO and Cookie Path

- All admin applications now use `cookie-path=/` in `weblogic.xml` to support Single Sign-On
  across admin apps loaded in the console shell's iframes.
- Unique session cookie names are retained per app to avoid session collisions.
- SSO requires enabling `AuthCookieEnabled` on each WebLogic server (configurable via the
  performance app).

---

## 2.9.4

**Build system: ANT**

This is the last release built with ANT (`build.xml`). All subsequent releases use Maven.

### Notes

- Admin console was a single monolithic application (`dev-console`) containing diagram editing,
  file management, EasyUI forms, JSON editor, and configurator functionality.
- Admin applications bundled all dependencies as standalone WARs.
- Configurator was embedded inside both the console and dev-console applications.
