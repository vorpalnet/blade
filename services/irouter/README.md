# BLADE Intelligent Router (iRouter)

[Javadocs](https://vorpal.net/javadocs/blade/irouter)

**Universal, config-driven SIP proxy.** The iRouter is the operator's answer to "please stop writing custom SIP services for every customer." Everything a routing decision needs — how to parse the inbound INVITE, which external systems to consult, what to use as the routing key, where to send the call, what outbound headers to stamp — lives in one JSON configuration file that the Configurator form editor edits visually. No Java knowledge required on the operator's side; no recompile for a new use case.

## Model

An incoming INVITE runs through two phases:

```
   ┌────────────────────────────────────────────┐     ┌─────────────────┐
   │ Pipeline (enrichment) — N stages, in order │ ──► │ Routing         │
   │ Each stage writes values into the Context  │     │ (one decision)  │
   └────────────────────────────────────────────┘     └─────────────────┘
                                                               │
                                                               ▼
                                                       proxy.proxyTo(uri)
```

**Phase 1 — Pipeline.** An ordered list of `Connector`s. Each reads what earlier stages wrote into a shared per-call `Context`, pulls new values from some source (SIP message, REST API, database, directory, in-memory map, translation table), and writes those new values back to the Context via `${var}` substitution. Pipelines are enrichment only — no routing decisions happen here.

**Phase 2 — Routing.** A single polymorphic `Routing` field that reads the now-enriched Context and returns a concrete `Route`: a destination SIP URI plus optional outbound INVITE headers. The SIP Servlet Proxy API then proxies the call to that URI.

Why two phases? Jeff's formulation: *"first we gather all the information via the pipeline; then we make the routing decision."* The split keeps table connectors singular in purpose, makes the routing step visually prominent, and lets any enrichment type (table, REST, JDBC, LDAP) sit alongside any other.

## Proxy, not B2BUA

iRouter uses the JSR 289 Proxy API. Re-INVITEs and in-dialog traffic pass through the container's proxy machinery unchanged once `proxy.proxyTo()` has been called for the initial INVITE. The servlet stays out of the mid-call path — the routing decision is made once, on the initial INVITE, and the proxy handles everything after. Use [`proxy-router`](../proxy-router/) if you need a stateless SIP proxy without iRouter's pipeline, or a B2BUA-based service (e.g. call queueing) if you need dialog-mid intervention.

## Pipeline connectors

All six connector types are polymorphic via `"type"`. The Configurator dropdown lists them in the pipeline editor; pick one, fill in the form.

| `type` | Purpose | Key fields |
|---|---|---|
| `sip` | Parse the inbound SIP request | `selectors` (extract headers, request URI, body, remote IP) |
| `table` | Key-driven lookup with fallback chain | `tables[]` — list of (match, keyExpression, translations) |
| `rest` | HTTP/REST API call | `url`, `method`, `authentication`, `bodyTemplate`, `selectors` |
| `jdbc` | SQL query against a WebLogic DataSource | `dataSource`, `queryTemplate`, `selectors` |
| `ldap` | LDAP directory lookup | `ldapUrl`, `bindDn`, `bindPassword`, `searchTemplate`, `selectors` |
| `map` | In-memory static key→value map | `keyExpression`, `entries`, `selectors` |

Each connector (except `table`) carries a list of `Selector`s that extract values from whatever payload the connector produced and write them into the Context. Selector subtypes — `attribute`, `regex`, `json`, `xml`, `sdp` — cover the common extraction techniques.

#### SIP pseudo-attributes

In a `SipConnector`, selectors typically read a named SIP header (`To`, `From`, `Contact`, `P-Asserted-Identity`, etc.), but a handful of pseudo-attribute names reach past the header map for request-level or transport-level info. Use them as the `attribute` field on any selector:

| Name (either casing) | Value |
|---|---|
| `Request-URI` / `requestURI` / `RequestURI` / `ruri` | Request URI of the inbound INVITE |
| `Remote-IP` / `remoteIP` | Original caller IP with the five-step fallback chain (X-Vorpal-ID origin → `getInitialRemoteAddr` → Via `received` → Via `sent-by` → `getRemoteAddr`) |
| `Peer-IP` / `peerIP` | Immediate transport peer — typically the upstream OCCAS service when iRouter isn't first in the chain |
| `content` / `Content` / `body` / `Body` | INVITE message body (SDP, MIME, whatever) |
| `Transport` / `transport` | Inbound transport: `UDP`, `TCP`, `TLS`, `WS`, `WSS` |
| `IsSecure` / `isSecure` | `"true"` when transport is `TLS` or `WSS`, else `"false"` |
| `ClientCertSubject` / `clientCertSubject` | Subject DN of the peer-presented X.509 cert (TLS/WSS with mutual auth) |
| `ClientCertIssuer` / `clientCertIssuer` | Issuer DN of the same cert |
| `TlsCipher` / `tlsCipher` | Negotiated TLS cipher suite |

Any other value in `attribute` is treated as an actual SIP header name and looked up via `getHeader()`.

### Table connector

Holds an ordered list of `TranslationTable`s. Each table has its own `match` strategy (`hash` for exact match, `prefix` for longest-prefix match, `range` for integer-interval match), its own `keyExpression`, and its own `translations` map. On invoke, the connector tries each table in order and the **first lookup that returns a match wins** — its matched `Translation`'s fields all spread into the Context as session attributes. Iteration stops at the first hit; remaining tables don't run.

That enables `"find the customer by remote IP, else by source number, else by domain"` as a single connector:

```json
{
  "type": "table",
  "id": "customers",
  "description": "Fetch this customer's credentials from whichever hint they gave us",
  "tables": [
    { "match": "hash",   "keyExpression": "${remoteIP}", "translations": { "172.16.32.173": { "customerId": "acme" } } },
    { "match": "prefix", "keyExpression": "${srcNum}",   "translations": { "1816": { "customerId": "kcco" } } },
    { "match": "hash",   "keyExpression": "${fromHost}", "translations": { "example.com": { "customerId": "exampleCo" } } }
  ]
}
```

### Translations

A `Translation` is a plain object with an optional `description` plus any number of arbitrary string key/value pairs. On match, every key/value flows directly into the Context — so a translation like `{ "customerId": "acme", "apiKey": "..." }` makes `${customerId}` and `${apiKey}` available to every downstream stage (and to the final routing decision).

No Java class per payload type, no generics. Operators write whatever fields they want and rely on `${var}` to pipe them through.

## Authentication on REST calls

`RestConnector.authentication` is a polymorphic field; pick a `type` and the form reshapes to that scheme's fields. Ten subtypes today:

| `type` | Scheme | Fields |
|---|---|---|
| `basic` | HTTP Basic | `username`, `password` |
| `bearer` | Static Bearer token | `token` |
| `apikey` | API key in arbitrary header | `header`, `value` |
| `oauth2-password` | OAuth 2.0 Resource Owner Password (RFC 6749 §4.3) | `tokenUrl`, `username`, `password`, `clientId?`, `clientSecret?`, `scope?` |
| `oauth2-client` | OAuth 2.0 Client Credentials (RFC 6749 §4.4) | `tokenUrl`, `clientId`, `clientSecret`, `scope?` |
| `oauth2-refresh-token` | OAuth 2.0 Refresh Token (RFC 6749 §6) | `tokenUrl`, `refreshToken`, `clientId?`, `clientSecret?`, `scope?` |
| `oauth2-jwt-bearer` | JWT Bearer assertion (RFC 7523) | `tokenUrl`, `assertion`, `clientId?`, `clientSecret?`, `scope?` |
| `oauth2-saml-bearer` | SAML 2.0 Bearer assertion (RFC 7522) | `tokenUrl`, `assertion`, `clientId?`, `clientSecret?`, `scope?` |
| `hmac` | Generic HMAC request signing (webhook-style: GitHub, Shopify, Twilio, Stripe) | `algorithm`, `secret`, `header`, `payloadTemplate?`, `encoding?`, `prefix?` |
| `aws-sigv4` | AWS Signature Version 4 (API Gateway, S3, Lambda URLs, …) | `accessKeyId`, `secretAccessKey`, `region`, `service`, `sessionToken?` |

OAuth subtypes share `AbstractOAuth2Authentication`, which caches the access token in memory with a `refreshSkewSeconds` (default 60) window and refreshes on demand via the Nimbus OAuth 2.0/OIDC SDK. Parallel calls with the same Authentication instance serialize through one `synchronized` refresh so you don't hammer the token endpoint.

`hmac` and `aws-sigv4` are **request-signing** schemes — unlike the other subtypes, they need access to the HTTP method, URL, and body to compute the signature. They see those via `Authentication.RequestSignature`, which RestConnector supplies at stamp time.

Every field is `${var}`-resolvable — credentials can come from environment variables, system properties, or values an upstream table connector wrote to the Context (`${apiKey}` from a customers lookup, etc.).

### HMAC

Generic request signing for webhook-style APIs. Sign a template of the request (default: just `${body}`) with a shared secret, then stamp the hex or base64 digest into a header. Covers GitHub (`X-Hub-Signature-256: sha256=…`), Shopify, Twilio, Stripe, and most custom APIs that use the HMAC pattern.

```json
"authentication": {
  "type": "hmac",
  "algorithm": "sha256",
  "secret": "${WEBHOOK_SECRET}",
  "header": "X-Hub-Signature-256",
  "payloadTemplate": "${body}",
  "encoding": "hex",
  "prefix": "sha256="
}
```

Three request-level placeholders are substituted *after* normal `${var}` resolution: `${method}` (uppercase), `${url}` (resolved request URL), and `${body}` (resolved request body; empty for GETs). Stripe-style `timestamp.body` signing becomes `"payloadTemplate": "${now}.${body}"`.

### AWS SigV4

AWS Signature Version 4 — the signing scheme every AWS service (API Gateway, S3, Lambda function URLs, SNS, DynamoDB, etc.) accepts. Hand-rolled; no AWS SDK dependency.

```json
"authentication": {
  "type": "aws-sigv4",
  "accessKeyId": "${AWS_ACCESS_KEY_ID}",
  "secretAccessKey": "${AWS_SECRET_ACCESS_KEY}",
  "region": "us-east-1",
  "service": "execute-api"
}
```

Stamps four headers: `Host`, `X-Amz-Date`, `Authorization`, and (when `sessionToken` is set) `X-Amz-Security-Token`. Only these participate in the signature, which works for most AWS APIs. Customers needing additional signed headers will need a dedicated subtype.

Simplifications vs. full SigV4: path is passed through verbatim (no double-encoding for S3); query parameters are sorted but not re-encoded. Clean paths and queries work; exotic characters may need pre-encoding.

## Match strategies

Tables (both `TranslationTable` in pipeline `TableConnector`s and `RoutingTable` in routing) use one of three lookup strategies, selected via the `match` field:

| `match` | Lookup | Key shape | Performance | Typical use |
|---|---|---|---|---|
| `hash` (default) | Exact match via `LinkedHashMap` | Any string | O(1) | Route by explicit action, customer ID, domain name |
| `prefix` | Longest-prefix match via trie | Any string, entries are prefixes of input | O(key length) | Dial-plan routing by area code or country code |
| `range` | First range containing the key value | `"lo-hi"` (inclusive integer bounds, whitespace tolerated) | O(entries), linear scan | Time-of-day buckets, numeric score thresholds, hour-of-day shifts |

Range keys parse as two integers separated by a single dash: `"0-7"`, `"100-199"`, `"8 - 17"`. Negative ranges aren't supported in this format (the dash would be ambiguous). The resolved key must parse as an integer for `range` to match; a non-integer falls through to the next table (or the `default` route). Malformed range keys are silently ignored — one bad entry can't derail a whole lookup.

## Routing

The top-level `routing` field is a polymorphic `Routing` object. Three subtypes today:

### `type: table` — `TableRouting`

First-match-wins across an ordered list of `RoutingTable` entries, each with its own `match` strategy (`hash` / `prefix` / `range`) and `keyExpression`. Falls through to the top-level `default` when no table hits. Mirrors the pipeline-side `TableConnector` fallback chain.

Single-table case (common):

```json
"routing": {
  "type": "table",
  "tables": [
    {
      "match": "prefix",
      "keyExpression": "${destNum}",
      "routes": {
        "1816": { "description": "Kansas City",    "requestUri": "sip:${destNum}@kc.example.com" },
        "1212": { "description": "NYC",            "requestUri": "sip:${destNum}@nyc.example.com" },
        "1":    { "description": "North America",  "requestUri": "sip:${destNum}@nanp.example.com" },
        "44":   { "description": "United Kingdom", "requestUri": "sip:${destNum}@uk.example.com" }
      }
    }
  ],
  "default": { "description": "International fallback", "requestUri": "sip:${destNum}@intl.example.com" }
}
```

Multi-table fallback ("route by explicit action, else by dial-plan prefix"):

```json
"routing": {
  "type": "table",
  "tables": [
    {
      "match": "hash",
      "keyExpression": "${action}",
      "routes": {
        "block": { "requestUri": "sip:rejected@pbx.example.com" }
      }
    },
    {
      "match": "prefix",
      "keyExpression": "${destNum}",
      "routes": {
        "1800": { "requestUri": "sip:tollfree@carrier.example.com" },
        "1":    { "requestUri": "sip:${destNum}@nanp.carrier.example.com" }
      }
    }
  ],
  "default": { "requestUri": "sip:operator@pbx.example.com" }
}
```

Compound keys work inside any table via `${a}:${b}` in `keyExpression` — `"${callPermission}:${officeStatus}"` yields route keys like `"allow:open"`.

### `type: conditional` — `ConditionalRouting`

An ordered `if / else-if / else` chain. Each `clause` pairs a boolean expression (`when`) with a `route`. The router walks clauses top-to-bottom and the **first clause whose `when` evaluates true wins**. If no clause matches, the top-level `default` Route is returned. Use this when the decision is a function of multiple context variables combined with `&&`, `||`, and comparisons — not a single key lookup.

```json
"routing": {
  "type": "conditional",
  "clauses": [
    {
      "when": "${action} == block",
      "route": {
        "description": "Blocked by screening",
        "requestUri": "sip:rejected@pbx.example.com",
        "headers": { "X-Screening": "blocked" }
      }
    },
    {
      "when": "${action} == allow && ${shift} == business",
      "route": {
        "description": "Allowed, business hours",
        "requestUri": "${routeTo}",
        "headers": { "X-Customer-Id": "${customerId}" },
        "conditionalHeaders": [
          { "name": "X-Priority", "value": "high",
            "when": "${customerTier} == premium" }
        ]
      }
    },
    {
      "when": "${action} == allow",
      "route": {
        "description": "Allowed, outside business hours \u2014 voicemail",
        "requestUri": "sip:voicemail@pbx.example.com"
      }
    }
  ],
  "default": {
    "description": "Fallback operator",
    "requestUri": "sip:operator@pbx.example.com"
  }
}
```

Parse errors in a clause's `when` expression are caught and resolve to false — a bad expression skips the clause rather than blowing up the routing decision. See the **Boolean expressions** section below for the full grammar.

### `type: direct` — `DirectRouting`

Skip the lookup. Always proxy to the inline `requestUri`:

```json
"routing": {
  "type": "direct",
  "description": "Always proxy to the customer's contact center",
  "requestUri": "sip:${destNum}@${customerPbx}",
  "headers": { "X-Customer-Id": "${customerId}" }
}
```


## Route

Every routing subtype ultimately produces a `Route`:

| Field | Type | Purpose |
|---|---|---|
| `description` | string | Human-readable label |
| `requestUri` | string (`${var}`-resolvable) | Destination SIP URI for the outbound INVITE. If null, the INVITE's original Request-URI is preserved (pass-through). |
| `headers` | `Map<String, String>` (values `${var}`-resolvable) | Outbound INVITE headers stamped unconditionally |
| `conditionalHeaders` | list of `ConditionalHeader` (optional) | Headers stamped only when their `when` expression evaluates true |

### Conditional headers

For the "always stamp this header" case, use the plain `headers` map — it stays ergonomic and reads as a simple key/value block. For the "only stamp this header when some condition is true" case, opt into `conditionalHeaders`:

```json
"route": {
  "requestUri": "${routeTo}",
  "headers": {
    "X-Customer-Id": "${customerId}"
  },
  "conditionalHeaders": [
    {
      "name": "X-Priority",
      "value": "high",
      "when": "${customerTier} == premium"
    },
    {
      "name": "X-Billing-Code",
      "value": "${billingCode}",
      "when": "${billingEnabled} == true"
    }
  ]
}
```

Each entry has three fields: `name` (the header), `value` (the template to stamp, `${var}`-resolvable), `when` (a boolean expression). Same grammar as `ConditionalRouting` clauses. If the expression parses cleanly and evaluates true, the header is stamped. Otherwise it's skipped — silently and without error.

## Boolean expressions

`ConditionalRouting` clauses and `ConditionalHeader.when` fields both evaluate a small, safe boolean expression language against the routing Context. It's intentionally constrained — variable lookups, literal values, comparisons, and boolean combinators. No method invocation, no scripting, no arbitrary code execution from config files.

### Grammar

```
expr       := or_expr
or_expr    := and_expr ( '||' and_expr )*
and_expr   := not_expr ( '&&' not_expr )*
not_expr   := '!' not_expr | primary
primary    := '(' expr ')' | comparison | atom
comparison := atom ( '==' | '!=' | '<' | '>' | '<=' | '>=' ) atom
atom       := variable | number | string | bareword | boolean
variable   := '${' name '}'
string     := "'" any-chars-except-quote "'"
bareword   := non-operator non-whitespace characters
boolean    := 'true' | 'false'
```

### Operators

| Operator | Kind | Meaning |
|---|---|---|
| `==` `!=` | Binary comparison | Equal / not equal |
| `<` `>` `<=` `>=` | Binary comparison | Ordered comparison (numeric if both operands parse as numbers, else lexicographic) |
| `&&` | Binary logical | Short-circuit AND |
| `\|\|` | Binary logical | Short-circuit OR |
| `!` | Unary logical | Boolean negation |
| `( )` | Grouping | Parenthesized subexpression |

Precedence (high to low): `!`, then relational (`<` `>` `<=` `>=`), then equality (`==` `!=`), then `&&`, then `||`. Parentheses override.

### Value syntax

- **`${name}`** — variable lookup, resolved against the Context with the same fallback chain as templates: SipSession → SipApplicationSession → environment variable → system property. Unresolved variables coerce to false in boolean context and to the empty string in comparisons.
- **Numbers** — integer or decimal literals, e.g. `42`, `3.14`. When both sides of a comparison parse as numbers, numeric comparison is used.
- **Bare words** — unquoted identifiers like `allow`, `block`, `premium`. Treated as string literals. Cannot contain whitespace or operator characters.
- **Quoted strings** — single-quoted, e.g. `'not allowed'`. Required when the value contains whitespace; optional otherwise. JSON stays clean — no backslash-escape hell.
- **Booleans** — literal `true` or `false` (lowercase). Case-sensitive.

### Boolean coercion

When a value appears in a boolean context (as the operand of `!`, `&&`, `||`, or as a bare atom like in `"when": "${blocked}"`), it's coerced:

- `true`, `1`, `yes` (case-insensitive) → true
- Everything else (including null, empty string, unresolved variables) → false

### Examples

```
${score} > 80
${action} == allow
${action} == allow && ${score} >= 50
${override} == true || ${emergencyMode} == true
!${blocked}
${description} == 'not allowed'
(${customerTier} == premium && ${shift} == business) || ${override}
${hour} >= 8 && ${hour} <= 17
${apiKey} != ''
```

### Safety

The grammar can only evaluate — never invoke. There's no syntax for calling Java methods, accessing class fields, loading classes, running shell commands, or reading files. A malicious config file can at worst produce a wrong routing decision for its own calls; it cannot escalate out of the configuration sandbox.

## Template files

REST bodies, SQL queries, and LDAP search templates live as plain text files under `<domain>/config/custom/vorpal/_templates/`. All three formats support:

- **`${var}` substitution** against the session Context (env vars, system properties, and earlier pipeline output).
- **`#` comments** — any line whose first non-whitespace character is `#` is stripped at load time, before substitution, so commented `${…}` placeholders can't accidentally leak.

### REST (HTTP-message format)

Headers above a blank line, body below:

```
# Bearer authentication is handled by authentication.type; no Authorization header here.
Content-Type: application/json
X-Request-ID: ${uuid}

{
  "callId":    "${callId}",
  "srcNum":    "${srcNum}",
  "destNum":   "${destNum}",
  "customer":  "${customerId}",
  "timestamp": "${now}"
}
```

### JDBC

Plain SQL; Jeff recommends computing derived fields in SQL rather than bending selectors:

```sql
-- _templates/office-hours.sql
SELECT
  CASE
    WHEN EXTRACT(DOW FROM NOW()) IN (0, 6)          THEN 'closed'
    WHEN LOCALTIME BETWEEN open_time AND close_time THEN 'open'
    ELSE 'closed'
  END AS status,
  open_time,
  close_time
FROM office_hours
WHERE extension = '${destNum}'
LIMIT 1;
```

### LDAP

Search parameters above a blank line, filter below:

```
# _templates/caller-permission.ldap
base=OU=Users,DC=corp,DC=example,DC=com
filter=(&(telephoneNumber=${pai})(memberOf=CN=VoipOutbound,OU=Groups,DC=corp,DC=example,DC=com))
attributes=callPermission,department
```

## Reserved template variables

Every `${var}` expression is resolved against the session Context. If the name isn't a session attribute, the fallback chain is: environment variable → system property → reserved meta-variable → literal `${name}` left in place.

Reserved meta-variables (always win over same-named session/env values):

| Form | Meaning |
|---|---|
| `${now}` | Current Unix time in milliseconds |
| `${now:FORMAT}` | Current UTC time rendered with a `DateTimeFormatter` pattern, e.g. `${now:yyyy-MM-dd'T'HH:mm:ssX}` |
| `${uuid}` | Random UUID (RFC 4122 version-4) |

## Configuration files

| File | Location | Source |
|---|---|---|
| Live config | `<domain>/config/custom/vorpal/irouter.json` | Hand-authored or Configurator-edited |
| JSON schema | `<domain>/config/custom/vorpal/_schemas/irouter.jschema` | Auto-regenerated on deploy from Java |
| Canonical sample | `<domain>/config/custom/vorpal/_samples/irouter.json.SAMPLE` | Auto-regenerated on deploy from `IRouterConfigSample` |
| Templates | `<domain>/config/custom/vorpal/_templates/` | Hand-authored |

## Deployment

Context root: `/irouter`. The WAR depends on the `vorpal-blade` shared library for Jackson, Nimbus OAuth SDK, and the other third-party JARs — do not remove the `<library-ref>` from `weblogic.xml`.

## Javadocs

**Service classes**

- [IRouterServlet](https://vorpal.net/javadocs/blade/irouter) — proxy servlet, pipeline orchestration
- [IRouterConfig](https://vorpal.net/javadocs/blade/irouter) — concrete `RouterConfiguration` binding
- [IRouterConfigSample](https://vorpal.net/javadocs/blade/irouter) — the canonical showcase config

**Framework — configuration model**

- [RouterConfiguration](https://vorpal.net/javadocs/blade/framework) — pipeline + routing + session + logging
- [Context](https://vorpal.net/javadocs/blade/framework) — per-call state and `${var}` resolution
- [MatchStrategy](https://vorpal.net/javadocs/blade/framework) — `hash` / `prefix` / `range` lookup modes
- [RangeKey](https://vorpal.net/javadocs/blade/framework) — parser for `"lo-hi"` range keys

**Framework — pipeline**

- [Connector](https://vorpal.net/javadocs/blade/framework) — polymorphic pipeline-step base (sip, rest, jdbc, ldap, map, table)
- [TableConnector](https://vorpal.net/javadocs/blade/framework) — first-match-wins table lookup in the pipeline
- [TranslationTable](https://vorpal.net/javadocs/blade/framework), [Translation](https://vorpal.net/javadocs/blade/framework) — enrichment lookup + entry
- [Selector](https://vorpal.net/javadocs/blade/framework) — pattern-based extraction (attribute / regex / json / xml / sdp)

**Framework — routing**

- [Routing](https://vorpal.net/javadocs/blade/framework) — polymorphic routing-decision base
- [TableRouting](https://vorpal.net/javadocs/blade/framework), [RoutingTable](https://vorpal.net/javadocs/blade/framework) — multi-table routing with first-match-wins
- [ConditionalRouting](https://vorpal.net/javadocs/blade/framework) — if/elif/else clauses with boolean expressions
- [DirectRouting](https://vorpal.net/javadocs/blade/framework) — single fixed Route, no lookup
- [Route](https://vorpal.net/javadocs/blade/framework), [ConditionalHeader](https://vorpal.net/javadocs/blade/framework) — decision payload + optional gated headers

**Framework — authentication**

- [Authentication](https://vorpal.net/javadocs/blade/framework) — eight auth subtypes for `RestConnector`
- [AbstractOAuth2Authentication](https://vorpal.net/javadocs/blade/framework) — shared Nimbus-backed token cache + refresh

**Framework — expressions**

- [Expression](https://vorpal.net/javadocs/blade/framework) — boolean expression parser + evaluator for `ConditionalRouting` and `ConditionalHeader`
