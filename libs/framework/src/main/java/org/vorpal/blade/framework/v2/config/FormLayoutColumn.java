package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// A single vertical stack of fields, used only nested inside
/// `@FormLayoutGroup(columns = { ... })`. The listed fields render
/// top-to-bottom as one column; several columns placed in a group sit
/// side-by-side on a single horizontal row.
///
/// ```java
/// @FormLayoutGroup(columns = {
///     @FormLayoutColumn({ "fileSize", "fileCount" }),
///     @FormLayoutColumn({ "useParentLogging", "appendFile", "colorsEnabled" })
/// })
/// ```
///
/// `@Target({})` — this annotation is never placed directly on a program
/// element; it exists solely as a member value of `@FormLayoutGroup`.
///
/// @see FormLayoutGroup
/// @see FormSection
/// @see FormLayout
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface FormLayoutColumn {
    String[] value();
}
