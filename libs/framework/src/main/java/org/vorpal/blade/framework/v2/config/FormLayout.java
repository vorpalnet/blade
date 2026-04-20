package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Hint to the configurator's form renderer for how to lay out and edit a
/// single field. Placed on a field or its bean getter.
///
/// - `wide = true` — field occupies a full row instead of flowing as a
///   compact tile alongside its siblings. Useful for long URLs, patterns,
///   etc. Ignored when `multiline = true` (already wide).
/// - `multiline = true` — render as a &lt;textarea&gt; instead of a
///   single-line &lt;input&gt;. Implies wide layout.
/// - `password = true` — render as a masked &lt;input type="password"&gt;.
/// - `readOnly = true` — render the input in disabled state. Useful for
///   computed/derived fields that should be visible but not editable.
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface FormLayout {
    boolean wide() default false;
    boolean multiline() default false;
    boolean password() default false;
    boolean readOnly() default false;
}
