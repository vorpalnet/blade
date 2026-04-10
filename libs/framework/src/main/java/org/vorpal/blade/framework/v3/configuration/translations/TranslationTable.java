package org.vorpal.blade.framework.v3.configuration.translations;

import java.io.Serializable;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import org.vorpal.blade.framework.v3.configuration.translations.tables.HashTranslationTable;
import org.vorpal.blade.framework.v3.configuration.translations.tables.PrefixTranslationTable;

/// A typed lookup table from `String` key to [Translation] of `T`.
/// Concrete implementations choose their own underlying storage strategy
/// (hash, prefix-trie, address-tree, etc.).
///
/// `TranslationTable` is intentionally **not** a [java.util.Map]. Concrete
/// implementations may *contain* a Map (or any other storage), but the
/// public abstract API exposed by this interface is just the operations
/// the BLADE framework needs polymorphically:
///   - identity (`getId` / `getDescription`)
///   - construction (`createTranslation`)
///   - exact lookup (`get`, `containsKey`, `size`)
///   - iteration (`keySet`)
///
/// Implementations are free to add more methods on top — for example,
/// [PrefixTranslationTable] adds `longestPrefixOf(String)` for telco-style
/// dial-plan lookups. Callers that want such operations hold a concrete
/// reference rather than the abstract interface.
///
/// ## Why no Map ancestry?
///
/// In an earlier design [TranslationTable] extended `Map<String, Translation<T>>`
/// for in-memory ergonomics. That made [com.kjetland.jackson.jsonSchema.JsonSchemaGenerator]
/// (and any introspector that walks the type hierarchy) stop at `Map` and
/// emit a generic Map schema, ignoring `@JsonSubTypes` polymorphism.
/// Dropping `Map` ancestry from the interface lets every schema generator
/// see this as a regular polymorphic abstract type and emit a clean
/// `oneOf` for it.
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
public interface TranslationTable<T> extends Serializable {

	@JsonPropertyDescription("Unique identifier for this translation table")
	String getId();

	void setId(String id);

	@JsonPropertyDescription("Human-readable description of this translation table")
	String getDescription();

	void setDescription(String description);

	/// Concrete tables create a freshly-keyed translation in their own way
	/// (e.g. `hash` stores by exact key, `prefix` by longest-prefix match).
	Translation<T> createTranslation(String key);

	/// Exact-match lookup. For prefix-style tables, this is still
	/// exact-match — use [PrefixTranslationTable#longestPrefixOf] for
	/// prefix matching.
	Translation<T> get(String key);

	boolean containsKey(String key);

	int size();

	Set<String> keySet();
}
