package org.vorpal.blade.framework.v3.configuration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Developer-owned display identity for a BLADE app, baked into the app's
/// generated JSON Schema instead of living in its configuration data. Place it
/// on the config class the schema is generated from:
///
/// ```java
/// @SchemaAbout(
///     name = "Configurator",
///     tagline = "Schema-Driven Configuration Editor",
///     description = "Edit every deployed BLADE service's configuration ...")
/// public class ConfiguratorSettings extends Configuration { ... }
/// ```
///
/// `SettingsManager.generateSchemaNode` stamps these onto the schema root as
/// `title` (name), `x-tagline`, and `description`. The BLADE Admin Portal reads
/// them straight off the schema (via `SettingsMXBean.getSchemaJson`) to build
/// each launcher card, and the Configurator shows `title` as its form heading.
///
/// Identity is therefore static and developer-owned — it can no longer be lost
/// or edited through an operator config save (the bug that motivated the move
/// off the old `About` config object). Operator-editable admin notes still live
/// in config data on `About.getNotes`.
///
/// `name` doubles as the schema `title`, so a class carrying `@SchemaAbout`
/// needs no separate `@SchemaTitle`.
///
/// `@Inherited` so a subclass's annotation overrides its base's and an
/// undeclared subclass inherits the base's — `Class.getAnnotation` walks the
/// superclass chain. (Whole-annotation override, no per-member merge;
/// superclasses only, not interfaces — both fine for the config hierarchy.)
///
/// New in BLADE 3.0; lives in `v3.configuration` like the rest of the 3.0
/// config model, but is consumed by the (v2) schema generator the same way
/// `v2.config.Configuration` already consumes `v3.configuration.Context`.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface SchemaAbout {
	String name();

	String tagline() default "";

	String description() default "";
}
