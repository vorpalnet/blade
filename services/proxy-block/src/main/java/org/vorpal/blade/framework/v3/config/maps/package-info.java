/// This package provides concrete implementations of translation maps for the Vorpal Blade
/// framework's configuration system. Translation maps route and translate SIP servlet
/// requests based on configurable attribute selectors and lookup strategies.
///
/// ## Key Classes
///
/// - [ConfigHashMap] - HashMap-based translation map with exact key matching
/// - [ConfigPrefixMap] - HashMap-based translation map with prefix-based matching
///
/// ## Map Implementations
///
/// ### ConfigHashMap Exact Matching
/// [ConfigHashMap] extends `HashMap<String, Translation<T>>` and implements
/// `TranslationsMap<T>`. It iterates through its list of `AttributeSelector` objects,
/// calling `findKey(request)` on each to extract a key from the `SipServletRequest`.
/// If the extracted key matches a map entry exactly, the corresponding `Translation`
/// is returned. An optional `defaultRoute` field provides fallback routing when no
/// exact match is found. Matched attribute variables from the selector are merged
/// into the translation's attributes map.
///
/// ### ConfigPrefixMap Prefix Matching
/// [ConfigPrefixMap] extends `HashMap<String, Translation<T>>` and implements
/// `TranslationsMap<T>`. After extracting a key via its selectors, it performs a
/// longest-prefix search by progressively shortening the key string from the full
/// length down to one character, returning the first map entry that matches. This
/// is useful for hierarchical routing scenarios such as telephone number prefix
/// matching (e.g., area codes or country codes).
///
/// ### Common Map Properties
/// Both maps expose:
/// - `id` - a required identifier, annotated with `@JsonPropertyDescription`
/// - `desc` - an optional human-readable description (ConfigHashMap only)
/// - `selectors` - a `List<AttributeSelector>` defining how to extract keys from requests
/// - `createTranslation(key)` - factory method to build and insert a new `Translation`
/// - `addSelector(selector)` - fluent method to append an `AttributeSelector`
///
/// ## JSON Serialization
///
/// ### Jackson Annotations
/// Both classes use `@JsonTypeInfo` with `JsonTypeInfo.Id.CLASS` for polymorphic
/// deserialization and `@JsonIdentityInfo` with `PropertyGenerator` keyed on the
/// `id` field to handle object identity during serialization cycles.
///
/// ### Configuration Persistence
/// The maps serialize cleanly to JSON for storage in the Blade configuration file
/// system (`config/custom/vorpal/`), enabling runtime loading and hot-reloading of
/// translation routing rules.
///
/// @see org.vorpal.blade.framework.v3.config.TranslationsMap
/// @see org.vorpal.blade.framework.v3.config.Translation
/// @see org.vorpal.blade.framework.v3.config.AttributeSelector
package org.vorpal.blade.framework.v3.config.maps;
