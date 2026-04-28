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
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(FormLayoutGroups.class)
public @interface FormLayoutGroup {
    String[] value();
}
