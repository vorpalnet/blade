/// FSMAR v3 configuration model — the data classes the data-driven Finite State
/// Machine Application Router routes on, independent of the runtime engine
/// (which lives in `org.vorpal.blade.library.fsmar3` and is packaged as the
/// `approuter/` fat JAR).
///
/// Each [State] (keyed by the previous application; `"null"` for initial
/// requests) runs its extraction
/// [Selector][org.vorpal.blade.framework.v3.configuration.selectors.Selector]s
/// on entry, publishing named values (`${To.user}`) into a routing context
/// that accumulates across the whole call-path — carried in the JSR-289
/// `stateInfo` (via the runtime engine's `RoutingState`), so it survives hops
/// and cluster replication. Each [Transition] fires on a `when`
/// [Expression][org.vorpal.blade.framework.v3.configuration.expressions.Expression]
/// over those values and builds `${}`-templated routes from them. Replaces
/// the v2 Condition/Action model and FSMAR 2's per-header comparison lists.
///
/// [Fsmar2Converter] migrates a legacy FSMAR 2 configuration into this model.
/// Living in the framework JAR (bundled per-WAR and shaded into the fat JAR)
/// lets admin-tier tools — notably the Flow editor — reuse the model and the
/// converter without linking the engine fat JAR.
package org.vorpal.blade.framework.v3.fsmar;
