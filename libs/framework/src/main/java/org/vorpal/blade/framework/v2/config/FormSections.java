package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Container for multiple @FormSection annotations on a single class.
/// Populated automatically by the compiler when @FormSection is used more
/// than once; not typically written by hand.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FormSections {
    FormSection[] value();
}
