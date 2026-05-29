# BLADE Release Notes

## 2.9.6 (unreleased)

### Build: Javadocs are generated automatically (needs a JDK 23+ tool)

`./build.sh` now generates the Javadoc site as part of a normal build, instead of requiring the `-- -Pjavadocs` flag by hand.

- **On by default when the build JDK is ≥ 23.** BLADE's source uses Java 23+ Markdown `///` doc comments (JEP 467), so the *javadoc tool* must come from a JDK 23+ — even though bytecode still targets Java 11 (`--release 11`). The generated `javadoc.war` is copied to `dist/<ver>-<build>/admin/` alongside the other admin apps.
- **Older build JDK → skipped with a warning.** When the build JDK is below 23, generation is skipped and a warning explains that it affects only the docs (not the build) and that bytecode still targets Java 11.
- **Escape hatches for dev loops**, mirroring `--no-dist`: `./build.sh --no-javadoc` (one-off) and `export BLADE_SKIP_JAVADOC=1` (sticky for the shell).

### Build: dist copy no longer ships stale duplicate WARs

`build.sh`'s dist step now copies each module's declared `<finalName>` artifact (e.g. `transfer.war`) instead of globbing `target/*.war`. The blind glob could sweep a stale WAR left by an earlier build under a different name — e.g. a leftover `vorpal-blade-services-transfer.war` beside the current `transfer.war` — into dist, because the default `./build.sh` does not `clean` between runs. Modules that declare no `<finalName>` fall back to the previous filtered glob.

### Test UAS: URI-driven modes, config and REST API removed

`test/test-uas` is cleaned up around its two concepts — strip-and-forward, and answer-as-an-endpoint — with the mode now inferred per-call from the initial INVITE's Request-URI instead of a config toggle.

- **Mode from the Request-URI.** An INVITE carrying `status`, `delay`, or `refer` is answered locally (endpoint mode); an INVITE with none of those is stripped of its non-SDP multipart parts and forwarded (B2BUA mode). The mode is stamped on the application session so in-dialog requests route consistently. The `b2bua` config boolean is gone.
- **No app-specific configuration.** `TestUasConfig` collapses to just the inherited logging/session parameters; `defaultStatus`/`defaultDelay`/`defaultDuration`/`sdpContent`/`errorMap`/`template` are removed. The per-DN `errorMap` use case is replaced by dialing `;status=NNN`. The entire JAX-RS REST API (`/api/v1/config/*`) is removed — there is nothing left to tune at runtime.
- **`delay` means time-until-BYE.** A 2xx answer now carries a blackhole/mute SDP (`c=0.0.0.0`, `a=inactive`) and, if `delay > 0`, the call is held up for `delay` then torn down with `BYE`. The old pre-response-latency `delay` and the separate `duration` param merge into this. Values are a bare integer (milliseconds) or `ms`/`s`/`m`/`h` (e.g. `delay=5000`, `delay=5s`, `delay=2m`).
- **Reuses framework primitives.** The 2xx mute answer and re-INVITE hold both use the framework's `Callflow.hold()` / `CallflowHold`, eliminating two hand-rolled blackhole SDP constants. `TestRefer` is now wired in (triggered by the `refer` parameter).
- **Dead code removed.** Deleted `UasCallflow` (a degenerate duplicate of the endpoint path), the empty `TestStripAttachments` stub, the unused `TestUasState`/`TestUasHeaders` models, and the B2BUA header-template subsystem. The responder callflows now extend `Callflow` directly rather than the B2BUA `InitialInvite`.

### New admin tool: API Explorer (`blade/api`)

