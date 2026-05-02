/// Create, Read, Update, Delete (CRUD) — a rule-driven SIP message
/// transformation service.
///
/// CRUD is a configurable B2BUA. At every B2BUA lifecycle event it walks
/// a list of operator-authored rules and applies the matching ones to the
/// outbound message. No Java code required: every transformation is JSON.
///
/// ## Addressing modes
///
/// Each operation picks one of four addressing modes for its target:
///
/// | Mode     | What it addresses                                | Operation type prefix |
/// |----------|--------------------------------------------------|-----------------------|
/// | Regex    | Headers, Request-URI, status, reason, body text  | `read` / `create` / `update` / `delete` |
/// | XPath    | XML body                                         | `xmlRead` / `xmlCreate` / `xmlUpdate` / `xmlDelete` |
/// | JsonPath | JSON body                                        | `jsonRead` / `jsonCreate` / `jsonUpdate` / `jsonDelete` |
/// | SDP      | SDP body, parsed via [org.vorpal.blade.framework.v2.sdp.Sdp] into a JSON tree | `sdpRead` / `sdpCreate` / `sdpUpdate` / `sdpDelete` |
///
/// SDP round-trips through the native parser, so untouched fields
/// (`b=`, `i=`, `u=`, attributes…) survive every modification.
///
///
/// ## Configuration shape
///
/// The standard BLADE selectors / translation maps / routing plan pick a
/// [RuleSet] for each call (the matched
/// [org.vorpal.blade.framework.v2.config.Translation] must carry a
/// `ruleSet` attribute naming an entry in `ruleSets`). Each rule set
/// holds an ordered list of [Rule]s; each rule holds an ordered list of
/// [Operation]s.
///
/// ```json
/// {
///   "ruleSets": {
///     "header-scrub": {
///       "id": "header-scrub",
///       "rules": [{
///         "id": "scrub-and-stamp",
///         "method": "INVITE",
///         "event": "callStarted",
///         "operations": [
///           { "type": "read",   "attribute": "From",
///             "pattern": "sip:(?<callerUser>[^@]+)@(?<callerHost>[^;>]+)" },
///           { "type": "create", "attribute": "X-Caller-Info",
///             "value": "${callerUser}@${callerHost}" },
///           { "type": "delete", "attribute": "P-Private-Data" }
///         ]
///       }]
///     }
///   }
/// }
/// ```
///
/// Operations run **top-to-bottom in the order written**, so a `read`
/// must precede any `create`/`update` that consumes the variable it
/// produces.
///
///
/// ## Rule filters
///
/// All four filters are optional; a `null` filter is a wildcard. They
/// combine via AND — every non-null filter must pass for the rule to
/// fire.
///
/// | Filter        | Default | Syntax                                                                   |
/// |---------------|---------|--------------------------------------------------------------------------|
/// | `method`      | any     | SIP method, comma-OR, `!` to negate                                      |
/// | `messageType` | both    | `request` or `response`                                                  |
/// | `event`       | any     | B2BUA lifecycle event, comma-OR, `!` to negate                           |
/// | `statusRange` | any     | response status: exact, range, `Nxx` shorthand, comma-OR, `!` to negate  |
///
/// ### `method` and `event` syntax
///
/// Each is a comma-separated list of tokens. A token is **positive**
/// (`INVITE`) or **negated** (`!BYE`). The filter passes when at least
/// one positive token matches *and* no negative token matches.
///
/// ```json
/// "method": "INVITE"               // single
/// "method": "INVITE,REGISTER"      // OR — INVITE or REGISTER
/// "method": "!BYE"                 // anything but BYE
/// "event":  "callAnswered,callConnected"
/// ```
///
/// ### `statusRange` syntax
///
/// Each token is one of:
///
/// - **Exact** — `200`
/// - **Range** — `200-299` (inclusive)
/// - **Hundred shorthand** — `4xx` ⇒ 400–499 (case-insensitive)
/// - **Negated** — `!500` or `!5xx`
///
/// Tokens combine with the same comma + negation rules. A non-null
/// `statusRange` implicitly restricts the rule to **responses** —
/// requests have no status, so they always fail this filter.
///
/// ```json
/// "statusRange": "200-299"         // successes
/// "statusRange": "4xx,5xx"         // any error
/// "statusRange": "!304"            // anything but 304
/// "statusRange": "200,301,302"     // any redirect-or-OK
/// ```
///
/// ### Lifecycle events
///
/// `event` matches one of:
///
/// `callStarted`, `callAnswered`, `callConnected`, `callCompleted`,
/// `callDeclined`, `callAbandoned`, `requestEvent`, `responseEvent`.
///
/// See [CrudServlet] for which lifecycle methods fire each event.
///
///
/// ## Operation reference
///
/// Operations are entries in the same `operations` list, distinguished
/// by `type`:
///
/// ### Regex (headers, Request-URI, status, reason, body text)
///
/// - `read` — `attribute`, `pattern`. Named regex groups become session
///   attributes.
/// - `create` — `attribute`, `value`, optional `contentType`. With
///   `attribute=body` and a `contentType`, attaches a MIME part instead
///   of replacing the body.
/// - `update` — `attribute`, `pattern`, `replacement`. Find-and-replace.
/// - `delete` — `attribute`. Removes the header (or clears the body, or
///   removes one MIME part if `contentType` is set).
///
/// **Pseudo-headers** (use these as `attribute`):
///
/// | Name              | Returns                                                                   |
/// |-------------------|---------------------------------------------------------------------------|
/// | `body` / `content`| Message body (multipart-aware via `contentType`)                          |
/// | `Request-URI` / `ruri` | Request URI (request only)                                           |
/// | `status`          | Response status code as string (response only)                            |
/// | `reason`          | Response reason phrase (response only)                                    |
/// | `originIP`        | Original caller across proxy hops — walks `X-Vorpal-ID;origin`, `InitialRemoteAddr`, bottom Via, transport peer |
/// | `peerIP`          | Immediate transport peer (`getRemoteAddr`)                                |
/// | `transport`       | UDP / TCP / TLS / WS / WSS                                                |
/// | `isSecure`        | `"true"` if transport is TLS or WSS, else `"false"`                       |
///
/// ### XPath (XML bodies)
///
/// - `xmlRead` — `expressions: {name → xpath}`. Evaluates each XPath and
///   stores the result as a session variable.
/// - `xmlCreate` — `parentXpath` + (`elementName` *or* `attributeName`) + `value`.
/// - `xmlUpdate` — `xpath`, `value`. Replaces the node's text content.
/// - `xmlDelete` — `xpath`. Removes matching nodes.
///
/// ### JsonPath (JSON bodies)
///
/// - `jsonRead` — `expressions: {name → jsonPath}`.
/// - `jsonCreate` — `parentPath`, `key` (object) or null (array), `value`.
/// - `jsonUpdate` — `jsonPath`, `value`.
/// - `jsonDelete` — `jsonPath`.
///
/// ### SDP
///
/// SDP is parsed into JSON via [org.vorpal.blade.framework.v2.sdp.Sdp],
/// JsonPath is applied, and the result is rendered back to SDP. All four
/// SDP ops behave like the JsonPath ops but operate on the parsed
/// representation, so addressing like `$.media[0].port`,
/// `$.connection.address`, and
/// `$.media[0].attributes[?(@.name=='rtpmap')].value` work as expected.
///
///
/// ## Variables and substitution
///
/// Read operations save extracted values to the
/// [javax.servlet.sip.SipApplicationSession] as attributes. Subsequent
/// operations reference them via `${name}` substitution. Variables
/// persist for the entire dialog (so values written on the INVITE are
/// visible on the 200 OK), and are replicated for cluster failover.
///
/// Substitution is provided by
/// [org.vorpal.blade.framework.v3.configuration.Context#substitute]
/// and supports:
///
/// | Form                  | Resolves to                                                       |
/// |-----------------------|-------------------------------------------------------------------|
/// | `${name}`             | Session attribute or read-op variable                             |
/// | `${now}`              | Current Unix epoch milliseconds                                   |
/// | `${now:FORMAT}`       | Current time via `DateTimeFormatter` pattern, UTC                 |
/// | `${uuid}`             | Random UUID v4                                                    |
/// | `${HOSTNAME}`         | Falls back to environment variable                                |
/// | `${user.home}`        | Falls back to JVM system property                                 |
///
/// Resolution is **iterative** — a resolved value containing `${...}` is
/// re-resolved up to 10 passes. Unresolved placeholders are left literal.
///
/// ### Stale values
///
/// If a rule re-fires (re-INVITE, in-dialog request) and its read pattern
/// fails to match this time, the prior capture is still on the session
/// and a downstream `${var}` resolves to the *stale* value. Set
/// `resetVariables: true` on the rule to clear its read-op variables
/// before each invocation.
///
///
/// ## Multipart bodies
///
/// Setting `contentType` on a body operation targets a single MIME part:
/// reads pull from that part, updates rewrite that part, deletes remove
/// it. [MimeHelper] preserves every part's headers (Content-ID,
/// Content-Disposition, custom MIME headers) through the round trip.
///
/// `create` with `attribute=body` and a `contentType` *attaches* a part
/// rather than replacing the body. If the message has a non-multipart
/// body, it is wrapped into `multipart/mixed` with a fresh boundary and
/// the new part is appended.
///
///
/// ## Save / restore across messages
///
/// Because variables persist for the dialog, a read on the INVITE can
/// save a structured value (e.g. an SDP media block) that a later
/// create on the 200 OK splices back in:
///
/// ```json
/// // On outbound INVITE — strip video, remember it.
/// {
///   "method": "INVITE", "event": "callStarted",
///   "operations": [
///     { "type": "sdpRead",   "contentType": "application/sdp",
///       "expressions": { "videoMedia": "$.media[1]" } },
///     { "type": "sdpDelete", "contentType": "application/sdp",
///       "jsonPath": "$.media[1]" }
///   ]
/// }
/// ```
///
/// ```json
/// // On 200 OK — splice video back in.
/// {
///   "method": "INVITE", "event": "callAnswered",
///   "operations": [
///     { "type": "sdpCreate", "contentType": "application/sdp",
///       "parentPath": "$.media", "value": "${videoMedia}" }
///   ]
/// }
/// ```
///
/// Values that look like JSON (start with `{` or `[`) are parsed before
/// being spliced, so the result is a structured array element — not a
/// literal string.
///
///
/// ## Core classes
///
/// - [CrudServlet] — the B2BUA entry point
/// - [CrudConfiguration] / [CrudConfigurationSample] — config surface
/// - [RuleSet] / [Rule] / [Operation] — the rule engine
/// - [MessageHelper] / [MimeHelper] / [XmlHelper] / [SdpHelper] — utilities
package org.vorpal.blade.services.crud;
