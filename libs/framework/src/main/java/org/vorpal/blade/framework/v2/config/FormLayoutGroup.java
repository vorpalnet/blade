package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Class-level hint for the configurator. Fields listed together flow
/// left-to-right as a single row; fields not listed in any @FormLayoutGroup
/// stack top-to-bottom (one field per row) by default.
///
/// Repeatable — use several annotations to define several rows:
///
/// ```java
/// @FormLayoutGroup({ "id", "description" })
/// @FormLayoutGroup({ "match", "keyExpression" })
/// public class TableConnector extends Connector { ... }
/// ```
///
/// A group is one of two mutually-exclusive forms:
///
/// - **Flat row** — set `value` with field names. They flow left-to-right as
///   compact tiles:
///
///   ```text
///   [ loggingLevel ] [ sequenceDiagramLevel ] [ configLevel ] [ analyticsLevel ]
///   ```
///
/// - **Row of columns** — set `columns` instead. Each [FormLayoutColumn] is a
///   vertical stack of fields; the columns sit side-by-side on the row, so the
///   row becomes a grid of independently-stacked tiles:
///
///   ```java
///   @FormLayoutGroup(columns = {
///       @FormLayoutColumn({ "fileSize", "fileCount" }),
///       @FormLayoutColumn({ "useParentLogging", "appendFile", "colorsEnabled" })
///   })
///   ```
///
///   ```text
///   [ fileSize  ] [ useParentLogging ]
///   [ fileCount ] [ appendFile       ]
///                 [ colorsEnabled    ]
///   ```
///
/// Set `value` or `columns`, not both; when both are present `columns` wins.
/// The two forms emit different schema keys (`x-form-groups` vs
/// `x-form-columns`), so adding `columns` never changes how existing flat
/// groups render.
///
/// A field listed in *no* group renders on its own full-width row by default —
/// there is no need to wrap a single field in a group to make it wide (see the
/// note on [FormLayout]'s `wide`).
///
/// @see FormLayoutColumn
/// @see FormSection
/// @see FormLayout
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(FormLayoutGroups.class)
public @interface FormLayoutGroup {
    /// Field names for a flat left-to-right row. Mutually exclusive with
    /// [#columns()].
    String[] value() default {};

    /// Vertical stacks placed side-by-side on the row. Set this *instead of*
    /// [#value()] for a grid layout; when both are set, `columns` wins.
    FormLayoutColumn[] columns() default {};
}
