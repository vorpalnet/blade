/// The standalone iRouter WAR — table-driven intelligent SIP routing built
/// on the framework's two-phase pipeline of enrichment connectors followed
/// by a translation-table routing decision.
///
/// The WAR contains a single class: [IRouterApp], the annotated leaf that
/// activates the framework's
/// [IRouterServlet][org.vorpal.blade.framework.v3.irouter.IRouterServlet].
/// Everything else — the connector pipeline (REST, LDAP, JDBC, map, table,
/// sip), translation tables, polymorphic authentication and the routing
/// engine — lives in the framework's
/// `irouter` and
/// `configuration` packages and
/// is driven entirely by the JSON configuration file. Routing decisions
/// always come from translation tables, never from code.
///
/// Commercial extensions subclass [IRouterServlet][org.vorpal.blade.framework.v3.irouter.IRouterServlet]
/// in their own WAR with their own annotated leaf, overriding only the
/// config-sample and initial-INVITE factory seams.
package org.vorpal.blade.services.irouter;
