package org.vorpal.blade.framework.v2.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Class-level hint that the configurator should show a live SIP-URI
/// syntax-validity badge (✓/✕, never color-only) next to each instance of
/// this type, computed entirely client-side from its fields — no server
/// round-trip, no live network check.
///
/// Requires the annotated class to expose exactly these JSON properties,
/// which the configurator assembles into `scheme:[user@]host[:port];
/// transport=X[;uriParams]`: `scheme`, `transport`, `host`, `port`, `user`,
/// `uriParams`. This is a fixed contract for one specific reusable feature,
/// not a general-purpose hook — a class without all six properties gets no
/// badge.
///
/// ```java
/// @FormUriPreview
/// public class Endpoint implements Serializable { ... }
/// ```
///
/// @see FormLayout
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FormUriPreview {
}
