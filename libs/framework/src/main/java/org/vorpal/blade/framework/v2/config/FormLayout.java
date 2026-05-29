package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Hint to the configurator's form renderer for how to lay out and edit a
/// single field. Placed on a field or its bean getter (by convention, the
/// getter — alongside `@JsonPropertyDescription`).
///
/// - `wide = true` — force the field to a full row. **Only has an effect on a
///   field that is inside a [FormLayoutGroup] row**, where it would otherwise
///   flow as a compact tile; setting `wide` breaks it out to its own full row.
///   A field that is *not* in any group is already full-width, so `wide` is a
///   no-op there. Ignored when `multiline = true` (already wide).
/// - `multiline = true` — render as a &lt;textarea&gt; instead of a
///   single-line &lt;input&gt;. Implies wide layout.
/// - `password = true` — render as a masked &lt;input type="password"&gt;.
/// - `readOnly = true` — render the input in disabled state. Useful for
///   computed/derived fields that should be visible but not editable.
/// - `regexTest = true` — surface a "test" button beside the field that
///   opens the configurator's regex-tester modal (sample input →
///   named/numbered capture groups).
///
/// For class-level layout (rows, columns, titled sections) see
/// [FormLayoutGroup] and [FormSection].
///
/// @see FormLayoutGroup
/// @see FormLayoutColumn
/// @see FormSection
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface FormLayout {
    boolean wide() default false;
    boolean multiline() default false;
    boolean password() default false;
    boolean readOnly() default false;
    boolean regexTest() default false;
}
