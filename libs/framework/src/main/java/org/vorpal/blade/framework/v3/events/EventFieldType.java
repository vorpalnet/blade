package org.vorpal.blade.framework.v3.events;

/// The kinds of value an [EventField] can hold.
///
/// Deliberately a short, closed list. An event payload is a message between two
/// systems, not a domain model — the set below covers what a CloudEvent `data`
/// block realistically carries, and every one of them maps cleanly to *both* a
/// Java type and a JSON Schema type. Anything richer (polymorphism, maps keyed
/// at runtime, recursive structures) is out of scope on purpose: the designer
/// would not be able to round-trip it, and a consumer in another language could
/// not rely on it.
///
/// [#OBJECT] and [#ARRAY] carry their shape in [EventField#getFields] and
/// [EventField#getItemType] respectively, which is what keeps this enum flat.
public enum EventFieldType {

	/// A JSON string. Java `String`.
	STRING,

	/// A 32-bit whole number. Java `Integer`, JSON Schema `integer`.
	INTEGER,

	/// A 64-bit whole number. Java `Long`, JSON Schema `integer` with
	/// `format: int64` — JSON itself has no integer width, so the format hint is
	/// the only thing that tells a consumer not to parse into a 32-bit type.
	LONG,

	/// A floating-point number. Java `Double`, JSON Schema `number`.
	NUMBER,

	/// Java `Boolean`.
	BOOLEAN,

	/// An instant in time. Java `Instant`, JSON Schema `string` with
	/// `format: date-time` — the same RFC-3339 form [CloudEvent#create] stamps
	/// into the envelope's `time` attribute.
	INSTANT,

	/// A closed set of string values, from [EventField#getEnumValues]. Generates
	/// a nested Java `enum`.
	ENUM,

	/// A nested structure, shaped by [EventField#getFields]. Generates a nested
	/// static Java class.
	OBJECT,

	/// A repeated value. The element kind is [EventField#getItemType]; when that
	/// is [#OBJECT] the element shape comes from [EventField#getFields].
	/// Generates a Java `List`.
	ARRAY;

	/// The Java type this maps to for a *scalar* kind, or null for [#ENUM],
	/// [#OBJECT] and [#ARRAY], whose Java types depend on the field's name and
	/// nested shape and are therefore resolved by [EventSourceGenerator].
	public String scalarJavaType() {
		switch (this) {
		case STRING:
			return "String";
		case INTEGER:
			return "Integer";
		case LONG:
			return "Long";
		case NUMBER:
			return "Double";
		case BOOLEAN:
			return "Boolean";
		case INSTANT:
			return "Instant";
		default:
			return null;
		}
	}

	/// The import the generated class needs for [#scalarJavaType], or null when
	/// the type is in `java.lang` or needs no import.
	public String scalarJavaImport() {
		return (this == INSTANT) ? "java.time.Instant" : null;
	}

	/// The JSON Schema `type` keyword for this kind. [#ARRAY] and [#OBJECT]
	/// return their own names lowercased; the rest map onto the six JSON types.
	public String schemaType() {
		switch (this) {
		case INTEGER:
		case LONG:
			return "integer";
		case NUMBER:
			return "number";
		case BOOLEAN:
			return "boolean";
		case OBJECT:
			return "object";
		case ARRAY:
			return "array";
		default:
			return "string";
		}
	}

	/// The JSON Schema `format` annotation for this kind, or null when it needs
	/// none.
	public String schemaFormat() {
		switch (this) {
		case LONG:
			return "int64";
		case INSTANT:
			return "date-time";
		default:
			return null;
		}
	}
}