A standalone admin WAR that brings back the OpenAPI/Swagger surface the retired `blade/admin/console` used to host — now its own app, rendered with [Scalar](https://github.com/scalar/scalar) instead of the dated Swagger UI.

- **Live discovery.** `ServicesResource` walks the AdminServer's DomainRuntime MBean tree for every ACTIVE webapp and server-side-probes each `<engineBaseUrl>/<contextRoot>/resources/openapi.json`; the ones that answer populate a pulldown. Unlike the portal deck it keeps **all** context-roots, not just `blade/*`, because REST services use flat ones (`transfer`, `hold`, …).
- **Deep-linkable.** The target is taken from `?app=<contextRoot>`, so the pulldown is bookmarkable and the portal can link a card straight to a service's docs.
- **Constrained spec proxy.** OpenAPI documents live on the engine tier (a different origin from the AdminServer), so `SpecProxyResource` fetches them server-side and serves them same-origin. The target URL is structurally pinned to `<engineBaseUrl>/<sanitized-app>/resources/openapi.<ext>` — not a general-purpose relay. Docs therefore render without any CORS configuration.
- **Config.** `engineBaseUrl` (the browser-reachable engine-tier base, e.g. `http://host:8001`) is set in `config/custom/vorpal/api.json` via the standard `SettingsManager`. The app registers a `Configuration` MBean, so it gets a portal card automatically.
- **Live "try it"** against the engine tier is cross-origin and needs CORS on the services (see below). The vendored Scalar bundle (`assets/scalar/standalone.js`, pinned 1.57.5) is served locally — no CDN — for air-gapped installs.

### Framework: opt-in CORS filter for REST endpoints

A `CorsFilter` is now registered fleet-wide via the framework's `META-INF/web-fragment.xml`, so every WAR that bundles the framework JAR (all of them) gets it. It enables the API Explorer's live "try it" requests to reach service REST endpoints across origins.

- **No-op by default.** It adds nothing and changes no behavior unless an operator sets `-Dblade.cors.allowedOrigins=<origin>[,<origin>…]` (exact origins, comma-separated) on the domain. Typically that's the AdminServer origin the API Explorer is served from.
- **Credentialed, exact-match only.** When the request `Origin` is in the allowlist it emits `Access-Control-Allow-Origin: <that origin>` + `Access-Control-Allow-Credentials: true` and handles `OPTIONS` preflight. A wildcard `*` is intentionally unsupported (invalid with credentials; reflecting arbitrary origins with credentials would be unsafe).
- **No CPS-path impact.** The filter is HTTP-only and async-aware; SIP messages never traverse servlet filter chains, so the high-CPS path is untouched.

### Transfer API: corrected `notification` field documentation

The `notification` field in a transfer REST request is a **`Notification` object** (set its `style` to `none`/`async`/`callback`/`jms`), not a bare string. Its OpenAPI `@Schema` description previously read "Type of notification: none, async, callback, jms" with `defaultValue = "async"` — a scalar default on an object field — which led callers to send `"notification": "async"` and hit a JSON-B deserialization error. The description now documents the object shape and the scalar default is removed; the correct request form is `"notification": { "style": "async" }`. Documentation only — no behavior or contract change.

### Javadoc site is now an admin app with a portal card

The Javadoc WAR moved from the top-level `javadoc/` module to `admin/javadoc/` and is mounted at `blade/javadoc` (was `/javadoc`) so it appears as a card on the portal alongside the other admin tools.

- **Portal card.** Registers a metadata `SettingsManager` (`JavadocSettings`/`Sample`/`Startup`) so the launcher card reads "API Reference / BLADE Javadoc" instead of a bare slug. Card discovery (`PortalCardsResource`) only walks `blade/*` context-roots, which is why the context-root changed from `/javadoc`.
- **Public.** No FORM auth on this WAR — the docs stay openly readable, as before. (Sanctioned: it's the one admin card that isn't login-gated.)
- **Build.** Registered as `admin/javadoc` in the `javadocs` profile; `collect-javadocs.sh`'s parent path and the dist-copy path adjusted for the new depth, and its stale Admin module list refreshed. Context-root references updated across README/DEPLOYMENT.

### Configurator: command-line validation + REST API auth

The Configurator's validation/deploy REST API (`/blade/configurator/api/v1/*`) is now reachable and CLI-authenticated, fixing two bugs that made `blade-validate.sh` unusable.

- **REST API was never wired up.** The Configurator had no JAX-RS `Application` class (unlike portal/tuning/logs), so `ValidationAPI`'s `@Path` mapped to nothing and every `api/v1/*` call returned `404`. Added `RestApplication` (`@ApplicationPath("/api/v1")`) and shortened `ValidationAPI`'s class path to `/`; external URLs are unchanged.
- **CLI auth via HTTP Basic, not the login form.** A FORM-declared WAR ignores an `Authorization: Basic` header (it redirects to `login.jsp`), and the `enforce-valid-basic-auth-credentials` domain flag does not change that. So `/api/v1/*` is now carved out of the FORM security-constraint and authenticated by a new `BasicAuthFilter`, which validates the header against the WebLogic realm and requires an admin role (`Admin`/`Operator`/`Deployer`/`Monitor`). The browser Editor still uses FORM login + the shared session cookie — this is the sanctioned exception to the admin-tier "never declare BASIC" rule, since the WAR still declares FORM and only the API path is filtered.
- **`blade-validate.sh`** now authenticates with the Basic header (`curl -u`) instead of POSTing to `j_security_check`, and its `--context` default is corrected to `blade/configurator`.
- **Docs:** new Configurator Handbook page (`docs/cli.html`, "Command-Line Validation") and README section; troubleshooting rows added for the 302/401/403/404 cases.

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
- **README refreshed** — admin app table updated against 2.9.5 reality (console/configurator/flow/tuning/file-manager/explorer/watcher/javadoc), four-tier ASCII diagram added, redundant deploy sections collapsed into `DEPLOYMENT.md` pointers.
- **`libs/fsmar/README.md`** — install steps replaced with a pointer to `DEPLOYMENT.md`; tutorial content kept.

### Breaking change

- **`services/pom.xml` `-Pdeploy` no longer defaults `wls.targets=AdminServer`.** A services EAR belongs on the engine cluster, never on AdminServer, and the old default silently deployed it to the wrong place. The caller must now supply `-Dwls.targets=<cluster>` explicitly, or use `./deploy.sh <env> services` which sets it from the deploy profile. Same applies to `-Pundeploy`, `-Pstop`, `-Pstart`.

### Removed

- **`admin/json-forms/` module** — the `/forms` webapp (`vorpal-blade-admin-json-forms.war`) is removed. Its JSPs referenced `ConfigurationMonitor` / `ConfigHelper` classes that live in the configurator and file-manager WARs, so the app couldn't actually resolve them at runtime. It had no Java source of its own and was a dead scaffolding module. Its role is covered by `admin/configurator/`.

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
