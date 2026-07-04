/// SIP service that applies configurable CRUD (create, read, update, delete)
/// rules to SIP message headers and content at every point in the call
/// lifecycle.
///
/// The WAR itself is deliberately thin: [CrudServlet] is a
/// [B2buaServlet][org.vorpal.blade.framework.v2.b2bua.B2buaServlet] that
/// applies a configured
/// [RuleSet][org.vorpal.blade.framework.v3.crud.RuleSet] in the B2BUA
/// lifecycle callbacks — the rule engine, configuration model and preview
/// machinery all live in the framework's
/// `crud` package. Rules are written in
/// the service's JSON configuration (no Java required) and can be authored
/// and previewed interactively with the CRUD Editor admin app at
/// `/blade/crud-editor`.
package org.vorpal.blade.services.crud;
