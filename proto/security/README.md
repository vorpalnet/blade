# Security (Admin)

The admin-tier authentication configuration app — the single place an operator configures
BLADE's inbound authentication posture. Today that means **inbound bearer-JWT single
sign-on** against an enterprise identity provider for the admin consoles: tokens are
validated against the IdP's JWKS and the token's group claim is mapped onto the four BLADE
admin roles (Admin, Operator, Deployer, Monitor). JWT auth is additive to the container
FORM/BASIC login and **ships disabled**.

This is a config-and-status app. The enforcement lives in the framework
(`org.vorpal.blade.framework.v3.security.JwtAuthFilter`), which every admin WAR carries in
its framework JAR. [SECURITY.md](../../SECURITY.md) is the authoritative document for the
full picture — container realm roles, the SIP trust model, credential storage, TLS/mTLS;
this app is the knob, not the doctrine.

## How it works

- On startup, the app registers its `SettingsManager` (so it appears on the
  [Admin Portal](../../admin/portal/README.md) deck and is editable in the
  [Configurator](../../admin/configurator/README.md)) and publishes a live
  `Supplier<JwtAuthConfig>` into the ServletContext.
- `JwtAuthFilter` reads through that supplier, so a Configurator save takes effect
  **without a redeploy**; the filter rebuilds its validator only when the config instance
  changes.
- First deployment materializes `./config/custom/vorpal/security.json` with JWT disabled.

## Status endpoint

`GET /blade/security/api/v1/status` returns the caller's identity as the container sees
it: `{user, authScheme, jwtEnabled, jwtIssuer}`. Useful for verifying an SSO rollout
before flipping anything on.

## Incubator status

This module lives in `proto/` — it builds under the `full` profile (WAR:
`blade-security.war`, context-root `blade/security`) but is **not** bundled into
`blade-admin.ear`. Promotion moves it to `admin/` and adds its `ear-security` profile to
the EAR pom in [apps/admin](../../apps/admin/README.md).

## Related modules

- [SECURITY.md](../../SECURITY.md) — the full security model
- [admin/portal](../../admin/portal/README.md) — where the app's card appears
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-security</artifactId>
```
