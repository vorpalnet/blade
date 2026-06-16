# BLADE Security

How callers authenticate to BLADE, and how to configure it. This is the map of
a thing that is otherwise scattered across descriptors, WebLogic realm config,
and OCCAS domain config — written down so the next round of work starts from a
shared picture.

> Status: v3.0 work in progress. The admin-tier hardening and the inbound-JWT
> path described here are implemented. The configurable SIP trust model is
> documented design plus the trusted-core behavior that already exists; the
> digest opt-in is a deployment recipe, not yet shipped as a descriptor.
> Items needing Jeff's OCCAS-domain knowledge are flagged **TODO**.

## The three authentication surfaces

BLADE authenticates in three independent places. WebLogic security realms own
the first; they *can* own the second; they are deliberately not involved in the
third.

| Surface | What it protects | Mechanism | Realm-backed? |
|---|---|---|---|
| **Inbound HTTP** | Admin consoles + their REST APIs | Container FORM / BASIC + (new) bearer JWT | Yes |
| **Inbound SIP** | Calls arriving from the SBC / network | Configurable: trusted-core (default) or digest (opt-in) | Only in digest mode |
| **Outbound REST** | BLADE calling external services | The v3 `Authentication` hierarchy | No — by design |

---

## 1. Inbound HTTP — admin consoles and REST APIs

Every admin app authenticates against the WebLogic **`default`** realm. Identity
(users, passwords, groups) lives in the realm / corporate directory, **not** in
BLADE. BLADE only names four roles and maps realm groups onto them.

### The four roles

`Admin`, `Operator`, `Deployer`, `Monitor` — declared as `<security-role>`s in
each app's `web.xml` and bound to realm groups of the same name via
`<wls:security-role-assignment><wls:externally-defined/>` in `weblogic.xml`.
The canonical Java enum is
`org.vorpal.blade.framework.v3.security.AdminRole` (framework jar); both the
FORM/BASIC path and the JWT path authorize against it, so the two front doors
stay consistent.

### Login methods

- **FORM** (browser) — `auth-method` `CLIENT-CERT,FORM`, form page under
  `/login/login.jsp`. Single sign-on across the admin tier via the
  `BLADEADMINSESSION` cookie (`cookie-path` `/`, shared across `blade-admin.ear`).
- **BASIC** (CLI) — exactly one carve-out: the Configurator's `/api/v1/*`, so
  `blade-validate.sh` can pass an `Authorization: Basic` header instead of
  driving `j_security_check`. Implemented by
  `admin/configurator/.../config/BasicAuthFilter.java` (`request.login()` then
  the four-role check). Do **not** declare BASIC elsewhere.
- **Bearer JWT** (SSO) — new in v3.0, additive; see §2.

### Canonical security snippet (every admin WAR except the allowlist)

`web.xml`:

```xml
<!-- login form + assets: no auth -->
<security-constraint>
    <web-resource-collection>
        <web-resource-name>login</web-resource-name>
        <url-pattern>/login/*</url-pattern>
    </web-resource-collection>
</security-constraint>
<!-- everything else: an admin role. "/" is WebLogic's catch-all. -->
<security-constraint>
    <web-resource-collection>
        <web-resource-name>APP</web-resource-name>
        <url-pattern>/</url-pattern>
    </web-resource-collection>
    <auth-constraint>
        <role-name>Admin</role-name>
        <role-name>Operator</role-name>
        <role-name>Deployer</role-name>
        <role-name>Monitor</role-name>
    </auth-constraint>
</security-constraint>
<login-config>
    <auth-method>CLIENT-CERT,FORM</auth-method>
    <realm-name>default</realm-name>
    <form-login-config>
        <form-login-page>/login/login.jsp</form-login-page>
        <form-error-page>/login/login.jsp</form-error-page>
    </form-login-config>
</login-config>
<!-- + the four <security-role> declarations -->
```

