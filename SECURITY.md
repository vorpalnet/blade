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

BLADE authenticates in four independent places. WebLogic security realms own the
first, and stand behind the second; they *can* own the third; they are
deliberately not involved in the fourth.

| Surface | What it protects | Mechanism | Realm-backed? |
|---|---|---|---|
| **Inbound HTTP** | Admin consoles + their REST APIs | Container FORM / BASIC + (new) bearer JWT | Yes |
| **Inbound WebSocket** | Browsers signaling to the WebRTC gateway | First-party bearer JWT, minted by the app that ran the FORM login | Indirectly — the token restates a realm login |
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

## 2a. First-party tokens — carrying identity across a tier boundary

§2 consumes tokens an outside IdP issued. This section is the other direction:
BLADE minting its own, for a hop no session cookie can make.

### The problem it solves

The WebRTC phone (`admin/phone`) is an ordinary admin app behind the FORM login,
so by the time the page loads, the container has already established who the user
is. None of that reaches the `webrtc` gateway. The gateway runs on the **engine**
tier — a different host and port — so `BLADEADMINSESSION` is not sent to it, and
the browser's WebSocket API cannot attach an `Authorization` header to a handshake
even if there were a session to attach.

That is a token-carrying problem, not an identity problem. Routing it through an
external IdP would add an outage mode without adding a fact, so the app that
already authenticated the caller mints the token itself.

### How it works

| Step | Where |
|---|---|
| User signs in (FORM, WebLogic realm) | `admin/phone`, container |
| `GET /blade/phone/api/v1/session` → identity + what the deployment allows | `TokenResource` |
| `POST /blade/phone/api/v1/token` → short-lived RS256 JWT | `TokenResource` |
| `GET /blade/phone/api/v1/jwks.json` → public keys, **unauthenticated** | `TokenResource` |
| Browser presents the token in `session.connect` | `blade-webrtc.js` |
| Gateway validates it offline against the JWKS | `BrowserAuthenticator` |

Framework classes: `JwtIssuer` + `JwtIssuerConfig` (mint and publish),
`JwtValidator` (unchanged — it cannot tell a BLADE issuer from Okta),
`JwtIdentity.claim(String)` (read app-specific claims).

### The claim is the authorization, not the signature

The token carries an `aor` claim naming the one address its holder may bind, and
the gateway honors the claim and refuses a browser asking for anything else. That
part is unconditional: a browser can never register an address the server did not
put in its token.

Which address the *phone* will put there is a policy, in `AddressPolicy`:

- `allowChosenAddress = false` — `<username>@<aorDomain>` and nothing else. One
  person, one address.
- `allowChosenAddress = true` (**default**) — the caller may name any well-formed
  `user@host`.

The default is the permissive one, and that is a deliberate trade rather than an
oversight. A browser-to-browser call needs two addresses; a deployment typically
has one `weblogic` operator. Binding strictly would mean the app could not be
tested or demonstrated without minting realm users, and demonstration is most of
what it is for.

What the permissive mode gives up is the restriction, and nothing else:

- the caller is still authenticated and must still hold a BLADE role, so nothing
  is available to an anonymous client on the network;
- the gateway rule is untouched — the token still names the single address it
  will honor;
- the token's `sub` is always the real WebLogic user, never the chosen address,
  so `webrtc` logs `registered on this node as '<user>'` and a registration stays
  attributable even when the address is someone else's name.

The address is validated as `user@host` in either mode. That is the exact form
`InboundToBrowser.addressOf` derives from an inbound request URI, so anything
else would register successfully and then never ring — and the check also keeps
CRLF out of a string that ends up in SIP.

### The signing key is deliberately ephemeral

`JwtIssuer` generates an RSA keypair at startup, holds it in memory, never writes
it down and never rotates it. Tokens live 60 seconds, so none outlives the process
that signed it; a restart mints a new `kid` and the consumer's JWKS cache refetches
on the miss. Key storage would buy nothing and would put a private key on disk.

### Fail-closed, and loudly

Unlike §2 — where JWT is additive and a disabled config falls through to the
FORM login still sitting underneath — there is **nothing underneath the gateway**.
So `BrowserAuthenticator` refuses when it cannot decide:

- no config loaded (settings failed, or the endpoint started first) → refuse.
  "We could not read the rule" must never mean "there is no rule".
- `jwt.enabled` true but unusable (blank/unreachable `jwksUri`) → refuse, naming
  the setting.
- `jwt.enabled` explicitly false → open, and the service logs SEVERE at startup
  *and* sets `authenticated: false` in `session.ready` so the page shows it.

The shipped `WebrtcSettingsSample` has `enabled = true` and a blank `jwksUri`,
which fails closed: a half-configured deployment gets a gateway that does not
work, not one that works and is open.

### Replacing it with a real IdP

Point `WebrtcSettings.jwt` at the customer's issuer and JWKS, arrange for the
page to obtain the IdP's token instead of calling `TokenResource`, and delete the
issuer. `JwtValidator` does not change, because nothing in the consumer's
configuration says the issuer was BLADE. That symmetry is the reason the issuer
publishes a JWKS at all rather than sharing a secret.

> **Not built:** the OIDC redirect dance (authorization code + PKCE) in the
> browser. Same position as §2 — terminate it at a reverse proxy, or point these
> settings at the IdP directly. WebLogic cannot stand in as the issuer: its
> Embedded LDAP is an identity *store*, and every OAuth artifact Oracle ships in
> OCCAS 8.1 is client-side (`oauth2-client`, `oauth1-client`, the IDCS
> integrator asserter). There is no authorization endpoint, no token endpoint and
> no JWKS anywhere in the install.

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
  domain, so it is not present in a stock install. **TODO (Jeff):** record the
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

