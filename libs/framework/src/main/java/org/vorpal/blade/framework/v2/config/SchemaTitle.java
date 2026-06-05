package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Human-readable title for a configuration class, emitted as the JSON
/// Schema `title` keyword by [SettingsManager#generateSchemaNode]. The
/// configurator's form editor displays it as the form heading; without it,
/// the UI falls back to the schema's filename.
///
/// BLADE-owned replacement for kjetland's `@JsonSchemaTitle` (the
/// mbknor-jackson-jsonschema library), which was dropped along with its
/// Scala/Kotlin transitive dependencies — the schema generator itself has
/// been victools since the Draft 2020-12 migration.
///
/// @see FormLayout
/// @see FormSection
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SchemaTitle {
	String value();
}
