package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One field in an event payload — a name, a kind, and whether it must be
/// present.
///
/// This is the *authored* model: what an operator types into the designer. The
/// JSON Schema and the Java class are both **derived** from it by
/// [EventSourceGenerator], never authored directly. That direction matters —
/// generating a schema from a field list is mechanical, whereas reducing an
/// arbitrary schema back to a field list is not, and picking the easy direction
/// as the source of truth is what keeps the editor honest about what it can and
/// cannot represent.
///
/// **Wire names versus Java names.** Event payloads on this bus are commonly
/// snake_case — a Python producer emits `when_text` — while Java wants
/// `whenText`. [#getName] is always the wire name, exactly as it appears in the
/// JSON. [#javaName] derives the Java identifier, and the generator emits a
/// `@JsonProperty` binding whenever the two differ, so the wire form is never
/// silently changed to suit Java.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventField implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private EventFieldType type = EventFieldType.STRING;
	private boolean required;
	private String description;
	private String defaultValue;
	private List<String> enumValues;
	private String pattern;
	private String format;
	private EventFieldType itemType;
	private List<EventField> fields;

	public EventField() {
	}

	/// Convenience for the common case — a scalar field.
	///
	/// @param name     the wire name, as it appears in the JSON payload
	/// @param type     the field kind
	/// @param required whether the payload must carry it
	public EventField(String name, EventFieldType type, boolean required) {
		this.name = name;
		this.type = type;
		this.required = required;
	}

	/// The Java identifier for this field: the wire name with `_` and `-`
	/// separators removed and the following letter capitalized, so `when_text`
	/// becomes `whenText`. A name that is already camelCase passes through
	/// unchanged.
	///
	/// Returns null when [#getName] is null, so a half-authored field doesn't
	/// throw its way out of a live preview.
	public String javaName() {
		return toCamelCase(name, false);
	}

	/// The Java *type* name this field contributes when it needs a generated
	/// nested type — the capitalized form of the wire name, so an `ENUM` field
	/// `call_result` generates `enum CallResult` and an `OBJECT` field
	/// `caller_info` generates `class CallerInfo`.
	public String javaTypeName() {
		return toCamelCase(name, true);
	}

	/// True when the wire name and the Java name differ, i.e. the generated class
	/// needs an explicit `@JsonProperty` binding to preserve the wire form.
	public boolean needsJsonPropertyBinding() {
		String java = javaName();
		return java != null && !java.equals(name);
	}

	private static String toCamelCase(String raw, boolean capitalizeFirst) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}
		StringBuilder sb = new StringBuilder(raw.length());
		boolean capitalizeNext = capitalizeFirst;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '_' || c == '-' || c == '.' || c == ' ') {
				capitalizeNext = true;
			} else if (capitalizeNext) {
				sb.append(Character.toUpperCase(c));
				capitalizeNext = false;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	@JsonPropertyDescription("Field name exactly as it appears in the event payload JSON. Commonly snake_case; the generated Java class binds it with @JsonProperty when the Java identifier differs.")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("The kind of value this field holds. OBJECT takes its shape from the nested field list; ARRAY takes its element kind from itemType.")
	public EventFieldType getType() {
		return type;
	}

	public void setType(EventFieldType type) {
		this.type = (type == null) ? EventFieldType.STRING : type;
	}

	@JsonPropertyDescription("Whether a published event must carry this field. Required fields are listed in the generated schema's 'required' array and rejected at the ingress when validation is on.")
	public boolean isRequired() {
		return required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}

	@JsonPropertyDescription("What this field means. Becomes the schema 'description' and a Javadoc comment on the generated accessor.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Default value, written as text and coerced to the field's type. Emitted as the schema 'default'; it does not make a required field optional.")
	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	@JsonPropertyDescription("The permitted values, for an ENUM field. Generates a nested Java enum and the schema's 'enum' list.")
	public List<String> getEnumValues() {
		return enumValues;
	}

	public void setEnumValues(List<String> enumValues) {
		this.enumValues = enumValues;
	}

	@JsonPropertyDescription("Regular expression a STRING field must match. Emitted as the schema 'pattern' and enforced at the ingress when validation is on.")
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
	}

	@JsonPropertyDescription("JSON Schema 'format' annotation for a STRING field — email, uri, uuid, ipv4 and the like. Advisory: most validators do not enforce it.")
	public String getFormat() {
		return format;
	}

	public void setFormat(String format) {
		this.format = format;
	}

	@JsonPropertyDescription("For an ARRAY field, the kind of its elements. When this is OBJECT the element shape comes from the nested field list.")
	public EventFieldType getItemType() {
		return itemType;
	}

	public void setItemType(EventFieldType itemType) {
		this.itemType = itemType;
	}

	@JsonPropertyDescription("Nested fields, for an OBJECT field or an ARRAY of OBJECT.")
	public List<EventField> getFields() {
		return fields;
	}

	public void setFields(List<EventField> fields) {
		this.fields = fields;
	}

	/// The nested field list, never null — for generators and renderers that
	/// would otherwise each need their own null guard.
	public List<EventField> fieldsOrEmpty() {
		return (fields == null) ? new ArrayList<>() : fields;
	}

	/// The enum value list, never null.
	public List<String> enumValuesOrEmpty() {
		return (enumValues == null) ? new ArrayList<>() : enumValues;
	}
}