**The login page itself is single-sourced.** The only real `login.jsp` in the
repo is the portal master, `admin/portal/src/main/webapp/login.jsp`; every
other admin WAR's pom injects a byte-identical copy at build time via a
`maven-war-plugin` `webResources` entry (at `/login/login.jsp` or `/login.jsp`,
matching that app's `form-login-page`). To change the login page, edit the
portal master and rebuild — **never create a per-app login page**; per-app
copies are exactly the drift this arrangement exists to prevent. The page's
assets (brand CSS, backdrop, logo) are served unauthenticated from
`/blade/portal/` for all apps.

`weblogic.xml` must also carry the four
`<wls:security-role-assignment><wls:externally-defined/>` blocks. **Both halves
are required** — without the role assignments the role names match no realm
group and the constraint rejects everyone.

### Intentionally open (do NOT add a constraint)

| WAR | Why it's open |
|---|---|
| `admin/watcher` | Headless config auto-publish shim, no UI, deployed standalone |
| `admin/redirect` | Default-app that 302s `/` → `/blade/portal` |
| `admin/javadoc` | Public API documentation |

### Anti-regression check

There is no `web.xml` include mechanism in this skinny-WAR setup, so consistency
is guarded by review against this snippet plus a build/CI grep: every admin WAR
except the allowlist above must contain an `<auth-constraint>`. (See the
**Verification** section.)

> **2026-06 hardening:** `admin/logs`, `admin/analytics-console`, and
> `admin/files` previously shipped **no** `<security-constraint>` and no role
> assignments — open inside the admin tier. `analytics-console` exposed
> `POST /api/provision/jms`, which creates WebLogic JMS resources. All three now
> carry the canonical snippet (FORM, four roles, role assignments) and a copy of
> the `login/` form. **TODO (Jeff):** confirm no EAR-level/proxy protection was
> masking this in production — i.e. whether they were ever actually reachable
> unauthenticated.

---

## 2. Inbound JWT single sign-on (admin tier)

Lets the admin consoles sit behind an enterprise IdP. OCCAS is **not** the
identity source — the corporate IdP holds passwords/groups; BLADE validates the
IdP's signed token and maps its group/role claim onto the four `AdminRole`s.

### Where it lives

- **Reusable code** — `org.vorpal.blade.framework.v3.security` (framework jar):
  - `JwtAuthConfig` — the editable settings (issuer, JWKS URI, audience,
    algorithm, username claim, roles claim, role mappings, clock skew).
  - `JwtValidator` — Nimbus-backed validation (signature via JWKS, issuer,
    audience, expiry) → `JwtIdentity`. Container-free and unit-tested offline
    (`libs/framework/src/test/.../security/JwtValidatorSmokeTest.java`).
  - `JwtAuthFilter` — JAX-RS `ContainerRequestFilter`, the inbound counterpart
    to `BasicAuthFilter`. Installs a `JwtSecurityContext` on success.
  - `AdminRole`, `JwtIdentity`, `JwtSecurityContext`, `JwtAuthException`.
- **Config app** — `admin/security` (context-root `blade/security`). Holds the
  `jwt` config section (`SecuritySettings`), edited in the Configurator like any
  other app, and publishes a live config supplier the filter reads.

### How it behaves (additive, fail-safe)

1. JWT disabled (default) **or** no `Authorization: Bearer` header → the filter
   does nothing and the container FORM/BASIC login handles the request. Shipping
   it dormant changes no existing behavior.
2. Bearer token present + JWT enabled → validate. Valid + holds an admin role →
   request proceeds as that principal. Valid but no admin role → `403`. Invalid
   → `401 WWW-Authenticate: Bearer`. Enabled-but-misconfigured (e.g. bad JWKS
   URI) → bearer requests fail closed (`401`), they don't silently fall through.

Because the filter ships in the framework jar (bundled in every admin WAR's
`WEB-INF/lib`), JAX-RS scanning registers it everywhere, but it **only activates
where a `JwtAuthConfig` supplier is published** — today, the `security` app.

### Claim → role mapping

The roles claim (default `groups`) may be a JSON array or a space/comma string.
Each value is mapped via `jwt.roleMappings` (`"idp-group" -> "Admin"`); a value
that is already a BLADE role name needs no entry. Values resolving to a
non-admin name grant nothing.

### Enabling it (per deployment)

In the `security` app config: set `jwt.issuer`, `jwt.jwksUri`, `jwt.audience`,
`jwt.rolesClaim`, `jwt.roleMappings`, then `jwt.enabled = true`.

> **TODO (Jeff) — IdP specifics:** issuer URL, JWKS URI, audience, and **which
> claim carries roles** for the corporate IdP / your planned cloud OCCAS+BLADE
> test instance, and the group→role mapping. The smoke test stands in for the
> IdP today.

> **Refinement — cross-WAR config:** the config supplier is published only by
> the app that owns the settings, so JWT currently guards the `security` app
> itself. To guard *every* admin WAR from one config, the next step is to
> distribute `JwtAuthConfig` cluster-wide — e.g. each admin WAR reads the
> `blade-security` config via the same `SettingsMXBean` JMX walk the Portal uses
> for launcher metadata (`admin/portal/.../PortalCardsResource.java`), or the
> security app pushes config into a shared store. Browser SSO (the OIDC redirect
> dance) is intentionally **not** built into BLADE — terminate it at a reverse
> proxy that injects the bearer token; BLADE validates it.

---

## 3. Inbound SIP — configurable trust model

Who authenticates the SIP user is a **deployment** choice, selected by which
descriptor is deployed, not a runtime flag. No framework code branches on it.

### Trusted-core (default) — lightweight

The SBC authenticates at the edge (registration/digest) and asserts identity
inward via `P-Asserted-Identity` over a secured transport. BLADE **trusts** the
boundary and **authorizes**, it does not re-authenticate. This is the existing
behavior; the only "work" is deployment config:

- **Transport** — TLS/SIPS + mTLS between SBC and engine tier, configured in the
  **WebLogic/OCCAS domain** (a custom SIP network channel with two-way SSL and
  identity/trust keystores in `config.xml`). BLADE ships no code for this.
- **Trust boundary / authorization** — the `acl` service already enforces it:
  `AclSipServlet` matches the request's source address against CIDR `AclRule`s
  (`services/acl/.../AclSipServlet.java`, `AclRule.java`). `P-Asserted-Identity`
  is trusted because the transport and source are trusted.

Explicitly **not** built in trusted-core mode: no `sip.xml`, no
`<proxy-authentication>`, no digest realm, no per-request realm lookup, no new
servlet or state machine.

### Edge / digest (opt-in) — heavier

For deployments where BLADE itself challenges SIP (acting as registrar/edge):

- Introduce a `sip.xml` carrying `<proxy-authentication>` for the SIP app that
  should challenge (BLADE has none today — services are pure-annotation, so this
  is a *new* descriptor, e.g. `services/acl/.../WEB-INF/sip.xml` or a dedicated
  edge-auth SIP app). Selection = deploy that variant instead of the
  annotation-only one.
- **Credential storage constraint (inherent, not a bug):** SIP digest needs
  `H(A1) = MD5(user:realm:password)`, so the identity store must hold cleartext
  or a precomputed per-realm hash. WebLogic's `DefaultAuthenticator` stores
  one-way hashes and **cannot** drive digest. Enabling digest means provisioning
  a digest-capable provider in the realm.
- The OCCAS digest provider is **JDBC-backed and manually installed** into the
  domain (it won't appear in decompiled OCCAS code). **TODO (Jeff):** record the
  exact provider class / install steps once confirmed.

---

## 4. Outbound REST — BLADE calling external services

Deliberately **not** realm-based and already complete: the polymorphic
`org.vorpal.blade.framework.v3.configuration.auth.Authentication` hierarchy —
`basic`, `bearer`, `apikey`, four OAuth2 grants, `hmac`, `aws-sigv4` — applied
by `RestConnector` on a worker thread, every field `${var}`-resolvable. This is
"how BLADE authenticates *itself* to others" and shares nothing with the inbound
realm machinery. See that package's Javadoc.

---

## 5. Credential storage

Config-file secrets are encrypted with the WebLogic domain key via
`framework/v2/config/CredentialEncryption.java` — convention `{CLEARTEXT}secret`
→ `{AES}base64…` on save, transparently decrypted on load by `SettingsManager`.
Degrades gracefully (no-op) outside a WebLogic domain (tests/CLI). Never commit
cleartext secrets; never transcribe a secret into a log or doc.

---

## Verification

Locally verifiable (CI / build box):

- **JWT validation** — `JwtValidatorSmokeTest` (offline, locally-signed token):
  signature, issuer, audience, expiry, claim→role mapping, string-vs-list roles,
  username-claim override, and rejection of wrong-issuer/wrong-audience/expired/
  foreign-signature/garbage tokens.
- **Descriptors / build** — `admin/security` packages as a skinny WAR (only
  `vorpal-blade-library-framework.jar` in `WEB-INF/lib`); the three hardened
  WARs and the admin EAR build.
- **Anti-regression grep** — every admin WAR except `watcher`/`redirect`/
  `javadoc` contains an `<auth-constraint>`:

  ```sh
  for d in admin/*/src/main/webapp/WEB-INF/web.xml; do
    case "$d" in */watcher/*|*/redirect/*|*/javadoc/*) continue;; esac
    grep -q '<auth-constraint>' "$d" || echo "MISSING auth-constraint: $d"
  done
  ```

Deploy-only (Jeff, in an OCCAS domain — "after you deploy, look for…"):

- The four roles resolve to real realm groups; FORM/BASIC still authenticate on
  the three newly-constrained WARs; `BLADEADMINSESSION` SSO still spans the tier.
- JWT SSO against the real IdP (issuer/JWKS/aud and the role-bearing claim).
- SIP: mTLS/SIPS handshake on the SBC↔engine channel; and, if digest is enabled,
  that a `407` is issued and validated against the JDBC digest store.

## Open items (next refinement)

1. **TODO** Confirm logs/analytics/files had no EAR-level protection before the
   hardening (were they ever reachable unauthenticated in production?).
2. **TODO** Corporate IdP details for JWT (issuer, JWKS, audience, roles claim,
   group→role map); wire the planned cloud OCCAS+BLADE test instance as the IdP.
3. **Refinement** Distribute one `blade-security` JWT config to every admin WAR
   (JMX or shared store) so JWT can guard the whole tier, not just `security`.
4. **TODO** Exact OCCAS 8.1 JDBC digest provider class + install steps, for the
   edge/digest SIP mode.
5. **Design** Ship the digest `sip.xml` variant (and decide whether it lives in
   `acl` or a dedicated edge-auth SIP app).
