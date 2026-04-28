/// Root of the v3 configuration model.
///
/// Top-level types:
///
/// - [org.vorpal.blade.framework.v3.configuration.RouterConfiguration]
///   — the base configuration every v3 router service extends. Carries
///   the enrichment pipeline, the top-level routing decision, and the
///   usual logging/session parameters.
/// - [org.vorpal.blade.framework.v3.configuration.Context] — per-call
///   state wrapper threaded through the pipeline. Connectors and
///   selectors operate on the Context's session-state API and
///   `${var}` substitution engine; only the SipConnector reaches
///   through to the underlying `SipServletRequest`.
/// - [org.vorpal.blade.framework.v3.configuration.MatchStrategy] — the
///   two lookup modes (hash = exact match, prefix = longest-prefix)
///   shared by [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector]'s
///   translation tables and
///   [org.vorpal.blade.framework.v3.configuration.routing.TableRouting].
///
/// Subpackages:
///
/// - [org.vorpal.blade.framework.v3.configuration.connectors] — pipeline
///   stages (SIP, REST, JDBC, LDAP, Map, Table).
/// - [org.vorpal.blade.framework.v3.configuration.selectors] — extract
///   values from a connector's payload (regex, JsonPath, XPath, SDP,
///   attribute).
/// - [org.vorpal.blade.framework.v3.configuration.translations] — lookup
///   entries ([org.vorpal.blade.framework.v3.configuration.translations.Translation])
///   and table wrappers
///   ([org.vorpal.blade.framework.v3.configuration.translations.TranslationTable])
///   consumed by TableConnector.
/// - [org.vorpal.blade.framework.v3.configuration.auth] — polymorphic
///   authentication schemes for RestConnector (Basic, Bearer, API key,
///   five OAuth 2.0 grants via the Nimbus SDK).
/// - [org.vorpal.blade.framework.v3.configuration.routing] — the
///   routing decision: polymorphic
///   [org.vorpal.blade.framework.v3.configuration.routing.Routing]
///   (table or direct) producing a
///   [org.vorpal.blade.framework.v3.configuration.routing.Route].
/// - [org.vorpal.blade.framework.v3.configuration.trie] — the prefix
///   trie backing MatchStrategy.prefix lookups.
package org.vorpal.blade.framework.v3.configuration;
