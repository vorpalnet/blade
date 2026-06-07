/// Admin app for editing WebLogic domain configuration files — XML,
/// properties and plain text — from the browser at `/blade/files`.
///
/// Files must be explicitly registered to be editable (deny-by-default):
/// [FilesSettings] holds the registry of permitted files, each typed via
/// [FileType] so the editor can apply the right syntax mode and the right
/// well-formedness validation ([FileValidators]) before a save is accepted.
/// Every save goes through a versioned backup store, so any previous
/// revision can be inspected or restored. [FilesAPI] is the JAX-RS surface
/// the browser editor talks to; [EditableFile] is its file/metadata
/// transfer object.
package org.vorpal.blade.applications.files;
