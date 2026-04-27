/// The routing decision phase — runs after the enrichment pipeline
/// completes and produces the destination SIP URI the servlet will
/// proxy to.
///
/// Types:
///
/// - [org.vorpal.blade.framework.v3.configuration.routing.Routing] —
///   abstract polymorphic base carried on
///   [org.vorpal.blade.framework.v3.configuration.RouterConfiguration]'s
///   `routing` field. Three concrete subtypes today:
///   - [org.vorpal.blade.framework.v3.configuration.routing.TableRouting]
///     (`type: table`) — first-match-wins across an ordered list of
///     [org.vorpal.blade.framework.v3.configuration.routing.RoutingTable]s,
///     each with its own `match` / `keyExpression` / `routes`. Supports
///     hash, prefix, and range matching; falls through to a top-level
///     `default` Route.
///   - [org.vorpal.blade.framework.v3.configuration.routing.ConditionalRouting]
///     (`type: conditional`) — ordered `if/elif/else` clauses, each
///     pairing a boolean expression (see
///     [org.vorpal.blade.framework.v3.configuration.expressions.Expression])
///     with a Route. First matching clause wins.
///   - [org.vorpal.blade.framework.v3.configuration.routing.DirectRouting]
///     (`type: direct`) — skip the lookup, always return the same
///     [org.vorpal.blade.framework.v3.configuration.routing.Route]
///     (useful when the pipeline has already enriched `${destNum}`
///     and friends sufficiently to compose the destination directly).
/// - [org.vorpal.blade.framework.v3.configuration.routing.Route] — the
///   routing decision payload: `description` + `requestUri` + optional
///   `headers` map + optional
///   [org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader]
///   list. Both `requestUri` and every header value are
///   `${var}`-interpolated against the enriched Context at decision
///   time; conditional headers are only stamped when their `when`
///   expression evaluates true.
///
/// When new grant-type routing logic is needed (conditional, scripted,
/// multi-table), add a new `Routing` subclass with a fresh `type`
/// discriminator — callers don't change.
///
/// A legacy package,
/// [org.vorpal.blade.framework.v3.configuration.routing.LooseRoutingHelper],
/// hosts OCCAS-specific SIP proxy reflection utilities unrelated to
/// the iRouter routing model.
package org.vorpal.blade.framework.v3.configuration.routing;