Driven by a customer mandate that all apps be TLS-encrypted by 2027 —
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

Customers with their own certificate process use
`./certs.sh <env> import` — a ready-made PKCS12, or PEM cert+key+chain,
packaged into the identical keystore layout. Same downstream steps either way.

### Wiring the domain

install.sh writes the certificate onto the server template at configure time, which enables the
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
`install.sh` auto-detects an SSL-enabled AdminServer in the live config.xml
and switches its derived admin URL to t3s. When using `install.sh` profile
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

- **JWT validation** — `JwtValidatorTest` (offline, locally-signed token):
  signature, issuer, audience, expiry, claim→role mapping, string-vs-list roles,
  username-claim override, app-specific string claims, and rejection of
  wrong-issuer/wrong-audience/expired/foreign-signature/garbage tokens.
- **JWT issuing** — `JwtIssuerTest`: the issuer→validator round trip, wired
  together only through the serialized JWKS document, as a deployment is. Covers
  claim carriage, that the published JWKS holds no private material, and that a
  token from a second issuer with identical claims does not verify.
- **Browser authorization** — `BrowserAuthenticatorTest` (`services/webrtc`): the
  gateway's own rule, against tokens from a real `JwtIssuer`. The cases that
  matter are the ones where a valid token from a genuine signed-in user is still
  refused because it asks for an address it was not granted, plus the three
  fail-closed paths (no config, unusable config, no token).
- **Address policy** — `AddressPolicyTest` (`admin/phone`): what the phone will
  mint for. Both modes, plus rejection of addresses that are not `user@host` —
  including a CRLF header-injection attempt.

  > These three replace `JwtValidatorSmokeTest`, a `main()`-driven pass/fail
  > driver that Surefire never ran — it carried no JUnit annotations, so the JWT
  > path had **no** coverage in the build while this section claimed it was
  > verified. `TlsClientConfigSmokeTest` below is still in that shape and still
  > does not run.
- **Descriptors / build** — `proto/security` packages as a skinny WAR (only
  `vorpal-blade-library-framework.jar` in `WEB-INF/lib`); the three hardened
  WARs and the admin EAR build.
- **TLS client config** — `TlsClientConfigSmokeTest` (offline): empty config →
  JVM default, truststore → working SSLContext, missing/garbage store → throws
  (fail closed).
- **Anti-regression grep** — every admin WAR except `watcher`/`redirect`/
  `javadoc` contains an `<auth-constraint>`. That half passes.

  The transport-guarantee and `cookie-secure` half is an **intent, not a
  statement of the tree**: as of 2026-08-06 no WAR in any tree carries either,
  so all 44 lines report MISSING. Closing it means editing every descriptor in
  `admin`, `services`, `proto` and `test`, which is a cross-cutting change and
  its own piece of work. Until then, read the second and third loops below as
  the work list they are (`libs/shared` is a library container, not an app, and
  inherits nothing here):

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
- TLS: once the certificate is on the template (install.sh rows `g`/`t`), the console answers
  on `https://…:7002`, engines on `:8002`, `openssl s_client -connect host:5061`
  shows the expected chain; with `tls.only=true`, ports 7001/8001/5060 refuse
  connections and `./deploy.sh <env> status` works over `t3s`. The `secure`
  step's WLST ran only in dry-run here — first execution against a real
  stopped domain is yours.
- Mutual TLS outbound: point a `RestConnector` `tls.keyStore` at
  `identity.p12` against an endpoint requiring client certs (the generated
  cert carries EKU clientAuth).
- WebRTC browser authentication, which has run only against unit tests here.
  After deploying `blade-phone` and `webrtc`, and setting `jwt.jwksUri` in
  `webrtc.json` to the phone's JWKS URL:
  1. `curl -k https://<admin>:7002/blade/phone/api/v1/jwks.json` from an
     **engine** node — it must return a `keys` array with no admin session. If
     that call fails, nothing else will work, and the gateway will say
     "misconfigured (jwksUri)" rather than guess.
  2. `curl -k https://<admin>:7002/blade/phone/api/v1/token` with no cookie must
     redirect to the login form, not mint anything.
  3. Register in the phone: the vorpal log should show
     `webrtc: <user>@<domain> registered on this node as '<user>'`, and the page
     should read "verified by the gateway".
  4. Two tabs, two addresses (`alice@…` and `bob@…`), call one from the other —
     the browser-to-browser path, and the reason `allowChosenAddress` defaults on.
  5. The hijack case, which needs devtools: register normally, then edit the
     `session.connect` frame's `aor` so it differs from the one in the token.
     The gateway must refuse with "token … grants X, not Y". Editing the page's
     address field alone will *not* reproduce it — the token is minted for
     whatever that field says, so the two agree and the socket is allowed. That
     is the mode working as configured, not the check failing.

## Open items (next refinement)

1. **TODO** Confirm logs/analytics/files had no EAR-level protection before the
   hardening (were they ever reachable unauthenticated in production?).
2. **TODO** Corporate IdP details for JWT (issuer, JWKS, audience, roles claim,
   group→role map); wire the planned cloud OCCAS+BLADE test instance as the IdP.
3. **Refinement** Distribute one `blade-security` JWT config to every admin WAR
   (JMX or shared store) so JWT can guard the whole tier, not just `security`.
   §2a now has a second consumer of `JwtAuthConfig` living in its own app's
   settings, which makes the case for one distributed config stronger, not
   weaker — `webrtc.json` and `blade-phone.json` currently have to agree by
   hand on issuer and audience, and nothing checks that they do.
4. **TODO** Exact OCCAS 8.1 JDBC digest provider class + install steps, for the
   edge/digest SIP mode.
5. **Design** Ship the digest `sip.xml` variant (and decide whether it lives in
   `acl` or a dedicated edge-auth SIP app).
