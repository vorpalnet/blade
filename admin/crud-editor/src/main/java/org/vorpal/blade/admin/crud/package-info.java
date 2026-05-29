/// Edits BLADE translation tables (servers, route patterns, allow/deny lists)
/// through a spreadsheet-style UI with live preview before commit.
///
/// The admin-tier WAR behind the CRUD Editor tool: it renders a deployed
/// service's translation tables as editable grids and round-trips changes
/// through the framework's configuration store.
package org.vorpal.blade.admin.crud;
