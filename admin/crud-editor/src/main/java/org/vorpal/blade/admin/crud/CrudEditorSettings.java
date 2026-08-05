package org.vorpal.blade.admin.crud;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the CRUD Editor admin app. Currently exposes only the
/// inherited `name` / `tagline` / `description` metadata fields (rendered on the BLADE Admin Portal launcher card).
/// Add app-specific knobs here later as the app grows.
@SchemaAbout(
		name = "CRUD Editor",
		tagline = "Rule-Set Authoring with Live Preview",
		description = "Author CRUD rule sets and preview them live: replay a sample SIP message through a rule set and see the transformed message, the rules that fired, and the session variables — before you publish.")
public class CrudEditorSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
}
