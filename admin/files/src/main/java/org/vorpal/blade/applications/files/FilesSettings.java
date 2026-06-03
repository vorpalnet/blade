package org.vorpal.blade.applications.files;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Files admin app. Beyond the inherited `about` metadata, it
/// holds the editable-file registry: the deny-by-default whitelist of domain
/// files this tool is permitted to read and write. The tool never browses the
/// filesystem freely — only the files an administrator lists here are reachable.
public class FilesSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected List<EditableFile> files = new ArrayList<>();

	@JsonPropertyDescription("The whitelist of editable files. Only files listed here can be opened or saved by the Files tool. Paths are relative to DOMAIN_HOME and confined to the domain.")
	public List<EditableFile> getFiles() {
		return files;
	}

	public FilesSettings setFiles(List<EditableFile> files) {
		this.files = files;
		return this;
	}
}
