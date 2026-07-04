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
- **Config app** — `proto/security` (context-root `blade/security`). Holds the
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

## 6. Transport security — TLS everywhere (HTTPS / SIPS / t3s)

Driven by the UHG/Optum mandate that all apps be TLS-encrypted by 2027 —
HTTPS and SIPS only. BLADE-side, this is tooling plus an operator switch;
OCCAS terminates TLS for both HTTP and SIP. The WARs themselves do **not**
force TLS: developers keep plain HTTP on :7001/:8001, and a customer goes
TLS-only by disabling the plaintext ports (`tls.only=true`, below) once
HTTPS is proven — enforcement by port, not by descriptor.

### What the WARs carry (framework, always on)

- **No URL session rewriting** — every weblogic.xml session-descriptor sets
  `<url-rewriting-enabled>false</url-rewriting-enabled>`, so session ids
  never leak into URLs. Deliberately absent: `CONFIDENTIAL`
  transport-guarantees (would break or redirect developer HTTP — and with
  no SSL port configured, WebLogic 500s instead of redirecting) and
  `<cookie-secure>true</cookie-secure>` (the `BLADEADMINSESSION` cookie
  would never ride plain HTTP, so FORM login on :7001 loops forever). Once
  a deployment is TLS-only the cookie only ever rides TLS anyway; a
  customer wanting the belt-and-suspenders secure flag can add it with a
  deployment plan without a BLADE rebuild.

### Certificates (per environment)

`./certs.sh <env> generate` builds a self-signed test PKI: a local CA
(`ca.p12`/`ca.pem`), a server identity keystore whose SAN covers every host
in the env conf, and a trust keystore — all PKCS12, written OUTSIDE the repo
(default `~/.blade/certs/<env>`). The server cert carries EKU
serverAuth **and** clientAuth, so the same identity keystore serves as the
client certificate where mutual TLS is demanded.

Customers with their own certificate process (e.g. Optum) use
`./certs.sh <env> import` — a ready-made PKCS12, or PEM cert+key+chain,
packaged into the identical keystore layout. Same downstream steps either way.

### Wiring the domain

`./install-occas.sh <env> secure` (offline WLST, domain stopped) enables the
SSL listen port with the keystores on the AdminServer (:7002), the engine
server-template, and the static engine (:8002). With `tls.only=true` in the
env conf it also **disables the plaintext HTTP listen ports and deletes the
plaintext `sip` network channels** — leaving HTTPS, SIPS (:5061), and t3s
only. That flag is the 2027 posture; run without it first to prove the certs
while both ports are up. NodeManager is already `ssl` per machine conf.

### Management traffic (t3s)

The mandate includes t3. `deploy.sh` and `misc/deploy-wls.sh` honor a
`t3s://` admin URL and pass CustomTrust JVM flags when `wls.truststore`
points at the CA trust keystore (password `wls.truststore.password` in the
secret, or `$BLADE_STORE_PASSWORD`); without a truststore they fall back to
the JVM default (import `ca.pem` with `keytool -importcert -cacerts`).
`blade.sh` auto-detects an SSL-enabled AdminServer in the live config.xml
and switches its derived admin URL to t3s. When using `blade.sh` profile
dirs, set `certs.dir` in the profile's occas.conf so the trust keystore is
found.

### Outbound REST — private trust and mutual TLS

`https://` URLs in `RestConnector` (and the OAuth token endpoints, and the
JWKS fetch) verify against the **JVM default truststore** — the normal
deployment loads the customer CA into cacerts once and everything outbound
trusts it. For endpoint-specific needs, `RestConnector` has an optional
`tls` section (`framework.v3.security.TlsClientConfig`): a private
`trustStore` for a CA you don't want JVM-wide, and/or a `keyStore` holding a
client certificate for **mutual TLS**. The connector hands the same
SSLContext to its auth scheme, so the OAuth token fetch presents the same
identity as the API call. A misconfigured store throws — the call fails
closed rather than silently downgrading to default trust. Offline coverage:
`TlsClientConfigSmokeTest`.

### SIP

SIPS channels (:5061) exist on every engine since domain creation;
`tls.only` removes the plaintext channel. The framework is
transport-agnostic (OCCAS owns the transport); `UriTidy` already treats
`sips:`/`transport=tls` as secure. Route/tenant configs with hard
`transport=udp|tcp` URI params are operator data — sweep them per
deployment when going SIPS-only, and re-point the SBC at :5061.

---

---

## Verification

Locally verifiable (CI / build box):

- **JWT validation** — `JwtValidatorSmokeTest` (offline, locally-signed token):
  signature, issuer, audience, expiry, claim→role mapping, string-vs-list roles,
  username-claim override, and rejection of wrong-issuer/wrong-audience/expired/
  foreign-signature/garbage tokens.
- **Descriptors / build** — `proto/security` packages as a skinny WAR (only
  `vorpal-blade-library-framework.jar` in `WEB-INF/lib`); the three hardened
  WARs and the admin EAR build.
- **TLS client config** — `TlsClientConfigSmokeTest` (offline): empty config →
  JVM default, truststore → working SSLContext, missing/garbage store → throws
  (fail closed).
- **Anti-regression grep** — every admin WAR except `watcher`/`redirect`/
  `javadoc` contains an `<auth-constraint>`; **every** active WAR (all trees,
  `retired/` excluded) carries the `CONFIDENTIAL` transport-guarantee and a
  `cookie-secure` session-descriptor (`libs/shared` is a library container, not
  an app, and inherits nothing here):

  ```sh
  for d in admin/*/src/main/webapp/WEB-INF/web.xml; do
    case "$d" in */watcher/*|*/redirect/*|*/javadoc/*) continue;; esac
    grep -q '<auth-constraint>' "$d" || echo "MISSING auth-constraint: $d"
  done
  for d in admin services proto test; do
    for f in $d/*/src/main/webapp/WEB-INF/web.xml; do
      grep -q '<transport-guarantee>CONFIDENTIAL' "$f" || echo "MISSING CONFIDENTIAL: $f"
    done
    for f in $d/*/src/main/webapp/WEB-INF/weblogic.xml; do
      grep -q '<wls:cookie-secure>true' "$f" || echo "MISSING cookie-secure: $f"
    done
  done
  ```

Deploy-only (Jeff, in an OCCAS domain — "after you deploy, look for…"):

- The four roles resolve to real realm groups; FORM/BASIC still authenticate on
  the three newly-constrained WARs; `BLADEADMINSESSION` SSO still spans the tier.
- JWT SSO against the real IdP (issuer/JWKS/aud and the role-bearing claim).
- SIP: mTLS/SIPS handshake on the SBC↔engine channel; and, if digest is enabled,
  that a `407` is issued and validated against the JDBC digest store.
- TLS: after `certs.sh` + `install-occas.sh <env> secure`, the console answers
  on `https://…:7002`, engines on `:8002`, `openssl s_client -connect host:5061`
  shows the expected chain; with `tls.only=true`, ports 7001/8001/5060 refuse
  connections and `./deploy.sh <env> status` works over `t3s`. The `secure`
  step's WLST ran only in dry-run here — first execution against a real
  stopped domain is yours.
- Mutual TLS outbound: point a `RestConnector` `tls.keyStore` at
  `identity.p12` against an endpoint requiring client certs (the generated
  cert carries EKU clientAuth).

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
