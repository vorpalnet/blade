package org.vorpal.blade.framework.v3.configuration.translations;

import java.io.Serializable;
import java.util.Set;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.translations.tables.HashTranslationTable;
import org.vorpal.blade.framework.v3.configuration.translations.tables.PrefixTranslationTable;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// A typed lookup table from `String` key to [Translation] of `T`.
/// Concrete implementations choose their storage strategy (hash =
/// exact-match, prefix = longest-prefix-match, …).
///
/// `TranslationTable` is intentionally **not** a [java.util.Map]. Concrete
/// implementations may *contain* a Map (or any other storage), but the
/// public API exposed by this class is just what the framework needs:
/// identity (`getId` / `getDescription`), key-driven matching against a
/// [Context] (`match`), strategy-specific exact-or-prefix `lookup`, and
/// creation/iteration helpers.
///
/// Implementations are free to add more methods on top — for example,
/// [PrefixTranslationTable] adds `longestPrefixOf(String)` for telco-style
/// dial-plan lookups. Callers that want such operations hold a concrete
/// reference rather than the abstract parent.
///
/// ## Why no Map ancestry?
///
/// An earlier design extended `Map<String, Translation<T>>` for in-memory
/// ergonomics. That made the JSON schema generator stop at `Map` and emit
/// a generic Map schema, ignoring `@JsonSubTypes` polymorphism. Dropping
/// `Map` ancestry lets every schema generator see this as a regular
/// polymorphic abstract type and emit a clean `oneOf`, which the
/// Configurator form editor can render as a typed union.
///
/// @param <T> the treatment type carried by each translation in this table
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
// `defaultImpl` is needed so a JSON back-reference (a bare id string with
// no `type` discriminator) doesn't blow up Jackson's polymorphic
// deserializer. The default impl is only ever consulted when the
// JsonIdentityInfo resolver can't find a previously-seen instance — which
// for a well-formed reference means never.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = HashTranslationTable.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = HashTranslationTable.class, name = "hash"),
		@JsonSubTypes.Type(value = PrefixTranslationTable.class, name = "prefix")
		// additional concrete tables registered here as they are added
})
@JsonPropertyOrder({ "type", "id", "description", "keyExpression", "translations" })
public abstract class TranslationTable<T> implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String id;
	protected String description;
	protected String keyExpression;

	@JsonPropertyDescription("Unique identifier for this translation table")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Human-readable description of this translation table")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("${var} template that produces the lookup key, e.g. ${to-user}")
	public String getKeyExpression() {
		return keyExpression;
	}

	public void setKeyExpression(String keyExpression) {
		this.keyExpression = keyExpression;
	}

	/// Resolves `keyExpression` against the [Context], runs the concrete
	/// `lookup`, and recurses into the matched translation's nested tables
	/// (deepest match wins; if nested search misses, the outer match is
	/// returned as a fallback). Returns null when no match is found at
	/// any level.
	public Translation<T> match(Context ctx) {
		if (keyExpression == null || ctx == null) return null;
		String key = ctx.resolve(keyExpression);
		if (key == null || key.equals(keyExpression)) return null;
		Translation<T> found = lookup(key);
		if (found == null) return null;
		if (found.getTables() != null) {
			for (TranslationTable<T> nested : found.getTables()) {
				Translation<T> deeper = nested.match(ctx);
				if (deeper != null) return deeper;
			}
		}
		return found;
	}

	/// Strategy-specific lookup. Hash does exact match; prefix does
	/// longest-prefix match; etc.
	public abstract Translation<T> lookup(String key);

	/// Concrete tables create a freshly-keyed translation in their own way.
	public abstract Translation<T> createTranslation(String key);

	public abstract boolean containsKey(String key);

	public abstract int size();

	public abstract Set<String> keySet();
}
