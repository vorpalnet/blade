# BLADE Release Notes

## 2.9.9 (unreleased)

### Portal: SIP service cards

The admin portal launcher now shows a second **SIP Services** tier below the administration
tools. SIP services have no console of their own, so each card is a documentation/configure
entry: it carries the service's name, tagline, and description, and opens that service's
configuration in the Configurator. The cards are discovered the same way the admin deck is —
`PortalCardsResource` keeps the Cluster-keyed `vorpal.blade …Type=Configuration` MBeans the
admin pass discards, and reads each card's text from the service's `@SchemaAbout` schema
identity. Service config classes were given `@SchemaAbout` (replacing the older
`@SchemaTitle`) to supply that text.

A service appears on the deck **only once it carries authored `@SchemaAbout` identity** — the
curation gate that keeps the test tier (`test-uac`/`uas`/`b2bua`) and assorted internal config
MBeans off the customer-facing deck. The crud service gets its identity from a new
`CrudSettings` subclass (between `CrudConfiguration` and `CrudConfigurationSample`) rather than
from `CrudConfiguration` directly, so the `@Inherited` `@SchemaAbout` does not leak onto the
test-suite's `TesterConfiguration` hierarchy, which extends `CrudConfiguration`.

### Retired: `proxy-router`

The `proxy-router` service (the "Reductive Reasoning Router") was moved to `retired/` — the
**iRouter** universal config-driven proxy supersedes it. It is no longer discovered or built
by the standard build; build by hand with `./mvnw -f retired/proxy-router/pom.xml package` if
ever needed for reference.

### Tuning app: "About this deployment" panel

The Tuning dashboard now opens with an **About this deployment** card that reads back, at a
glance, what BLADE is running on:

- **BLADE** — version (`WebLogic-Application-Version`, i.e. `<revision>-<build>`), build
  date, license, and copyright, read from the bundled framework JAR's `MANIFEST.MF`.
- **Platform** — OCCAS / WebLogic / Coherence product versions parsed from the Oracle-home
  install inventory (`inventory/ContentsXML/comps.xml`), plus the live domain name, config
  version, JVM, and OS. The Oracle home is resolved from `oracle.home`/`bea.home`/env, falling
  back to `platform.home`'s parent.
- **Applied patches** — the one-off patches from the OPatch inventory
  (`inventory/patches/*.xml`: each file's `patch-id` + `description`).

(Deployed-app inventory stays in the portal launcher, not here.) New REST endpoint
`GET /blade/tuning/api/v1/about`. Every source degrades to an empty value rather than failing
the panel.

### `deploy.sh`: one env file drives the whole deploy

`deploy.sh` was reworked so a single command deploys an entire environment from its
`build-profiles/deploy/<env>.conf` profile. **`./deploy.sh <env>`** (no tier) now deploys
all four tiers in dependency-safe order — **shared → fsmar → admin → services** — so the
shared library is always in place (≥ spec-version 3.0) before any WAR that references it;
`undeploy` walks the reverse order (library last). `./deploy.sh <env> <tier>` still does a
single tier for dev loops.

- **The `<target>` positional argument is gone.** Each tier's WebLogic target is read from
  the conf (`wls.targets.admin` / `wls.targets.cluster` / `wls.targets.both`) instead of
  being retyped on the command line. **Migration:** drop the target from any scripted
  `./deploy.sh <env> <tier> <target>` calls → `./deploy.sh <env> <tier>`.
- **`engine.nodes`** (new conf key): the FSMAR fat JAR is `scp`'d to every listed engine
  host's `approuter/`, not just one. Back-compat: falls back to a single `ssh.host`, then to
  a local `cp` into `approuter.dir`. This is an operator-time push, separate from the paused
  cluster-file-sync restart-time pull.
- **`deploy.services`** (new conf key): optional CSV allowlist narrowing which service WARs
  deploy (`*`/empty = all built). Admin ships as one EAR, so it stays all-or-nothing.
- `<env>` may now be a profile **name** or a **path** to a conf file.
- Fixed a latent fragility: `read_prop` no longer aborts the script (via `set -o pipefail`)
  when an optional conf key is absent.

### Library deploy artifacts renamed `vorpal-blade-library-*` → `blade-*`

The three deployable library filenames lost the `vorpal-` company prefix and the redundant
`-library` segment: `blade-framework.jar`, `blade-fsmar.jar`, `blade-shared.war` (was
`vorpal-blade-library-framework.jar` / `-fsmar.jar` / `-shared.war`). *Vorpal* is the
company, *BLADE* is the project — the deploy units now read as the project's. **Only the
Maven `<finalName>` (build output) changed.** The Maven coordinates / `artifactId`s are
unchanged (`org.vorpal.blade:vorpal-blade-library-framework`, …), so the framework jar still
lands in each skinny WAR's `WEB-INF/lib` as `vorpal-blade-library-framework-<ver>.jar` (named
by artifactId) and the `packagingExcludes` skinny-WAR filter is untouched. `build.sh`/`deploy.sh`
reference the new `blade-*` filenames; the admin EAR is `blade-admin.ear`.

### WebLogic shared library renamed `vorpal-blade` → `blade-shared` (spec-version 3.0)

