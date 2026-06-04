package org.vorpal.blade.applications.files;

/// The kind of a registry entry — drives the well-formedness check the editor
/// runs server-side before overwriting the file.
public enum FileType {
	/// Parsed as XML; rejected if not well-formed.
	XML,
	/// Parsed as JSON; rejected if not well-formed (including trailing content).
	JSON,
	/// Parsed with `java.util.Properties`; rejected on a malformed line / escape.
	PROPERTIES,
	/// No structural check — saved as-is.
	TEXT
}
