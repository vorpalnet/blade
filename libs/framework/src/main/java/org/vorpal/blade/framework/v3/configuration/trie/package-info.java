/// Generic prefix-search trie used by BLADE for longest-prefix routing
/// lookups. The implementation is intentionally simple and dependency-free
/// — pure JDK, no Jackson, no SIP, no logging.
///
/// This package exists because BLADE's earlier prefix-map implementations
/// were either O(L²) (HashMap with substring iteration) or buggy
/// (Apache `commons-collections4` `PatriciaTrie` sometimes failed to
/// match). [Trie] is a clean from-scratch alternative that delivers
/// O(L) longest-prefix matching for telco-style dial plan lookups.
package org.vorpal.blade.framework.v3.configuration.trie;