The shared library's `Extension-Name` (the identity WebLogic matches `<library-ref>` against)
changed from `vorpal-blade` to `blade-shared`, and its `Specification-Version` from `2.0` to
`3.0` (aligning the shared library with the framework's 3.0 line). This is a **coordinated**
change — the `Extension-Name` in `libs/shared`'s manifest, the `<library-name>` +
`<specification-version>` in **all 30 referring WARs'** `weblogic.xml`, and
`deploy.sh`'s `SHARED_LIB_NAME` (the `-Dname` of the library deployment) all moved together.
A WAR whose `<library-name>` doesn't match the deployed library's `Extension-Name` fails to
deploy (unresolved library reference), so they must stay in lockstep.

**Deploy order:** `<exact-match>` is `false`, so a WAR requires the library version to be
**≥** its referenced version. Deploy the new `blade-shared` 3.0 library **before** redeploying
any WAR that now references 3.0 — an old `vorpal-blade` 2.0 library still on the server would
fail the `≥3.0` check. (The shared library's `<context-root>` — a fixed deploy id — is
unchanged.)

### Services tier: dropped `blade-cluster.ear` — deploy service WARs individually

The services/cluster EAR (`blade-cluster.ear`) is gone. OCCAS 8.3 gives no visibility into
what's deployed inside an EAR, and the services tier has no equivalent of the admin portal to
enumerate its apps — so a bundled services EAR was an opaque blob. Each service now deploys as
its own WAR (`hold.war`, `proxy-router.war`, …), visible individually in WebLogic. The build
already emits these WARs to `dist/<ver>/services/`, and `deploy.sh <env> services` already
loops them when no EAR is present — so no deploy.sh code change was needed. The EAR aggregator (`services/cluster`) was deleted; its `cluster` Maven profile and build-profile entries were
removed. **The admin EAR (`blade-admin.ear`) stays** — the portal makes it self-describing.

### `admin/watcher` retired

The headless config auto-publish shim moved to `retired/watcher/` (excluded from the build,
not shipped). It was already standalone-only (never in `blade-admin.ear`); the Configurator's
auto-publish covers the same need. Its `pom.xml` profile, build-profile entries, `build.sh`
classifier line, and javadoc-collection entry were removed.

### New `proto/` incubator; `admin/security` + `admin/test-console` moved there

Apps that aren't ready to ship now live under **`proto/`** instead of `admin/`. They still
build — `build.sh` discovers `libs/admin/services/test/proto`, and they compile under the
`full` profile — but are **excluded from the everyday `default`/`production` builds and are
never bundled in `blade-admin.ear`**. `proto/security` and `proto/test-console` moved there
(removed from `default.conf`/`production.conf` and from the `ear-security`/`ear-test-console`
EAR profiles). **All new apps start in `proto/`**; promote one by moving it to `admin/` (or
`services/`) and re-adding its `ear-<name>` profile. Mirror of `retired/` at the other end of
the lifecycle. (The proto poms' `login.jsp` web-resource path was repointed from `../portal`
to `../../admin/portal` for the deeper directory.)

### FSMAR config is un-versioned by filename, with auto-upgrade on load

The FSMAR App Router now reads `config/custom/vorpal/fsmar.json` (was `fsmar3.json`) — the
**version lives inside the config** (the framework `version` field), not in the filename.
On load it version-dispatches:

- a current (v3) `fsmar.json` loads as-is;
- a pre-3 / FSMAR 2-shaped config (a `version` < 3, or a `previous` map with no `states`) is
  **auto-upgraded to FSMAR 3 in memory** via `Fsmar2Converter` before deserialization;
- if `fsmar.json` is absent, it falls back to the legacy `fsmar2.json` filename and upgrades
  that — so an existing FSMAR 2 domain keeps routing after the jar swap, no file rename needed.

Every auto-upgrade logs loudly (SEVERE + the converter's REVIEW/NOTE items). Because the
converter **fails closed** (unconvertible conditions become `when:"false"`), a forgotten
migration under-routes and logs rather than mis-routing — but the intended path is still to
open the config in the Flow editor, review the conversion, and re-save it. The Flow editor's
publish/load targets moved to `fsmar.json` to match.

Mechanism: new `Settings.domainFile()` seam (framework) for the legacy-filename fallback, and
`FsmarSettings`/`FsmarSettingsManager` (in `libs/fsmar`) overriding the `readConfigTree` /
`createSettings` seams. Upgraders stay typed Java; no executable upgrade rules in the schema.
This is the first concrete client of the planned config-versioning upgrade-on-load design.

### FSMAR 2 retired; FSMAR 3 is now the un-versioned canonical FSMAR

FSMAR 3 is now *the* FSMAR. The module `libs/fsmar3/` was renamed to `libs/fsmar/` and its
artifact de-versioned from `vorpal-blade-library-fsmar3.jar` to `blade-fsmar.jar` (version
belongs in MANIFEST.MF, not the filename; the `vorpal-`/`-library` strip is noted above). The
legacy **FSMAR 2** module moved to `retired/fsmar2/` and is **excluded from the standard
build** — no Maven profile references it, and `build.sh` only discovers modules under
`libs/`, `admin/`, `services/`, `test/`. It can still be built by hand
(`./mvnw -f retired/fsmar2/pom.xml package`).

Internals are unchanged this pass (least churn): the runtime package stays
`org.vorpal.blade.library.fsmar3`, the SPI provider stays
`…library.fsmar3.AppRouterProvider`, and the config file stays `fsmar3.json`. Only the
build *output* (jar) and module *directory* lost the version number.

**Deploy note:** the FSMAR fat jar (now `blade-fsmar.jar`) previously meant FSMAR 2; it now
means FSMAR 3. A domain that drops in the new jar over an existing `fsmar2.json` keeps routing
— the engine auto-upgrades the legacy config on load (see the un-versioned-by-filename note
above), logging loudly. No simultaneous file swap is required; the recommended path is still to
open the config in the Flow editor, review the FSMAR 2 → 3 conversion, and re-save as
`fsmar.json`.

### FSMAR config model moved into the framework JAR

The FSMAR 2 and FSMAR 3 **configuration/data model** classes now live in the
framework, leaving only the App Router runtime in the two FSMAR fat JARs:

| Was (`libs/fsmar`, `libs/fsmar3`) | Now |
|---|---|
| `org.vorpal.blade.library.fsmar2.{AppRouterConfiguration, State, Trigger, Transition, Action, AppRouterConfigurationSample}` | `org.vorpal.blade.framework.v2.fsmar.*` |
| `org.vorpal.blade.library.fsmar3.{AppRouterConfiguration, State, Trigger, Transition, Ingress, Egress, Placement, Diagram, AppRouterConfigurationSample, Fsmar2Converter}` | `org.vorpal.blade.framework.v3.fsmar.*` |

The runtime stays in `org.vorpal.blade.library.fsmar2`/`fsmar3` (`AppRouter`,
`AppRouterProvider`, FSMAR 3's `Fsmar3Metrics`/`RouteTrace`/`RoutingState`), so
the `META-INF/services` SPI registration is unchanged and the fat JARs still
drop into `approuter/` as before. Because the framework JAR is bundled in every
WAR (and shaded into the fat JARs), admin-tier tools — the Flow editor in
particular — can now use the FSMAR model and `Fsmar2Converter` directly without
linking the engine fat JAR. Existing `fsmar2.json`/`fsmar3.json` files load
unchanged (configs key on logical Jackson type names, not class FQNs).

### Flow editor opens FSMAR 2 configs and saves them as FSMAR 3

Opening a legacy FSMAR 2 configuration in the Flow editor (file, paste, or
"Load live") now converts it to FSMAR 3 on import via the framework
`Fsmar2Converter` (new `/fsmarConvert` servlet). The editor shows a conversion
summary — state/transition/selector counts plus any REVIEW/NOTE items — and a
read-only preview before loading; the editor only ever saves FSMAR 3. Anything
that cannot be converted faithfully becomes `when:"false"` (fail closed) with a
REVIEW warning, so a conversion gap narrows routing rather than widening it.

### FSMAR 2 → 3 conversion is now lossless

Three FSMAR 3 capabilities were added so the conversion no longer loses
behavior:

- **Repeating headers.** `AttributeSelector` gained an opt-in `allInstances`
  mode that reads *every* instance of a repeating header (joined), and the
  Expression `matches` operator is existential over those instances ("any
  instance matches"). This restores FSMAR 2's scan-every-instance behavior for
  `contains`, `value`, and parameter operators (e.g. matching any `Via`,
  `Route`, or `Diversion`). Single-valued conditions are unchanged.
- **`${regionLabel}`.** A new pseudo-variable exposes the JSR-289 routing region
  label; FSMAR 2's `Region-Label` condition maps to it.
- **Quoted values.** Expression string literals accept a `\'` escape, so a match
  value containing an apostrophe (e.g. a display name) is representable.

### Flow round-trip preserves retired fields

The Flow import/export round-trip no longer silently drops a retired
`description` field (on selectors and egress nodes); like any unknown field it
is preserved verbatim, while the engine continues to ignore it.

### Admin-tier WARs renamed `blade-<app>.war`

Every admin webapp's output WAR is now prefixed `blade-` so its WebLogic app
name can't collide with the like-named services-tier WAR (e.g. admin
`blade-crud.war` vs. a service `crud.war`). The `<finalName>` in each
`admin/*/pom.xml` and the matching `<bundleFileName>` in `admin/ear/pom.xml`
were updated together; the generated `application.xml` web-uris follow.

| Source module | Old WAR | New WAR | Context root (unchanged) |
|---|---|---|---|
| `admin/portal` | `portal.war` | `blade-portal.war` | `/blade/portal` |
| `admin/redirect` | `blade-redirect.war` | `blade-redirect.war` | `/` |
| `admin/api` | `api.war` | `blade-api.war` | `/blade/api` |
| `admin/configurator` | `configurator.war` | `blade-configurator.war` | `/blade/configurator` |
| `admin/files` | `files.war` | `blade-files.war` | `/blade/files` |
| `admin/crud-editor` | `crud-editor.war` | `blade-crud.war` | `/blade/crud-editor` |
| `admin/flow` | `flow.war` | `blade-flow.war` | `/blade/flow` |
| `admin/tuning` | `tuning.war` | `blade-tuning.war` | `/blade/tuning` |
| `admin/security` | `security.war` | `blade-security.war` | `/blade/security` |
| `admin/logs` | `logs.war` | `blade-logs.war` | `/blade/logs` |
| `admin/analytics-console` | `analytics-console.war` | `blade-analytics.war` | `/blade/analytics` |
| `admin/test-console` | `test-console.war` | `blade-test.war` | `/blade/test-console` |
| `admin/javadoc` | `javadoc.war` | `blade-javadoc.war` | `/blade/javadoc` |
| `admin/watcher` | `watcher.war` | `blade-watcher.war` | `/blade/watcher` (standalone) |

The three "console/editor" apps got short names — `blade-analytics`,
`blade-crud`, `blade-test` — instead of mechanical `blade-analytics-console`
etc. **Context-roots did not change**, so bookmarks, portal cards, SSO cookies,
and config-file names (`blade-tuning.json`, derived from the context path) are
all unaffected. Only the standalone deploy path changes the registered app name
(`deploy.sh` uses `basename(war)`): a domain that previously deployed, say,
`configurator` standalone must **undeploy the old `configurator` app** before
deploying `blade-configurator` — the EAR path (`blade-admin.ear`, one app) is
clean. `build.sh`'s manifest classifier, `DEPLOYMENT.md`, and `README.md` were
updated to match.

### FSMAR 3: route-back egress (detour out, resume the flow)

An egress can now **return**. Draw a line from an egress node back to a state
and it becomes a `ROUTE_BACK` exit: the call goes out to the egress's routes
(an external media server, transcoder, SBC), and when it comes back the flow
**resumes at that state**. No line out = `ROUTE_FINAL` as before (the call
leaves OCCAS for good). The exit kind is inferred from the diagram topology —
there's no modifier dropdown anymore.

This works because OCCAS already round-trips our routing state: on `ROUTE_BACK`
the container BASE64-encodes the App Router's `stateInfo` into the route it
pushes back to itself, and re-invokes the AR (directive CONTINUE) with that
exact `stateInfo` when the call returns (verified in the decompiled
`ContainerProcessRequest`). So FSMAR needs no special detection — it sets the
return state as its `currentStateId` when issuing the `ROUTE_BACK`, and resumes
there on the continuation hop. In the config a route-back egress is a transition
with `routeModifier: ROUTE_BACK` whose `next` is the resume state; `diagram`
records the egress's `returnState`.

**`RoutingState` slimmed for the wire.** Because `ROUTE_BACK` serializes
`stateInfo` into a SIP Route URI, `RoutingState.config` is now **transient** —
the whole configuration is no longer carried on the wire (it was). The engine
re-binds the current config snapshot on each invocation. Trade-off: a config
reload mid-call now applies to that call's later hops instead of being pinned —
acceptable, and it keeps the route-back Route header small at 1000+ CPS.

The sample shows it: anonymous callers detour through a media server
(`media-greeting`) and resume at `b2bua`.

### FSMAR 3: a state's id is now separate from its application

A state used to be keyed by the application that runs there — the states-map key
*was* the app name. That made it impossible to invoke one application **twice**
on a call-path (e.g. a B2BUA once per subscriber leg): on each continuation hop
the engine recomputed "where am I" from the SIP session's last application name,
so two states sharing an app name were indistinguishable.

Now a state has an **id** (its unique map key) distinct from its **`app`** (the
application it invokes). Two states can share an `app` under different ids:

```json
"b2bua-caller": { "app": "b2bua", … },
"b2bua-callee": { "app": "b2bua", … }
```

- `app` is optional and defaults to the id, so the common one-instance-per-app
  case is unchanged (no `app` field). A transition's `next` is a **state id**.
- The engine (`AppRouter`) carries the current **state id** in its `stateInfo`
  (`RoutingState.currentStateId`) and resumes from it rather than re-deriving it
  from the session — which is what lets the two states be told apart. The
  application to invoke is resolved through the target state's `app`.
- New pseudo-variable `${previousState}` (the state id); `${previousApp}` remains
  the application name.
- Flow editor: a **State ID** field (defaults to the name); a node whose id
  differs from its app shows the id as a qualifier so duplicate-app boxes read
  distinctly. The Route Simulator visits each instance as its own hop.
- The sample demonstrates a two-leg B2BUA (`b2bua` → `b2bua-callee`, both running
  `b2bua`).

FSMAR 3 has no users yet, so this is a clean config-shape change with no
migration.

### FSMAR 3: egress exits (the mirror of ingresses)

Ingresses made each SBC a real entry state classified by source IP. Egress is
the symmetric idea at the *other* end of the callpath: where a call **leaves
OCCAS**. Routes are pointless on a transition between two apps inside OCCAS (the
next app routes itself) — they only matter on the final hop, which is an egress:
either an explicit external destination (carrier, PBX) or a return toward the
origin SBC.

- **Engine fix (`libs/fsmar3` `AppRouter`)** — a matched *terminal* transition
  (no `next`) that carries `routes` now produces a real JSR-289 routing decision
  (`createRouterInfo` with a null application name), applying the routes and
  `routeModifier`. Previously such routes were silently dropped — the one place
  they make sense was the one place the engine ignored them. The
  default-application fallback is guarded so an egress hung off the entry state
  isn't clobbered.
- **Editor** — a new **Egress** node (the mirror of the ingress gateway). Draw a
  transition into it; set the exit's route URIs and kind (**to destination** =
  `ROUTE_FINAL`, **back to origin** = `ROUTE_BACK`) on the node. The transition's
  own route editor is hidden for app-to-app hops (routes belong at the egress).
  Egress nodes round-trip through `diagram.egresses`, matched to their terminal
  transitions by (routes, routeModifier).
- **Route Simulator** — shows the egress as the final outcome ("the call left
  OCCAS via its routes") rather than a downstream fall-through.
- **Sample config** — adds a `to-carrier` (`ROUTE_FINAL`) off-net exit and a
  `back-to-origin` (`ROUTE_BACK`) blocklist exit.

### Flow editor: graceful recovery from an expired admin session

When the admin session (`BLADEADMINSESSION`, 1h idle) expired mid-edit, the
Flow editor's next call to a FORM-auth-protected `/fsmar*` endpoint surfaced
raw HTML in the UI — usually `Export failed: 500 …
MaxPostSizeExceededException: MaxSavePostSize [4096] exceeded`. That 500 comes
from WebLogic's FORM-auth save-post buffer: it tries to stash the POST body to
replay after re-login, but the multi-KB mxGraph XML overruns the fixed 4096
cap. (That cap is *not* a `weblogic.xml` web-app knob — `max-save-post-size`
belongs to the `weblogic-application-client` descriptor — so there's nothing to
raise per-WAR.)

Now all editor calls route through a single wrapper (`js/flowSession.js`,
`window.flowRequest`). On a response that is actually the auth layer talking
(401/403, a login page, or the save-post 500), it shows a "Your admin session
expired — Log in" banner instead of dumping HTML into the editor, and parks the
request. Clicking **Log in** opens the portal (`/blade/portal/`) in a popup;
after you authenticate and close it, the shared session cookie is refreshed and
the parked request retries automatically — now with a valid session, so it
completes normally (no save-post, no 500). The diagram lives in the mxGraph
model the whole time, so nothing is lost. No background keep-alive; idle
sessions still time out per policy. The portal popup also closes itself the
moment login completes (it's same-origin, so the editor watches it land back on
the portal), so the parked action retries without the user closing it by hand.

The editor also now warns before you leave the page (close tab / refresh /
navigate away) when the diagram has edits that were never published — the work
lives only in the in-memory model, so this guards against losing it. The flag
is cleared on publish and on load/import.

### One spelling for pseudo-headers/variables (lowerCamelCase)

The v3 selector layer used to accept every spelling of each synthetic name
(`Origin-IP`/`OriginIP`/`originIP`, `Request-URI`/`requestURI`/`RequestURI`/`ruri`,
`Peer-IP`/`peerIP`, `Transport`, `IsSecure`, …). Standardized to ONE spelling
each — lowerCamelCase with acronyms uppercase: `originIP`, `peerIP`,
`requestURI`, `transport`, `isSecure`, `clientCertSubject`, `clientCertIssuer`,
`tlsCipher`, `reasonPhrase`, `body`. The alias spellings are gone from
`Selector.readSource` and `MessageHelper`; a config still using an old spelling
falls through to a real-header lookup (→ null). Real SIP header names
(`From`, `To`, `Call-ID`, …) are untouched. Also fixed an inconsistency: the
router published the context var as `requestUri` but read it as `requestURI` —
now `requestURI` everywhere (engine, RouteSimulator, RouteTrace JSON, the
FSMAR2→3 converter, sample, docs). FSMAR2 config files keep their own
`Request-URI` spelling — the converter maps it to the v3 canonical.

### `insubnet` operator + sample with two SBC ingresses

The v3 expression engine gains an `insubnet` operator for **real CIDR
matching**, backed by the already-bundled `ipaddress` library:
`${originIP} insubnet '10.20.0.0/16'`. True subnet math at any boundary
(/18, /26, …), IPv4 and IPv6; a bare IP on the right means exact match;
unparseable input evaluates to false (never throws mid-routing). This is the
honest answer to source-IP matching — the old string `matches` regex only
worked at octet boundaries and couldn't do arbitrary CIDR.

The FSMAR3 sample now demonstrates it: two ingress SBC entry states,
**Atlanta** (`10.20.0.0/16`) and **Dallas** (`10.30.0.0/16`), classified by
`${originIP}` via `insubnet`. Each is a real entry state routing its calls
out its own site gateway; the default ingress (`null`) catches everything
else. The sample sets `diagram.ingresses` so it renders as ingress clouds
in Flow.

### Flow editor: ingresses are real FSMAR entry states

Multiple ingress clouds (SBCs) are now honest, not decoration. An ingress box
is a genuine FSMAR state with its OWN selectors and arrows; the `"null"` state
became a thin **dispatch layer** that classifies inbound traffic by a
per-ingress **Source match** (`when` expression, e.g.
`${originIP} insubnet '10.20.0.0/16'`) and bypasses it into the matching
ingress state — using the existing bypass loop, no engine change. The
**default ingress = the `"null"` state** ("any source"); with no named
ingresses it's exactly the old single-entry model. Per-ingress selectors are
real now (different SBCs, different header conventions), fixing the old
all-gateways-collapse-to-null selector asymmetry.

- `Diagram` reshaped: `states` (positions, incl `"null"`) + `ingresses`
  (state name → `{match}`). `Gateway`/`from`/`to` retired — an ingress is a
  state, so edges connect to it directly.
- Export emits each ingress as a real state and generates its source-dispatch
  transition on `"null"` (`dispatch-<name>`, leading the trigger so
  classified traffic wins first-match). Import absorbs those dispatch
  transitions (re-derived from each ingress's match, not drawn as arrows).
- Editor: ingress node panel gains a **Source match** field; selectors are
  per-ingress. Validator warns on a matchless named ingress / a missing state.
- Egress unchanged this round (a transition `next:"null"` + routes, drawn into
  the default box); richer per-SBC egress is the symmetric follow-on.
- **Breaking (uncommitted-era):** the `diagram` shape changed again
  (gateways/from/to → ingresses). Old-shape diagrams don't round-trip; re-save
  from Flow. The live dev config has no diagram (stripped earlier).

### Config cleanup: drop `about`/notes; FSMAR3 declares version 3

- **`about` removed framework-wide.** The `About` class (operator `notes`) is
  deleted and the `about` field is gone from the base `Configuration` — app
  identity already lives developer-side in `@SchemaAbout`, `notes` had no
  readers, and the empty `"about": {}` cluttered every config. Backward
  compatible: the SettingsManager mapper ignores unknown keys, so existing
  customer files with an `about` block still load (the key is dropped on next
  save). The Flow validator still tolerates a stray legacy `about` without a
  typo warning.
- **FSMAR3 config baseline version is 3.** `AppRouterConfiguration.getVersion()`
  defaults a missing version to 3, and the generated sample emits
  `"version": 3`. The framework `version` field now encodes the FSMAR
  *generation* for this config lineage — a future upgrader can spot a pre-3
  fsmar file and run the `Fsmar2Converter` transform. Other services' version
  counters are independent and unchanged.

### One login page: portal master injected into every admin WAR at build time

The ten per-app login pages (rewritten lookalikes of the portal's hand-built
`login.jsp` — three drifted variants, missing the backdrop, remember-me, and
footer) are deleted from source. Each admin WAR's pom now injects the portal
master byte-identically at packaging time via maven-war-plugin
`webResources`, at whichever path that app's `form-login-page` expects. One
source file; drift is structurally impossible. Runtime assets were already
centralized (unauthenticated `/blade/portal/brand/*` + `/blade/portal/assets/*`);
JSTL comes from the `vorpal-blade` shared library, so the master runs in
every WAR. See SECURITY.md.

### Flow editor: Gateways replace Ingress/Egress clouds

One cloud type — **Gateway** (SBC, trunk, carrier) — where direction lives
on the arrows: an edge OUT of a gateway is ingress traffic (a transition in
the `"null"` state), an edge INTO one is egress (`next: "null"`). The same
SBC no longer has to be drawn twice, and an administrator's multi-SBC
topology now survives reload: the `diagram` section is restructured
(`states` placements, `gateways` list, `from`/`to` attachment maps keyed by
transition id — or `state/METHOD/index` for id-less transitions, which
degrade to first-gateway attachment if reordered). The neutral gateway
shape carries direction as drawn arrows, not color-coding (the old
purple-in/orange-out scheme). Legacy Ingress/Egress elements in saved XML
diagrams still open and export as gateways. Null-state selectors
concatenate from all gateways on export and land on the first on reload
(known v1 asymmetry). The validator warns on duplicate gateway labels
(duplicates merge on reload).

**Breaking (uncommitted-era only):** the `diagram` schema changed from this
morning's flat placement map to the structured `Diagram` class. The live
dev config's old-shape `diagram` section was stripped (backup at
`fsmar3.json.pre-gateway.bak`) — valid for both jar versions; re-save from
Flow to regain a stored layout.

### Flow editor: hand-tool panning + auto-position; Configurator form hints

- **Hand tool pans the canvas**: dragging the background with the pan tool
  slides the view (explicit view-translate drag — tracks client coordinates
  and consumes the gesture, so the rubberband can't fight it; clicking a
  cell still selects). Grab cursor while active. Ctrl+Shift-drag panning
  still works in any mode.
- **Auto-position toolbar button** re-runs the hierarchical left-to-right
  layout on the whole diagram and centers it. Imports also center the view.
- **Live JSON view** (curly-braces toolbar button): a read-only window
  showing the FSMAR 3 JSON for the diagram being edited, refreshed
  (debounced 400 ms) on every model change — drag a box or draw an edge and
  watch the config update. Mid-edit unexportable states (e.g. a dangling
  edge) display the named reason instead of going blank.
- **`@FormLayout(collapsed = true)`** — new framework form hint (emitted as
  `x-collapsed`): the Configurator renders the property's section minimized
  even when it has data. `readOnly = true` now also works on complex
  properties (object/map/array): the whole subtree renders disabled.
- The fsmar3 `diagram` property is annotated collapsed + read-only, so the
  Configurator shows it as a minimized, non-editable section — visible,
  but clearly owned by the Flow editor.

### Flow editor: single-artifact workflow — FSMAR JSON carries its own layout

The two-file workspace (mxGraph XML for layout + FSMAR JSON for routing) is
gone. `AppRouterConfiguration` gains an optional `diagram` section — a map of
state name → `Placement{x,y}` (reserved keys `(ingress)`/`(egress)` for the
clouds) — so one JSON file holds both the routing semantics and the editor
layout. Only geometry is stored, never duplicated semantics, so a Configurator
edit can't make the diagram lie; states without a stored position are
auto-laid-out. The field had to be typed into the config class: the
SettingsManager mapper fails on unknown properties, so a bare `diagram` key
would have broken the engine's config load.

- Export emits `diagram` from live cell geometry; import honors stored
  positions (grid fallback per state). Round-trip covered by new smoke tests.
- Importing a config with no diagram runs the bundled mxHierarchicalLayout
  left-to-right — a bare hand-written config renders as a readable callflow.
- The editor's Save/Open buttons now use FSMAR JSON as the native file
  format (legacy mxGraph XML files still open, sniffed by leading `<`).
- The import dialog gains **Load live fsmar3** (GET on the publish servlet),
  completing the no-files loop: Load live → edit → Save to fsmar3.
- **Load sample** (was "Load example") now serves the canonical generated
  sample `_samples/fsmar3.json.SAMPLE` instead of a hardcoded JS copy that
  had drifted from `AppRouterConfigurationSample` — one source of truth.

**Deploy order matters**: engines must get the new FSMAR jar (now `blade-fsmar.jar`)
before any diagram-bearing config is published, or the old class rejects the
unknown `diagram` field on load.

### Flow editor: publish straight to the live fsmar3 config

The FSMAR Export dialog gains a **Save to fsmar3** button. A new
`FsmarPublishServlet` writes `config/custom/vorpal/fsmar3.json` on
AdminServer — the same file and mechanism as a Configurator save — and the
engine-tier SettingsManager reloads it live. The JSON is re-parsed through
Jackson before writing, so malformed edits are rejected instead of
clobbering a working config. Download / Copy remain for source control and
lab transfers. Also: the export/import dialogs now size to the viewport and
are resizable (the fixed 600×500 window clipped the validation findings),
and the property panels' CSS actually loads now — panel styles moved to a
shared `css/panels.css` linked from flow.html, because jQuery's
fragment-load discards the fragment pages' `<head>` styles.

### Fix: Flow editor toolbar icons

`config/wftoolbar-commons.xml` references 24 toolbar icons under `svg/`, but
only three (simulate, server-in, server-out) ever existed — every other
toolbar button rendered as a broken image and 404'd in the console. Authored
the 21 missing SVGs in the same hand-drawn `currentColor` style.

### Fix: 403 Forbidden on Flow and Test Console admin apps

`admin/flow` had its `<security-role-assignment>` blocks commented out
("temporarily disabled for development") and `admin/test-console` never had
them. With no assignment, WebLogic maps each declared role to a same-named
principal — no group is literally named `Admin`, so every user landed in zero
roles and got 403 after a successful FORM login. Both weblogic.xml files now
carry the standard four `<externally-defined/>` assignments
(Admin/Operator/Monitor/Deployer), matching the rest of the admin tier. All
other admin apps, services, and test WARs audited — no other mismatches.

### iRouter: routing decision re-dispatched onto a container thread

`IRouterInvite` now wraps `applyRouting` in a 0-ms ServletTimer. When the
pipeline contains an async connector (REST), the connector chain completes on
the HttpClient executor thread, where the container has no call context —
`request.getProxy()` in `executeRoute` threw an NPE (hit in the field on
forward routes behind a REST screening call). The timer fires the routing
decision on a container thread with appSession context regardless of which
thread completed the chain; sync-only pipelines just take one extra 0-ms hop.

### Enterprise SIP test suite: scenario-driven test-uac / test-uas + Test Console

The test pair grows from "load script + parameter tricks" into one
scenario-driven testing toolkit — BLADE's SIPp replacement. Core insight:
simulated tests and real-call manipulation are the same thing with two call
sources, and the message-transformation engine already existed (v3 CRUD).

**New framework subsystem `framework.v3.tester`** (shared by both WARs):

- `TesterConfiguration extends CrudConfiguration` — adds a `scenarios` map
  (+ `defaultScenario`, `originate` load defaults) on top of the CRUD
  `ruleSets`/selectors/maps/plan machinery, so scenario selection and message
  transformation reuse the proven engine and the Configurator edits all of it
  schema-validated.
- `Scenario` — role (`originate` / `answer` / `b2bua`), optional template,
  referenced ruleSet and/or inline rules, `ResponseScript`, assertions.
- `ScriptedAnswer` — generalizes TestInvite/TestRefer: ordered status steps
  with per-step delay/reason/SDP control, REFER transfer (NOTIFY handshake),
  auto-BYE. The `status=`/`delay=`/`refer=` URI shorthands still work
  unchanged (they synthesize an ephemeral scenario) — existing SIPp scripts
  keep passing.
- `LoadEngine` / `OriginateCallflow` — the test-uac load generator, moved to
  the framework and made scenario-aware (CPS + concurrent pacing preserved
  verbatim). Originated calls apply rules to requests AND responses, validate
  the final status against `expectFinal` (`2xx`, `!5xx`, … — the Rule
  statusRange syntax), and evaluate **assertions**: v3 `Expression`
  predicates over per-call variables (`${lastStatus}`, `${statusSequence}`,
  `${setupMs}`, `${index}`, plus anything a rule `read` captured).
- `SipMessageTemplate` / `TemplateLoader` — the template engine, extracted
  and hardened: mtime-based hot reload (no more cache invalidation hack),
  MimeHelper-backed multipart merge (template SDP wins, softphone SDP
  preserved otherwise), Contact param-merge via the framework.
- `TesterMetrics` / `ScenarioStats` / `ScenarioReport` — per-node,
  per-scenario lock-free counters: status distribution, latency buckets with
  p50/p90/p99/avg/max, expectation mismatches, assertion pass/fail/warn.
- `TesterControl` / `TesterMXBean` — per-node JMX control surface
  (`vorpal.blade:Name=<app>,Type=TesterControl`): startLoad/stopLoad/
  status/report as JSON, registered via the explicit-StandardMBean pattern.
- `TesterServlet` — abstract base both WARs extend: scenario resolution
  (URI param → translation plan → shorthands → configured default → built-in
  default), endpoint vs B2BUA routing, lifecycle rule application.

**CRUD engine additions**: `KeepOnlyPartOperation` (`keepPart`) collapses a
multipart body to the part(s) matching a content type — the SIPREC-strip as
one operation; `MimeHelper` gains string-based `parseParts`, `compose`,
`keepOnlyPart`, public `extractBoundary`; `MessageHelper` now guards the
`Contact` system header (merges parameters like `+sip.src` instead of
throwing); `Rule.matchesStatus` is public (reused by `expectFinal`).

**test-uac / test-uas** shrink to thin leaves. Both hand-rolled multipart
parsers are gone (replaced by MimeHelper); the four test-uas callflows fold
into `ScriptedAnswer`. REST API gains `GET /api/v1/loadtest/report` and
`POST /reset`; `start` accepts `scenario`. The single-call
`POST /api/v1/connect` now honors its request fields (the hardcoded
`siprecInvite.txt` body-stuffing debug leftover is gone) and accepts
`template`/`scenario`. Legacy config fields still load: test-uac's top-level
`template` remains functional, and `fromAddressPattern`/`toAddressPattern`/
`requestUriTemplate`/`duration` feed into the new `originate` block.
test-uas keeps strip-multipart-to-SDP as its built-in default scenario.

**New admin app `admin/test-console`** (`blade/test-console`, in
blade-admin.ear, portal card via SettingsManager metadata): discovers every
tester node's `TesterControl` MBean over federated JMX, starts/stops runs on
all nodes at once, and renders per-node status cards plus an aggregated
per-scenario metrics table (counters summed across nodes; latency shows the
worst node — percentiles don't merge). State conveyed by text/shape, not
color alone.

### FSMAR 3 Route Simulator, call replay, and live heat overlay (Flow editor)

The Flow editor (`blade/flow`) gains a **Route Simulator** window (toolbar
button, three tabs) built on one shared routing-trace JSON format:

- **Simulate** — runs a synthetic request (form fields or a pasted raw SIP
  message) through the diagram *being edited* and animates the routing path
  hop by hop on the canvas: per-hop extracted values, every transition
  evaluated with FIRED/no-match and its `when`, resolved `${}` routes,
  subscriber URI, region. Pseudo-variables (`${hour}`, `${dayOfWeek}`,
  `${hash100}`) are overridable, and any application can be marked
  *undeployed* to explore bypass / cycle-detection / default-fallback
  behavior before deploying anything. New `FsmarSimulateServlet` +
  `RouteSimulator` (flow-side mirror of `AppRouter.getNextApplication`,
  chained across invocations so one run traces the whole call path; the hard
  semantics — selectors, `Expression`, tables, `${}` resolution — run through
  the same framework v3 classes the engine bundles). A **Validate diagram**
  button runs the semantic validator and outlines named states on the canvas.
- **Replay** — `Fsmar3Metrics` gains opt-in trace capture: MBean ops
  `captureNextCalls(n)` / `getCaptureRemaining` / `getCapturedTraces` /
  `clearCapturedTraces` (new `RouteTrace` record, max 100 calls, disarmed
  cost = one atomic read per request; multi-hop calls correlate by Call-ID).
  The Replay tab arms capture across every engine over federated JMX and
  replays captured production calls through the same diagram animation.
- **Live** — polls per-transition hit counters across all engines every 5 s
  and overlays them on the edges: count appended to the edge label, stroke
  width scaled by traffic share (width + text carry the meaning, not color).
  Plus domain-wide totals and a reset button.

New `FsmarMetricsServlet` bridges the browser to
`org.vorpal.blade:type=Fsmar3,name=metrics` on every engine via the
DomainRuntime MBeanServer (the admin tier's JMX convention). All canvas
effects are mxCellHighlight overlays — cell styles, the model, and undo
history are untouched.

Verified: `Fsmar3CaptureSmokeTest` 52/52 (arm/claim/extend, trace shape,
bypass/cycle/fallback flags, per-hop diffs, clamp/bounds/order),
`SimulateSmokeTest` 50/50 (full call-path chaining, tier table + `matches`,
bypass, cycle, fallback, pseudo overrides, engine-parity selector handling),
existing fsmar3 suites still green. JMX reachability of the engine MBeans
follows the proven SettingsMXBean pattern (same platform-MBeanServer
registration, same federated walk the portal uses).

### Build: fixed shade-plugin double-shading on incremental builds

Incremental (no-`clean`) builds of the fsmar fat-jar modules re-shaded the
previous build's already-shaded jar: shade overwrites
`target/<finalName>.jar` with the uber-jar, and maven-jar-plugin's default
`forceCreation=false` then skipped recreating the thin jar on the next run.
Symptoms were thousands of bogus "overlapping classes" warnings — and a real
risk of stale classes from removed/upgraded dependencies surviving in the
shipped jar. Parent pom now sets `forceCreation=true` on maven-jar-plugin.
Both fsmar shade configs also set `createDependencyReducedPom=false`
(nothing consumes these artifacts via Maven; the reduced POM was source-tree
clutter).

Verified: fsmar and fsmar3 rebuilt twice without `clean` — zero overlap
warnings, no `dependency-reduced-pom.xml` generated.

### Context-path-derived config names (BREAKING for admin-app config files)

The canonical per-app name — JMX MBean (`vorpal.blade:Name=…`), config file
(`<name>.json` + cluster/server overlays), `_schemas`/`_samples` files, and
log files — is now derived from the webapp's **context path**, flattened
(strip leading `/`, then `/` → `-`), via new `SettingsManager.flatten()` /
`deriveName()`. Previously it came from the web.xml display-name, which
forced every admin app to pick a flat name differing from its context-root.

- **Services unchanged**: `/crud` → `crud` — same names as before.
- **Admin apps change**: `/blade/crud` → `blade-crud`. MBeans, config files,
  and log files for the whole admin tier are now `blade-*`.
- Portal launcher join simplified to the same flatten; the
  `analytics`→`analytics-console` special-case map is gone.
- Configurator auto-publish self-reference updated to `blade-configurator`.
- LogManager shares the same single name derivation (log files match MBean
  and config names byte-for-byte).

**Migration**: existing domain config files for admin apps must be renamed
(`configurator.json` → `blade-configurator.json`, `portal.json` →
`blade-portal.json`, `files.json` → `blade-files.json`, `logs.json` →
`blade-logs.json`, `flow.json` → `blade-flow.json`, `tuning.json` →
`blade-tuning.json`, `watcher.json` → `blade-watcher.json`, `api.json` →
`blade-api.json`, `javadoc.json` → `blade-javadoc.json`, `crud-editor.json`
→ `blade-crud-editor.json`, `analytics-console.json` → `blade-analytics.json`),
including any `_clusters/`/`_servers/` overlays. Un-renamed files are ignored
(app falls back to its sample config). `_schemas`/`_samples` regenerate on
deploy. Service config files need no changes. External JMX scripts using old
admin MBean names must update.

Verified: new FlattenSmokeTest 7/7; full build green (framework, portal,
configurator, watcher, all services).

### Config schema version field (groundwork for automatic config upgrades)

`Configuration` (v2 base class, so every service/admin config) now carries an
`Integer version` — the config file's schema version, serialized first in
every config file and SAMPLE. Absent/null reads as 0, meaning "pre-versioning
file". Rendered read-only in the Configurator form (`@FormLayout(readOnly)`),
deliberately NOT Jackson read-only so the value still binds from files. First
step toward framework-managed config-file upgrade chains (BLADE 3.0).

Verified: new ConfigurationVersionSmokeTest 6/6 (absent⇒0, explicit binds,
serialized first, schema `x-readonly`).

Follow-up: `SettingsManager`/`Settings` refactored for extensibility ahead of
the v3 SettingsManager — six duplicated event-constructor bodies collapsed
into `initContext()`; `build()` decomposed into `initConfigPaths()` /
`createSettings()` (Settings factory) / `configureMapper()`, all protected
overridable seams; `Settings.reload()` now reads each overlay file through a
protected `readConfigTree(file, configType)` hook (the future per-file
upgrade point); Settings fields widened to protected. No behavior change —
all constructors and public methods unchanged, full build + existing
subclasses (queue, acl, proxy-block, proxy-balancer, configurator) compile
as-is, smoke tests green.

### FSMAR 3: razzle-dazzle round — tiering as data, pseudo-variables, observability

Five features landed together (framework + fsmar3), closing every remaining
FSMAR 2 gap worth closing and adding capabilities v2 never had:

- **`matches` / `contains` operators** in the v3 `Expression` grammar
  (framework — iRouter gets them too). `matches` is full-string regex with a
  cached compiled pattern; `contains` is literal substring; malformed
  patterns evaluate false rather than throwing.
- **`TableSelector`** (framework, selector type `table`): looks up
  already-extracted context values in an embedded `TranslationTable`
  (hash/prefix/range keys) and spreads the matched extras into the context —
  classification/tiering as data. Gold/silver/bronze is one config plus a
  table operators edit; conditions just test `${tier} == 'gold'`.
- **Pseudo-variables** published each hop before the state's selectors:
  `${method}`, `${requestURI}`, `${directive}`, `${region}`,
  `${previousApp}`, `${hour}`, `${dayOfWeek}`, `${hash100}` (stable per-call
  0–99 bucket from the Call-ID — `${hash100} < 5` canaries ~5% of calls).
- **Trace + metrics**: FINER trace of every transition evaluated with its
  outcome (the Route Simulator's raw material); JMX MBean
  `org.vorpal.blade:type=Fsmar3,name=metrics` (explicit StandardMBean) with
  per-transition hit counts, requests routed, default-app fallbacks,
  undeployed bypasses, cycle detections, and a reset operation.
- **Config validation** on each loaded config's first routing use: malformed
  `when` → SEVERE (it would silently never match); `next`/defaultApplication
  naming an undeployed app → WARNING. Plus an optional per-transition
  **`region`** field (ORIGINATING/TERMINATING/NEUTRAL, default NEUTRAL) —
  the last JSR-289 feature v2 had that v3 lacked.

The sample config now demonstrates the tier table, a `matches` toll-free
rule, and documents the pseudo-variables. README updated.

Verified: ExpressionSmokeTest 71/71, new TableSelectorSmokeTest 13/13,
FsmarRoutingSmokeTest 46/46 (pseudo-vars incl. hash stability, tier
classification end-to-end, matches in `when`, region round-trip, metrics);
full production build + javadocs green; connect/securelogix regression
build green against the modified framework. Wire-level behavior (trace in
live logs, JMX metrics in a real engine) is deploy-only.

### Javadoc site: every module card now has a tagline

Seven modules on the javadoc landing page (`/blade/javadoc`) showed a bare
title with no description. Three fixes:

- **Six missing `package-info.java` files written** — services/crud,
  services/irouter, admin/files, admin/logs, admin/analytics-console, and
  admin/flow (its real root package is `applications.console.mxgraph`). Each
  has a card-sized first sentence plus a real package overview.
- **Stale-docroot fix in `collect-javadocs.sh`**: the collected docroot
  persists across builds by design (modules absent from a trimmed build keep
  their docs), but a module's own directory is now `rm -rf`'d before copy.
  The analytics service's card was blank because its docroot still carried
  the console's old `applications.analytics` classes (May 29 layout), which
  won the root-package tie-break over the real `services.analytics` package
  and its description.
- **Display names**: the index generator now special-cases names that naive
  Title-Casing mangles — ACL, API, CRUD, CRUD Editor, iRouter, TPCC, FSMAR,
  FSMAR 3, Test UAC/UAS/B2BUA. Also shortened the analytics package-info
  first sentence, which was being truncated mid-word at the card's 200-char
  limit.

Verified: `./build.sh production` regenerates the index with all 31 cards
carrying descriptions; browser check of `/blade/javadoc` after deploy.

### Javadoc site matches the build profile; test apps promoted to production

- **javadoc.war now contains exactly the active profile's modules.** build.sh
  passes the profile's module list to `collect-javadocs.sh`
  (`-Dblade.included.modules` → 4th script argument): only listed modules are
  collected (a leftover `target/` from another profile's build no longer
  leaks in) and previously collected modules not in the list are pruned.
  A plain `mvn -Pjavadocs` without build.sh keeps the legacy
  accumulate-everything behavior (empty list).
- **Test apps promoted to production** (2026-06-05): `test-b2bua`,
  `test-uac`, `test-uas` added to production.conf and to the cluster EAR via
  their own `ear-test-*` profiles — they now deploy with the services tier as
  live-diagnostics tools, with more features planned. `context.war` remains
  the only standalone services-tier WAR.

Verified: production build → blade-cluster.ear contains the 3 test WARs;
javadoc docroot contains exactly the profile's modules (`context` pruned with
a log line, test-app docs present, 30 index cards).

### Context service promoted to full/production

The `context` service (REST lookup of raw inbound SIP headers, captured
before a cloud-provider trunk scrubs them) is now a featured BLADE service:
added to production.conf and bundled into blade-cluster.ear via its own
`ear-context` profile. Every services-tier WAR now rides the EAR; the only
standalone deployable left anywhere is the admin tier's `watcher.war`.

With this, the `default`, `full`, and `production` profiles select identical
module sets (verified by diff). They remain separate files on purpose —
production is the customer-facing anchor; default/full can grow dev-only
modules again later.

Verified: production build → blade-cluster.ear contains context.war (18
WARs); javadoc index back to 31 cards with the Context tagline.

### Build: profile-driven tier EARs — blade-admin.ear + blade-cluster.ear

Both tiers now package as one EAR each, with contents that track the active
`build-profiles/*.conf`:

- **`blade-cluster.ear`** (`services/cluster`, conf name `cluster`) — all
  service WARs in one cluster deployable, mirror of the admin EAR. Test apps
  and `context.war` are deliberately standalone (deploy manually, like
  `watcher.war` on the admin side).
- **`blade-admin.ear`** (`admin/ear`) converted from a fixed WAR list to the
  same profile-driven pattern: every app rides an `ear-<name>` profile
  activated by `!skip.<name>` — the flags build.sh derives from the conf — so
  dropping an app from the conf drops it from the EAR with no dangling
  references.
- **javadoc.war rides the `javadocs` profile id**: the admin EAR carries it
  exactly when docs are generated. `--no-javadoc` (or an old build JDK) no
  longer skips the whole admin EAR — it just assembles without javadoc.war.
- `deploy.sh` already prefers an EAR in a tier dir as the deploy unit, so
  `deploy.sh <env> services <target>` now deploys `blade-cluster.ear`.
- Cleanup: stale "EAR is disabled" text and the TODO(EAR) marker removed from
  build.sh; DEPLOYMENT.txt manifest now classifies both EARs and shows the
  real `/blade/<app>` context-roots (several were stale, e.g. `/configurator`);
  dead `blade.war`/`explorer.war` classifier cases dropped; DEPLOYMENT.md
  services-tier section rewritten.

Verified: `./build.sh production` → both EARs with the full production set
(11 admin WARs incl. javadoc.war; 14 service WARs, correct context-roots in
application.xml); `./build.sh production --no-javadoc -Dskip.queue -Dskip.flow`
→ admin EAR builds *without* javadoc.war/flow.war, cluster EAR without
queue.war; dist run → EARs land in `dist/<ver>/{admin,services}/` beside the
individual WARs, manifest correct.

### Framework: keep-alive `style: UPDATE` actually implemented

`session.keepAlive.style: UPDATE` previously behaved identically to `REINVITE`
— the enum value was accepted by the schema but never checked (the only style
comparison was `!DISABLED.equals(style)`), and every refresh sent re-INVITEs
with full SDP shuttling. Now UPDATE sends lightweight, bodiless RFC 3311
UPDATEs — **but only when both endpoints are known to support UPDATE**;
otherwise each refresh falls back to the re-INVITE behavior, unchanged.

- **Capability detection**: `AsyncSipServlet` passively sniffs `Allow:`
  headers on every inbound request/response and records
  `Callflow.ALLOW_UPDATE` (Boolean) on that leg's SipSession. No Allow header
  → flag untouched; unknown counts as unsupported (conservative).
- **Refresh dispatch** (`keepalive.KeepAlive.handle`): style is read from
  config at refresh time (a republish flips live calls on their next cycle).
  UPDATE style + both legs flagged TRUE → sequential bodiless UPDATE on each
  leg (leg B only after leg A's 2xx, so at most one fallback can fire). Any
  non-2xx final → that cycle completes via re-INVITE (refreshes both legs, so
  a half-refreshed pair self-corrects); 405/501 additionally latches the
  failing leg to non-supporting so future cycles skip UPDATE entirely.
- B2BUA chains relay the refresh end-to-end — UPDATE already routes through
  `Passthru`. Glare logic is untouched (UPDATE never set PROTECT, same as
  INFO).
- One new public constant (`Callflow.ALLOW_UPDATE`) and one public static
  helper (`KeepAlive.allowsUpdate(List<String>)`); everything else private or
  package-private.

Verified: framework builds clean; new `KeepAliveUpdateSmokeTest` 20/20 (Allow
parsing, config gate, per-leg flag); existing Callflow/SDP smoke tests 84/84.
Wire-level behavior (UPDATE vs re-INVITE on a real refresh cycle, 405
fallback) is SIPp/deploy-only.

### Framework: Callflow.java internal refactor (behavior-preserving)

`v2.callflow.Callflow` decomposed for readability; no public/protected API
signature, session-attribute name, or wire behavior changed. v3's `Callflow`
subclasses v2 and inherits everything.

- `sendRequest` shrank from ~265 lines to ~55: the Vorpal-ID header stamping
  (previously duplicated verbatim in `sendResponse`) is now
  `stampVorpalIdHeaders` (`protected static`, usable by subclasses); appSession
  expiration and keep-alive setup moved to private helpers.
- **Fix:** the keep-alive *expiry* callback used a captured `SipSession`
  reference instead of the live session the container hands the callback —
  stale after cluster failover. Now matches the refresh callback (which was
  already correct).
- `sendRequestsInSerial`: the triple-duplicated "next request or dummy 408"
  block is one helper; removed a provably unreachable dummy-408 branch.
- Dead-code sweep: unused private constants, commented-out constants,
  always-true null checks, a redundant `Math.abs` on `nextLong(0, bound)`.

Verified: framework builds clean; Callflow/SDP smoke tests pass 84/84.

### Framework: AsyncSipServlet.java internal refactor (same treatment as Callflow)

`v2.AsyncSipServlet` decomposed; no public/protected API signature or
session-attribute name changed. All new helpers are `private` (static where
possible), per the no-protected-instance-helpers rule.

- `doRequest` shrank from ~380 lines to ~130; `doResponse` from ~330 to ~110.
  Extracted: FINER diagnostics (both directions), initial-INVITE caller/
  keep-alive bookkeeping, origin/destination AttributeSelector processing,
  early-dialog session merge, and both error-recovery blocks.
- **Fix:** `servletCreated` was invoked **twice** for any app whose config has
  no analytics section — once unconditionally, then again in the
  `getAnalytics() == null` else-branch. Now exactly once.
- **Fix:** `Callflow.setLogger(sipLogger)` ran *before*
  `sipLogger = LogManager.getLogger(event)`, pushing the previous (null on
  first deploy) logger into Callflow; the fresh logger only arrived later via
  `SettingsManager.build`. Reordered so Callflow gets the new logger
  immediately.
- Dead code removed: an always-null `callflow` pre-check before
  `chooseCallflow`, a provably unreachable `isProxy(response)` re-check in
  `doResponse` (false on entry to that branch, attribute can't change in
  between), an unused `rqst` assignment in downstream-kill recovery, the
  commented-out glare-queue block, and dead `sipLogger != null` checks that
  followed unguarded `sipLogger.warning` calls (warnings now inside the
  guard).
- Log-text corrections: `doReponse`/`sendReponse` typos, a "doResponse" tag on
  a doRequest warning and on the timer-expiry message, and the `#5`/`Error #3`
  tags normalized to `#ex7`.
- **Known no-op left in place (flagged, not fixed):** the analytics "start"
  event (`createEvent("start", ...)` → `start(event)` →
  `SettingsManager.sendEvent(contextEvent)`) has never been published —
  `createEvent(name, SipServletContextEvent)` deliberately does not attach the
  event to the context (the "jwm - this is a bad idea" comment in
  SettingsManager), and `sendEvent(ssce)` only sends what it finds there.
  Wiring it up would newly publish JMS events; that's a product decision.

Verified: framework builds clean; Callflow/SDP smoke tests pass 84/84.

### Dependencies: kjetland mbknor-jackson-jsonschema removed (kills the Scala/Kotlin CVE surface)

The legacy kjetland schema-generator library is gone from the framework, the
shared library, both FSMAR jars, and the Configurator. The generator code path
has been victools since the Draft 2020-12 migration; the only remaining use was
the `@JsonSchemaTitle` annotation on config classes.

- **`@SchemaTitle`** (`org.vorpal.blade.framework.v2.config`) replaces kjetland's
  `@JsonSchemaTitle` on the 14 config classes that used it. Unlike before, the
  title is now actually emitted: `SettingsManager.generateSchemaNode` gained a
  victools title resolver, so the Configurator shows the declared title as the
  form heading instead of falling back to the schema filename. (kjetland's
  annotation had been a silent no-op under victools.)
- **Security payoff:** drops `scala-library` 2.13.1 (CVE-2022-36944, Critical),
  `kotlin-stdlib` 1.3.50 (CVE-2020-29582, CVE-2022-24329), `kotlinx-coroutines`
  1.1.1, and the kotlin-scripting jars from every shipped artifact. (The
  kotlin-stdlib 1.8.21 still in the shared library is okhttp's — current, no
  known CVEs.)

### Dependencies: CVE cleanup — all shipped artifacts scan clean

Beyond the kjetland removal, the remaining scanner findings were fixed; grype
reports **zero vulnerabilities** across every artifact in `dist/` (shared
library, both FSMAR jars, both EARs, every admin/service/test WAR).

- **Apache Oltu removed** (`org.apache.oltu.oauth2.client` 1.0.2, shared
  library). Retired Apache project, no BLADE code imported it, and its
  `org.json:20140107` transitive carried two High CVEs (GHSA-3vqj-43w4-2q58,
  GHSA-4jq9-2xhw-jpx7). The live OAuth stack is Nimbus `oauth2-oidc-sdk`.
- **Patch bumps:** jackson family 2.18.2 → 2.18.6 (jackson-core
  GHSA-72hv-8253-57qq); nimbus-jose-jwt → 9.37.4 (GHSA-xwmg-2g98-w7v9);
  commons-lang3 → 3.18.0 (GHSA-j288-q9x7-2f5v); jakarta.mail → 1.6.8
  (GHSA-9342-92gg-6v29). joschi's `jackson-datatype-threetenbp` is pinned
  separately at 2.18.2 (it lags Jackson patch releases).
- Scan note: grype/syft mangle Maven groupIds for jars nested inside
  WAR-inside-EAR (e.g. `kotlin-stdlib/kotlin-stdlib`), silently missing
  advisories — verify archive scans with corrected purl queries
  (`grype purl:<file>`).

### Configurator: converted to a skinny WAR (shared-library architecture restored)

The Configurator was the one WAR violating the packaging rule that `WEB-INF/lib`
carries only the framework jar with all 3rd-party JARs coming from the
`vorpal-blade` shared library. It bundled 47 3rd-party JARs (27 MB); it now
ships the framework jar only (< 1 MB), like every other admin and service WAR.

- `weblogic.xml` gained the standard `<library-ref>`; `prefer-web-inf-classes`
  is gone (the shared library's auto-generated `prefer-application-packages`
  owns classloader policy).
- The shared library gained the Configurator's extras: networknt
  `json-schema-validator` (+ `itu`, `jackson-dataformat-yaml` transitives) and
  `taglibs-standard-impl` (which the portal's JSTL `login.jsp` needed anyway).
  **Not** `xalan` — bundling it in the shared library breaks the WebLogic Admin
  Console (see the provided-scope note in `libs/shared/pom.xml`); the
  Configurator now uses the server's Xalan.
- The Configurator no longer deploys without the shared library present on
  AdminServer — same requirement as every other admin app.
- The per-WAR multi-release-stripping exec hack in the Configurator pom is gone
  (it existed only because 3rd-party jars were bundled).

### Configurator: welcome panel on initial load

Before a schema is selected, the editor area no longer sits as a blank void
under a dead toolbar. A centered welcome card now explains the three steps
(pick a Target scope, pick a Schema, then edit/save/publish) with links to the
Overview and Documentation. The toolbar stays hidden until the first schema
loads — every toolbar action required a loaded schema anyway. Styled from the
theme variables, so it renders correctly in all four themes.

### iRouter: shared `IRouterServlet` base class in the framework

`IRouterServlet` moved from the `services/irouter` WAR into the framework
(`org.vorpal.blade.framework.v3.irouter`), joining its siblings `IRouterInvite`,
`IRouterConfig`, `IRouterConfigSample`. It's now the shared, unannotated base
(like `B2buaServlet`) that any iRouter app or commercial extension subclasses
with **only the framework dependency** — no cross-WAR `-classes.jar` needed.

- Centralizes everything common: the `settings` lifecycle (`servletCreated` /
  `servletDestroyed`), the static `settings` snapshot, and the initial-INVITE
  dispatch in `chooseCallflow`. Also traps an SNMP `ERROR` on init failure — a
  universal default for every iRouter app now.
- Two overridable seams, both with plain-iRouter defaults: `newSampleConfig()`
  (the pipeline + routing config) and `newInvite(config)` (the initial-INVITE
  callflow). A subclass overrides only what differs.
- `IRouterApp` (the standalone iRouter WAR's annotated leaf) is unchanged — an
  empty annotated body over the base defaults. `SecureLogixServlet` collapses to
  ~12 lines of code: the `@Sip` annotations plus the two factory overrides
  (`SecureLogixConfigSample`, `SecureLogixInvite`). Its `servletCreated` /
  `servletDestroyed` / `chooseCallflow` / static field / SNMP wiring are all
  inherited. (`SecureLogixSettingsManager` was already deleted — the framework
  `RestConnector` self-materializes the body template.)
- Verified: framework + iRouter WAR + securelogix WAR all build; securelogix WAR
  carries exactly one `@SipServlet`-annotated leaf (the base is unannotated and
  rides in the framework jar), so no redundant-annotation deployment error.

### iRouter connectors: circuit breaker with SNMP edge traps (REST, LDAP, JDBC)

The three IO-bound iRouter connectors gain an optional circuit breaker so a
flaky/down backend doesn't drag every call through the request timeout and doesn't
storm the NMS with a trap per failure.

- Two additive, off-by-default fields on `RestConnector`, `LdapConnector`, and
  `JdbcConnector`: `circuitBreakerCooldownSeconds` (0/empty = disabled, unchanged
  behavior) and `circuitBreakerTrap` (emit SNMP on the edges). The in-memory
  connectors (sip/map/table) make no external call and don't get the fields.
- When a call fails (REST transport/timeout/non-2xx; LDAP bind/search error; JDBC
  datasource/connection/SQL error), the breaker **opens**: for the cooldown window
  the connector skips the call entirely and returns immediately, so its selectors
  don't run and the iRouter falls to its **default route**. The breaker is
  policy-neutral — whether "no data" means allow or block is the routing config's
  call, not the connector's. After the window, a live call retries; success
  **closes** the breaker. (For LDAP/JDBC, "success" = the server was reachable and
  the query ran, even if zero rows/entries came back — an empty result is not a
  failure.)
- **Edge-triggered, storm-proof.** Shared `CircuitBreaker` helper; state is a
  single node-local `AtomicLong` (0 = healthy; else epoch-ms suppress-until).
  `getAndSet` gives lock-free edge detection: under a flood of concurrent failures
  during an outage, exactly one thread sees the open transition and emits one
  "down" trap/log; one sees recovery and emits one "up." Verified with a
  2000-thread concurrency test (1 down, 1 up) plus a behavior test of the real
  class. No singleton, no background timer/thread — just a timestamp compared on
  the call path.
- SecureLogix (att-tao) enables it on its HUCS FRS screening (REST) connector:
  `cooldown=60s`, `trap=true`. During a HUCS outage, calls fall to the existing
  `X-Screening: fallback` allow route instead of each eating the 3s timeout, and
  AT&T's NOC gets one trap down + one trap up per outage.

### SNMP: user-defined traps from BLADE apps + a Tuning agent editor

BLADE can now emit SNMP traps for AT&T-style NMS monitoring, reusing the
trap-sending machinery OCCAS already ships (`com.bea.wcp.sip.management.snmp.SNMPAgent.sendSipAppTrap`)
— no new dependency, no custom MIB. The seven severity-keyed SIP-application
traps (`sipAppInfoTrap` … `sipAppEmergencyTrap`, OIDs `1.3.6.1.4.1.140.626.200.14`–`.20`)
are defined in the shipped `WLSS-MIB.asn1`; give that MIB to the NMS to decode them.

- **`framework.v2.snmp.Snmp`** — reflective, fail-closed wrapper (same pattern as
  `EngineOverload`). `Snmp.trap(Severity, message)` for explicit business events;
  a `Severity` enum mirrors OCCAS's `TRAP_SIP_APP_SEVERITY`. If `SNMPAgent` is
  absent (off-OCCAS, unit tests, a renamed engine) every call is a silent no-op —
  a trap must never break its caller (verified: no throw off-engine).
- **Log→trap bridge.** New per-service `LogParameters.snmpTrapLevel` (default
  **OFF**): any log statement at or above that level also fires a trap, mapping
  JUL → trap severity (SEVERE→error, WARNING→warning, else→info). Wired through
  the central `Logger.log(...)`, applied at logger creation (`LogManager`) and on
  live config reload (`Settings`). ANSI color codes are stripped from trap text.
  Purely additive — existing `configuration.json` without the field stays OFF, so
  behavior is unchanged until opted in.
- **Tuning → "SNMP" tab.** Edits the **domain** SNMP agent
  (`DomainMBean.getSNMPAgent()` — the same agent OCCAS's `isTrapEnabled()`
  consults) through the JMX edit tree like the other tabs: enable/automatic-traps,
  port, community, v1/v2c, plus an add/remove trap-destination table. This is
  where the destination host:port belongs (domain config), not in any service's
  `configuration.json`. Agent enable/port changes take effect on agent restart;
  destination edits apply on activate.

Division of labor: **where** traps go = Tuning (domain agent); **whether/at what
severity** a service emits = its logging config (`snmpTrapLevel`); **sending** =
`framework.v2.snmp.Snmp`. All on the current v2 surface (additive).

### Options: drain the node via OPTIONS when OCCAS is overloaded

The Options service now reflects OCCAS overload protection into its OPTIONS
health check. When the engine's overload protection is actively rejecting
traffic, OPTIONS answers **503 Service Unavailable** (with `Retry-After`) so a
SIP-aware load balancer stops routing **new** calls here and drains the node —
instead of the engine rejecting calls one at a time after they arrive. In-flight
dialogs are unaffected.

- `EngineOverload.isOverloaded()` reads OCCAS's own
  `com.bea.wcp.sip.engine.server.olp.OverloadProtection.getInstance().isBusy()`
  **reflectively** — no compile-time dependency on engine internals. If the class
  is absent or changes, it fails closed to "available," so OPTIONS keeps
  answering 200 exactly as before (verified: returns false off-engine).
- No custom OCCAS overload *handler* / `OlpEventHandler` / ServiceLoader wiring
  needed — OPTIONS simply reads the flag OCCAS already maintains.
- Gated by two new `OptionsSettings`: `unavailableWhenOverloaded` (sample
  default true) and `overloadRetryAfter` (sample default 5s). Existing
  `options.json` without the field defaults to off, so deployed behavior is
  unchanged until opted in. The 503 path can only fire when overload thresholds
  are actually configured.
- Built on the current v2 Options service; carries forward into the planned v3
  OPTIONS rewrite (roadmap item 6, drain control).

### Tuning: JDK 21 / OCCAS 8.3 latency tuning, Health Check, overload protection

Driven by fact-checked research for the OCCAS 8.3 / WebLogic 14.1.2 / JDK 21 stack.

- **JDK 21 low-pause GC, done correctly.** Added `-XX:+ZGenerational` and
  `-XX:+AlwaysPreTouch` as known JVM flags, and a **"Low-Latency (ZGC)"** preset
  that selects ZGC + Generational + AlwaysPreTouch + `-Xms=-Xmx`. Critical fix:
  on JDK 21 plain `-XX:+UseZGC` selects the *legacy non-generational* collector —
  Generational ZGC needs the extra flag. It is JDK-21/22-only (default in 23,
  removed in 24), called out in the tooltip. Framed as predictable/bounded pause
  times, never "guaranteed sub-ms" (a claim the research explicitly refuted).
- **Health Check panel** (read-only): flags `-Xms ≠ -Xmx`, removed CMS/ParNew GC
  flags (fatal on JDK 21), `-Xshare:off` (disables free CDS warmup), a non-default
  socket muxer (14.1.2 default is the NIO muxer), ZGC-without-Generational, and a
  best-effort sub-21 JDK detection from JavaHome. Pure diagnostics — fixes are
  applied in the sections below.
- **Server Tuning gains `SelfTuningThreadPoolSizeMax`** (default 400, max 65534);
  the Recommended preset now also raises socket readers to ≥ 10 (OCCAS engine rec).
- **Work Manager preset** now fills OCCAS engine capacities: `wlss.transport`
  capacity 5,000,000 and `wlss.timer` capacity 150,000 (from the OCCAS 8.0 tuning
  doc — stable across versions, re-confirm against the 8.3 guide).
- **SIP overload protection** — new fields for the classic `<overload>` element
  (`OverloadBean`: threshold-policy session-rate/queue-length, threshold value,
  release value), created on first save if absent. NOTE: OCCAS also has a separate,
  richer `<overload-protection>` framework that some domains scaffold instead;
  these fields drive the classic element only.

### Tuning: JDBC pool tab + per-section "Recommended" presets

New **JDBC Connection Pools** tab (`JdbcSettings`, `@Path("/jdbc")`) reads/writes
each data source's `InitialCapacity` / `MinCapacity` / `MaxCapacity` by
navigating `DomainConfiguration → JDBCSystemResources → JDBCResource →
JDBCConnectionPoolParams`, through the same edit-session path as the other tabs.

Each tunable section (JVM, Server Tuning, OCCAS Threads, JDBC) gained a
**"Recommended"** button that *pre-fills* values for the operator to review and
Save — nothing auto-activates. The presets encode the standard high-CPS tuning
heuristics:
- **JVM** — `-Xms = -Xmx` (heap fully committed at startup, no resize pauses).
- **Server Tuning** — Max Message Size × 4 (WLS default 10 MB → 40 MB).
- **OCCAS Threads** — `wlss.timer` Max Threads ≥ 200, and Min = Max for any work
  manager that defines both constraints (only `wlss.replica.blocking`,
  `wlss.tracing.domain`, `wlss.tracing.local` in the OCCAS model — the rest
  expose only one of the pair, so there's nothing to equalize).
- **JDBC** — `Initial = Min = Max`, floored at 300, so the pool is fully
  allocated at startup and never pays connection create/teardown latency.

### Tuning: "Fast SecureRandom (urandom)" JVM flag

The JVM tab gains a checkbox for `-Djava.security.egd=file:/dev/./urandom`,
registered as a known boolean flag in `JvmSettings`. Seeding `SecureRandom` from
non-blocking `/dev/urandom` removes entropy-starvation stalls on startup and TLS
handshakes — a real throughput win for SIP-over-TLS. The `/./` is the standard
JDK workaround (plain `file:/dev/urandom` is silently ignored and falls back to
blocking `/dev/random`). Like all `ServerStart` arguments, it's restart-required
and applied via Node Manager on the next boot.

### Tuning: SIP timers edited through the JMX edit tree, not a raw file write

The Tuning app's SIP Timers tab (`sipserver.xml`: T1/T2/T4, Timer B/F/L/M/N,
default behavior, stale-session handling, etc.) previously wrote the file
directly with a DOM transform. That only touched the node the admin app runs on,
skipped the OCCAS descriptor validator, and could be silently reverted when the
AdminServer re-serialized its in-memory `SipServerBean`.

- **`SipTimerSettings` now reads and writes through the edit tree.**
  `sipserver` is a WebLogic `<custom-resource>` whose descriptor bean is
  `SipServerBean`; the resource is reached via
  `DomainConfiguration → CustomResources[name=sipserver] → CustomResource`, and
  each attribute (`T1TimeoutInterval`, `EnableSipOutBound`, `EnableRport`,
  `EngineCallStateCacheEnabled`, …) is get/set inside a
  `startEdit`/`save`/`activate` session — the same pattern the Server Tuning and
  Work Manager tabs already use. `activate` persists `sipserver.xml` and runs the
  descriptor validator, and each engine node picks the change up on its next
  restart.
- **"Requires restart" is now surfaced.** The engine snapshots the SIP timers in
  a `static` initializer at class load (`Transaction.<clinit>`), so a timer
  change does not affect a running engine. The API returns `requiresRestart`, and
  the SIP Timers save now warns that timer changes take effect only after a
  rolling restart of the engine tier (instead of the old unconditional "saved").


### Analytics: schema cleanup + multi-tenant tenant column

Groundwork for hosting one BLADE analytics database behind one Oracle Analytics
Cloud instance serving many customers, each seeing only their own calls.

- **`MySQL-database-schema.sql` is now the single source of truth.** The Oracle
  and SQL Server dialect scripts were removed; they'll be regenerated from the
  MySQL script when needed.
- **Plural table names** — all analytics tables are plural (`sessions`,
  `events`, `attributes`, `session_keys`, `event_types`, `attribute_names`,
  `applications`). `session` (singular) is a reserved word in Oracle, so the
  whole set is pluralized for portability. `sessions.id` is DB-assigned
  (`AUTO_INCREMENT`); the call's cluster-unique vorpal-id rides in `vorpal_id`
  (see the session identity note below).
- **`application.tenant`** (`VARCHAR(64)`, nullable) — customer code stamped by
  the producer (`JmsPublisher.applicationStart`) from `SettingsManager.getTenant()`,
  which reads `-Dblade.tenant=<code>` or `BLADE_TENANT`. NULL on single-tenant
  installs, so existing deployments are unaffected. `sessions`/`events` rows reach
  their tenant by joining `applications(id)` — no hot-path change.
  `idx_application_tenant` keeps the RLS predicate cheap.
- *Note:* the analytics entities live in the frozen `framework/v2/analytics`
  package (there is no v3 analytics); the tenant addition is nullable and
  backward-compatible.

> Oracle/OAC-specific artifacts (reporting views, row-level-security support) are
> deferred — MySQL is the only supported database for now; the OAC dashboard layer
> will be rebuilt when Oracle support returns.

### Analytics: session identity is the vorpal-id; PK is DB-assigned

The Snowflake session id and the entire worker-id subsystem are removed. The
analytics `sessions` primary key is now DB-assigned (`AUTO_INCREMENT`); a call is
correlated by its cluster-unique **vorpal-id** (the `X-Vorpal-ID` Callflow
already mints at first-touch).

- **Callflow** no longer mints or propagates a Snowflake `sid` parameter on
  `X-Vorpal-ID`; the vorpal-id alone identifies the call across services in a
  chain. `ANALYTICS_SESSION`, the `SID_PARAM` parameter, `SnowflakeId`, and
  `WorkerIdAllocator` (with the `blade_worker_id` table) are deleted.
- **Producer** sends only the vorpal-id on the `Session`/`Event`/`SessionKey` —
  the JMS messages carry no environment id; nothing about the domain travels on
  the wire.
- **Consumer** (`AnalyticsJmsListener`) is the only writer to the database. It
  reads its **domain id** from the analytics service config
  (`AnalyticsConfig.domainId` in `analytics.json`, falling back to the WebLogic
  domain name) and stamps it as `cluster_name` on every row it writes. It assigns
  the PK on the session-started message, keeps an in-memory `vorpal-id → PK` map,
  and resolves later events/keys through it — falling back to the open session
  row in the DB, **scoped by its own domain id**, on a cold cache (restart /
  second consumer instance). The domain id is required because customers run many
  clusters sharing one WebLogic domain name (e.g. SIPREC × 10, VOICE × 10) all
  feeding **one shared analytics DB**, so a vorpal-id is only unique within an
  environment; `cluster_name` is what keeps rows distinct, enforced by the
  `(cluster_name, vorpal_id)` `open_key` unique index.
- **analytics-console:** the worker-allocation audit (`IdConfigAudit`) and the
  Snowflake `/decode` endpoint are removed.

### Files: new admin tool for schema-less domain files

A new admin app, **BLADE Files** (`admin/files`, context-root `/blade/files`),
edits domain files that have **no** JSON Schema — so the Configurator can't
manage them — through the browser instead of over SSH: `sipserver.xml`,
datasource descriptors, logging configs, plain `.properties`.

- **Deny-by-default registry.** The editable set is an admin-defined whitelist
  in the app's own settings (`config/custom/vorpal/files.json`) — `label` +
  `path` (relative to `DOMAIN_HOME`) + `type` (`XML` / `PROPERTIES` / `TEXT`).
  There is no filesystem browse; a path not in the registry is rejected before
  any disk access, and a registered path that resolves outside `DOMAIN_HOME` is
  rejected too (path-traversal guard).
- **Well-formedness check on save.** XML is parsed (external entities disabled);
  properties are parsed with `java.util.Properties`; text is saved as-is. A
  malformed file is rejected and the on-disk copy is left untouched.
- **Backups & restore.** Every overwrite first copies the current file into a
  sibling `.versions/` directory (keep-last-20); the editor lists versions and
  can restore one. This uses the new framework helper
  `org.vorpal.blade.framework.io.VersionedFileStore` — the same backup
  discipline the Configurator's `FileManagerServlet` grew, now factored out for
  reuse. (Repointing the Configurator at the shared helper is a separate
  follow-up.)

### Analytics Console: renamed, audit fix, one-click JMS provisioning

The admin Analytics app was renamed `analytics` → **`analytics-console`** (Maven
artifact + web.xml `display-name`, so its JMX MBean is `Name=analytics-console`)
to stop colliding with the analytics **cluster service** (also `Name=analytics`).
Context-root stays `/blade/analytics`; the portal card resolves via a documented
`SETTINGS_NAME_BY_SLUG` alias.

- **Audit fix.** The resource audit now finds **Uniform** Distributed Queues
  (`UniformDistributedQueues`) — and plain `Queues` — not just the legacy
  weighted `DistributedQueues`. It was reporting a present UDQ as missing.
- **"Create missing JMS resources" button.** When a JMS resource is missing, the
  audit page offers a fix that provisions the whole JMS stack — file store, JMS
  server, system module, subdeployment, connection factory, uniform distributed
  queue — in-process via the WebLogic Edit MBean server (`WlsResourceProvisioner`,
  the Java/JMX equivalent of `configure-jms.py`). Idempotent, auto-targets the
  single engine cluster, rolls the edit back on failure. The JDBC data source is
  out of scope (it needs DB credentials).

### FSMAR 3: data-driven call-paths

FSMAR transitions can now route on **any** data in the message and construct
routes from extracted values — keeping FSMAR the stateful sequencer (state =
previous application). Built by reusing the v3 `selectors` / `Context` /
`Expression` stack rather than FSMAR's old header-only matcher.

- **Per-state extraction.** Each state carries `selectors` (the existing
  `Attribute`/`Json`/`Xml`/`Sdp`/`Regex` selectors) that run on entry and write
  named values into a routing context. Selectors live on the state (not a
  top-level pipeline) because the App Router sees the *evolving* request across
  hops — per-state capture-and-carry avoids silently overwriting a value
  mid-path.
- **Condition + constructed routes.** A transition fires on a `when` expression
  over the extracted values (the same `Expression` grammar iRouter uses); its
  `routes[]` are `${}`-templated and resolved against the context, e.g.
  `sip:${To.user}@proxy`. `next` stays a literal app (the FSM edge) and
  `subscriber` stays a header name (the JSR-289 contract). Routing Bob
  differently from Alice is one transition, not one per subscriber.
- **State carried in `stateInfo`.** The extraction context rides the JSR-289
  `stateInfo` alongside the pinned config snapshot (`RoutingState`), so values
  captured in an early state remain available to later states and survive
  cluster failover — without any SipSession (which the App Router doesn't have).
- **New `MemoryContext`** (framework): a map-backed `Context` for use where no
  SIP session exists. `Context.resolve` now routes through the overridable
  `get()` (behavior-identical for session-backed contexts).
- **Regex named groups** are now stored namespaced as `${selectorId.group}`
  (e.g. `${To.user}`) in addition to the bare `${group}`, so two selectors
  capturing the same group name no longer collide.
- Retired FSMAR's header-only `RequestSelector` / `SelectorGroup` (superseded by
  the extraction selectors + `Expression`).

### FSMAR 3: routing fixes + bypass of undeployed applications

- **Bypass undeployed targets.** When a matched transition's `next` application
  isn't currently deployed, the router no longer hands the container a null
  application name. It treats that application as the new "previous" state and
  keeps evaluating the state machine, as if it had already run. When the chain
  dead-ends it returns no router info, so OCCAS routes the request downstream.
  A visited-state guard prevents a self-referential config from looping.
- **`defaultApplication`** still applies only to an initial request that matched
  nothing at all; a request that walked (and bypassed) the machine before
  dead-ending goes downstream rather than to the default.
- **Bug fixes.** `applicationUndeployed` now removes entries from the deployed
  map (previously a no-op, leaving stale version names); the deployed map is a
  `ConcurrentHashMap` (was an unsynchronized `HashMap` read/written across
  engine threads); an init-time config failure no longer NPEs on a null logger
  while reporting itself; and runtime evaluation uses read-only map lookups so
  it no longer inserts empty states/triggers into the live config.

### Configurator: guided editing for keyed config

- **`@FormKeyEnum`** (new, generic) on a `Map` getter constrains its keys to a
  fixed set, emitted as JSON Schema `propertyNames.enum`; the form renders the
  map-entry key as a dropdown instead of free text, preserving an existing key
  even if it's no longer in the set. FSMAR 3 uses it to limit a state's triggers
  to known SIP methods.
- **Selector reuse via identity, not a parallel ref list.** `RequestSelector`
  now carries `@JsonIdentityInfo` (like `Connector`/`Selector`), so a selector
  group's `selectors` list does double duty — define one inline, or use the
  Configurator's existing "+ Add Reference" picker to point at one defined
  elsewhere (e.g. the top-level `selectors` library) by `id`. The redundant
  `SelectorGroup.selectorRefs` field is gone.
- FSMAR 3's config classes also gained `@FormSection` / `@FormLayoutGroup` /
  `@FormLayout` annotations for grouped, labeled, regex-testable fields, and the
  inherited `session` block is hidden (FSMAR is an Application Router, not a
  converged app).
- Internal: `SettingsManager.saveSchema` now delegates schema construction to a
  pure `generateSchemaNode(Class, ObjectMapper)`, separating it from file IO.

### Admin tier: one deployable EAR (`blade-admin.ear`)

The admin webapps now also ship bundled as a single `blade-admin.ear`, so the
whole admin tier deploys to AdminServer in one step.

- **Additive packaging, proven WARs.** Each WAR inside the EAR is self-contained
  exactly as it deploys standalone — it carries the framework jar and references
  the `vorpal-blade` shared library via its own `weblogic.xml`. The EAR bundles
  no libraries of its own; nothing about how an individual WAR loads classes
  changed.
- **New `admin/ear`** module → `blade-admin.ear`, with explicit per-module
  context-roots in the generated `application.xml` that match each WAR's
  `weblogic.xml` exactly (incl. `redirect` at `/`).
- **Build/deploy:** `./build.sh` builds the EAR by default (auto-skipped when
  javadoc is skipped, since the EAR bundles `javadoc.war`) and still drops the
  individual admin WARs in `dist/<ver>/admin/` for single-app test redeploys.
  `./deploy.sh <env> admin AdminServer` deploys the EAR (when an EAR is present
  in the tier dir it is the deploy unit; loose WARs there are for manual
  individual redeploys).

(A future optimization — hosting the framework once in the EAR's `APP-INF/lib`
instead of per-WAR — needs the shared library repackaged as an EAR-referenceable
library, since a WAR-packaged shared library is only visible at the WAR
classloader level. Deferred.)

### Configurator: auto-publish absorbed; `watcher` WAR kept as the standalone alternative

The Configurator now owns the file-system auto-publish behavior in-process, so
sites running the Configurator no longer need the separate `watcher` WAR.

- **Auto-publish off by default.** `ConfiguratorSettings.autoPublish` defaults
  to `false` (via the shipped sample): changes go live only through the UI's
  explicit Save + Publish flow. Turning it on makes on-disk edits to
  `./config/custom/vorpal/*.json` republish to live services via JMX — the
  same behavior ops scripts relied on under `watcher`.
- **Live on/off toggle in the UI.** A sliding Auto-publish switch sits at the
  right of the form toolbar. Flipping it writes `autoPublish` to
  `configurator.json` and reloads the Configurator's own MBean; the watcher
  thread starts/stops immediately — no redeploy. The lifecycle is owned by
  `ConfiguratorSettingsManager.initialize()`, the framework's per-reload hook.
- **`watcher` retained — standalone only, not in `blade-admin.ear`.** (It was
  briefly deleted during the 2.9.9 cycle, then restored: some sites can't
  deploy the Configurator UI — it doesn't pass their security scans — and
  `watcher`, with no UI / no servlets / no login, is what they deploy
  instead.) It builds as `watcher.war` in `dist/<ver>/admin/` and is
  deliberately excluded from the admin EAR, since running it alongside the
  Configurator's Auto-publish double-publishes every file edit. Its "this
  module will be removed" deprecation banners (startup log, README, webapp
  pages) are gone; it's a supported peer of the Configurator's auto-publish
  now. Deploy one or the other.

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
