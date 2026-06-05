# BLADE Release Notes

## 2.9.9 (unreleased)

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
