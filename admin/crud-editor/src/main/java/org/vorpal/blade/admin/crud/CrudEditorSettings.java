package org.vorpal.blade.admin.crud;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the CRUD Editor admin app. Currently exposes only the
/// inherited `name` / `tagline` / `description` metadata fields (rendered on the BLADE Admin Portal launcher card).
/// Add app-specific knobs here later as the app grows.
@SchemaAbout(
		name = "CRUD Editor",
		tagline = "Translation Table Editor",
		description = "Edit BLADE translation tables (servers, route patterns, allow/deny lists) through a spreadsheet-style UI with live preview before commit.")
public class CrudEditorSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
