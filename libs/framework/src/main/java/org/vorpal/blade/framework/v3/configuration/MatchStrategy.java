package org.vorpal.blade.framework.v3.configuration;

/// Strategy a table or routing uses to match a resolved key against its
/// translation/route entries.
///
/// - [#hash] (default) — exact-match lookup via a `LinkedHashMap`. O(1).
/// - [#prefix] — longest-prefix match (telco dial-plan semantics): the
///   most specific stored key that is a prefix of the input wins. For
///   example, with routes `"1"`, `"1816"`, and `"1212"`,
///   `"18165551234"` resolves to `"1816"`; `"12125551234"` resolves to
///   `"1212"`; `"15559876543"` falls back to `"1"`. Backed by a trie,
///   O(key-length) per lookup.
/// - [#range] — integer interval match. Keys are `"lo-hi"` (inclusive);
///   the resolved key must parse as an integer and fall within one of
///   the stored ranges. First-matching range wins; linear scan,
///   O(entries), intended for small tables like time-of-day / score
///   buckets.
///
/// Referenced by both [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector]
/// (pipeline enrichment) and [org.vorpal.blade.framework.v3.configuration.routing.TableRouting]
/// (routing decision). The JSON discriminator values are the lowercase
/// names (`"hash"`, `"prefix"`, `"range"`) to keep configs readable.
public enum MatchStrategy {
	hash,
	prefix,
	range;
}
