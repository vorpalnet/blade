/// Create, Read, Update, Delete (CRUD) — a rule-driven SIP message
/// transformation service.
///
/// ## What It Does
///
/// CRUD is a configurable B2BUA that applies transformation rules to outbound
/// SIP messages at every B2BUA lifecycle event. Without writing Java code, you
/// can mutate headers, Request-URIs, or message bodies using one of four
/// addressing modes:
///
/// - **Regex** — for headers, Request-URI, status, reason, and the body as text
/// - **XPath** — for XML bodies
/// - **JsonPath** — for JSON bodies
/// - **SDP** — body is parsed via [org.vorpal.blade.framework.v2.sdp.Sdp]
///   into a JSON tree, JsonPath is applied, and the result is rendered back
///   to SDP. Untouched fields (`b=`, `i=`, `u=`, attributes…) survive
///   the round trip intact.
///
///
/// ## Configuration Shape
///
/// Each call is matched to a [RuleSet] via the standard BLADE selectors /
/// translation maps / routing plan; the matched [org.vorpal.blade.framework.v2.config.Translation]
/// must carry a `ruleSet` attribute naming an entry in `ruleSets`.
///
/// A [RuleSet] holds an ordered list of [Rule]s. Each [Rule] has three
/// optional filters (`method`, `messageType`, `event` — null means wildcard)
/// and one ordered [Operation] list. Operations run top-to-bottom, so a
/// [ReadOperation] that produces `${variable}` must be listed above any
/// [CreateOperation] / [UpdateOperation] that consumes it.
///
/// Sample (the in-code [CrudConfigurationSample] generates this on first
/// deploy):
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
///
/// ## Operation Types
///
/// All operations are entries in the same `operations` list, distinguished
/// by `type`:
///
/// | Addressing      | Read           | Create           | Update           | Delete           |
/// |-----------------|----------------|------------------|------------------|------------------|
/// | Regex (headers) | `read`         | `create`         | `update`         | `delete`         |
/// | XPath (XML)     | `xmlRead`      | `xmlCreate`      | `xmlUpdate`      | `xmlDelete`      |
/// | JsonPath (JSON) | `jsonRead`     | `jsonCreate`     | `jsonUpdate`     | `jsonDelete`     |
/// | SDP             | `sdpRead`      | `sdpCreate`      | `sdpUpdate`      | `sdpDelete`      |
///
///
/// ## Variables
///
/// Read operations save extracted values to the [javax.servlet.sip.SipApplicationSession]
/// as attributes. Subsequent create / update operations reference them via
/// `${name}` substitution, including across rules within the same dialog
/// (BLADE's session is replicated to other cluster nodes for failover).
///
/// Variables persist for the life of the dialog. If a rule re-fires (e.g. on
/// re-INVITE) and its read pattern this time fails to match, the prior
/// capture is still on the session and a downstream `${var}` resolves to the
/// stale value. Set `resetVariables: true` on the rule to clear its read-op
/// variables before each invocation.
///
///
/// ## Multipart Bodies
///
/// For multipart messages, set `contentType` on any operation to target a
/// specific MIME part. [MimeHelper] preserves every part's headers
/// (Content-ID, Content-Disposition, custom MIME headers) — only the
/// targeted part's body is rewritten.
///
/// `create` with `attribute=body` and a `contentType` *attaches* a part:
/// if the message currently has a non-multipart body, it is wrapped into
/// `multipart/mixed` with a fresh boundary and the new part is appended.
///
///
/// ## Save and Restore Across Messages
///
/// Because variables live on the [javax.servlet.sip.SipApplicationSession],
/// they persist for the entire dialog and survive cluster failover. A read
/// on the outbound INVITE can save a value (including a structured JSON
/// fragment, like an SDP media block) that a later create or update on the
/// 200 OK splices back in:
///
/// ```json
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
/// {
///   "method": "INVITE", "event": "callAnswered",
///   "operations": [
///     { "type": "sdpCreate", "contentType": "application/sdp",
///       "parentPath": "$.media", "value": "${videoMedia}" }
///   ]
/// }
/// ```
///
/// JSON-shaped values (those starting with `{` or `[`) are parsed before
/// being inserted, so the splice produces a structured array element rather
/// than a literal string.
///
///
/// ## Core Classes
///
/// - [CrudServlet] — the B2BUA entry point
/// - [CrudConfiguration] / [CrudConfigurationSample] — config surface
/// - [RuleSet] / [Rule] / [Operation] — the rule engine
/// - [MessageHelper] / [MimeHelper] / [XmlHelper] / [SdpHelper] — utilities
package org.vorpal.blade.services.crud;
