package org.vorpal.blade.framework.v3.configuration;

/// Strategy a table or routing uses to match a resolved key against its
/// translation/route entries.
///
/// - [#HASH] (default) — exact-match lookup via a `LinkedHashMap`.
/// - [#PREFIX] — longest-prefix match (telco dial-plan semantics): the most
///   specific stored key that is a prefix of the input wins. For example,
///   with routes `"1"`, `"1816"`, and `"1212"`, `"18165551234"` resolves
///   to `"1816"`; `"12125551234"` resolves to `"1212"`; `"15559876543"`
///   falls back to `"1"`.
///
/// Referenced by both [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector]
/// (pipeline enrichment) and [org.vorpal.blade.framework.v3.configuration.routing.TableRouting]
/// (routing decision). The JSON discriminator values are the lowercase
/// names (`"hash"` / `"prefix"`) to keep configs readable.
public enum MatchStrategy {
	hash,
	prefix;
}
