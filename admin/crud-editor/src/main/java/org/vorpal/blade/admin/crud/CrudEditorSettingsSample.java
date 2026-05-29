package org.vorpal.blade.admin.crud;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/crud-editor.json` on first deployment when no
/// operator-supplied file is present.
public class CrudEditorSettingsSample extends CrudEditorSettings {
	private static final long serialVersionUID = 1L;

	public CrudEditorSettingsSample() {
		this.about.setName("CRUD Editor")
				.setTagline("Translation Table Editor")
				.setDescription("Edit BLADE translation tables (servers, route patterns, allow/deny lists) through a spreadsheet-style UI with live preview before commit.");
	}
}
