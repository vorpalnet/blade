package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Constrains the *keys* of a `Map<String, ?>` property to a fixed set of
/// allowed strings. Placed on the map field or (by convention) its getter.
///
/// Emitted into the generated schema as standard JSON Schema
/// `propertyNames: { enum: [...] }` on the map property. The Configurator
/// honors this by rendering the map-entry key as a dropdown instead of a
/// free-text input, so typos can't silently create dead entries. An existing
/// key that isn't in the list is preserved as a selectable value, so editing
/// never drops data.
///
/// Generic — any keyed map can declare its allowed keys; nothing here is
/// specific to one config. Example:
///
/// ```java
/// @FormKeyEnum({ "INVITE", "REGISTER", "OPTIONS", "SUBSCRIBE" })
/// public Map<String, Trigger> getTriggers() { ... }
/// ```
///
/// @see FormSection
/// @see FormLayout
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface FormKeyEnum {
    String[] value();
}
