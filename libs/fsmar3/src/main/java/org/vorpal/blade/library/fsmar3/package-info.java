/// FSMAR v3 — the data-driven Finite State Machine Application Router that
/// routes initial SIP requests between applications using per-state value
/// extraction and condition-matched transitions.
///
/// Each [State] (keyed by the previous application; `"null"` for initial
/// requests) runs its extraction
/// [Selector][org.vorpal.blade.framework.v3.configuration.selectors.Selector]s
/// on entry, publishing named values (`${To.user}`) into a routing context
/// that accumulates across the whole call-path — carried in the JSR-289
/// `stateInfo` via [RoutingState], so it survives hops and cluster
/// replication. Each [Transition] fires on a `when`
/// [Expression][org.vorpal.blade.framework.v3.configuration.expressions.Expression]
/// over those values and builds `${}`-templated routes from them. Replaces
/// the v2 Condition/Action model and FSMAR 2's per-header comparison lists.
package org.vorpal.blade.library.fsmar3;
