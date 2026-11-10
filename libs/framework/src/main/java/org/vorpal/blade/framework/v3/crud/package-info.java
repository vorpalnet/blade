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
/// `ruleSet` attribute naming an entry in `ruleSets`). A configuration
/// holds `variables` every call starts with, the `pipeline` of connectors,
/// and `ruleSets`; each rule set holds [Rule]s keyed by id, run in the order
/// written; each rule holds an ordered list of [Operation]s.
///
/// ```json
/// {
///   "variables": { "carrierHost": "sbc1.example.net" },
///   "ruleSets": {
///     "header-scrub": {
///       "rules": {
///         "scrub-and-stamp": {
///           "method": "INVITE",
///           "event": "callStarted",
///           "operations": [
///             { "type": "read",   "attribute": "From",
///               "pattern": "sip:(?<callerUser>[^@]+)@(?<callerHost>[^;>]+)" },
///             { "type": "create", "attribute": "X-Caller-Info",
///               "value": "${callerUser}@${callerHost}" },
///             { "type": "delete", "attribute": "P-Private-Data" }
///           ]
///         }
///       }
///     }
///   }
/// }
/// ```
///
/// Operations run **top-to-bottom in the order written**, so a `read`
/// must precede any `create`/`update` that consumes the variable it
/// produces.
///
/// ## Domain, cluster and server files
///
/// A server runs its domain file, its cluster's file and its own file merged
/// in that order: objects merge key by key, and a list is appended to the
/// list below it. Variables, connectors ([Pipeline]) and rules ([RuleSet])
/// are keyed by name for that reason: a cluster or server file changes one
/// by naming it and inherits the rest, and a new name runs after the
/// inherited ones. A rule's fields replace the inherited ones and its
/// operations are added after them. `"enabled": false` switches a rule off,
/// and `null` removes a rule, connector or variable outright: how a lower
/// file drops one it inherits. The earlier list forms
/// (`"rules": [{ "id": … }]`, `"pipeline": [ … ]`) still load, and a list
/// in one file merges with the keyed form in another by id
/// ([org.vorpal.blade.framework.v3.configuration.ConfigFiles#merge]); the
/// editor saves the keyed form when the application's schema has it.
///
///
/// ## Rule filters
///
/// Every filter is optional; a `null` filter is a wildcard. They combine
/// with AND: every non-null filter must pass for the rule to fire.
///
/// | Filter        | Default | Syntax                                                                   |
/// |---------------|---------|--------------------------------------------------------------------------|
/// | `method`      | any     | SIP method, comma-OR, `!` to negate                                      |
/// | `messageType` | both    | `request` or `response`                                                  |
/// | `event`       | any     | B2BUA lifecycle event, comma-OR, `!` to negate                           |
/// | `leg`         | both    | `caller` or `callee`: the side of the B2BUA the message is on            |
/// | `sdp`         | any     | `offer` or `answer`: the message carries that half of an SDP exchange    |
/// | `statusRange` | any     | response status: exact, range, `Nxx` shorthand, comma-OR, `!` to negate  |
/// | `when`        | always  | boolean expression over variables                                        |
///
/// [Rule] describes how `leg` and `sdp` decide.
///
/// ### All or nothing
///
/// If an operation throws, the rule is undone ([MessageSnapshot]): headers,
/// body, Request-URI, `To`, `From`, `Contact` parameters, variables, and any
/// pending status or refusal go back to what they were, and the next rule
/// runs as usual. An operation that finds nothing to do returns quietly and
/// is not a failure.
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
/// See `CrudServlet` for which lifecycle methods fire each event. Inside an
/// application's own `crud` section a rule sees `send` on every message the
/// application sends ([CrudHook#apply]), and runs then when it leaves `event`
/// unset. It sees `receive` on every message the application receives, before
/// the application handles it ([CrudHook#receive]), and runs then only when
/// its `event` names `receive`.
///
///
/// ## Operation reference
///
/// Operations are entries in the same `operations` list, distinguished
/// by `type`:
///
/// ### Regex (headers, Request-URI, status, reason, body text)
///
/// - `read`: `attribute`, `pattern`. Named regex groups become session
///   variables. On a header that appears more than once, the first
///   occurrence the pattern matches.
/// - `create`: `attribute`, `value`, optional `contentType`. On a header it
///   adds an occurrence, never overwrites. With `attribute=body` and a
///   `contentType`, attaches a MIME part instead of replacing the body.
/// - `update`: `attribute`, `pattern`, `replacement`, `everyMatch`.
///   Find-and-replace; no match, no change. Each occurrence of a repeated
///   header is updated on its own.
/// - `delete`: `attribute`, optional `pattern`. Removes the header (only the
///   occurrences `pattern` matches, if given), or clears the body, or
///   removes one MIME part if `contentType` is set.
/// - `reject`: `status`, `reasonPhrase`. Refuses an initial INVITE: the
///   caller is answered with `status` and the INVITE is not sent
///   ([RejectOperation]). With no caller to answer (an originated call, a
///   caller who has cancelled), the INVITE is still not sent: the send
///   throws.
///
/// Writing `status` or `reasonPhrase` on a response sends a copy with the
/// new values ([CrudHook#restatus]); only a failure can become another
/// failure. `originIP`, `peerIP`, `transport` and `isSecure` are read-only.
///
/// **Pseudo-headers** (use these as `attribute`):
///
/// | Name              | Returns                                                                   |
/// |-------------------|---------------------------------------------------------------------------|
/// | `body`            | Message body (multipart-aware via `contentType`)                          |
/// | `requestURI`      | Request URI (request only)                                                |
/// | `status`          | Response status code as string (response only)                            |
/// | `reasonPhrase`    | Response reason phrase (response only)                                    |
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
/// The configuration's `variables` sit beneath every call's own: a
/// `${name}` the call has not set resolves to the file's value
/// ([CrudHook#fileVariables]), for calls the application receives and
/// originates alike, and a connector or read operation that sets the name
/// overrides it for that call. Nothing is copied into the session for them.
/// Put what differs between servers there, so a server file sets one value
/// instead of changing rules.
///
/// Read operations save extracted values to the
/// [javax.servlet.sip.SipApplicationSession] as attributes named
/// `crud.<name>` ([MessageHelper#VARIABLE_PREFIX]), so a variable never
/// overwrites an attribute of the application hosting the rules. Subsequent
/// operations reference them via `${name}` substitution, which also resolves
/// any other String attribute on the session by its own name. Variables
/// persist for the entire dialog (so values written on the INVITE are
/// visible on the 200 OK), and are replicated for cluster failover.
///
/// Substitution is provided by
/// [org.vorpal.blade.framework.v3.configuration.Context#substituteStrict]
/// and supports:
///
/// | Form                  | Resolves to                                                       |
/// |-----------------------|-------------------------------------------------------------------|
/// | `${name}`             | Session attribute or read-op variable                             |
/// | `${now}`              | Current Unix epoch milliseconds                                   |
/// | `${now:FORMAT}`       | Current time via `DateTimeFormatter` pattern, UTC                 |
/// | `${uuid}`             | Random UUID v4                                                    |
/// | `${env:CRUD_NAME}`    | Environment variable `CRUD_NAME` (only names starting `CRUD_`)    |
/// | `${sys:crud.name}`    | JVM system property `crud.name` (only names starting `crud.`)     |
///
/// A plain name never falls back to the environment: CRUD output goes on
/// the wire, and a misspelt `${HOME}` must not carry the server's home
/// directory to a carrier. Each placeholder is replaced once; text a value
/// brings with it is not scanned again. A placeholder with no value skips
/// the operation, so `${name}` never goes out as text.
///
/// Patterns, JsonPaths and XPaths take placeholders too, inserted as data:
/// quoted in a regex, escaped in a single-quoted JsonPath string, a whole
/// string literal in an XPath ([Placeholders]).
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
/// ## Inside an application
///
/// Any v3 application can run CRUD rules itself, without the CRUD service in
/// its FSMAR chain: its configuration carries a `crud` section ([Crud]), the
/// same rule sets under the same names. [CrudHook] runs them on every message
/// the application's callflows send, after the application's own changes. The
/// CRUD service is still the way to change messages between two applications
/// without touching either.
///
/// ## Core classes
///
/// - `CrudServlet` · the B2BUA entry point
/// - [CrudConfiguration] / [CrudConfigurationSample] · config surface
/// - [Crud] / [CrudHook] / [CrudCapable] · the same rules inside any v3 application
/// - [RuleSet] / [Rule] / [Operation] · the rule engine
/// - [MessageHelper] / [MimeHelper] / [XmlHelper] / [SdpHelper] · utilities
package org.vorpal.blade.framework.v3.crud;
