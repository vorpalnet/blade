/// Translation entries and table wrappers consumed by
/// [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector].
///
/// Two concrete types:
///
/// - [org.vorpal.blade.framework.v3.configuration.translations.Translation]
///   — one entry in a translations map. Carries an optional
///   `description` plus an arbitrary bag of string key/value pairs
///   (`@JsonAnySetter`/`@JsonAnyGetter`). On match, every entry flows
///   into the session [org.vorpal.blade.framework.v3.configuration.Context]
///   so `${var}` templates downstream can use them.
/// - [org.vorpal.blade.framework.v3.configuration.translations.TranslationTable]
///   — one (match, keyExpression, translations) lookup attempt.
///   Resolves its `keyExpression` against the Context, does a hash
///   (exact) or prefix (longest-match) lookup, returns the matched
///   Translation or null. The prefix trie is built lazily on first
///   use and rebuilt when the backing map changes.
///
/// TableConnector holds an ordered `List<TranslationTable>` and tries
/// each in turn — **first lookup that matches wins**, its Translation's
/// extras spread into the Context, iteration stops. This lets
/// operators express fallback-chain lookups ("by IP, else by source
/// number, else by domain") as one connector.
package org.vorpal.blade.framework.v3.configuration.translations;
