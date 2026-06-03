package org.vorpal.blade.applications.files;

import java.util.Arrays;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/files.json` on first deployment when no
/// operator-supplied file is present.
///
/// The registry ships EMPTY of real targets on purpose — deny-by-default. The
/// two sample entries below show the shape (and point at files that exist in a
/// stock domain); an administrator edits this list to expose the files their
/// site actually maintains by hand.
public class FilesSettingsSample extends FilesSettings {
	private static final long serialVersionUID = 1L;

	public FilesSettingsSample() {
		this.about.setName("Files")
				.setTagline("Domain File Editor")
				.setDescription("Edit schema-less domain files — XML, properties, plain text — from the browser instead of over SSH. Files are an admin-defined whitelist; every save is well-formedness-checked and backed up so a bad edit can be rolled back.");

		this.files = Arrays.asList(
				new EditableFile()
						.setLabel("SIP Server Config (sipserver.xml)")
						.setPath("config/custom/sipserver.xml")
						.setType(FileType.XML),
				new EditableFile()
						.setLabel("Logging Properties")
						.setPath("config/custom/logging.properties")
						.setType(FileType.PROPERTIES));
	}
}
