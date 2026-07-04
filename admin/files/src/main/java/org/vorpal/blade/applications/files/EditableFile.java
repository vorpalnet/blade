package org.vorpal.blade.applications.files;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One entry in the editable-file registry: a file an administrator has
/// declared safe to edit through the Files tool. This is the whitelist — a path
/// that isn't an entry here cannot be read or written, no matter what the
/// browser asks for.
///
/// `path` is resolved relative to `DOMAIN_HOME`. It is canonicalized and
/// confined to the domain server-side ([FilesAPI]); a `..` that escapes the
/// domain is rejected at request time.
public class EditableFile implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String label;
	protected String path;
	protected FileType type = FileType.TEXT;
	protected ConsumerTier consumer = ConsumerTier.NONE;

	@JsonPropertyDescription("Human-readable name shown in the file picker, e.g. \"SIP Server Config\".")
	public String getLabel() {
		return label;
	}

	public EditableFile setLabel(String label) {
		this.label = label;
		return this;
	}

	@JsonPropertyDescription("Path to the file, relative to DOMAIN_HOME, e.g. \"config/custom/sipserver.xml\". Must stay inside the domain.")
	public String getPath() {
		return path;
	}

	public EditableFile setPath(String path) {
		this.path = path;
		return this;
	}

	@JsonPropertyDescription("File kind — XML, JSON and PROPERTIES are well-formedness-checked before save; TEXT is saved as-is.")
	public FileType getType() {
		return type;
	}

	public EditableFile setType(FileType type) {
		this.type = type;
		return this;
	}

	@JsonPropertyDescription("Which server tier reads this file — drives the \"restart to apply\" offer after a save. "
			+ "ADMIN = AdminServer (e.g. config.xml); ENGINE = SIP engine tier (e.g. approuter.xml); BOTH; "
			+ "NONE = no restart needed. Defaults to NONE.")
	public ConsumerTier getConsumer() {
		return consumer;
	}

	public EditableFile setConsumer(ConsumerTier consumer) {
		this.consumer = consumer;
		return this;
	}
}
