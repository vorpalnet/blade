package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// The deployable CRUD **service's** settings type.
///
/// [CrudConfiguration] is the shared CRUD *engine* config — the crud service,
/// the crud-editor preview tool, and the test-suite's `TesterConfiguration` all
/// build on it. This subclass exists purely so the crud service can carry its
/// own portal / Configurator identity (`@SchemaAbout`) WITHOUT that identity
/// leaking onto the tester hierarchy: `@SchemaAbout` is `@Inherited`, and
/// `TesterConfiguration` (and the test services beneath it) extend
/// `CrudConfiguration` directly, so they never pick this up.
///
/// Adds no fields — the on-disk config shape is identical to
/// [CrudConfiguration], so [CrudConfigurationSample] (which now extends this)
/// and every existing crud config file load unchanged.
@SchemaAbout(
		name = "CRUD",
		tagline = "Rule-Based SIP Message Rewriting",
		description = "A configurable engine that creates, reads, updates, and deletes SIP "
				+ "headers and body parts (SIP, XML, JSON, SDP) by rule — data-driven message "
				+ "manipulation without custom code.")
public class CrudSettings extends CrudConfiguration implements Serializable {
	private static final long serialVersionUID = 1L;
}
