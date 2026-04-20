package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Class-level hint that groups a set of fields under a named bordered
/// sub-section in the configurator. Useful when a class has two or three
/// logical groupings (e.g. "Connection", "Authentication", "Retry policy").
/// Fields not listed in any @FormSection render at the top level.
///
/// Repeatable — use several annotations to define several sections:
///
/// ```java
/// @FormSection(title = "Connection",     fields = { "url", "method" })
/// @FormSection(title = "Authentication", fields = { "authentication" })
/// public class RestConnector extends Connector { ... }
/// ```
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(FormSections.class)
public @interface FormSection {
    String title();
    String[] fields();
}
